// 本文件原始实现：Codex，2026-11-04。
// 本次中文注释与可读步骤日志：DeepSeek Harness 补充，2026-11-04。
// 局部审计修正：Codex，2026-11-04；如实记录查询异常、工具结果和跳过的操作。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改分支、计时、来源或日志行为。
package logic

import kotlinx.serialization.json.*
import java.time.Duration
import java.sql.SQLException

/** 教师返回的原始回复和可选的教师会话标识；文本始终按未核验处理。 */
data class TeacherReply(
    // 教师给出的原始文本，可能多行；项目不会把它升级为已核验知识。
    val text: String,
    // 教师侧会话标识，仅用于追溯；测试或离线教师可能为 null。
    val sessionId: String? = null,
)

/** 教师可见的工具操作事件；只表示工具请求/结果，不包含模型内部推理。 */
data class ToolEvent(
    // 事件类型，例如 tool_request/tool_result；决定日志阶段和可读说明。
    val type: String,
    // 工具名称，例如 read；只记录名称，不记录参数或结果内容。
    val name: String,
)

/** 学生回答及来源；sourceRunId 用于追溯记忆中的原始运行。 */
data class StudentAnswer(
    // 最终返回给 Main 的文本，可能是教师回复或 unknown JSON。
    val text: String,
    // 来源标记：local_memory/external_teacher/mock_teacher/unknown。
    val source: String,
    // 命中本地记忆时的原始运行 id；新调用教师时通常为当前 run_id。
    val sourceRunId: String? = null,
)

/** 外部教师的最小接口；实现方只负责回答，学习与记忆由 Student 控制。 */
interface Teacher {
    // 运行框架标识，用于来源标记和日志。
    val runtime: String
    // 配置的模型名；离线 mock 或未知来源可为 null。
    val model: String?
    // 模型提供方；默认 null，真实实现可覆盖。
    val provider: String? get() = null

    /** 向教师提问；observe 只报告可见工具请求/结果，不暴露内部推理。 */
    fun answer(question: String, observe: (ToolEvent) -> Unit): TeacherReply
}

/** 教师调用失败的稳定错误码；Main 负责输出给终端。 */
class TeacherException(val code: String, message: String) : RuntimeException(message)

/** 离线 mock 教师，仅用于本地测试和演示，不访问外部服务。 */
class MockTeacher : Teacher {
    // 固定来源标记，让日志和答案能区分 mock 与真实教师。
    override val runtime = "mock"
    override val model: String? = null
    override fun answer(question: String, observe: (ToolEvent) -> Unit) = TeacherReply("MOCK: $question")
}

/**
 * V0 只把问题转给教师；V1 在此基础上增加“完全相同的原始问题”持久复用。
 * 这里不判断语义，也不把教师回复提升为已核验知识。
 */
class Student(
    // teacher=null 表示 --no-teacher；此时只允许复用已有记忆。
    private val teacher: Teacher?,
    // memory=null 表示 V0 或未启用 V1 记忆；V1 传入 AnswerMemory。
    private val memory: AnswerMemory? = null,
) {
    /**
     * 处理一次问题：先查本地记忆，未命中才按配置调用教师，V1 保存原始回复。
     * 关键分支都会写 JSONL 事件和中文步骤日志，便于区分命中、miss、unknown 和失败。
     */
    fun answer(question: String, events: Events): StudentAnswer {
        // 空问题不进入记忆或教师，避免把无意义输入写成学习记录。
        require(question.isNotBlank()) { "问题不能为空。" }
        // 先记“开始”，再记 input；读者能看出接收问题发生在查询之前。
        events.step("Student.answer", "开始处理问题", phase = "start")
        events.record(
            "input", "harness",
            function = "Student.answer",
            description = "已接收问题输入",
        ) { put("input", question) }

        // 只有 V1 有记忆；查询使用原始问题作为精确键，不做 trim 或同义改写。
        val saved = if (memory != null) {
            events.step("AnswerMemory.find", "开始按原始问题精确查询本地记忆", phase = "start")
            try {
                memory.find(question)
            } catch (error: SQLException) {
                // 查询故障不能当作 miss；否则会误调用教师并掩盖数据库问题。
                // 记录失败事件用 runCatching 包裹：辅助日志失败也不能吞掉原始 SQLException。
                runCatching {
                    events.record("error", "harness", function = "AnswerMemory.find",
                        description = "本地记忆查询失败，未当作未命中继续调用教师", phase = "failure") {
                        put("error_code", "MEMORY_ERROR")
                    }
                }
                throw error
            }
        } else {
            // V0 没有记忆，明确记录“不查询”，避免读者误以为发生了一次 miss。
            events.step("Student.answer", "V0 不查询本地记忆", phase = "info")
            null
        }

        if (saved != null) {
            // 命中后直接复用原始教师回复，不再调用教师，也不改变未核验标记。
            // 事件同时带回原始来源 run_id、教师会话、运行框架、模型和保存时间。
            events.record(
                "memory_hit", "student",
                function = "AnswerMemory.find",
                description = "本地记忆命中，复用未核验的原始教师回复",
                phase = "success",
            ) {
                put("question_key", question)
                put("source_run_id", saved.sourceRunId)
                put("teacher_session_id", saved.teacherSessionId?.let(::JsonPrimitive) ?: JsonNull)
                put("source_runtime", saved.runtime)
                put("configured_model", saved.model?.let(::JsonPrimitive) ?: JsonNull)
                put("created_at", saved.createdAt)
                put("verification_status", "unverified")
                // 命中复用不调用教师，所以本次教师/模型调用计数为 0。
                put("teacher_invocation_count", 0)
                put("model_call_count", 0)
            }
            events.record(
                "answer", "student",
                function = "Student.answer",
                description = "返回本地记忆中的答案",
                phase = "success",
            ) {
                // output 是原样复用文本；source=local_memory 让 Main 提示来源。
                put("output", saved.text); put("source", "local_memory")
                put("source_run_id", saved.sourceRunId); put("verification_status", "unverified")
            }
            events.step("Student.answer", "处理完成，来源为本地记忆", phase = "success")
            return StudentAnswer(saved.text, "local_memory", saved.sourceRunId)
        }

        // V1 未命中只记一次 miss；V0 没有记忆，不产生该事件。
        if (memory != null) {
            events.record(
                "memory_miss", "student",
                function = "AnswerMemory.find",
                description = "本地记忆未命中",
                phase = "info",
            ) { put("question_key", question) }
        }

        if (teacher == null) {
            // 教师关闭且没有可复用记录时，明确返回 unknown，不暗中调用教师。
            // JSON 里保留 status/reason/question，调用方可区分“关闭教师”和“调用失败”。
            val unknown = buildJsonObject {
                put("status", "unknown"); put("reason", "teacher_disabled"); put("question", question)
            }.toString()
            events.record(
                "answer", "student",
                function = "Student.answer",
                description = "教师已关闭且无可用记忆，返回 unknown",
                phase = "success",
            ) {
                // 这里是成功处理出的 unknown 结果，不是异常；调用计数仍为 0。
                put("output", unknown); put("source", "unknown"); put("status", "unknown")
                put("teacher_invocation_count", 0); put("model_call_count", 0)
            }
            events.step("Student.answer", "处理完成，来源为 unknown", phase = "success")
            return StudentAnswer(unknown, "unknown")
        }

        // 教师调用开始：这里是发起外部/模拟教师调用的语义起点，不是完成。
        // route 区分 mock 教师和真实 agent，runtime/provider/model 用于来源追溯。
        events.record(
            "teacher_request", "student",
            function = "Student.answer",
            description = "教师调用开始",
            phase = "start",
        ) {
            put("runtime", teacher.runtime)
            put("model_provider", teacher.provider?.let(::JsonPrimitive) ?: JsonNull)
            put("runtime_version", JsonNull)
            put("configured_model", teacher.model?.let(::JsonPrimitive) ?: JsonNull)
            put("route", if (teacher.runtime == "mock") "teacher_mock" else "teacher_agent")
        }
        // 用单调时钟测耗时，避免系统时间被调整时出现负数或异常延迟。
        val started = System.nanoTime()
        try {
            val reply = teacher.answer(question) { tool ->
                // 工具回调可能来自教师输出线程；Events.record 内部同步，保证序号和追加安全。
                // tool_request 只表示开始；tool_result 只表示“收到结果”，不能据此断言工具成功，
                // 因此使用 info 阶段，避免把未知成功状态写成 success。
                val (description, phase) = when (tool.type) {
                    "tool_request" -> "教师可见工具请求：${tool.name}" to "start"
                    "tool_result" -> "收到教师工具结果：${tool.name}（本事件不判断工具是否成功）" to "info"
                    else -> "教师可见步骤：${tool.type}" to "info"
                }
                events.record(
                    tool.type, "teacher",
                    function = "CliTeacher.answer",
                    description = description,
                    phase = phase,
                ) { put("operation_id", tool.name) }
            }
            // 教师返回后先记结果：回复原文可能很长，只把原文放进 JSONL，不放进步骤说明。
            events.record(
                "teacher_result", "teacher",
                function = "Student.answer",
                description = "教师调用返回，回复保持未核验",
                phase = "success",
            ) {
                put("response", reply.text)
                put("verification_status", "unverified")
                put("teacher_session_id", reply.sessionId?.let(::JsonPrimitive) ?: JsonNull)
                // latency_ms 使用前面单调时钟的起点，只衡量本次教师调用耗时。
                put("latency_ms", Duration.ofNanos(System.nanoTime() - started).toMillis())
                put("teacher_invocation_count", 1)
                // mock 没有真实模型调用，计 0；真实教师无法取得内部模型调用数时为 null。
                put("model_call_count", if (teacher.runtime == "mock") JsonPrimitive(0) else JsonNull)
                put("usage", JsonNull)
                put("trace_completeness", "projected_events_only")
            }

            // 只有非空回复才保存；save 内部提交事务，返回即表示提交完成。
            if (memory != null && reply.text.isNotBlank()) {
                events.step("AnswerMemory.save", "开始保存教师原始回复", phase = "start")
                memory.save(question, reply, teacher, events.runId)
                events.record(
                    "memory_saved", "student",
                    function = "AnswerMemory.save",
                    description = "记忆保存提交完成",
                    phase = "success",
                ) {
                    // 保存的是原始教师答案；来源 run_id 取本次运行，匹配键仍为原始问题。
                    put("question_key", question); put("source_run_id", events.runId)
                    put("kind", "raw_teacher_answer"); put("verification_status", "unverified")
                    put("normalizer_version", "identity-v1")
                }
            } else if (memory != null) {
                // 空回复不写入记忆；如实记录“跳过”，避免读者以为保存发生过。
                events.step("Student.answer", "教师回复为空，跳过 AnswerMemory.save", phase = "info")
            }

            events.record(
                "answer", "student",
                function = "Student.answer",
                description = "返回教师回复",
                phase = "success",
            ) {
                // output 是教师原文；source 按 mock/真实教师区分，文本始终未核验。
                put("output", reply.text)
                put("source", if (teacher.runtime == "mock") "mock_teacher" else "external_teacher")
                put("verification_status", "unverified")
            }
            events.step("Student.answer", "处理完成，来源为教师", phase = "success")
            // 新调用教师时把本次 run_id 作为来源，便于之后从记忆记录追溯。
            return StudentAnswer(reply.text, if (teacher.runtime == "mock") "mock_teacher" else "external_teacher", events.runId)
        } catch (error: TeacherException) {
            // 失败路径也留下可追踪步骤，但不复制教师原始诊断或内部思考。
            events.record(
                "error", "harness",
                function = "Student.answer",
                description = "教师调用失败",
                phase = "failure",
            ) { put("error_code", error.code) }
            events.step("Student.answer", "处理失败，教师调用异常", phase = "failure")
            throw error
        } catch (error: SQLException) {
            // 保存阶段数据库异常同样不能静默；记录 MEMORY_ERROR 后原样抛出给 Main。
            events.record(
                "error", "harness",
                function = "Student.answer",
                description = "记忆数据库操作失败",
                phase = "failure",
            ) { put("error_code", "MEMORY_ERROR") }
            events.step("Student.answer", "处理失败，记忆数据库异常", phase = "failure")
            throw error
        }
    }
}
