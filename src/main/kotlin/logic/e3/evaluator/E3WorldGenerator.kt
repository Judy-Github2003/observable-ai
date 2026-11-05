// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A2 最小独立世界生成器；仅 SOLVABLE 与 LATENT_ALIASING，公开观察与隐藏真值分层。
// 边界：PublicWorld 不携带 worldKind/隐藏特征/目标规则；WorldFamily 与 oracle 只属于评测端。
package logic.e3.evaluator

import logic.e3.*

enum class WorldFamily { SOLVABLE, LATENT_ALIASING, NOISY_LABELS, SEARCH_LIMITED, AMBIGUOUS_EVIDENCE }

/** 公开可查询点：只有可见字段，没有标签，也没有隐藏上下文。 */
data class PublicSample(val sampleId: String, val visible: Map<String, String>)

/** 学生可见世界：schema、带标签公开观察、无标签查询池；不包含 family/隐藏特征/目标规则。 */
data class PublicWorld(
    val worldId: String,
    val schema: VisibleSchema,
    val observations: List<Observation>,
    val publicLabeledIds: List<String>,
    val queryPool: List<PublicSample>,
)

/** 评测端真值点：visible + hidden + 标签。hidden 只允许评测端持有。 */
data class HiddenPoint(
    val sampleId: String,
    val visible: Map<String, String>,
    val hidden: Map<String, String>,
    val label: Boolean,
)

/**
 * 隐藏真值层：
 * - family / ruleDescription / hiddenFeature 只给评测端；
 * - truthBySampleId 覆盖公开、查询池和隐藏点，oracle 只能按公开 sampleId 回答；
 * - fullDomain 是声明有限域的全枚举，供后续评测/等价审查。
 */
data class HiddenTruth(
    val worldId: String,
    val family: WorldFamily,
    val ruleDescription: String,
    val hiddenFeature: String?,
    val truthBySampleId: Map<String, Boolean>,
    val fullDomain: List<HiddenPoint>,
    /** 目标规则在冻结 IR 中的近似深度；仅评测端用于 SEARCH_LIMITED 区分。 */
    val ruleDepth: Int = 1,
    /** 明确保存 held-out 隐藏点，禁止用 fullDomain 的 D 系列 ID 推断隐藏集。 */
    val heldOut: List<HiddenPoint>,
)

sealed interface PublicQueryAnswer {
    data class Labeled(val sampleId: String, val label: Boolean) : PublicQueryAnswer
    data class Rejected(val reason: String) : PublicQueryAnswer
}

/** 学生查询入口：只允许 queryPool 的 sampleId；不持有 HiddenTruth/fullDomain。 */
class PublicQueryOracle private constructor(private val allowed: Map<String, Boolean>) {
    val allowedIds: Set<String> get() = allowed.keys
    fun query(sampleId: String): PublicQueryAnswer =
        allowed[sampleId]?.let { PublicQueryAnswer.Labeled(sampleId, it) }
            ?: PublicQueryAnswer.Rejected("query_not_allowed")

    companion object {
        fun from(queryPool: List<PublicSample>, labels: Map<String, Boolean>): PublicQueryOracle =
            PublicQueryOracle(queryPool.associate { it.sampleId to labels.getValue(it.sampleId) })
    }
}

data class StudentView(val public: PublicWorld, val query: PublicQueryOracle)

data class EvaluatedWorld(
    val public: PublicWorld,
    val truth: HiddenTruth,
    val publicQuery: PublicQueryOracle,
) {
    /** 学生接口只拿 public + 受控查询；不得把 truth/fullDomain 传出去。 */
    fun studentView(): StudentView = StudentView(public, publicQuery)
}

/** 独立 oracle：只能按 sampleId 返回标签；不向学生暴露 hidden/family。 */
class E3WorldOracle(private val truth: HiddenTruth) {
    fun label(sampleId: String): Boolean =
        truth.truthBySampleId[sampleId] ?: error("unknown_sample:$sampleId")

    fun fullDomain(): List<HiddenPoint> = truth.fullDomain
}

/**
 * 固定种子生成器：
 * - 同一 seed/family/index → 完全相同的公开数据与真值；
 * - 不同 seed → 采样与规则模板至少一项变化；
 * - 目标规则只在评测端 HiddenTruth 中保存。
 */
class E3WorldGenerator(private val seed: Long) {

    fun generate(family: WorldFamily, index: Int = 0): EvaluatedWorld = when (family) {
        WorldFamily.SOLVABLE -> solvable(index)
        WorldFamily.LATENT_ALIASING -> latentAliasing(index)
        else -> E3AdvancedWorldGenerator(seed).generate(family, index)
    }

    private fun random(family: WorldFamily, index: Int) =
        java.util.Random(seed * 1_000_003L + family.ordinal * 97L + index)

    private fun cartesian(fields: List<String>, values: Map<String, List<String>>): List<Map<String, String>> {
        var result = listOf(emptyMap<String, String>())
        for (field in fields) {
            result = result.flatMap { prefix -> values.getValue(field).map { prefix + (field to it) } }
        }
        return result
    }

    private fun observation(
        id: String,
        objectId: String,
        values: Map<String, String>,
        fields: List<String>,
        label: Boolean,
        source: String,
        reliable: Boolean?,
    ) = logic.e3.Observation(
        observationId = id,
        objectId = objectId,
        visible = fields.sorted().associateWith { ObservedValue.Known(values.getValue(it)) },
        label = label,
        provenance = EvidenceProvenance(
            sourceId = source,
            sourceKind = SourceKind.EXTERNAL_PROVIDED,
            requestedByStudent = false,
            reliableDeterministicLabels = reliable,
        ),
        round = 1,
    )

    private fun solvable(index: Int): EvaluatedWorld {
        val fields = listOf("material", "seal")
        val values = mapOf(
            "material" to listOf("iron", "copper", "ceramic"),
            "seal" to listOf("sealed", "open", "partial"),
        )
        val domain = cartesian(fields, values)
        val rng = random(WorldFamily.SOLVABLE, index)
        val template = rng.nextInt(3)
        val rule: (Map<String, String>) -> Boolean = when (template) {
            0 -> { p -> p.getValue("material") == "copper" }
            1 -> { p -> p.getValue("material") == "copper" && p.getValue("seal") == "sealed" }
            else -> { p -> p.getValue("seal") == "sealed" || p.getValue("material") == "ceramic" }
        }
        val ruleText = when (template) {
            0 -> "material == copper"
            1 -> "material == copper AND seal == sealed"
            else -> "seal == sealed OR material == ceramic"
        }
        val shuffled = domain.shuffled(rng)
        val selected = ArrayList<Map<String, String>>()
        shuffled.firstOrNull(rule)?.let(selected::add)
        shuffled.firstOrNull { !rule(it) }?.let(selected::add)
        for (point in shuffled) if (selected.size < 5 && point !in selected) selected += point
        val hiddenPoints = shuffled.filterNot { it in selected }.take(2)
        val queryPoints = shuffled.filterNot { it in selected || it in hiddenPoints }.take(2)
        // 公开 worldId 不带家族名，避免 worldKind 通过 ID 泄漏。
        val worldId = "world-$seed-$index"

        val observations = selected.mapIndexed { i, point ->
            observation("$worldId-P${i + 1}", "obj-$i", point, fields, rule(point), "public-source", true)
        }
        val query = queryPoints.mapIndexed { i, point ->
            PublicSample("$worldId-Q${i + 1}", point)
        }
        val hidden = hiddenPoints.mapIndexed { i, point ->
            HiddenPoint("$worldId-H${i + 1}", point, emptyMap(), rule(point))
        }
        val domainTruth = domain.mapIndexed { i, point ->
            HiddenPoint("$worldId-D${i + 1}", point, emptyMap(), rule(point))
        }
        val truth = HiddenTruth(
            worldId = worldId,
            family = WorldFamily.SOLVABLE,
            ruleDescription = ruleText,
            hiddenFeature = null,
            truthBySampleId = (observations.map { it.observationId to (it.label ?: false) } +
                hidden.map { it.sampleId to it.label } +
                query.map { sample -> sample.sampleId to rule(sample.visible) } +
                domainTruth.map { it.sampleId to it.label }).toMap(),
            fullDomain = domainTruth,
            heldOut = hidden,
        )
        return EvaluatedWorld(
            PublicWorld(worldId, VisibleSchema(values), observations, observations.map { it.observationId }, query),
            truth,
            PublicQueryOracle.from(query, truth.truthBySampleId),
        )
    }

    private fun latentAliasing(index: Int): EvaluatedWorld {
        val fields = listOf("kind", "shape")
        val values = mapOf(
            "kind" to listOf("k0", "k1", "k2"),
            "shape" to listOf("s0", "s1"),
        )
        val visibleDomain = cartesian(fields, values)
        val rng = random(WorldFamily.LATENT_ALIASING, index)
        val shuffled = visibleDomain.shuffled(rng)
        val worldId = "world-$seed-$index"
        val rule: (Map<String, String>, Boolean) -> Boolean = { point, latent ->
            (point.getValue("kind") == "k1") xor latent
        }

        // 两个可见点各给两个独立来源/对象，制造同可见向量、不同标签的真实碰撞。
        val collisionPoints = shuffled.take(1)
        val otherPoints = shuffled.drop(1).take(1)
        val queryPoints = shuffled.drop(2).take(1)
        val hiddenPoints = shuffled.drop(3).take(2)

        val observations = ArrayList<Observation>()
        for ((i, point) in collisionPoints.withIndex()) {
            val label0 = rule(point, false)
            val label1 = rule(point, true)
            observations += observation("$worldId-P${i * 2 + 1}", "obj-${i}a", point, fields, label0, "source-a", null)
            observations += observation("$worldId-P${i * 2 + 2}", "obj-${i}b", point, fields, label1, "source-b", null)
        }
        for ((i, point) in otherPoints.withIndex()) {
            observations += observation("$worldId-P${i + 5}", "obj-o$i", point, fields, rule(point, false), "source-a", null)
        }

        val query = queryPoints.mapIndexed { i, point ->
            PublicSample("$worldId-Q${i + 1}", point)
        }
        val hidden = hiddenPoints.mapIndexed { i, point ->
            HiddenPoint("$worldId-H${i + 1}", point, mapOf("latent" to (i % 2).toString()), rule(point, i % 2 == 1))
        }
        val fullDomain = visibleDomain.flatMapIndexed { i, point ->
            listOf(false, true).mapIndexed { j, latent ->
                HiddenPoint("$worldId-D${i * 2 + j + 1}", point, mapOf("latent" to if (latent) "1" else "0"), rule(point, latent))
            }
        }
        val truth = HiddenTruth(
            worldId = worldId,
            family = WorldFamily.LATENT_ALIASING,
            ruleDescription = "kind == k1 XOR latent",
            hiddenFeature = "latent",
            truthBySampleId = (observations.map { it.observationId to (it.label ?: false) } +
                hidden.map { it.sampleId to it.label } +
                query.mapIndexed { i, sample -> sample.sampleId to rule(sample.visible, i % 2 == 1) } +
                fullDomain.map { it.sampleId to it.label }).toMap(),
            fullDomain = fullDomain,
            heldOut = hidden,
        )
        return EvaluatedWorld(
            PublicWorld(worldId, VisibleSchema(values), observations, observations.map { it.observationId }, query),
            truth,
            PublicQueryOracle.from(query, truth.truthBySampleId),
        )
    }
}
