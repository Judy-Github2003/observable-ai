// 本文件新增测试：DeepSeek Harness，2026-11-04；仅检查可读步骤日志，不扩展业务行为测试。
// 局部审计补充：Codex，2026-11-04；验证顺序、脱敏、查询异常及辅助日志失败不改变答题。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改测试数据、操作或断言。
package logic

import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

/**
 * 新增日志检查：关键函数/步骤确有记录，且 JSONL 仍逐行可解析。
 * 这里只验证训练设施的可读性，不把日志当作学生学习能力。
 */
class StepLogTest {
    // 每个测试用独立临时目录，JSONL、步骤日志和数据库互不干扰。
    @get:Rule val temp = TemporaryFolder()

    @Test fun `step log records key functions while JSONL stays parseable`() {
        val log = temp.root.toPath().resolve("events.jsonl")
        val db = temp.root.toPath().resolve("memory.db")
        // 准备：替身教师既返回原文，也发出可见工具请求/结果，便于检查日志阶段。
        var teacherCalls = 0
        val teacher = object : Teacher {
            override val runtime = "offline-teacher"
            override val model: String? = "fixture"
            override fun answer(question: String, observe: (ToolEvent) -> Unit): TeacherReply {
                teacherCalls++
                observe(ToolEvent("tool_request", "read"))
                observe(ToolEvent("tool_result", "read"))
                return TeacherReply("教师原文:$question", "step-session")
            }
        }
        val events = Events(log, studentVersion = "V1")
        val student = Student(teacher, AnswerMemory(db))

        // 第一次 miss→教师→保存，第二次命中；同一 run_id 下步骤顺序应连续。
        student.answer("什么是苹果？", events)
        student.answer("什么是苹果？", events)

        // 断言：JSONL 每行仍是合法 JSON，并保留 function 字段和 memory_hit 事件。
        val jsonl = Files.readAllLines(log)
        assertTrue(jsonl.isNotEmpty())
        val parsed = jsonl.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(parsed.any { it["function"]?.jsonPrimitive?.content == "AnswerMemory.find" })
        assertTrue(parsed.any { it["event_type"]?.jsonPrimitive?.content == "memory_hit" })

        // 断言：中文步骤日志包含关键函数和可读结果，但不复制教师原文。
        val steps = Files.readAllLines(temp.root.toPath().resolve("events.steps.log"))
        val readable = steps.joinToString("\n")
        assertTrue(readable.contains("Student.answer"))
        assertTrue(readable.contains("AnswerMemory.find"))
        assertTrue(readable.contains("AnswerMemory.save"))
        assertTrue(readable.contains("本地记忆命中"))
        assertTrue(readable.contains("记忆保存提交完成"))
        assertTrue(readable.contains("开始") && readable.contains("成功"))
        assertFalse(readable.contains("教师原文"))
        // 断言：JSONL 与步骤日志逐条对应，step_seq 从 1 连续递增，顺序没有跳号。
        assertEquals((1..parsed.size).toList(), parsed.map { it.getValue("step_seq").jsonPrimitive.int })
        assertEquals(parsed.size, steps.size)
        // 断言：tool_result 只是“收到工具结果”，不能标成 success（是否成功并不由此事件判断）。
        assertTrue(parsed.filter { it["event_type"]?.jsonPrimitive?.content == "tool_result" }
            .none { it["step_phase"]?.jsonPrimitive?.content == "success" })

        // 不可写的辅助日志不能阻断已有记忆复用；JSONL 的父事件链仍应连续。
        // 把一个目录当作 stepPath，写入步骤日志必然失败，用来验证降级路径。
        val blockedStepPath = temp.newFolder("blocked-steps").toPath()
        val fallbackLog = temp.root.toPath().resolve("fallback.jsonl")
        val fallbackEvents = Events(fallbackLog, studentVersion = "V1", stepPath = blockedStepPath)
        // 操作：再次问同一个苹果问题，应直接命中已有记忆，而不是新增教师调用。
        assertEquals("教师原文:什么是苹果？", student.answer("什么是苹果？", fallbackEvents).text)
        // 断言：即使步骤日志失败，JSONL 仍写入，且相邻事件的父链首尾相接。
        val fallbackRows = Files.readAllLines(fallbackLog).map { Json.parseToJsonElement(it).jsonObject }
        fallbackRows.zipWithNext().forEach { (previous, current) ->
            assertEquals(previous.getValue("event_id"), current.getValue("parent_event_ids").jsonArray.single())
        }
        assertEquals(1, teacherCalls)

        // 两种输出都脱敏；中文日志把换行收成一行，防止输入伪装成新步骤。
        events.step("Events.step", "Bearer demo-secret sk-offline_trace_secret\n下一行")
        // 断言：JSONL 和步骤日志都不含原始密钥；步骤日志最后一行被压成单行且保留脱敏标记。
        val safeText = Files.readString(events.stepPath)
        val safeJson = Files.readString(log)
        assertFalse(safeText.contains("demo-secret") || safeJson.contains("demo-secret"))
        assertFalse(safeText.contains("sk-offline_trace_secret") || safeJson.contains("sk-offline_trace_secret"))
        assertTrue(Files.readAllLines(events.stepPath).last().contains("[REDACTED] 下一行"))

        // 查询异常仍然抛出，不伪装成 miss，也不能新增教师调用。
        // 准备：破坏表结构，让下一次 AnswerMemory.find 真正抛 SQLException。
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { it.executeUpdate("DROP TABLE answers") }
        }
        // 操作/断言：异常原样抛出，教师调用次数不变，证明没有把数据库错误当成 miss。
        assertFailsWith<SQLException> { student.answer("未知问题", events) }
        assertEquals(1, teacherCalls)
        // 断言：最后一条 JSONL 是 AnswerMemory.find 的失败事件，函数和状态都可追踪。
        val failed = Files.readAllLines(log).map { Json.parseToJsonElement(it).jsonObject }.last()
        assertEquals("AnswerMemory.find", failed["function"]?.jsonPrimitive?.content)
        assertEquals("failed", failed["status"]?.jsonPrimitive?.content)
    }
}
