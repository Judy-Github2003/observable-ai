// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：学前24课批量学生需要一个显式、可持久化、可原子提交的受控世界模型；
// 本文件只保存材料/教学/证据里明确给出的数据，不根据名称、颜色或题号补答案。
package logic

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** 批量世界状态文件的 schema 标识；读取时必须匹配，避免误认旧格式。 */
internal const val BATCH_WORLD_STATE_SCHEMA = "batch-world-state-v1"

/** 参数或材料结构错误；BatchCli 统一转成 status=error/reason=invalid_argument。 */
internal class BatchArgumentException(message: String) : IllegalArgumentException(message)

/** 一条属性变化历史；保留旧值来源，不覆盖相反证据。 */
internal class WorldAttributeHistory(
    val key: String,
    val oldValue: String?,
    val newValue: String,
    val changeType: String,
    val source: String,
    val introducedLesson: String,
    val acquiredAt: String,
    val runId: String,
)

/** 一个显式导入的对象；名称、属性、类别分开保存，名称不能替代身份。 */
internal class WorldObject(
    val id: String,
    var name: String?,
    val attributes: LinkedHashMap<String, String> = LinkedHashMap(),
    val categories: MutableList<String> = mutableListOf(),
    var source: String = "",
    var introducedLesson: String = "",
    var acquiredAt: String = "",
    var runId: String = "",
    val history: MutableList<WorldAttributeHistory> = mutableListOf(),
)

/** relation.set 的前值历史；旧关系保留为 inactive，查询只使用 active 关系。 */
internal class WorldRelationHistory(
    val previousTargets: List<String>,
    val newTarget: String,
    val source: String,
    val introducedLesson: String,
    val acquiredAt: String,
    val runId: String,
)

/** 一条明确关系；subject/predicate 只是数据字段，不按名称推断空间或用途。 */
internal class WorldRelation(
    val id: String,
    val subject: String,
    val predicate: String,
    var target: String,
    var active: Boolean,
    var source: String,
    var introducedLesson: String,
    var acquiredAt: String,
    var runId: String,
    val previousTargets: MutableList<String> = mutableListOf(),
    val history: MutableList<WorldRelationHistory> = mutableListOf(),
)

/** 一条显式证据；truth=false 表示明确否定，不表示缺记录。 */
internal class WorldClaim(
    val id: String,
    val subject: String,
    val predicate: String,
    val value: String,
    val truth: Boolean,
    var active: Boolean,
    val dependsOn: List<String>,
    var source: String,
    var introducedLesson: String,
    var acquiredAt: String,
    var runId: String,
    var retractedAt: String? = null,
    var retractedByRunId: String? = null,
)

/** 一条给定观察；fact_id 稳定，供派生结论引用而不伪造真实世界证据。 */
internal class WorldObservation(
    val id: String,
    val factId: String,
    val conditions: LinkedHashMap<String, String>,
    val outcome: LinkedHashMap<String, String>,
    var source: String,
    var introducedLesson: String,
    var acquiredAt: String,
    var runId: String,
)

/** 一个结构化故事事件；只重放 take/give/place，不补写动机。 */
internal class StoryEvent(
    val id: String,
    val actor: String,
    val action: String,
    val objectId: String?,
    val to: String?,
    val place: String?,
)

/** 一条显式生活/情绪规则；when 是属性条件，不做读心。 */
internal class WorldPolicy(
    val id: String,
    val conditions: LinkedHashMap<String, String>,
    val decision: String,
    var source: String,
    var introducedLesson: String,
    var acquiredAt: String,
    var runId: String,
)

/** 一个 world_id 对应的完整世界状态；每次请求从磁盘重建，保证跨进程持久化。 */
internal class BatchWorld(
    var worldId: String,
    var source: String,
    var sourceDetail: String?,
    var createdAt: String,
    var updatedAt: String,
    var revision: Long,
    var loadCount: Int,
    var lastRunId: String,
    val objects: LinkedHashMap<String, WorldObject>,
    val relations: MutableList<WorldRelation>,
    val claims: LinkedHashMap<String, WorldClaim>,
    val observations: LinkedHashMap<String, WorldObservation>,
    val story: MutableList<StoryEvent>,
    val policies: MutableList<WorldPolicy>,
    val turns: MutableList<String>,
    var currentTurn: String?,
)

/**
 * world.load 的严格构建器。
 *
 * 先完整解析、校验重复 ID/字段类型，再构造对象并一次性落盘；因此任何校验失败都不会留下半套世界。
 */
internal object BatchWorldLoader {
    fun load(
        worldId: String,
        source: String,
        lessonId: String,
        runId: String,
        element: JsonElement,
        now: Instant,
    ): BatchWorld {
        val root = element as? JsonObject
            ?: throw BatchArgumentException("args.world 必须是 JSON 对象")
        // 顶层 id 若出现，必须与请求 world_id 一致，防止把材料写到错误世界。
        root["id"]?.let { idElement ->
            val declared = idElement.stringValue("world.id")
            require(declared == worldId) {
                "world.id=$declared 与请求 world_id=$worldId 不一致"
            }
        }
        val timestamp = now.toString()
        val objects = LinkedHashMap<String, WorldObject>()
        optionalArray(root, "objects").forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw BatchArgumentException("world.objects[$index] 必须是对象")
            val id = item.requiredString("id", "world.objects[$index]")
            require(!objects.containsKey(id)) { "world.objects 包含重复对象ID：$id" }
            val name = item.optionalString("name", "world.objects[$index]")
            val attributes = parseStringMap(item["attributes"], "world.objects[$index].attributes")
            val categories = parseStringList(item["categories"], "world.objects[$index].categories")
            objects[id] = WorldObject(
                id = id,
                name = name,
                attributes = attributes,
                categories = categories.toMutableList(),
                source = source,
                introducedLesson = lessonId,
                acquiredAt = timestamp,
                runId = runId,
            )
            attributes.forEach { (key, value) ->
                objects.getValue(id).history += WorldAttributeHistory(
                    key = key,
                    oldValue = null,
                    newValue = value,
                    changeType = "given",
                    source = source,
                    introducedLesson = lessonId,
                    acquiredAt = timestamp,
                    runId = runId,
                )
            }
        }

        val relations = mutableListOf<WorldRelation>()
        val relationIds = mutableSetOf<String>()
        optionalArray(root, "relations").forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw BatchArgumentException("world.relations[$index] 必须是对象")
            val id = item.optionalString("id", "world.relations[$index]") ?: "rel-auto-${index + 1}"
            require(relationIds.add(id)) { "world.relations 包含重复关系ID：$id" }
            relations += WorldRelation(
                id = id,
                subject = item.requiredString("subject", "world.relations[$index]"),
                predicate = item.requiredString("predicate", "world.relations[$index]"),
                target = item.requiredString("object", "world.relations[$index]"),
                active = item.optionalBoolean("active", "world.relations[$index]") ?: true,
                source = source,
                introducedLesson = lessonId,
                acquiredAt = timestamp,
                runId = runId,
            )
        }

        val claims = LinkedHashMap<String, WorldClaim>()
        optionalArray(root, "claims").forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw BatchArgumentException("world.claims[$index] 必须是对象")
            val id = item.requiredString("id", "world.claims[$index]")
            require(!claims.containsKey(id)) { "world.claims 包含重复证据ID：$id" }
            val truth = item.requiredBoolean("truth", "world.claims[$index]")
            val dependsOn = parseStringList(item["depends_on"], "world.claims[$index].depends_on")
            claims[id] = WorldClaim(
                id = id,
                subject = item.requiredString("subject", "world.claims[$index]"),
                predicate = item.requiredString("predicate", "world.claims[$index]"),
                value = item.requiredPrimitive("value", "world.claims[$index]"),
                truth = truth,
                active = item.optionalBoolean("active", "world.claims[$index]") ?: true,
                dependsOn = dependsOn,
                source = source,
                introducedLesson = lessonId,
                acquiredAt = timestamp,
                runId = runId,
            )
        }

        val observations = LinkedHashMap<String, WorldObservation>()
        val factIds = mutableSetOf<String>()
        optionalArray(root, "observations").forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw BatchArgumentException("world.observations[$index] 必须是对象")
            val id = item.optionalString("id", "world.observations[$index]") ?: "obs-auto-${index + 1}"
            require(!observations.containsKey(id)) { "world.observations 包含重复观察ID：$id" }
            val factId = item.optionalString("fact_id", "world.observations[$index]")
                ?: "observation:$id"
            require(factIds.add(factId)) { "world.observations 包含重复 fact_id：$factId" }
            observations[id] = WorldObservation(
                id = id,
                factId = factId,
                conditions = parseStringMap(item["conditions"], "world.observations[$index].conditions"),
                outcome = parseStringMap(item["outcome"], "world.observations[$index].outcome"),
                source = source,
                introducedLesson = lessonId,
                acquiredAt = timestamp,
                runId = runId,
            )
        }

        val story = mutableListOf<StoryEvent>()
        val storyIds = mutableSetOf<String>()
        optionalArray(root, "story").forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw BatchArgumentException("world.story[$index] 必须是对象")
            val id = item.optionalString("id", "world.story[$index]") ?: "story-auto-${index + 1}"
            require(storyIds.add(id)) { "world.story 包含重复事件ID：$id" }
            story += StoryEvent(
                id = id,
                actor = item.requiredString("actor", "world.story[$index]"),
                action = item.requiredString("action", "world.story[$index]"),
                objectId = item.optionalString("object", "world.story[$index]"),
                to = item.optionalString("to", "world.story[$index]"),
                place = item.optionalString("place", "world.story[$index]"),
            )
        }

        val policies = mutableListOf<WorldPolicy>()
        val policyIds = mutableSetOf<String>()
        optionalArray(root, "policies").forEachIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw BatchArgumentException("world.policies[$index] 必须是对象")
            val id = item.optionalString("id", "world.policies[$index]") ?: "policy-auto-${index + 1}"
            require(policyIds.add(id)) { "world.policies 包含重复规则ID：$id" }
            val whenElement = item["when"]
                ?: throw BatchArgumentException("world.policies[$index] 缺少 when")
            policies += WorldPolicy(
                id = id,
                conditions = parsePolicyConditions(whenElement, "world.policies[$index].when"),
                decision = item.requiredString("decision", "world.policies[$index]"),
                source = source,
                introducedLesson = lessonId,
                acquiredAt = timestamp,
                runId = runId,
            )
        }

        val turns = parseStringList(root["turns"], "world.turns")
        require(turns.distinct().size == turns.size) { "world.turns 不能包含重复角色" }

        return BatchWorld(
            worldId = worldId,
            source = source,
            sourceDetail = (root["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            createdAt = timestamp,
            updatedAt = timestamp,
            revision = 1,
            loadCount = 1,
            lastRunId = runId,
            objects = objects,
            relations = relations,
            claims = claims,
            observations = observations,
            story = story,
            policies = policies,
            turns = turns.toMutableList(),
            currentTurn = null,
        )
    }

    private fun optionalArray(root: JsonObject, key: String): List<JsonElement> =
        when (val value = root[key]) {
            null -> emptyList()
            is JsonArray -> value.toList()
            else -> throw BatchArgumentException("world.$key 必须是数组")
        }

    private fun parseStringMap(element: JsonElement?, field: String): LinkedHashMap<String, String> {
        if (element == null) return LinkedHashMap()
        val obj = element as? JsonObject
            ?: throw BatchArgumentException("$field 必须是对象")
        val values = LinkedHashMap<String, String>()
        obj.forEach { (key, raw) ->
            require(key.isNotBlank()) { "$field 的属性名不能为空" }
            val primitive = raw as? JsonPrimitive
                ?: throw BatchArgumentException("$field.$key 必须是字符串或数字")
            values[key] = primitive.content
        }
        return values
    }

    private fun parseStringList(element: JsonElement?, field: String): List<String> {
        if (element == null) return emptyList()
        val array = element as? JsonArray
            ?: throw BatchArgumentException("$field 必须是数组")
        return array.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive
                ?: throw BatchArgumentException("$field[$index] 必须是字符串")
            require(primitive.isString && primitive.content.isNotBlank()) {
                "$field[$index] 必须是非空字符串"
            }
            primitive.content
        }.distinct()
    }

    /**
     * 规则条件支持三种明确写法：{key,value}、属性名到值的对象、[{key,value},...]。
     * 不做默认规则或隐藏条件推断；空条件表示无条件规则，由调用方决定是否允许。
     */
    private fun parsePolicyConditions(element: JsonElement, field: String): LinkedHashMap<String, String> {
        val conditions = LinkedHashMap<String, String>()
        when (element) {
            is JsonArray -> element.forEachIndexed { index, item ->
                val obj = item as? JsonObject
                    ?: throw BatchArgumentException("$field[$index] 必须是对象")
                val key = obj.requiredString("key", "$field[$index]")
                conditions[key] = obj.requiredPrimitive("value", "$field[$index]")
            }
            is JsonObject -> {
                if (element.containsKey("key") || element.containsKey("value")) {
                    val key = element.requiredString("key", field)
                    conditions[key] = element.requiredPrimitive("value", field)
                } else {
                    element.forEach { (key, raw) ->
                        val primitive = raw as? JsonPrimitive
                            ?: throw BatchArgumentException("$field.$key 必须是字符串或数字")
                        conditions[key] = primitive.content
                    }
                }
            }
            else -> throw BatchArgumentException("$field 必须是对象或数组")
        }
        return conditions
    }
}

/** 世界状态的 JSON 编解码；也是 actions.run 模拟提交前深拷贝的唯一实现。 */
internal object BatchWorldCodec {
    fun encode(world: BatchWorld): JsonObject = buildJsonObject {
        put("schema_version", BATCH_WORLD_STATE_SCHEMA)
        put("world_id", world.worldId)
        put("source", world.source)
        put("source_detail", world.sourceDetail?.let(::JsonPrimitive) ?: JsonNull)
        put("created_at", world.createdAt)
        put("updated_at", world.updatedAt)
        put("revision", world.revision)
        put("load_count", world.loadCount)
        put("last_run_id", world.lastRunId)
        putJsonArray("objects") {
            world.objects.values.forEach { obj ->
                add(buildJsonObject {
                    put("id", obj.id)
                    put("name", obj.name?.let(::JsonPrimitive) ?: JsonNull)
                    putJsonObject("attributes") { obj.attributes.forEach { (k, v) -> put(k, v) } }
                    putJsonArray("categories") { obj.categories.forEach { add(it) } }
                    put("source", obj.source)
                    put("introduced_lesson", obj.introducedLesson)
                    put("acquired_at", obj.acquiredAt)
                    put("run_id", obj.runId)
                    putJsonArray("history") {
                        obj.history.forEach { entry ->
                            add(buildJsonObject {
                                put("key", entry.key)
                                put("old_value", entry.oldValue?.let(::JsonPrimitive) ?: JsonNull)
                                put("new_value", entry.newValue)
                                put("change_type", entry.changeType)
                                put("source", entry.source)
                                put("introduced_lesson", entry.introducedLesson)
                                put("acquired_at", entry.acquiredAt)
                                put("run_id", entry.runId)
                            })
                        }
                    }
                })
            }
        }
        putJsonArray("relations") {
            world.relations.forEach { relation ->
                add(buildJsonObject {
                    put("id", relation.id)
                    put("subject", relation.subject)
                    put("predicate", relation.predicate)
                    put("object", relation.target)
                    put("active", relation.active)
                    put("source", relation.source)
                    put("introduced_lesson", relation.introducedLesson)
                    put("acquired_at", relation.acquiredAt)
                    put("run_id", relation.runId)
                    putJsonArray("previous_targets") { relation.previousTargets.forEach { add(it) } }
                    putJsonArray("history") {
                        relation.history.forEach { entry ->
                            add(buildJsonObject {
                                putJsonArray("previous_targets") { entry.previousTargets.forEach { add(it) } }
                                put("new_target", entry.newTarget)
                                put("source", entry.source)
                                put("introduced_lesson", entry.introducedLesson)
                                put("acquired_at", entry.acquiredAt)
                                put("run_id", entry.runId)
                            })
                        }
                    }
                })
            }
        }
        putJsonArray("claims") {
            world.claims.values.forEach { claim ->
                add(buildJsonObject {
                    put("id", claim.id)
                    put("subject", claim.subject)
                    put("predicate", claim.predicate)
                    put("value", claim.value)
                    put("truth", claim.truth)
                    put("active", claim.active)
                    putJsonArray("depends_on") { claim.dependsOn.forEach { add(it) } }
                    put("source", claim.source)
                    put("introduced_lesson", claim.introducedLesson)
                    put("acquired_at", claim.acquiredAt)
                    put("run_id", claim.runId)
                    put("retracted_at", claim.retractedAt?.let(::JsonPrimitive) ?: JsonNull)
                    put("retracted_by_run_id", claim.retractedByRunId?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
        putJsonArray("observations") {
            world.observations.values.forEach { observation ->
                add(buildJsonObject {
                    put("id", observation.id)
                    put("fact_id", observation.factId)
                    putJsonObject("conditions") {
                        observation.conditions.forEach { (k, v) -> put(k, v) }
                    }
                    putJsonObject("outcome") {
                        observation.outcome.forEach { (k, v) -> put(k, v) }
                    }
                    put("source", observation.source)
                    put("introduced_lesson", observation.introducedLesson)
                    put("acquired_at", observation.acquiredAt)
                    put("run_id", observation.runId)
                })
            }
        }
        putJsonArray("story") {
            world.story.forEach { event ->
                add(buildJsonObject {
                    put("id", event.id)
                    put("actor", event.actor)
                    put("action", event.action)
                    put("object", event.objectId?.let(::JsonPrimitive) ?: JsonNull)
                    put("to", event.to?.let(::JsonPrimitive) ?: JsonNull)
                    put("place", event.place?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
        putJsonArray("policies") {
            world.policies.forEach { policy ->
                add(buildJsonObject {
                    put("id", policy.id)
                    putJsonObject("when") { policy.conditions.forEach { (k, v) -> put(k, v) } }
                    put("decision", policy.decision)
                    put("source", policy.source)
                    put("introduced_lesson", policy.introducedLesson)
                    put("acquired_at", policy.acquiredAt)
                    put("run_id", policy.runId)
                })
            }
        }
        putJsonArray("turns") { world.turns.forEach { add(it) } }
        put("current_turn", world.currentTurn?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun decode(root: JsonObject): BatchWorld {
        val worldId = root.requiredString("world_id", "state")
        val objects = LinkedHashMap<String, WorldObject>()
        (root["objects"] as? JsonArray).orEmptyElements().forEach { element ->
            val item = element as JsonObject
            val id = item.requiredString("id", "state.objects")
            objects[id] = WorldObject(
                id = id,
                name = item.optionalString("name", "state.objects"),
                attributes = item.stringMap("attributes"),
                categories = item.stringList("categories").toMutableList(),
                source = item.optionalString("source", "state.objects").orEmpty(),
                introducedLesson = item.optionalString("introduced_lesson", "state.objects").orEmpty(),
                acquiredAt = item.optionalString("acquired_at", "state.objects").orEmpty(),
                runId = item.optionalString("run_id", "state.objects").orEmpty(),
                history = (item["history"] as? JsonArray).orEmptyElements().map { historyElement ->
                    val h = historyElement as JsonObject
                    WorldAttributeHistory(
                        key = h.requiredString("key", "state.history"),
                        oldValue = h.optionalString("old_value", "state.history"),
                        newValue = h.requiredString("new_value", "state.history"),
                        changeType = h.requiredString("change_type", "state.history"),
                        source = h.optionalString("source", "state.history").orEmpty(),
                        introducedLesson = h.optionalString("introduced_lesson", "state.history").orEmpty(),
                        acquiredAt = h.optionalString("acquired_at", "state.history").orEmpty(),
                        runId = h.optionalString("run_id", "state.history").orEmpty(),
                    )
                }.toMutableList(),
            )
        }
        val relations = (root["relations"] as? JsonArray).orEmptyElements().map { element ->
            val item = element as JsonObject
            WorldRelation(
                id = item.requiredString("id", "state.relations"),
                subject = item.requiredString("subject", "state.relations"),
                predicate = item.requiredString("predicate", "state.relations"),
                target = item.requiredString("object", "state.relations"),
                active = item.optionalBoolean("active", "state.relations") ?: true,
                source = item.optionalString("source", "state.relations").orEmpty(),
                introducedLesson = item.optionalString("introduced_lesson", "state.relations").orEmpty(),
                acquiredAt = item.optionalString("acquired_at", "state.relations").orEmpty(),
                runId = item.optionalString("run_id", "state.relations").orEmpty(),
                previousTargets = item.stringList("previous_targets").toMutableList(),
                history = (item["history"] as? JsonArray).orEmptyElements().map { historyElement ->
                    val h = historyElement as JsonObject
                    WorldRelationHistory(
                        previousTargets = h.stringList("previous_targets"),
                        newTarget = h.requiredString("new_target", "state.relations.history"),
                        source = h.optionalString("source", "state.relations.history").orEmpty(),
                        introducedLesson = h.optionalString("introduced_lesson", "state.relations.history").orEmpty(),
                        acquiredAt = h.optionalString("acquired_at", "state.relations.history").orEmpty(),
                        runId = h.optionalString("run_id", "state.relations.history").orEmpty(),
                    )
                }.toMutableList(),
            )
        }.toMutableList()
        val claims = LinkedHashMap<String, WorldClaim>()
        (root["claims"] as? JsonArray).orEmptyElements().forEach { element ->
            val item = element as JsonObject
            val id = item.requiredString("id", "state.claims")
            claims[id] = WorldClaim(
                id = id,
                subject = item.requiredString("subject", "state.claims"),
                predicate = item.requiredString("predicate", "state.claims"),
                value = item.requiredString("value", "state.claims"),
                truth = item.requiredBoolean("truth", "state.claims"),
                active = item.optionalBoolean("active", "state.claims") ?: true,
                dependsOn = item.stringList("depends_on"),
                source = item.optionalString("source", "state.claims").orEmpty(),
                introducedLesson = item.optionalString("introduced_lesson", "state.claims").orEmpty(),
                acquiredAt = item.optionalString("acquired_at", "state.claims").orEmpty(),
                runId = item.optionalString("run_id", "state.claims").orEmpty(),
                retractedAt = item.optionalString("retracted_at", "state.claims"),
                retractedByRunId = item.optionalString("retracted_by_run_id", "state.claims"),
            )
        }
        val observations = LinkedHashMap<String, WorldObservation>()
        (root["observations"] as? JsonArray).orEmptyElements().forEach { element ->
            val item = element as JsonObject
            val id = item.requiredString("id", "state.observations")
            observations[id] = WorldObservation(
                id = id,
                factId = item.requiredString("fact_id", "state.observations"),
                conditions = item.stringMap("conditions"),
                outcome = item.stringMap("outcome"),
                source = item.optionalString("source", "state.observations").orEmpty(),
                introducedLesson = item.optionalString("introduced_lesson", "state.observations").orEmpty(),
                acquiredAt = item.optionalString("acquired_at", "state.observations").orEmpty(),
                runId = item.optionalString("run_id", "state.observations").orEmpty(),
            )
        }
        val story = (root["story"] as? JsonArray).orEmptyElements().map { element ->
            val item = element as JsonObject
            StoryEvent(
                id = item.requiredString("id", "state.story"),
                actor = item.requiredString("actor", "state.story"),
                action = item.requiredString("action", "state.story"),
                objectId = item.optionalString("object", "state.story"),
                to = item.optionalString("to", "state.story"),
                place = item.optionalString("place", "state.story"),
            )
        }.toMutableList()
        val policies = (root["policies"] as? JsonArray).orEmptyElements().map { element ->
            val item = element as JsonObject
            WorldPolicy(
                id = item.requiredString("id", "state.policies"),
                conditions = item.stringMap("when"),
                decision = item.requiredString("decision", "state.policies"),
                source = item.optionalString("source", "state.policies").orEmpty(),
                introducedLesson = item.optionalString("introduced_lesson", "state.policies").orEmpty(),
                acquiredAt = item.optionalString("acquired_at", "state.policies").orEmpty(),
                runId = item.optionalString("run_id", "state.policies").orEmpty(),
            )
        }.toMutableList()
        return BatchWorld(
            worldId = worldId,
            source = root.optionalString("source", "state").orEmpty(),
            sourceDetail = root.optionalString("source_detail", "state"),
            createdAt = root.optionalString("created_at", "state").orEmpty(),
            updatedAt = root.optionalString("updated_at", "state").orEmpty(),
            revision = (root["revision"] as? JsonPrimitive)?.longOrNull ?: 1L,
            loadCount = (root["load_count"] as? JsonPrimitive)?.intOrNull ?: 1,
            lastRunId = root.optionalString("last_run_id", "state").orEmpty(),
            objects = objects,
            relations = relations,
            claims = claims,
            observations = observations,
            story = story,
            policies = policies,
            turns = root.stringList("turns").toMutableList(),
            currentTurn = root.optionalString("current_turn", "state"),
        )
    }

    /** actions.run 的模拟提交使用编码后解码的深拷贝，避免手写浅拷贝漏掉历史列表。 */
    fun deepCopy(world: BatchWorld): BatchWorld = decode(encode(world))
}

/**
 * 按 world_id 存取状态文件。
 *
 * world_id 仅作为内容写入文件，不直接拼路径；文件名是 SHA-256，十六进制固定 64 字符，
 * 因此 "../"、绝对路径或保留设备名都无法穿越 state 目录。
 */
internal class BatchStateStore(private val directory: Path) {
    fun stateFileFor(worldId: String): Path {
        require(worldId.isNotBlank()) { "world_id 不能为空" }
        return directory.resolve(worldStateFileName(worldId))
    }

    @Synchronized
    fun load(worldId: String): BatchWorld? {
        val path = stateFileFor(worldId)
        if (!Files.isRegularFile(path)) return null
        val text = Files.readString(path)
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("世界状态不是合法 JSON：${path.fileName}", error)
        } ?: throw IllegalStateException("世界状态不是 JSON 对象：${path.fileName}")
        val schema = root.optionalString("schema_version", "state")
        check(schema == BATCH_WORLD_STATE_SCHEMA) {
            "世界状态 schema 不匹配：${schema ?: "missing"}"
        }
        val world = try {
            BatchWorldCodec.decode(root)
        } catch (error: Exception) {
            // 状态文件是程序自己写的；解析失败属于存储状态损坏，不是请求参数错误。
            throw IllegalStateException("世界状态损坏：${error.message}", error)
        }
        check(world.worldId == worldId) {
            "世界状态 world_id 与请求不一致：${world.worldId} != $worldId"
        }
        return world
    }

    @Synchronized
    fun save(world: BatchWorld) {
        Files.createDirectories(directory)
        val target = stateFileFor(world.worldId)
        val temporary = directory.resolve(target.fileName.toString() + ".tmp-" + UUID.randomUUID())
        Files.writeString(temporary, BatchWorldCodec.encode(world).toString())
        try {
            // 先写临时文件再原子替换；读取方要么看到旧状态，要么看到完整新状态。
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** 稳定的安全文件名；测试可据此确认没有把 world_id 当作路径片段。 */
fun worldStateFileName(worldId: String): String {
    require(worldId.isNotBlank()) { "world_id 不能为空" }
    val digest = MessageDigest.getInstance("SHA-256").digest(worldId.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".json"
}

// 以下小工具只做类型校验和取值，不替材料补默认语义。

internal fun JsonObject.requiredString(key: String, context: String): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw BatchArgumentException("$context.$key 必须是非空字符串")
    require(primitive.isString && primitive.content.isNotBlank()) {
        "$context.$key 必须是非空字符串"
    }
    return primitive.content
}

internal fun JsonObject.optionalString(key: String, context: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw BatchArgumentException("$context.$key 必须是字符串")
    require(primitive.isString && primitive.content.isNotBlank()) {
        "$context.$key 必须是非空字符串"
    }
    return primitive.content
}

internal fun JsonObject.requiredPrimitive(key: String, context: String): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw BatchArgumentException("$context.$key 必须是字符串或数字")
    return primitive.content
}

internal fun JsonObject.requiredBoolean(key: String, context: String): Boolean {
    val primitive = this[key] as? JsonPrimitive
        ?: throw BatchArgumentException("$context.$key 必须是布尔值")
    require(!primitive.isString) { "$context.$key 必须是布尔值，不能是字符串" }
    return primitive.content.toBooleanStrictOrNull()
        ?: throw BatchArgumentException("$context.$key 必须是布尔值")
}

internal fun JsonObject.optionalBoolean(key: String, context: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw BatchArgumentException("$context.$key 必须是布尔值")
    require(!primitive.isString) { "$context.$key 必须是布尔值" }
    return primitive.content.toBooleanStrictOrNull()
        ?: throw BatchArgumentException("$context.$key 必须是布尔值")
}

internal fun JsonElement.stringValue(context: String): String {
    val primitive = this as? JsonPrimitive
        ?: throw BatchArgumentException("$context 必须是字符串")
    require(primitive.isString) { "$context 必须是字符串" }
    return primitive.content
}

internal fun JsonObject.stringMap(key: String): LinkedHashMap<String, String> {
    val element = this[key]
    if (element == null || element is JsonNull) return LinkedHashMap()
    val obj = element as? JsonObject
        ?: throw IllegalStateException("state.$key 必须是对象")
    val values = LinkedHashMap<String, String>()
    obj.forEach { (k, v) -> values[k] = (v as JsonPrimitive).content }
    return values
}

internal fun JsonObject.stringList(key: String): List<String> {
    val element = this[key]
    if (element == null || element is JsonNull) return emptyList()
    val array = element as? JsonArray
        ?: throw IllegalStateException("state.$key 必须是数组")
    return array.map { (it as JsonPrimitive).content }
}

private fun JsonArray?.orEmptyElements(): List<JsonElement> = this ?: emptyList()
