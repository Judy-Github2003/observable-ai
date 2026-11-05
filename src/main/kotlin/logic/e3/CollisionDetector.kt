// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A4 可观察特征碰撞检测；只报告同一完整可见向量上的标签冲突，不解释原因。
package logic.e3

/** 碰撞参与者：保留 observationId、来源、轮次和已观察标签，便于审计。 */
data class CollisionParticipant(
    val observationId: String,
    val sourceId: String,
    val round: Int,
    val label: Boolean,
)

data class CollisionFinding(
    val featureKey: String,
    val participants: List<CollisionParticipant>,
    val scope: EvidenceScope,
)

/**
 * A4 报告：
 * - findings 仅表示“同一完整可见向量出现不同已观察标签”；
 * - 不推断 hidden/noise/world 原因；
 * - Unknown 值或缺失标签不参与分组，单独列出；
 * - validationErrors 原样返回，不把非法输入当碰撞。
 */
data class CollisionReport(
    val findings: List<CollisionFinding>,
    val excludedUnknownValueIds: List<String>,
    val excludedUnlabeledIds: List<String>,
    val validationErrors: List<ContractError>,
    val scope: EvidenceScope,
)

object ObservableCollisionDetector {

    fun detect(schema: VisibleSchema, observations: List<Observation>): CollisionReport {
        val validation = validateObservations(schema, observations)
        val scope = EvidenceScope(
            hypothesisSpace = "observable feature vectors only; no causal/hidden explanation",
            observationDomain = "${observations.size} observations, schema fields=${schema.fieldNames()}",
        )
        if (!validation.ok) {
            return CollisionReport(emptyList(), emptyList(), emptyList(), validation.errors, scope)
        }

        val excludedUnknown = ArrayList<String>()
        val excludedUnlabeled = ArrayList<String>()
        val groups = LinkedHashMap<String, MutableList<Observation>>()
        for (observation in validation.validObservations) {
            if (observation.label == null) {
                excludedUnlabeled += observation.observationId
                continue
            }
            if (observation.visible.values.any { it == ObservedValue.Unknown }) {
                excludedUnknown += observation.observationId
                continue
            }
            val key = featureKey(observation).canonical
            groups.getOrPut(key) { ArrayList() } += observation
        }

        val findings = groups.entries
            .mapNotNull { (key, group) ->
                val labels = group.mapNotNull { it.label }.toSet()
                if (labels.size <= 1) return@mapNotNull null
                CollisionFinding(
                    featureKey = key,
                    participants = group
                        .map { CollisionParticipant(it.observationId, it.provenance.sourceId, it.round, it.label!!) }
                        .sortedBy { it.observationId },
                    scope = scope,
                )
            }
            .sortedBy { it.featureKey }

        return CollisionReport(
            findings = findings,
            excludedUnknownValueIds = excludedUnknown.sorted(),
            excludedUnlabeledIds = excludedUnlabeled.sorted(),
            validationErrors = emptyList(),
            scope = scope,
        )
    }
}
