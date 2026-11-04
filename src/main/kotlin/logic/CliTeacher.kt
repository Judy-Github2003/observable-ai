// 本文件原始实现：Codex，2026-11-04。
// 本次中文注释与可读步骤日志：DeepSeek Harness 补充，2026-11-04。
// 局部审计修正：Codex，2026-11-04；区分收到事件、正常退出和准备清理，避免提前宣告成功。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改进程、超时、解析或清理行为。
package logic

import kotlinx.serialization.json.*
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

/**
 * 外部教师适配器：把问题写入教师进程的标准输入，读取其 JSONL 结构化输出。
 *
 * 这里只记录“可见进程步骤”（启动、写入、等待、解析），不读取、不推断也不记录
 * 教师内部推理、思维链、原始 stderr 或认证信息；教师工具使用仍归教师所有。
 */
class CliTeacher(
    // 教师进程的完整命令行，由 deepSeek() 按本机安装布局生成。
    private val command: List<String>,
    // 子进程工作目录；同时用于存放教师运行产物，避免写到项目根目录。
    private val workspace: Path,
    // 本次调用的总超时；进程启动、写输入、等待退出和读输出共享同一预算。
    private val timeout: Duration,
    // 追加给子进程的环境变量；只包含 headless 运行所需项，不记录到日志。
    private val environment: Map<String, String> = emptyMap(),
    // 来源标记：日志和记忆记录用它区分运行框架、模型和提供方。
    override val runtime: String = "deepseek-harness",
    override val model: String? = "deepseek-flash",
    override val provider: String? = "DeepSeek",
    // 可见步骤回调；默认空实现，测试或不需要日志时可忽略。
    private val onStep: (phase: String, description: String) -> Unit = { _, _ -> },
) : Teacher {
    /**
     * 执行一次教师调用：受统一 deadline 约束，成功时返回最终文本和会话标识。
     * 失败统一映射为 TeacherException，便于 Main 保持原有退出码和提示。
     */
    override fun answer(question: String, observe: (ToolEvent) -> Unit): TeacherReply {
        // deadline 在调用开始时一次算好；后续所有 get/wait 都从同一绝对时间点倒推剩余时间。
        val deadline = System.nanoTime() + timeout.toNanos()
        // 剩余时间下限为 0，避免已超时后出现负值导致 JDK 等待参数异常。
        fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(0)
        // 教师工作目录可能不存在；先创建，失败会按运行环境错误向上抛出。
        Files.createDirectories(workspace)
        visibleStep("start", "开始启动外部教师进程")
        val process = try {
            // 用给定命令、工作目录和环境变量启动子进程；环境变量只做追加，不打印。
            ProcessBuilder(command).directory(workspace.toFile()).apply {
                environment().putAll(this@CliTeacher.environment)
            }.start()
        } catch (_: Exception) {
            // 不把底层异常文本暴露给终端；统一映射为可识别的启动失败码。
            visibleStep("failure", "外部教师进程启动失败")
            throw TeacherException("TEACHER_START_FAILED", "无法启动 DeepSeek Harness，请检查安装路径。")
        }
        visibleStep("success", "外部教师进程已启动")

        // 三个短任务分别读取 stdout、排空 stderr、写入 stdin，避免管道互相阻塞。
        // 线程设为 daemon，异常退出时不会阻止 JVM 结束。
        val io = Executors.newFixedThreadPool(3) { task -> Thread(task, "teacher-io").apply { isDaemon = true } }
        try {
            val output = io.submit<TeacherReply> {
                // final 保存最终文本，session 保存会话 id；failed 标记教师显式报错。
                var final: String? = null
                var session: String? = null
                var failed = false
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    // 空行不是协议事件，跳过以免 JSON 解析失败。
                    lines.filter { it.isNotBlank() }.forEach { line ->
                        val event = Json.parseToJsonElement(line).jsonObject
                        // 统一按字段名取字符串；字段不存在或不是字符串时返回 null。
                        fun text(key: String) = (event[key] as? JsonPrimitive)?.contentOrNull
                        when (text("type")) {
                            // session 兼容三种常见字段名，只记录会话标识，不记录内容。
                            "session" -> {
                                session = text("id") ?: text("sessionId") ?: text("session_id")
                                visibleStep("info", "已读取教师会话标识")
                            }
                            // 工具事件只把名称交给上层；参数和结果内容不进入项目日志。
                            "tool_call" -> observe(ToolEvent("tool_request", text("tool") ?: "unknown"))
                            "tool_result" -> observe(ToolEvent("tool_result", text("tool") ?: "unknown"))
                            // 收到 final 只说明看到了最终文本，成功与否还要等进程退出和空值校验。
                            "final" -> {
                                final = text("text")
                                visibleStep("info", "已收到教师 final 事件，等待校验最终回复")
                            }
                            // 教师显式 error：标记失败，后续统一抛协议错误。
                            "error" -> {
                                failed = true
                                visibleStep("failure", "教师输出中出现错误事件")
                            }
                            // 中间文本和 thinking 不复制进项目日志。
                        }
                    }
                }
                // 成功条件：没有 error 事件且确实收到 final 文本；否则不把半截输出当成功。
                if (failed || final == null) {
                    throw TeacherException("TEACHER_PROTOCOL_ERROR", "教师未返回完整的成功结果。")
                }
                TeacherReply(final!!, session)
            }
            val diagnostics = io.submit {
                // 不转发原始 provider 诊断或认证数据，只排空管道；否则 stderr 满会阻塞子进程。
                process.errorStream.use { it.copyTo(OutputStream.nullOutputStream()) }
            }
            // 先记“开始写入”再提交任务，避免日志把尚未开始的动作写成已完成。
            visibleStep("start", "开始向教师标准输入写入问题")
            val input = io.submit {
                // 问题只写一次；关闭流表示输入结束，教师可据此开始处理。
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(question) }
            }
            // 写输入也受同一 deadline 约束；超时会进入统一清理路径。
            input.get(remaining(), NANOSECONDS)
            visibleStep("success", "问题已写入教师标准输入")

            visibleStep("start", "等待教师进程结束")
            // waitFor 返回 false 表示剩余时间内未退出，交给 TimeoutException 统一处理。
            if (!process.waitFor(remaining(), NANOSECONDS)) throw TimeoutException()
            // 非零退出即使输出过 final 也不能算成功；错误信息只包含退出码，不含原始 stderr。
            if (process.exitValue() != 0) {
                throw TeacherException("TEACHER_FAILED", "DeepSeek Agent 执行失败（退出码 ${process.exitValue()}），请检查现有登录、模型可用性或网络。")
            }
            visibleStep("success", "教师进程正常退出（退出码 0）")
            visibleStep("start", "等待并解析教师结构化输出")
            // output.get 会把解析线程的异常带回当前线程；diagnostics.get 确认 stderr 也已排空。
            val reply = output.get(remaining(), NANOSECONDS)
            diagnostics.get(remaining(), NANOSECONDS)
            visibleStep("success", "教师输出解析完成")
            return reply
        } catch (_: TimeoutException) {
            // 超时先记录“即将清理”，真正的进程终止在 finally 中完成。
            visibleStep("failure", "教师调用超时，即将清理本次进程")
            throw TeacherException("TEACHER_TIMEOUT", "教师调用超时，已终止本次进程。")
        } catch (error: ExecutionException) {
            // 解析线程里的 TeacherException 保持原错误码；其他异常统一归为协议解析失败。
            val cause = error.cause
            if (cause is TeacherException) {
                visibleStep("failure", "教师调用失败（${cause.code}）")
                throw cause
            }
            visibleStep("failure", "教师结构化输出解析失败")
            throw TeacherException("TEACHER_PROTOCOL_ERROR", "无法解析教师的结构化输出。")
        } finally {
            // 无论成功、超时还是解析失败，都先终止仍挂接的子进程，再关闭线程池，防止泄漏。
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, SECONDS)
            }
            io.shutdownNow()
        }
    }

    /** 只记录进程可见步骤；辅助日志失败不能改变教师调用的成功/失败结果。 */
    private fun visibleStep(phase: String, description: String) {
        try {
            onStep(phase, description)
        } catch (_: Exception) {
            // 日志是训练设施，不能覆盖教师调用本身的异常或超时。
        }
    }

    companion object {
        /** 按本机 DSH 安装布局创建教师；只读权限和既有登录方式保持不变。 */
        fun deepSeek(
            installDir: Path,
            projectDir: Path,
            timeout: Duration,
            onStep: (phase: String, description: String) -> Unit = { _, _ -> },
        ): CliTeacher {
            // 这三个文件分别代表可执行程序、应用归档和本次只读启动补丁；缺一都不能安全启动。
            val executable = installDir.resolve("DeepSeek Harness.exe")
            val archive = installDir.resolve("resources/app.asar")
            val overlay = projectDir.resolve("tools/deepseek-teacher.patch.yml")
            if (!Files.isRegularFile(executable) || !Files.isRegularFile(archive)) {
                throw TeacherException("DSH_NOT_FOUND", "找不到 DeepSeek Harness；请设置 DSH_INSTALL_DIR。")
            }
            if (!Files.isRegularFile(overlay)) {
                throw TeacherException("CONFIG_ERROR", "找不到教师启动配置，请在项目目录运行。")
            }
            // asar 内部入口是当前 DSH 版本的 headless CLI；升级后需要重新确认路径。
            val entry = archive.resolve("dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js")
            return CliTeacher(
                // --json 让教师按 JSONL 输出；最后的 "-" 表示从标准输入读取问题。
                command = listOf(executable.toString(), "--expose-internals", entry.toString(),
                    "--profile", "headless", "--patch", overlay.toString(), "--json", "-"),
                // 教师产物写到 runs/teacher-workspace，和用户项目文件隔离。
                workspace = projectDir.resolve("runs/teacher-workspace"),
                timeout = timeout,
                // ELECTRON_RUN_AS_NODE 让桌面程序按 Node 运行；read-only 限制教师权限。
                environment = mapOf("ELECTRON_RUN_AS_NODE" to "1", "DSH_PERMISSION_MODE" to "read-only"),
                onStep = onStep,
            )
        }
    }
}
