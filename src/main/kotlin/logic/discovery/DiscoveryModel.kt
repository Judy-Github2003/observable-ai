// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：Observable AI V2 规则自主发现的最小内核；不修改 V1 的 BatchStudent/Main/AuditTrail。
// 本文件只放数据模型、JSON 读写与校验辅助；规则语义在 ConsistencyEngine/RuleInterpreter。
package logic.discovery

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal const val ALGORITHM_VERSION = "discovery-v1"
internal const val SCHEMA_VERSION = "discovery-v1"

internal val discoveryJson = Json { prettyPrint = false }

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun nowIso(): String = Instant.now().toString()
internal fun newTraceId(): String = "trace-${UUID.randomUUID()}"

/** 参数问题统一抛这个；CLI 转成 invalid_input，不把内部异常伪装成 unknown。 */
internal class DiscoveryInputException(message: String) : IllegalArgumentException(message)

// ---------- JSON 读取辅助：所有错误都带位置，便于审计 ----------

internal fun JsonElement.requireObject(where: String): JsonObject =
    this as? JsonObject ?: throw DiscoveryInputException("$where 必须是对象")

internal fun JsonElement.requireArray(where: String): JsonArray =
    this as? JsonArray ?: throw DiscoveryInputException("$where 必须是数组")

internal fun JsonObject.requireString(key: String, where: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw DiscoveryInputException("$where.$key 必须是非空字符串")

internal fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requireBoolean(key: String, where: String): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString.not() && (it.content == "true" || it.content == "false") }
        ?.booleanOrNull
        ?: throw DiscoveryInputException("$where.$key 必须是布尔值")

internal fun JsonElement.requireStringList(where: String): List<String> =
    requireArray(where).mapIndexed { index, element ->
        val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw DiscoveryInputException("$where[$index] 必须是非空字符串")
        if (value.isEmpty()) throw DiscoveryInputException("$where[$index] 不能为空")
        value
    }

internal fun JsonElement.requireFeatureMap(where: String): LinkedHashMap<String, String> {
    val obj = requireObject(where)
    val map = LinkedHashMap<String, String>()
    obj.forEach { (key, value) ->
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw DiscoveryInputException("$where.$key 必须是字符串")
        map[key] = text
    }
    return map
}

internal fun JsonObject.requireFeatureMap(key: String, where: String): LinkedHashMap<String, String> {
    val element = this[key] ?: throw DiscoveryInputException("$where.$key 缺失")
    return element.requireFeatureMap("$where.$key")
}

// ---------- 规则与候选 ----------

internal data class Clause(val field: String, val eq: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("field", field)
        put("eq", eq)
    }

    companion object {
        fun fromJson(obj: JsonObject): Clause = Clause(
            obj.requireString("field", "clause"),
            obj.requireString("eq", "clause"),
        )
    }
}

/**
 * 受限 DSL：单条件就是 1 个 clause；双条件就是 2 个 clause 的 AND。
 * 这里不保存 op，因为当前只支持 AND；输出时统一补 "op":"and"。
 */
internal data class Rule(val clauses: List<Clause>) {
    init {
        require(clauses.size in 1..2) { "规则只支持 1..2 个条件" }
        require(clauses.map { it.field }.distinct().size == clauses.size) { "同一规则不能重复字段" }
    }

    fun canonical(fieldOrder: List<String>): Rule =
        Rule(clauses.sortedBy { fieldOrder.indexOf(it.field).let { index -> if (index < 0) Int.MAX_VALUE else index } })

    fun hash(): String = sha256(toJson().toString())

    fun toJson(): JsonObject = buildJsonObject {
        put("op", "and")
        putJsonArray("clauses") { clauses.forEach { add(it.toJson()) } }
    }

    companion object {
        fun fromJson(obj: JsonObject): Rule {
            val op = obj.optionalString("op") ?: "and"
            if (op != "and") throw DiscoveryInputException("只支持 and 规则")
            val clauses = obj["clauses"]?.requireArray("rule.clauses")
                ?: throw DiscoveryInputException("rule.clauses 缺失")
            return Rule(clauses.map { Clause.fromJson(it.requireObject("rule.clauses[]")) })
        }
    }
}

internal fun Rule.describe(): String = clauses.joinToString(" AND ") { "${it.field}==${it.eq}" }

// ---------- 状态 ----------

internal data class Observation(
    val objectId: String,
    val label: Boolean,
    val evidenceId: String,
    val source: String,
    val at: String,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("object_id", objectId)
        put("label", label)
        put("evidence_id", evidenceId)
        put("source", source)
        put("at", at)
    }

    companion object {
        fun fromJson(obj: JsonObject): Observation = Observation(
            obj.requireString("object_id", "observation"),
            obj.requireBoolean("label", "observation"),
            obj.requireString("evidence_id", "observation"),
            obj.optionalString("source") ?: "teacher_oracle",
            obj.optionalString("at") ?: "",
        )
    }
}

internal data class Elimination(
    val ruleHash: String,
    val ruleText: String,
    val evidenceId: String,
    val predicted: String,
    val observed: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rule_hash", ruleHash)
        put("rule", ruleText)
        put("evidence_id", evidenceId)
        put("predicted", predicted)
        put("observed", observed)
    }

    companion object {
        fun fromJson(obj: JsonObject): Elimination = Elimination(
            obj.requireString("rule_hash", "elimination"),
            obj.optionalString("rule") ?: "",
            obj.requireString("evidence_id", "elimination"),
            obj.requireString("predicted", "elimination"),
            obj.requireBoolean("observed", "elimination"),
        )
    }
}

internal data class PromotedRule(
    val ruleId: String,
    val rule: Rule,
    val evidenceIds: List<String>,
    val promotedAt: String,
    val domainSize: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rule_id", ruleId)
        put("rule", rule.toJson())
        putJsonArray("evidence_ids") { evidenceIds.forEach { add(it) } }
        put("acquisition_method", "student_induced")
        put("generator_method", "designer_programmed_hypothesis_search")
        put("scope", "finite_attribute_schema")
        put("schema_version", SCHEMA_VERSION)
        put("promoted_at", promotedAt)
        put("domain_size", domainSize)
    }

    companion object {
        fun fromJson(obj: JsonObject): PromotedRule = PromotedRule(
            obj.requireString("rule_id", "promoted"),
            Rule.fromJson(obj["rule"]?.requireObject("promoted.rule") ?: throw DiscoveryInputException("promoted.rule 缺失")),
            obj["evidence_ids"]?.requireStringList("promoted.evidence_ids") ?: emptyList(),
            obj.optionalString("promoted_at") ?: "",
            (obj["domain_size"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }
}

internal data class PendingQuery(val objectId: String, val issuedAt: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("object_id", objectId)
        put("issued_at", issuedAt)
    }

    companion object {
        fun fromJson(obj: JsonObject): PendingQuery = PendingQuery(
            obj.requireString("object_id", "pending"),
            obj.optionalString("issued_at") ?: "",
        )
    }
}

internal data class LearningState(
    val worldId: String,
    val schema: LinkedHashMap<String, List<String>>,
    val objects: LinkedHashMap<String, LinkedHashMap<String, String>>,
    val pool: List<String>,
    val observations: MutableList<Observation>,
    val eliminations: MutableList<Elimination>,
    var promoted: PromotedRule?,
    var pending: PendingQuery?,
) {
    val fieldOrder: List<String> get() = schema.keys.toList()

    fun toJson(): JsonObject = buildJsonObject {
        put("world_id", worldId)
        putJsonObject("schema") {
            schema.forEach { (field, values) ->
                putJsonArray(field) { values.forEach { add(it) } }
            }
        }
        putJsonObject("objects") {
            objects.forEach { (id, features) ->
                putJsonObject(id) { features.forEach { (key, value) -> put(key, value) } }
            }
        }
        putJsonArray("pool") { pool.forEach { add(it) } }
        putJsonArray("observations") { observations.forEach { add(it.toJson()) } }
        putJsonArray("eliminations") { eliminations.forEach { add(it.toJson()) } }
        // 注意：JsonObjectBuilder.put 返回“旧值”，不能用 ?: 串联；否则刚写进去的规则会被 null 覆盖。
        if (promoted != null) put("promoted", promoted!!.toJson()) else put("promoted", JsonNull)
        if (pending != null) put("pending_query", pending!!.toJson()) else put("pending_query", JsonNull)
        put("algorithm_version", ALGORITHM_VERSION)
        put("schema_version", SCHEMA_VERSION)
    }

    companion object {
        fun fromJson(obj: JsonObject): LearningState {
            val schema = LinkedHashMap<String, List<String>>()
            val schemaJson = obj["schema"]?.requireObject("state.schema")
                ?: throw DiscoveryInputException("state.schema 缺失")
            schemaJson.forEach { (field, value) -> schema[field] = value.requireStringList("state.schema.$field") }

            val objects = LinkedHashMap<String, LinkedHashMap<String, String>>()
            val objectsJson = obj["objects"]?.requireObject("state.objects")
                ?: throw DiscoveryInputException("state.objects 缺失")
            objectsJson.forEach { (id, value) -> objects[id] = value.requireFeatureMap("state.objects.$id") }

            val pool = obj["pool"]?.requireStringList("state.pool") ?: emptyList()
            val observations = (obj["observations"] as? JsonArray ?: JsonArray(emptyList()))
                .map { Observation.fromJson(it.requireObject("state.observations[]")) }.toMutableList()
            val eliminations = (obj["eliminations"] as? JsonArray ?: JsonArray(emptyList()))
                .map { Elimination.fromJson(it.requireObject("state.eliminations[]")) }.toMutableList()
            val promoted = (obj["promoted"] as? JsonObject)?.let { PromotedRule.fromJson(it) }
            val pending = (obj["pending_query"] as? JsonObject)?.let { PendingQuery.fromJson(it) }
            return LearningState(
                worldId = obj.requireString("world_id", "state"),
                schema = schema,
                objects = objects,
                pool = pool,
                observations = observations,
                eliminations = eliminations,
                promoted = promoted,
                pending = pending,
            )
        }
    }
}

/** 所有 op 的统一返回；由 CLI 拼成一行 JSON。 */
internal data class OpResult(
    val status: String,
    val result: JsonObject = buildJsonObject { },
    val reason: String? = null,
    val traceId: String,
)

internal fun okResult(traceId: String, block: JsonObjectBuilder.() -> Unit): OpResult =
    OpResult("ok", buildJsonObject(block), null, traceId)

internal fun unknownResult(traceId: String, reason: String, block: JsonObjectBuilder.() -> Unit = {}): OpResult =
    OpResult("unknown", buildJsonObject(block), reason, traceId)

internal fun conflictResult(traceId: String, reason: String, block: JsonObjectBuilder.() -> Unit = {}): OpResult =
    OpResult("conflict", buildJsonObject(block), reason, traceId)

internal fun errorResult(traceId: String, reason: String, block: JsonObjectBuilder.() -> Unit = {}): OpResult =
    OpResult("error", buildJsonObject(block), reason, traceId)
