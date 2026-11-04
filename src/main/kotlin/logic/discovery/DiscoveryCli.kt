// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 独立 JSONL 入口与学习状态机；不修改 Main.kt，宿主用 logic.discovery.DiscoveryCliKt 启动。
// 约束：标签只能经 discovery.observe 逐条进入；next_query 的决策发生在拿到标签之前。
package logic.discovery

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_FIELDS = 8
private const val MAX_DOMAIN = 4096L
private const val MAX_CANDIDATES = 4096

internal class DiscoveryService(stateDir: Path) {
    private val store = DiscoveryStore(stateDir)
    private val audit = DiscoveryAudit(store)

    fun handle(request: JsonObject): OpResult {
        val trace = newTraceId()
        val op = request.optionalString("op") ?: ""
        return try {
            val worldId = request.requireString("world_id", "request")
            val taskId = request.optionalString("task_id")
            val args = (request["args"] as? JsonObject) ?: buildJsonObject { }
            when (op) {
                "discovery.start" -> start(worldId, taskId, trace, args)
                "discovery.hypotheses" -> hypotheses(worldId, trace)
                "discovery.next_query" -> nextQuery(worldId, taskId, trace)
                "discovery.observe" -> observe(worldId, taskId, trace, args)
                "discovery.teach" -> teach(worldId, taskId, trace, args)
                "discovery.reset" -> reset(worldId, taskId, trace, args)
                "discovery.finalize" -> finalize(worldId, taskId, trace)
                "discovery.predict" -> predict(worldId, taskId, trace, args)
                "discovery.status" -> status(worldId, trace)
                else -> errorResult(trace, "unknown_op") { put("op", op) }
            }
        } catch (error: DiscoveryInputException) {
            errorResult(trace, "invalid_input") { put("message", error.message ?: "") }
        } catch (error: Exception) {
            errorResult(trace, "internal_error") { put("message", error.javaClass.simpleName) }
        }
    }

    // ---------- discovery.start ----------

    private fun start(worldId: String, taskId: String?, trace: String, args: JsonObject): OpResult {
        // 2026-11-04 审查修正：同一 world_id 不允许静默覆盖既有学习历史；重置必须显式走 discovery.reset。
        if (store.load(worldId) != null) {
            return errorResult(trace, "world_already_initialized") { put("world_id", worldId) }
        }
        val schemaJson = args["feature_schema"]?.requireObject("args.feature_schema")
            ?: throw DiscoveryInputException("args.feature_schema 缺失")
        val schema = LinkedHashMap<String, List<String>>()
        schemaJson.forEach { (field, element) ->
            if (field.isBlank()) throw DiscoveryInputException("字段名不能为空")
            val values = element.requireStringList("args.feature_schema.$field")
            if (values.isEmpty()) throw DiscoveryInputException("$field 的值域不能为空")
            if (values.distinct().size != values.size) throw DiscoveryInputException("$field 的值域有重复")
            schema[field] = values
        }
        if (schema.isEmpty()) throw DiscoveryInputException("feature_schema 不能为空")

        // 2026-11-04 审查修正：先给可测资源上限，避免有限域穷举和候选枚举无界膨胀。
        var domainProduct = 1L
        for (values in schema.values) {
            if (domainProduct > MAX_DOMAIN) break
            domainProduct *= values.size
        }
        var candidateCount = schema.values.sumOf { it.size }
        val fields = schema.keys.toList()
        for (i in fields.indices) for (j in i + 1 until fields.size) {
            candidateCount += schema.getValue(fields[i]).size * schema.getValue(fields[j]).size
        }
        if (schema.size > MAX_FIELDS || domainProduct > MAX_DOMAIN || candidateCount > MAX_CANDIDATES) {
            return errorResult(trace, "hypothesis_space_too_large") {
                put("fields", schema.size)
                put("domain_product", domainProduct)
                put("candidate_count", candidateCount)
            }
        }

        val objects = LinkedHashMap<String, LinkedHashMap<String, String>>()

        fun putObject(id: String, features: LinkedHashMap<String, String>, where: String) {
            if (id.isBlank()) throw DiscoveryInputException("$where.id 不能为空")
            if (objects.containsKey(id)) throw DiscoveryInputException("对象 ID 重复：$id")
            for ((field, value) in features) {
                val domain = schema[field] ?: throw DiscoveryInputException("$where 的字段 $field 不在 schema")
                if (value !in domain) throw DiscoveryInputException("$where 的值 $value 不在 $field 的值域")
            }
            objects[id] = features
        }

        val examples = mutableListOf<Observation>()
        val evidenceIds = mutableSetOf<String>()
        ((args["known_examples"] as? JsonArray) ?: JsonArray(emptyList())).forEachIndexed { index, element ->
            val obj = element.requireObject("args.known_examples[$index]")
            val id = obj.requireString("id", "example")
            val features = obj.requireFeatureMap("features", "example")
            putObject(id, features, "args.known_examples[$index]")
            val label = obj.requireBoolean("label", "example")
            val evidenceId = obj.requireString("evidence_id", "example")
            if (!evidenceIds.add(evidenceId)) throw DiscoveryInputException("evidence_id 重复：$evidenceId")
            examples += Observation(id, label, evidenceId, "teacher_observation", nowIso())
        }

        val poolIds = mutableListOf<String>()
        ((args["query_pool"] as? JsonArray) ?: JsonArray(emptyList())).forEachIndexed { index, element ->
            val obj = element.requireObject("args.query_pool[$index]")
            val id = obj.requireString("id", "pool")
            val features = obj.requireFeatureMap("features", "pool")
            putObject(id, features, "args.query_pool[$index]")
            poolIds += id
        }

        val state = LearningState(worldId, schema, objects, poolIds, mutableListOf(), mutableListOf(), null, null)
        val allCandidates = HypothesisGenerator.generate(schema)
        audit.record("hypotheses_generated", worldId, taskId, trace, HypothesisGenerator.setHash(allCandidates)) {
            put("count", allCandidates.size)
        }
        // 2026-11-04 审查修正：每条初始样本都按 before/after 写独立淘汰事件，不能只给最终 hash。
        for (observation in examples) {
            val before = ConsistencyEngine.surviving(allCandidates, state)
            state.observations += observation
            val after = ConsistencyEngine.surviving(allCandidates, state)
            val eliminated = ConsistencyEngine.eliminations(before, after, observation, objects)
            state.eliminations += eliminated
            val afterHash = HypothesisGenerator.setHash(after)
            audit.record("observation_received", worldId, taskId, trace, afterHash,
                evidenceIds = listOf(observation.evidenceId)) {
                put("object_id", observation.objectId)
                put("label", observation.label)
                put("source", observation.source)
                put("candidate_count", after.size)
            }
            for (item in eliminated) {
                audit.record("hypothesis_eliminated", worldId, taskId, trace, afterHash,
                    evidenceIds = listOf(item.evidenceId)) {
                    put("rule_hash", item.ruleHash)
                    put("rule", item.ruleText)
                    put("predicted", item.predicted)
                    put("observed", item.observed)
                }
            }
        }
        store.save(state)
        val surviving = ConsistencyEngine.surviving(allCandidates, state)
        return okResult(trace) {
            put("state", "initialized")
            put("candidate_count", allCandidates.size)
            put("surviving_count", surviving.size)
            put("object_count", objects.size)
            put("query_pool_size", poolIds.size)
            put("candidate_set_hash", HypothesisGenerator.setHash(surviving))
        }
    }

    // ---------- discovery.hypotheses ----------

    private fun hypotheses(worldId: String, trace: String): OpResult {
        val state = load(worldId)
        val all = HypothesisGenerator.generate(state.schema)
        val surviving = ConsistencyEngine.surviving(all, state)
        return okResult(trace) {
            put("count", surviving.size)
            putJsonArray("candidates") {
                surviving.forEach { candidate ->
                    add(buildJsonObject {
                        put("hash", candidate.hash())
                        put("text", candidate.userText())
                        put("rule", candidate.toJson())
                    })
                }
            }
            put("candidate_set_hash", HypothesisGenerator.setHash(surviving))
            putJsonArray("evidence_ids") { state.observations.forEach { add(it.evidenceId) } }
        }
    }

    // ---------- discovery.next_query ----------

    private fun nextQuery(worldId: String, taskId: String?, trace: String): OpResult {
        val state = load(worldId)
        state.promoted?.let {
            return unknownResult(trace, "rule_already_promoted") { put("rule_id", it.ruleId) }
        }
        val all = HypothesisGenerator.generate(state.schema)
        val surviving = ConsistencyEngine.surviving(all, state)
        if (surviving.isEmpty()) return unknownResult(trace, "hypothesis_space_exhausted")
        val choice = QueryPlanner.choose(surviving, state)
            ?: return unknownResult(trace, "no_discriminating_query") {
                put("candidate_count", surviving.size)
                putJsonArray("candidates") { surviving.forEach { add(it.userText()) } }
            }

        audit.record("query_selected", worldId, taskId, trace, HypothesisGenerator.setHash(surviving)) {
            put("object_id", choice.objectId)
            put("score", choice.score)
            putJsonObject("features") { choice.features.forEach { (key, value) -> put(key, value) } }
            putJsonObject("groups") {
                choice.groups.forEach { (outcome, hashes) ->
                    putJsonArray(outcome) { hashes.forEach { add(it) } }
                }
            }
        }
        state.pending = PendingQuery(choice.objectId, nowIso())
        store.save(state)
        return okResult(trace) {
            put("object_id", choice.objectId)
            put("score", choice.score)
            putJsonObject("features") { choice.features.forEach { (key, value) -> put(key, value) } }
            putJsonObject("groups") {
                choice.groups.forEach { (outcome, hashes) ->
                    putJsonArray(outcome) { hashes.forEach { add(it) } }
                }
            }
            put("candidate_count", surviving.size)
        }
    }

    // ---------- discovery.observe ----------

    private fun observe(worldId: String, taskId: String?, trace: String, args: JsonObject): OpResult =
        recordObservation(worldId, taskId, trace, args, requirePending = true, source = "teacher_oracle")

    /** 教师显式讲授：不要求 pending_query，但来源单独记为 teacher_teaching，不能混作学生主动查询。 */
    private fun teach(worldId: String, taskId: String?, trace: String, args: JsonObject): OpResult =
        recordObservation(worldId, taskId, trace, args, requirePending = false, source = "teacher_teaching")

    private fun recordObservation(
        worldId: String,
        taskId: String?,
        trace: String,
        args: JsonObject,
        requirePending: Boolean,
        source: String,
    ): OpResult {
        val state = load(worldId)
        val objectId = args.requireString("object_id", "args")
        val label = args.requireBoolean("label", "args")
        val evidenceId = args.requireString("evidence_id", "args")
        if (!state.objects.containsKey(objectId)) throw DiscoveryInputException("对象不存在：$objectId")
        // 2026-11-04 审查修正：observe 必须绑定 next_query 选中的对象，防止调用方绕过主动查询直接喂标签。
        if (requirePending) {
            val pending = state.pending
            if (pending == null || pending.objectId != objectId) {
                return errorResult(trace, "no_pending_query") {
                    put("object_id", objectId)
                    put("pending_object", pending?.objectId ?: "")
                }
            }
        }
        if (state.observations.any { it.evidenceId == evidenceId }) {
            throw DiscoveryInputException("evidence_id 重复：$evidenceId")
        }
        val existing = state.observations.filter { it.objectId == objectId }
        if (existing.any { it.label == label }) {
            throw DiscoveryInputException("同一对象同一标签已经观察过：$objectId")
        }
        if (state.promoted != null) {
            return unknownResult(trace, "rule_already_promoted") { put("rule_id", state.promoted!!.ruleId) }
        }

        val all = HypothesisGenerator.generate(state.schema)
        val before = ConsistencyEngine.surviving(all, state)
        val observation = Observation(objectId, label, evidenceId, source, nowIso())
        state.observations += observation
        val after = ConsistencyEngine.surviving(all, state)
        val eliminated = ConsistencyEngine.eliminations(before, after, observation, state.objects)

        if (existing.any { it.label != label }) {
            // 同一对象出现互斥标签：保留双方，规则不得晋升。
            if (requirePending) state.pending = null
            store.save(state)
            audit.record("observation_received", worldId, taskId, trace, HypothesisGenerator.setHash(after),
                evidenceIds = listOf(evidenceId)) {
                put("object_id", objectId)
                put("label", label)
                put("conflict", true)
            }
            return conflictResult(trace, "conflicting_evidence") {
                put("object_id", objectId)
                put("candidate_count", after.size)
                putJsonArray("evidence_ids") { state.observations.filter { it.objectId == objectId }.forEach { add(it.evidenceId) } }
            }
        }

        state.eliminations += eliminated
        if (requirePending) state.pending = null
        store.save(state)
        audit.record("observation_received", worldId, taskId, trace, HypothesisGenerator.setHash(after),
            evidenceIds = listOf(evidenceId)) {
            put("object_id", objectId)
            put("label", label)
        }
        for (item in eliminated) {
            audit.record("hypothesis_eliminated", worldId, taskId, trace, HypothesisGenerator.setHash(after),
                evidenceIds = listOf(item.evidenceId)) {
                put("rule_hash", item.ruleHash)
                put("rule", item.ruleText)
                put("predicted", item.predicted)
                put("observed", item.observed)
            }
        }
        return okResult(trace) {
            put("accepted", true)
            put("candidate_count", after.size)
            putJsonArray("eliminated") { eliminated.forEach { add(it.ruleHash) } }
            put("candidate_set_hash", HypothesisGenerator.setHash(after))
        }
    }

    // ---------- discovery.reset ----------

    private fun reset(worldId: String, taskId: String?, trace: String, args: JsonObject): OpResult {
        if (args.optionalString("confirm") != "reset") {
            throw DiscoveryInputException("重置需要 args.confirm=reset")
        }
        val archived = store.archive(worldId)
        audit.record("world_reset", worldId, taskId, trace, "") {
            put("archived", archived ?: "")
        }
        return okResult(trace) {
            put("status", "reset")
            put("archived", archived ?: "")
        }
    }

    // ---------- discovery.finalize ----------

    private fun finalize(worldId: String, taskId: String?, trace: String): OpResult {
        val state = load(worldId)
        state.promoted?.let {
            return okResult(trace) {
                put("status", "promoted")
                put("already_promoted", true)
                put("rule_id", it.ruleId)
                put("rule", it.rule.toJson())
            }
        }
        val conflicts = ConsistencyEngine.conflictingObjects(state.observations)
        if (conflicts.isNotEmpty()) {
            return conflictResult(trace, "conflicting_evidence") {
                putJsonArray("objects") { conflicts.forEach { add(it) } }
            }
        }
        val all = HypothesisGenerator.generate(state.schema)
        val surviving = ConsistencyEngine.surviving(all, state)
        if (surviving.isEmpty()) return unknownResult(trace, "hypothesis_space_exhausted")

        val hasPositive = state.observations.any { it.label }
        val hasNegative = state.observations.any { !it.label }
        if (!hasPositive || !hasNegative) {
            return unknownResult(trace, "insufficient_evidence") {
                put("missing", if (!hasPositive) "positive_observation" else "negative_observation")
                put("candidate_count", surviving.size)
            }
        }

        val classes = ConsistencyEngine.equivalenceClasses(surviving, state.schema)
        if (classes.size != 1) {
            return unknownResult(trace, "insufficient_evidence") {
                put("candidate_count", surviving.size)
                put("class_count", classes.size)
                putJsonArray("classes") {
                    classes.forEach { group ->
                        add(buildJsonArray { group.forEach { add(it.userText()) } })
                    }
                }
            }
        }

        val chosen = classes[0][0]
        val domainSize = ConsistencyEngine.domainSize(state.schema)
        val promoted = PromotedRule(
            ruleId = "induced-${sha256(worldId + "|" + chosen.hash()).take(12)}",
            rule = chosen,
            evidenceIds = state.observations.map { it.evidenceId }.distinct(),
            promotedAt = nowIso(),
            domainSize = domainSize,
        )
        state.promoted = promoted
        store.save(state)
        audit.record("rule_promoted", worldId, taskId, trace, HypothesisGenerator.setHash(listOf(chosen)),
            evidenceIds = promoted.evidenceIds) {
            put("rule_id", promoted.ruleId)
            put("rule", chosen.toJson())
            put("equivalent_candidates", classes[0].size)
            put("domain_size", domainSize)
        }
        return okResult(trace) {
            put("status", "promoted")
            put("rule_id", promoted.ruleId)
            put("rule", chosen.toJson())
            putJsonArray("evidence_ids") { promoted.evidenceIds.forEach { add(it) } }
            put("equivalent_candidates", classes[0].size)
            put("domain_size", domainSize)
        }
    }

    // ---------- discovery.predict ----------

    private fun predict(worldId: String, taskId: String?, trace: String, args: JsonObject): OpResult {
        val state = load(worldId)
        val promoted = state.promoted ?: return unknownResult(trace, "rule_not_promoted")
        val features: Map<String, String> = when {
            args["features"] is JsonObject -> args.requireFeatureMap("features", "args")
            args["object_id"] is JsonPrimitive -> {
                val id = args.requireString("object_id", "args")
                state.objects[id] ?: throw DiscoveryInputException("对象不存在：$id")
            }
            else -> throw DiscoveryInputException("args 需要 features 或 object_id")
        }
        val outcome = RuleInterpreter.evaluate(promoted.rule, features)
        if (outcome == Tri.UNKNOWN) {
            audit.record("rule_abstained", worldId, taskId, trace, HypothesisGenerator.setHash(listOf(promoted.rule)),
                evidenceIds = promoted.evidenceIds) {
                put("rule_id", promoted.ruleId)
                put("reason", "missing_attribute")
            }
            return unknownResult(trace, "missing_attribute") {
                put("label", "unknown")
                put("rule_id", promoted.ruleId)
            }
        }
        audit.record("rule_executed", worldId, taskId, trace, HypothesisGenerator.setHash(listOf(promoted.rule)),
            evidenceIds = promoted.evidenceIds) {
            put("rule_id", promoted.ruleId)
            put("label", outcome == Tri.TRUE)
        }
        return okResult(trace) {
            put("label", outcome.wire())
            put("rule_id", promoted.ruleId)
        }
    }

    // ---------- discovery.status ----------

    private fun status(worldId: String, trace: String): OpResult {
        val state = load(worldId)
        val all = HypothesisGenerator.generate(state.schema)
        val surviving = ConsistencyEngine.surviving(all, state)
        return okResult(trace) {
            put("world_id", worldId)
            put("candidate_count", surviving.size)
            put("initial_candidate_count", all.size)
            put("evidence_count", state.observations.size)
            put("conflict", ConsistencyEngine.conflictingObjects(state.observations).isNotEmpty())
            put("promoted", state.promoted != null)
            state.promoted?.let { put("rule_id", it.ruleId); put("rule", it.rule.toJson()) }
            put("algorithm_version", ALGORITHM_VERSION)
            put("candidate_set_hash", HypothesisGenerator.setHash(surviving))
        }
    }

    private fun load(worldId: String): LearningState =
        store.load(worldId) ?: throw DiscoveryInputException("世界未初始化：$worldId")
}

/** 供测试直接调用；main 只负责把结果打到 stdout。 */
internal fun runDiscoveryCli(args: Array<String>, emit: (String) -> Unit) {
    if (args.size != 2) {
        emit(buildJsonObject { put("status", "error"); put("reason", "usage"); put("trace_id", newTraceId()) }.toString())
        return
    }
    val requests = Path.of(args[0])
    val stateDir = Path.of(args[1])
    Files.createDirectories(stateDir)
    val service = DiscoveryService(stateDir)
    // "-" 表示从 stdin 逐行读取，便于外部演示器用一个 JVM 交互式驱动（每行仍立即输出响应）。
    val lines: Sequence<String> = if (args[0] == "-") {
        generateSequence(::readLine)
    } else {
        Files.readAllLines(requests, Charsets.UTF_8).asSequence()
    }
    lines.forEach { line ->
        if (line.isBlank()) return@forEach
        val request = try {
            discoveryJson.parseToJsonElement(line).requireObject("request")
        } catch (error: Exception) {
            emit(buildJsonObject {
                put("status", "error")
                put("reason", "invalid_json")
                put("trace_id", newTraceId())
            }.toString())
            return@forEach
        }
        val response = service.handle(request)
        emit(buildJsonObject {
            put("task_id", request.optionalString("task_id") ?: "")
            put("world_id", request.optionalString("world_id") ?: "")
            put("op", request.optionalString("op") ?: "")
            put("status", response.status)
            put("result", response.result)
            put("reason", response.reason?.let { JsonPrimitive(it) } ?: JsonNull)
            put("trace_id", response.traceId)
        }.toString())
    }
}

fun main(args: Array<String>) {
    runDiscoveryCli(args) {
        println(it)
        System.out.flush()
    }
}
