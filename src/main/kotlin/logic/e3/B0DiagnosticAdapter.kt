// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A5 只读诊断适配器；把公开观察交给冻结 B0 Composer/Filter，不读取评测端 family/truth。
package logic.e3

import logic.capability.*

data class DiagnosticBudget(
    val maxDepth: Int = 3,
    val maxNodes: Int = 32,
    val maxCandidates: Int = 3000,
    val maxSteps: Int = 10000,
)

data class SearchTrace(
    val maxDepth: Int,
    val generated: Int,
    val exhausted: Boolean,
    val reason: String?,
    val candidateCount: Int,
)

data class FilterTrace(
    val status: String,
    val reason: String?,
    val survivorCount: Int,
    val queryCount: Int,
    val domainSize: Int,
)

enum class DiagnosticSignalStatus {
    INVALID_INPUT,
    NO_COMPLETE_OBSERVATIONS,
    OBSERVATION_COLLISION,
    IDENTIFIED_WITHIN_BOUNDED_SPACE,
    AMBIGUOUS_WITHIN_BOUNDED_SPACE,
    REFUSED,
    NO_CANDIDATE,
}

/**
 * 只读诊断信号：
 * - search/filter 真实调用冻结 B0；
 * - IDENTIFIED 只表示“当前有界假设空间 + 查询域内”的辨识；
 * - collision 直接来自 A4 报告，不重新实现碰撞算法；
 * - 不携带 WorldFamily/HiddenTruth/target rule/ruleDepth。
 */
data class DiagnosticSignal(
    val status: DiagnosticSignalStatus,
    val usableObservationIds: List<String>,
    val excludedUnknownValueIds: List<String>,
    val excludedUnlabeledIds: List<String>,
    val collisionReport: CollisionReport,
    val search: SearchTrace?,
    val filter: FilterTrace?,
    val boundedScope: EvidenceScope?,
    val notes: List<String>,
)

class E3B0DiagnosticAdapter {

    fun diagnose(
        schema: VisibleSchema,
        observations: List<Observation>,
        constants: List<String> = emptyList(),
        queryDomain: List<Map<String, String>> = emptyList(),
        budget: DiagnosticBudget = DiagnosticBudget(),
    ): DiagnosticSignal {
        val validation = validateObservations(schema, observations)
        val collision = ObservableCollisionDetector.detect(schema, observations)
        if (!validation.ok) {
            return DiagnosticSignal(
                status = DiagnosticSignalStatus.INVALID_INPUT,
                usableObservationIds = emptyList(),
                excludedUnknownValueIds = collision.excludedUnknownValueIds,
                excludedUnlabeledIds = collision.excludedUnlabeledIds,
                collisionReport = collision,
                search = null,
                filter = null,
                boundedScope = null,
                notes = validation.errors.map { "invalid:${it.code}" },
            )
        }

        // 有完整 Known 向量上的标签冲突时，搜索不能解决逻辑矛盾；保留 A4 证据并停止。
        if (collision.findings.isNotEmpty()) {
            return DiagnosticSignal(
                status = DiagnosticSignalStatus.OBSERVATION_COLLISION,
                usableObservationIds = emptyList(),
                excludedUnknownValueIds = collision.excludedUnknownValueIds,
                excludedUnlabeledIds = collision.excludedUnlabeledIds,
                collisionReport = collision,
                search = null,
                filter = null,
                boundedScope = collision.scope,
                notes = listOf("observable_collision_present; composer_search_skipped_to_avoid_pretending_to_resolve_it"),
            )
        }

        val usable = observations.filter { obs ->
            obs.label != null && obs.visible.values.all { it is ObservedValue.Known }
        }
        if (usable.isEmpty()) {
            return DiagnosticSignal(
                status = DiagnosticSignalStatus.NO_COMPLETE_OBSERVATIONS,
                usableObservationIds = emptyList(),
                excludedUnknownValueIds = collision.excludedUnknownValueIds,
                excludedUnlabeledIds = collision.excludedUnlabeledIds,
                collisionReport = collision,
                search = null,
                filter = null,
                boundedScope = null,
                notes = listOf("no_complete_known_labeled_observations"),
            )
        }

        val type = CapType.Record(schema.fieldNames().associateWith { CapType.Str })
        val inputs = mapOf("x" to type)
        val capConstants = (constants.ifEmpty { schema.fields.values.flatten().distinct() })
            .distinct()
            .map { CapValue.Str(it) }
        val examples = usable.map { obs ->
            PublicExample(
                mapOf("x" to CapValue.Rec(obs.visible.mapValues { (_, v) -> CapValue.Str((v as ObservedValue.Known).value) })),
                CapValue.Bool(obs.label!!),
            )
        }
        val domainValues = if (queryDomain.isNotEmpty()) {
            queryDomain.map { point ->
                mapOf("x" to CapValue.Rec(schema.fieldNames().associateWith { CapValue.Str(point.getValue(it)) }))
            }
        } else {
            usable.map { obs ->
                mapOf("x" to CapValue.Rec(obs.visible.mapValues { (_, v) -> CapValue.Str((v as ObservedValue.Known).value) }))
            }
        }

        val composed = CapabilityComposer().compose(
            ComposeRequest(inputs, CapType.Bool3, capConstants, emptyList(), ComposeBudget(
                maxDepth = budget.maxDepth,
                maxNodes = budget.maxNodes,
                maxCandidates = budget.maxCandidates,
            )),
        )
        val evidence = CandidateEvidenceFilter(InMemoryResolver(emptyList())).filter(
            inputs,
            composed.candidates,
            examples,
            mapOf("x" to domainValues.map { it.getValue("x") }),
            EvalBudget(maxSteps = budget.maxSteps),
            composed.reason == "candidate_limit",
        )

        val status = when (evidence.status) {
            EvidenceStatus.IDENTIFIED -> DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE
            EvidenceStatus.AMBIGUOUS -> DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE
            EvidenceStatus.REFUSED -> if (composed.candidates.isEmpty()) DiagnosticSignalStatus.NO_CANDIDATE else DiagnosticSignalStatus.REFUSED
        }
        val notes = ArrayList<String>()
        if (composed.reason == "depth_limit") {
            notes += "depth_limit_observed; this does NOT prove the target rule is outside the hypothesis space"
        }
        if (composed.reason == "candidate_limit") {
            notes += "candidate_limit_observed; search truncated before exhaustion"
        }
        notes += "identified_scope_is_bounded_to_current_hypothesis_and_query_domain"
        if (queryDomain.isEmpty()) notes += "query_domain_restricted_to_observed_points"
        if (collision.excludedUnknownValueIds.isNotEmpty()) notes += "unknown_values_excluded_from_examples"
        if (collision.excludedUnlabeledIds.isNotEmpty()) notes += "unlabeled_observations_excluded"

        return DiagnosticSignal(
            status = status,
            usableObservationIds = usable.map { it.observationId },
            excludedUnknownValueIds = collision.excludedUnknownValueIds,
            excludedUnlabeledIds = collision.excludedUnlabeledIds,
            collisionReport = collision,
            search = SearchTrace(
                maxDepth = budget.maxDepth,
                generated = composed.generated,
                exhausted = composed.exhausted,
                reason = composed.reason,
                candidateCount = composed.candidates.size,
            ),
            filter = FilterTrace(
                status = evidence.status.name,
                reason = evidence.reason,
                survivorCount = evidence.survivors.size,
                queryCount = evidence.distinguishingQueries.size,
                domainSize = domainValues.size,
            ),
            boundedScope = EvidenceScope(
                hypothesisSpace = "typed IR depth<=${budget.maxDepth}, nodes<=${budget.maxNodes}, library=[]",
                observationDomain = "${usable.size} complete Known labeled observations",
                queryDomain = "${domainValues.size} public query points",
            ),
            notes = notes,
        )
    }
}
