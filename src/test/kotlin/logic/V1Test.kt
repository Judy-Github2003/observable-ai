// 本文件原始测试：Codex，2026-11-04。
// 本次中文注释：DeepSeek Harness 补充，2026-11-04。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改测试数据、操作或断言。
package logic

import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

/** V1 行为：精确记忆持久化、关闭教师复用、未知问题和撤销。 */
class V1Test {
    // 每个测试使用独立临时目录，数据库和事件文件不会跨测试残留。
    @get:Rule val temp = TemporaryFolder()

    /**
     * 覆盖一次 miss→教师→保存、一次命中复用，以及关闭教师后的 unknown。
     * 同时确认不同问题/空白差异/全半角差异都不会误命中旧记录。
     */
    @Test fun `memory persists with provenance and a new question cannot return the old answer`() {
        // 准备：替身教师记录调用次数，并返回带会话号的固定格式回复。
        var calls = 0
        val teacher = object : Teacher {
            override val runtime = "offline-teacher"
            override val model = "fixture"
            override fun answer(question: String, observe: (ToolEvent) -> Unit): TeacherReply {
                calls++
                return TeacherReply("教师原文:$question", "session-$calls")
            }
        }
        val db = temp.root.toPath().resolve("memory.db")
        val log = temp.root.toPath().resolve("events.jsonl")
        // 每次 answer 新建 Events，模拟不同 run_id；数据库文件则复用。
        fun events() = Events(log, studentVersion = "V1")
        // 操作：第一次问题应 miss→教师→保存。
        val first = Student(teacher, AnswerMemory(db)).answer("什么是苹果？", events())
        // 断言：重新打开数据库仍能按原问题查回同一条记录及来源。
        val saved = AnswerMemory(db).find("什么是苹果？")!!
        assertEquals(first.sourceRunId, saved.sourceRunId)
        assertEquals("session-1", saved.teacherSessionId)
        assertEquals("offline-teacher", saved.runtime)

        // 重新打开存储并完全移除教师，验证已有记录仍可复用。
        val offline = Student(null, AnswerMemory(db))
        // 操作：完全相同的问题应命中记忆，不再调用教师。
        val second = offline.answer("什么是苹果？", events())
        // 断言：文本不变、来源为 local_memory，且教师调用次数仍为 1。
        assertEquals(first.text, second.text)
        assertEquals("local_memory", second.source)
        assertEquals(1, calls)
        // 操作：不同问题、尾部空格、半角问号都应 miss；关闭教师后返回 unknown。
        for (question in listOf("什么是香蕉？", "什么是苹果？ ", "什么是苹果?")) {
            val unknown = offline.answer(question, events())
            // 断言：来源和 JSON 内部 status 都是 unknown，证明没有误命中。
            assertEquals("unknown", unknown.source)
            assertEquals("unknown", Json.parseToJsonElement(unknown.text).jsonObject["status"]?.jsonPrimitive?.content)
        }
        // 操作：香蕉是新问题，应再次调用教师；断言回复正确且累计调用为 2。
        val banana = Student(teacher, AnswerMemory(db)).answer("什么是香蕉？", events())
        assertEquals("教师原文:什么是香蕉？", banana.text)
        assertEquals(2, calls)
        // 操作：撤销苹果记忆后，即使数据库里还有行也不能再命中。
        assertTrue(AnswerMemory(db).revoke("什么是苹果？"))
        assertEquals("unknown", Student(null, AnswerMemory(db)).answer("什么是苹果？", events()).source)
        // 断言：整个文件仍可逐行解析，且只有两次真正调用教师，版本统一为 V1。
        val recorded = Files.readAllLines(log).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, recorded.count { it["event_type"]?.jsonPrimitive?.content == "teacher_request" })
        assertTrue(recorded.all { it["student_version"]?.jsonPrimitive?.content == "V1" })
    }
}
