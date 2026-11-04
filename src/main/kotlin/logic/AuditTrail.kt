// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：批量契约要求每个操作和子步骤都有 rule_enter/rule_exit/rule_error 事件、
// 稳定的 span/trace、规则出处、实际参数、事实依据和耗时；本文件只记录可见执行，不记录思考。
// 2026-11-04 Codex 局部修正：规则失败出口补显式空 fact_ids，供审计区分“无依据”与“未记录”。
package logic

import kotlinx.serialization.json.*
import java.time.Duration
import java.util.UUID

/** 批量操作的状态；error 只用于参数/存储/执行故障，未知信息用 unknown。 */
object OpStatus {
    const val OK = "ok"
    const val UNKNOWN = "unknown"
    const val CONFLICT = "conflict"
    const val ERROR = "error"
}

/**
 * 一次操作的结构化结果。
 *
 * result 直接进入外层 JSON 的 result 字段；reason 只在 unknown/conflict/error 时解释原因；
 * factIds 是本操作实际读取或写入的事实依据，供规则事件与报告展开证据链。
 */
data class OpOutcome(
    val status: String,
    val result: JsonObject = JsonObject(emptyMap()),
    val reason: String? = null,
    val factIds: List<String> = emptyList(),
    // AuditTrail 执行后回填，便于父操作（actions.run/plan.run）引用真实子 span。
    val spanId: String? = null,
)

/** 规则执行期间传给实现函数的 span 信息；子操作会把它作为 parent_span_id。 */
data class RuleSpan(
    val spanId: String,
    val parentSpanId: String?,
    val rule: RuleInfo,
)

/**
 * 全链路审计包装器。
 *
 * 关键约束：
 * 1. 先写 rule_enter，再执行规则；成功执行出的 unknown/conflict 也写 rule_exit，而不是伪装成成功或失败；
 * 2. 只有异常或返回 error 才写 rule_error；
 * 3. 子操作通过 currentParent 继承父 span，不能让子调用变成没有来源的最终答案；
 * 4. trace_ids 收集真实写入事件的 event_id，供外层结果和报告回查。
 */
class AuditTrail(
    val events: Events,
    val catalog: RuleCatalog,
    val taskId: String,
    val lessonId: String,
    val worldId: String,
    val traceId: String = "trace-${UUID.randomUUID()}",
) {
    private var currentParent: String? = null
    private val traceEventIds = LinkedHashSet<String>()
    private var requestStartedNanos: Long = System.nanoTime()

    /** 当前结果可见的 trace 事件 id；request_completed 写入后调用方取最终列表。 */
    val traceIds: List<String> get() = traceEventIds.toList()

    /** 当前规则 span；actions.run 等父操作记录准备/提交阶段时使用。 */
    val currentSpanId: String? get() = currentParent

    /** 写请求接收事件，返回 event_id；这是每个请求事件链的起点。 */
    fun beginRequest(op: String?, args: JsonElement?): String {
        requestStartedNanos = System.nanoTime()
        val id = events.record(
            type = "request_received",
            actor = "student",
            function = "BatchCli.processRequest",
            description = "收到批量请求：${op ?: "invalid"}" ,
            phase = "start",
            taskId = taskId,
        ) {
            put("lesson_id", lessonId)
            put("world_id", worldId)
            put("op", op ?: "")
            put("trace_id", traceId)
            args?.let { put("args", it) }
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }
        traceEventIds += id
        return id
    }

    /**
     * 执行一条规则并记录进入、完成/失败事件。
     *
     * commitState 用于区分“已在内存准备”和“已持久化提交”；actions.run 的子步骤先记 prepared，
     * 全部成功并落盘后再由父事件记 committed，避免把准备阶段写成已完成。
     */
    fun invokeRule(
        ruleId: String,
        op: String,
        args: JsonObject,
        commitState: String = "committed",
        block: (RuleSpan) -> OpOutcome,
    ): OpOutcome {
        val rule = catalog.require(ruleId)
        val spanId = "span-${UUID.randomUUID()}"
        val parent = currentParent
        val started = System.nanoTime()
        val enterId = events.record(
            type = "rule_enter",
            actor = "student",
            function = rule.function,
            description = "开始执行规则 ${rule.ruleId}：${rule.title}",
            phase = "start",
            taskId = taskId,
        ) {
            putRuleContext(rule)
            put("lesson_id", lessonId)
            put("world_id", worldId)
            put("op", op)
            put("args", args)
            put("trace_id", traceId)
            put("span_id", spanId)
            put("parent_span_id", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("commit_state", "prepared")
            // 进入时尚未读取事实；空数组明确“还没有依据”，完成事件再填实际 fact_ids。
            putJsonArray("fact_ids") { }
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }
        traceEventIds += enterId

        val previousParent = currentParent
        currentParent = spanId
        try {
            val outcome = block(RuleSpan(spanId, parent, rule))
            val duration = Duration.ofNanos(System.nanoTime() - started).toMillis()
            if (outcome.status == OpStatus.ERROR) {
                recordRuleError(rule, op, spanId, parent, outcome.reason ?: "rule_error", null, duration)
            } else {
                val exitId = events.record(
                    type = "rule_exit",
                    actor = "student",
                    function = rule.function,
                    description = "规则执行完成 ${rule.ruleId}：状态 ${outcome.status}",
                    phase = if (outcome.status == OpStatus.OK) "success" else "info",
                    taskId = taskId,
                ) {
                    putRuleContext(rule)
                    put("lesson_id", lessonId)
                    put("world_id", worldId)
                    put("op", op)
                    put("trace_id", traceId)
                    put("span_id", spanId)
                    put("parent_span_id", parent?.let(::JsonPrimitive) ?: JsonNull)
                    // 不覆盖 Events 原有的通用 status（表示事件写入是否失败）；规则状态单独命名。
                    put("rule_status", outcome.status)
                    put("result", outcome.result)
                    put("reason", outcome.reason?.let(::JsonPrimitive) ?: JsonNull)
                    putJsonArray("fact_ids") { outcome.factIds.forEach { add(it) } }
                    put("duration_ms", duration)
                    put("commit_state", commitState)
                    put("teacher_invocation_count", 0)
                    put("model_call_count", 0)
                }
                traceEventIds += exitId
            }
            return outcome.copy(spanId = spanId)
        } catch (error: Exception) {
            val duration = Duration.ofNanos(System.nanoTime() - started).toMillis()
            recordRuleError(rule, op, spanId, parent, classifyError(error), error, duration)
            throw error
        } finally {
            // 恢复父 span：兄弟子操作不能误挂在已经结束的 span 下。
            currentParent = previousParent
        }
    }

    /** 写一条非规则事件（动作准备/提交、轮次变化等），仍属于当前 trace。 */
    fun note(
        type: String,
        description: String,
        phase: String,
        span: RuleSpan? = null,
        fields: JsonObjectBuilder.() -> Unit = {},
    ) {
        val id = events.record(
            type = type,
            actor = "harness",
            function = span?.rule?.function ?: "BatchCli.processRequest",
            description = description,
            phase = phase,
            taskId = taskId,
        ) {
            put("lesson_id", lessonId)
            put("world_id", worldId)
            put("trace_id", traceId)
            if (span != null) {
                put("span_id", span.spanId)
                put("parent_span_id", span.parentSpanId?.let(::JsonPrimitive) ?: JsonNull)
                putRuleContext(span.rule)
            }
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
            fields()
        }
        traceEventIds += id
    }

    /** 写请求完成事件；即使状态 unknown/conflict 也代表请求被正常处理完。 */
    fun completeRequest(op: String, outcome: OpOutcome) {
        val duration = Duration.ofNanos(System.nanoTime() - requestStartedNanos).toMillis()
        val snapshot = traceEventIds.toList()
        val id = events.record(
            type = "request_completed",
            actor = "student",
            function = "BatchCli.processRequest",
            description = "批量请求处理完成：${outcome.status}",
            phase = if (outcome.status == OpStatus.OK) "success" else "info",
            taskId = taskId,
        ) {
            put("lesson_id", lessonId)
            put("world_id", worldId)
            put("op", op)
            put("operation_status", outcome.status)
            put("result", outcome.result)
            put("reason", outcome.reason?.let(::JsonPrimitive) ?: JsonNull)
            put("trace_id", traceId)
            putJsonArray("trace_ids") { snapshot.forEach { add(it) } }
            put("duration_ms", duration)
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }
        traceEventIds += id
    }

    /** 写请求失败事件；参数/存储/内部故障都要有终点，不能只留下 received。 */
    fun failRequest(op: String, errorCode: String, message: String?) {
        val duration = Duration.ofNanos(System.nanoTime() - requestStartedNanos).toMillis()
        val snapshot = traceEventIds.toList()
        val id = events.record(
            type = "request_failed",
            actor = "harness",
            function = "BatchCli.processRequest",
            description = "批量请求处理失败：$errorCode",
            phase = "failure",
            taskId = taskId,
        ) {
            put("lesson_id", lessonId)
            put("world_id", worldId)
            put("op", op)
            put("operation_status", OpStatus.ERROR)
            put("reason", errorCode)
            put("error_code", errorCode)
            message?.let { put("error_message", redact(it).replace('\n', ' ').replace('\r', ' ')) }
            put("trace_id", traceId)
            putJsonArray("trace_ids") { snapshot.forEach { add(it) } }
            put("duration_ms", duration)
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }
        traceEventIds += id
    }

    /** 规则元数据在 enter/exit/error 中重复出现，便于单独筛选任意一条事件。 */
    private fun JsonObjectBuilder.putRuleContext(rule: RuleInfo) {
        put("rule_id", rule.ruleId)
        put("rule_version", rule.version)
        put("rule_title", rule.title)
        put("introduced_lesson", rule.introducedLesson)
        put("rule_acquired_at", rule.acquiredAt)
        put("rule_reason", rule.reason)
        put("rule_author", rule.author)
        put("acquisition_method", rule.acquisitionMethod)
        put("source_file", rule.sourceFile)
        put("source_anchor", rule.sourceAnchor)
    }

    private fun recordRuleError(
        rule: RuleInfo,
        op: String,
        spanId: String,
        parent: String?,
        errorCode: String,
        error: Exception?,
        duration: Long,
    ) {
        val id = events.record(
            type = "rule_error",
            actor = "harness",
            function = rule.function,
            description = "规则执行失败 ${rule.ruleId}：$errorCode",
            phase = "failure",
            taskId = taskId,
        ) {
            putRuleContext(rule)
            put("lesson_id", lessonId)
            put("world_id", worldId)
            put("op", op)
            put("trace_id", traceId)
            put("span_id", spanId)
            put("parent_span_id", parent?.let(::JsonPrimitive) ?: JsonNull)
            put("rule_status", OpStatus.ERROR)
            put("error_code", errorCode)
            // 2026-11-04 Codex 修正：失败的规则出口也要显式写 fact_ids。
            // 规则执行失败时通常没有可用依据，但字段缺失会让审计端无法区分“无依据”和“没记录”。
            putJsonArray("fact_ids") { }
            error?.message?.let {
                put("error_message", redact(it).replace('\n', ' ').replace('\r', ' '))
            }
            put("duration_ms", duration)
            put("commit_state", "aborted")
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }
        traceEventIds += id
    }

    /** 稳定错误分类：参数、存储、状态和内部故障分开，报告才能定位最早失败节点。 */
    private fun classifyError(error: Exception): String = when (error) {
        is BatchArgumentException -> "invalid_argument"
        is IllegalArgumentException -> "invalid_argument"
        is java.io.IOException -> "storage_error"
        is IllegalStateException -> "state_error"
        else -> "internal_error"
    }
}
