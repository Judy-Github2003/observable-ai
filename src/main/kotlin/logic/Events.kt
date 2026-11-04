// 本文件原始实现：Codex，2026-11-04。
// 本次中文注释与可读步骤日志：DeepSeek Harness 补充，2026-11-04。
// 局部审计修正：Codex，2026-11-04；辅助日志失败只提示一次，不影响事件链和答题。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改事件字段、脱敏或降级行为。
// 批量全链路扩展：DeepSeek Harness，2026-11-04；record 返回 event_id 并把 task_id 作为可选参数，
// 供 BatchCli/AuditTrail 把每个请求和子步骤的 trace 串起来；旧调用与旧字段保持不变。
package logic

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import java.util.UUID

/**
 * 对所有日志文本做统一脱敏。
 * 这里只处理两种常见凭据形态（Bearer 头和 sk- 前缀），避免把明显的密钥写进 JSONL 或步骤日志。
 */
internal fun redact(text: String): String = text
    .replace(Regex("(?i)\\bbearer\\s+[a-z0-9._~+/-]+"), "Bearer [REDACTED]")
    .replace(Regex("\\bsk-[A-Za-z0-9_-]{8,}"), "[REDACTED]")

/**
 * 根据 JSONL 路径推导相邻的中文步骤日志路径。
 * 例如 runs/events.jsonl -> runs/events.steps.log；其他后缀追加 .steps.log。
 */
private fun defaultStepPath(eventPath: Path): Path {
    // 没有文件名时给一个保守默认名；正常调用总会带 events.jsonl 之类的文件名。
    val name = eventPath.fileName?.toString() ?: "events.jsonl"
    // 只替换 .jsonl 后缀，避免把 events.jsonl 写成 events.jsonl.steps.log。
    val stepName = if (name.endsWith(".jsonl")) {
        name.removeSuffix(".jsonl") + ".steps.log"
    } else {
        "$name.steps.log"
    }
    return eventPath.resolveSibling(stepName)
}

/** 步骤日志只保留单行，避免描述中的换行伪装成另一条步骤或破坏按 run_id 阅读的顺序。 */
private fun oneLine(text: String): String = text.replace('\n', ' ').replace('\r', ' ')

/**
 * 训练设施的事件记录器，不属于学生记忆或推理能力。
 *
 * 每次 record 同时做两件事：
 * 1. 保持原有 JSONL 事件、字段和脱敏方式，追加 function/description/step_phase/step_seq 便于定位；
 * 2. 向相邻的 *.steps.log 追加一行中文可读步骤，包含序号、时间、版本、run_id、函数、阶段和说明；
 *    步骤日志属于辅助设施，写入失败不影响原有 JSONL 和答题结果。
 *
 * step 统一经过 record 的实例锁：串行处理本次运行的工具回调，不承诺跨进程文件互斥。
 */
class Events(
    // JSONL 主日志路径；原有事件都写到这里。
    private val path: Path,
    // 一次运行的稳定标识；同一 Events 实例产生的所有事件共用它。
    val runId: String = UUID.randomUUID().toString(),
    // 写入学生版本（V0/V1），便于区分基线行为和 V1 记忆行为。
    private val studentVersion: String = "V0",
    // 相邻中文步骤日志路径；默认由 JSONL 路径推导，测试可显式覆盖。
    val stepPath: Path = defaultStepPath(path),
) {
    // previous 保存上一条事件的 event_id，新事件用它形成父事件链，便于按顺序回放。
    private var previous: String? = null
    // sequence 是当前 Events 实例内的步骤序号；工具回调与主线程共用时靠下面的同步锁递增。
    private var sequence = 0
    // 辅助步骤日志失败时只向 stderr 提示一次，避免刷屏或影响 JSONL 事件链。
    private var stepLogWarningEmitted = false

    /**
     * 写入一个事件到 JSONL，并同步写一行中文步骤日志。
     *
     * function/description 是可选的语义说明；phase 用 start/success/failure/info
     * 区分“开始”“成功”“失败”“信息”，避免把准备执行写成已完成。
     * fields 继续承载原有事件字段，调用方不需要改变已有读取方式。
     */
    @Synchronized
    fun record(
        type: String,
        actor: String = "student",
        function: String? = null,
        description: String? = null,
        phase: String = "info",
        // 批量请求的 task_id 与一次 CLI 运行的 run_id 不一定相同；旧调用不传时仍沿用 run_id。
        // 返回 event_id，便于 Batch 层把请求 trace 精确指向实际写入的事件。
        taskId: String? = null,
        fields: JsonObjectBuilder.() -> Unit = {},
    ): String {
        // 每条事件独立 id；同一事件共用同一个 now，保证 JSONL 与步骤日志时间一致。
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        // 序号在 @Synchronized 内递增，避免多线程工具回调拿到重复序号。
        sequence++
        val seq = sequence
        val event = buildJsonObject {
            // 标识列：event_id 定位单条事件，run_id/task_id 用于按一次运行聚合。
            put("event_id", id)
            put("run_id", runId)
            put("task_id", taskId ?: runId)
            // 上下文列：时间、学生版本、写入者（student/teacher/harness）。
            put("timestamp", now.toString())
            put("student_version", studentVersion)
            put("actor", actor)
            put("event_type", type)
            // status 保持原读取兼容；失败阶段和 error 事件都标 failed，其余标 ok。
            put("status", if (type == "error" || phase == "failure") "failed" else "ok")
            // 父事件链只指向上一条成功写入 JSONL 的事件，便于重建写入顺序。
            putJsonArray("parent_event_ids") { previous?.let { add(it) } }
            // 新增的可读字段：序号保证顺序，function/description 说明语义，phase 区分开始/成功/失败。
            put("step_seq", seq)
            function?.let { put("function", it) }
            description?.let { put("description", it) }
            if (phase != "info") put("step_phase", phase)
            // fields() 继续追加调用方原有字段，保持旧事件 schema 兼容。
            fields()
        }
        // 先写 JSONL；JSONL 是原有主日志，写失败仍按原行为抛出。
        writeLine(path, event.toString())
        // 先推进父链：即使下面的辅助步骤日志失败，下一条 JSONL 事件也能接上当前事件。
        previous = id
        try {
            writeLine(stepPath, formatStepLine(seq, now, type, function, description, phase))
        } catch (_: Exception) {
            // 中文步骤日志是辅助可读设施；写入失败不能改变原有 JSONL 行为和答题结果。
            // 只向 stderr 提示一次，且不把异常细节或路径写进日志。
            if (!stepLogWarningEmitted) {
                System.err.println("提示：中文步骤日志写入失败，JSONL 记录仍已保留，请检查日志路径或权限。")
                stepLogWarningEmitted = true
            }
        }
        // 返回主事件 id；旧调用方忽略返回值，行为不变。
        return id
    }

    /**
     * 很小的步骤记录入口：写一条 event_type=step 的 JSONL 事件，
     * 并自动生成同一序号的中文步骤日志行。
     */
    fun step(
        function: String,
        description: String,
        phase: String = "info",
        actor: String = "harness",
    ): String {
        // 统一复用 record：这样步骤事件也拥有 event_id、父链、脱敏和同步保证。
        return record("step", actor, function, description, phase)
    }

    /** 中文步骤日志行；只使用本次传入的说明，不复制大段模型回答或原始诊断。 */
    private fun formatStepLine(
        seq: Int,
        now: Instant,
        type: String,
        function: String?,
        description: String?,
        phase: String,
    ): String {
        // 阶段转成中文，读者不必理解内部 start/success/failure 常量。
        val phaseLabel = when (phase) {
            "start" -> "开始"
            "success" -> "成功"
            "failure" -> "失败"
            else -> "信息"
        }
        // 没有 description 时退回事件类型；换行会被压成空格，防止伪造新步骤。
        val readable = oneLine(description ?: "事件 $type")
        val functionName = function ?: "-"
        // 固定列顺序：序号、时间、版本、run_id、函数、阶段、说明，方便按 run_id 阅读。
        return "#${seq.toString().padStart(4, '0')} | $now | $studentVersion | " +
            "run=$runId | $functionName | $phaseLabel | $readable"
    }

    /** JSONL 和步骤日志共用：创建父目录、脱敏后按 UTF-8 追加一行。 */
    private fun writeLine(target: Path, line: String) {
        // 首次写入时自动创建 runs/ 等父目录，调用方不必提前准备目录。
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        // CREATE + APPEND 保留历史记录；redact 是 JSONL 和步骤日志的共同脱敏入口。
        Files.writeString(target, redact(line) + "\n", CREATE, APPEND)
    }
}
