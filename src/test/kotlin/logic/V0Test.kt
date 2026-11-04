// 本文件原始测试：Codex，2026-11-04。
// 本次中文注释：DeepSeek Harness 补充，2026-11-04。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改测试数据、操作或断言。
package logic

import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*
import kotlin.system.exitProcess

/** V0 基线：每次问题都调用教师，不使用 V1 记忆；同时检查事件与脱敏。 */
class V0Test {
    // 每个测试用独立临时目录，避免事件文件和数据库互相污染。
    @get:Rule val temp = TemporaryFolder()

    /** 同一问题重复两次应产生两次教师调用和两个独立 run_id。 */
    @Test fun `repeated questions call the teacher twice and preserve trace and answer`() {
        // 准备：用计数器替身教师，确认 V0 不会因重复问题而缓存或跳过调用。
        var calls = 0
        val teacher = object : Teacher {
            override val runtime = "offline-test"
            override val model: String? = null
            override fun answer(question: String, observe: (ToolEvent) -> Unit): TeacherReply {
                calls++
                // 断言教师收到的仍是原始问题，而不是被 trim 或改写后的文本。
                assertEquals("什么是苹果？", question)
                return TeacherReply("原样回复\n第二行\n", "test-session")
            }
        }
        val log = temp.root.toPath().resolve("events.jsonl")
        val student = Student(teacher)
        // 操作：每次 answer 都新建 Events，因此两次调用应各有一个独立 run_id。
        repeat(2) { assertEquals("原样回复\n第二行\n", student.answer("什么是苹果？", Events(log)).text) }
        // 断言：教师确实被调用两次；答案已在上面 repeat 中逐字校验。
        assertEquals(2, calls)
        val events = Files.readAllLines(log).map { Json.parseToJsonElement(it).jsonObject }
        // 断言：每次调用都写 teacher_request，且事件里的版本统一为 V0。
        assertEquals(2, events.count { it["event_type"]?.jsonPrimitive?.content == "teacher_request" })
        assertEquals(2, events.map { it.getValue("run_id") }.distinct().size)
        assertTrue(events.all { it["student_version"]?.jsonPrimitive?.content == "V0" })
        // 断言：脱敏入口会把测试用 token 和 sk- 密钥替换掉。
        assertEquals("Bearer [REDACTED] [REDACTED]", redact("Bearer test-token sk-offline_test_secret"))
    }

    /** 本地 CLI fixture 应正确完成 Unicode stdin 往返，并暴露可见工具名称。 */
    @Test fun `offline CLI exchanges Unicode over stdin and records tool metadata`() {
        // 准备：收集 CliTeacher 通过 observe 暴露的可见工具事件。
        val observations = mutableListOf<ToolEvent>()
        // 操作：fixture 从 stdin 读入含换行的中文问题，再按协议输出结果。
        val result = fixture("ok").answer("苹果\n香蕉", observations::add)
        // 断言：Unicode 和换行没有被截断，会话标识被解析出来。
        assertEquals("原样:苹果\n香蕉", result.text)
        assertEquals("offline-session", result.sessionId)
        // 断言：只暴露工具请求/结果及其名称，不暴露工具参数或结果内容。
        assertEquals(listOf(ToolEvent("tool_request", "read"), ToolEvent("tool_result", "read")), observations)
    }

    /** 子进程非零退出时即使输出过 final，也必须按失败处理。 */
    @Test fun `nonzero CLI exit is a failure even if it emitted a final answer`() {
        // 操作：fixture 先输出 final，再以退出码 3 结束；进程状态必须优先于半截答案。
        val error = assertFailsWith<TeacherException> { fixture("fail").answer("问题") {} }
        // 断言：失败被映射为稳定的 TEACHER_FAILED，而不是返回 final 文本。
        assertEquals("TEACHER_FAILED", error.code)
    }

    /** 超时后必须终止挂起的教师进程，避免测试和真实调用永久阻塞。 */
    @Test fun `stalled CLI is terminated by the deadline`() {
        // 准备：记录开始时间，给 fixture 很短的 500ms 超时。
        val started = System.nanoTime()
        // 操作：fixture 睡眠 30 秒；CliTeacher 应在 deadline 到达时主动终止。
        val error = assertFailsWith<TeacherException> {
            fixture("hang", Duration.ofMillis(500)).answer("问题") {}
        }
        // 断言：错误码为超时，且整个测试在 5 秒内结束，说明没有无限等待。
        assertEquals("TEACHER_TIMEOUT", error.code)
        assertTrue(Duration.ofNanos(System.nanoTime() - started).seconds < 5)
    }

    /** 用当前 JVM 启动本地 fixture 子进程，不访问网络或外部服务。 */
    private fun fixture(mode: String, timeout: Duration = Duration.ofSeconds(10)) = CliTeacher(
        // 准备：复用当前 JVM 和测试运行时 classpath，确保 fixture 不依赖外部安装。
        command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("test.runtime.classpath"), "logic.CliFixture", mode),
        // 工作目录放在临时目录，避免向候选目录写运行产物。
        workspace = temp.root.toPath(), timeout = timeout,
    )
}

/** 本地子进程 fixture；只模拟教师协议，不是网络服务或另一项学生学习能力。 */
object CliFixture {
    /** 从 stdin 读取问题，按模式输出会话、工具和最终回复 JSON。 */
    @JvmStatic fun main(args: Array<String>) {
        // 与真实教师一样从标准输入读完整问题；fixture 只做原样回显。
        val question = System.`in`.reader(Charsets.UTF_8).readText()
        // hang 模式故意长时间不输出，用于验证调用方的 deadline 和进程清理。
        if (args[0] == "hang") { Thread.sleep(30_000); return }
        // 按协议先发会话，再发工具请求/结果，最后发 final 文本。
        println("""{"type":"session","id":"offline-session"}""")
        println("""{"type":"tool_call","tool":"read"}""")
        println("""{"type":"tool_result","tool":"read"}""")
        println(buildJsonObject { put("type", "final"); put("text", "原样:$question") })
        // fail 模式已经输出 final，但仍用非零退出码验证“退出状态优先”。
        if (args[0] == "fail") exitProcess(3)
    }
}
