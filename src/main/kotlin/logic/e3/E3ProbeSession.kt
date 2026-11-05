// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A7 最小确定性公开探测策略；只使用公开候选池/受控查询，不读取 HiddenTruth/family。
package logic.e3

sealed interface ProbeAction {
    data class RepeatLabel(val objectId: String, val sourceId: String) : ProbeAction
    data class RequestPublicInstance(val sampleId: String) : ProbeAction
    data object NoAction : ProbeAction
}

data class ProbeObservation(
    val sampleId: String?,
    val objectId: String?,
    val visible: Map<String, String>?,
    val label: Boolean?,
    val sourceId: String,
    val rejectedReason: String? = null,
)

/** 由评测端提供的受控公开查询入口；学生策略不持有 HiddenTruth。 */
interface PublicProbeGateway {
    val allowedQueryIds: Set<String>
    fun visibleOf(sampleId: String): Map<String, String>?
    fun requestPublicInstance(sampleId: String): ProbeObservation
    fun repeatLabel(objectId: String, sourceId: String): ProbeObservation?
}

data class ProbeDecision(
    val action: ProbeAction,
    val reason: String,
    val cost: Int,
    val legal: Boolean,
    val notes: List<String>,
    val attribution: String = "student_selected_public_probe",
)

data class ProbeTrace(
    val action: ProbeAction,
    val cost: Int,
    val observation: ProbeObservation?,
    val independentEvidence: Boolean,
    val diagnosisBefore: DiagnosticSignal,
    val diagnosisAfter: DiagnosticSignal,
    val notes: List<String>,
    val attribution: String = "student_selected_public_probe",
)

/**
 * 确定性策略：
 * 1. 完整 Known 向量冲突 → RepeatLabel（不宣称噪声）；
 * 2. Filter 仍有多个假设且有合法区分性公开查询 → 选第一个未用过的；
 * 3. 预算截断/无合法动作/无法判断 → NoAction。
 */
class E3ProbeSession(
    private val schema: VisibleSchema,
    observations: List<Observation>,
    private val constants: PublicConstants,
    private val queryDomain: List<Map<String, String>>,
    private val budget: DiagnosticBudget,
    private val gateway: PublicProbeGateway?,
    private val maxActions: Int = 1,
) {
    private val adapter = E3B0DiagnosticAdapter()
    private val currentObservations = observations.toMutableList()
    private val usedQueryIds = HashSet<String>()
    private var actionsTaken = 0

    fun diagnosis(): DiagnosticSignal = adapter.diagnose(schema, currentObservations, constants, queryDomain, budget)

    fun plan(): ProbeDecision {
        if (actionsTaken >= maxActions) return noAction("action_budget_exhausted")
        val diagnosis = diagnosis()
        if (diagnosis.status == DiagnosticSignalStatus.OBSERVATION_COLLISION) {
            val participant = diagnosis.collisionReport.findings.firstOrNull()?.participants?.firstOrNull()
                ?: return noAction("collision_without_participant")
            return ProbeDecision(
                ProbeAction.RepeatLabel(participant.objectId, participant.sourceId),
                "collision_cause_uncertain_repeat_measurement_not_noise_claim",
                cost = 1,
                legal = true,
                notes = listOf("repeat_is_not_new_independent_evidence"),
            )
        }
        if (diagnosis.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE) {
            if (gateway == null || gateway.allowedQueryIds.isEmpty()) return noAction("no_legal_public_query")
            val suggestedIds = diagnosis.suggestedQueryValues.mapNotNull { value ->
                gateway.allowedQueryIds.firstOrNull { id -> gateway.visibleOf(id) == value }
            }.distinct()
            val next = suggestedIds.firstOrNull { it !in usedQueryIds }
                ?: return noAction("no_unused_distinguishing_query")
            return ProbeDecision(
                ProbeAction.RequestPublicInstance(next),
                "distinguishing_public_query_selected",
                cost = 1,
                legal = true,
                notes = listOf("query_selected_from_filter_suggested_and_gateway_allowed"),
            )
        }
        if (diagnosis.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE ||
            diagnosis.status == DiagnosticSignalStatus.TRAINING_ONLY_CONSISTENCY
        ) {
            return noAction("already_identified_within_bounded_space_no_valuable_action")
        }
        if (diagnosis.search?.reason == "depth_limit" || diagnosis.search?.reason == "candidate_limit" ||
            diagnosis.status == DiagnosticSignalStatus.REFUSED ||
            diagnosis.status == DiagnosticSignalStatus.NO_CANDIDATE
        ) {
            return noAction("search_budget_or_no_candidate_no_valuable_public_action")
        }
        return noAction("already_identified_or_no_valuable_action")
    }

    fun execute(decision: ProbeDecision): ProbeTrace {
        val before = diagnosis()
        if (decision.action is ProbeAction.NoAction || !decision.legal) {
            actionsTaken++
            return ProbeTrace(decision.action, 0, null, false, before, before, listOf("no_action_executed"), decision.attribution)
        }
        actionsTaken++
        return when (val action = decision.action) {
            is ProbeAction.RequestPublicInstance -> {
                if (action.sampleId in usedQueryIds) {
                    return ProbeTrace(action, 0, null, false, before, before, listOf("duplicate_query_not_new_evidence"), decision.attribution)
                }
                val observation = gateway?.requestPublicInstance(action.sampleId)
                    ?: return ProbeTrace(action, 0, null, false, before, before, listOf("no_public_gateway"), decision.attribution)
                if (observation.rejectedReason != null || observation.label == null || observation.visible == null) {
                    return ProbeTrace(action, 1, observation, false, before, before, listOf("query_rejected_or_unlabeled"), decision.attribution)
                }
                usedQueryIds += action.sampleId
                val round = (currentObservations.maxOfOrNull { it.round } ?: 0) + 1
                currentObservations += Observation(
                    observationId = "probe-${action.sampleId}-$round",
                    objectId = observation.objectId ?: "query-${action.sampleId}",
                    visible = observation.visible.mapValues { (_, v) -> ObservedValue.Known(v) },
                    label = observation.label,
                    provenance = EvidenceProvenance(observation.sourceId, SourceKind.STUDENT_REQUESTED, true, null),
                    round = round,
                )
                val after = diagnosis()
                ProbeTrace(action, 1, observation, true, before, after,
                    listOf("new_public_instance_added_to_observations"), decision.attribution)
            }
            is ProbeAction.RepeatLabel -> {
                val repeated = gateway?.repeatLabel(action.objectId, action.sourceId)
                    ?: return ProbeTrace(action, 0, null, false, before, before, listOf("repeat_not_supported_or_rejected"), decision.attribution)
                if (repeated.label == null || repeated.visible == null) {
                    return ProbeTrace(action, 1, repeated, false, before, before, listOf("repeat_unlabeled_or_incomplete"), decision.attribution)
                }
                val round = (currentObservations.maxOfOrNull { it.round } ?: 0) + 1
                currentObservations += Observation(
                    observationId = "repeat-${action.objectId}-$round",
                    objectId = action.objectId,
                    visible = repeated.visible.mapValues { (_, v) -> ObservedValue.Known(v) },
                    label = repeated.label,
                    provenance = EvidenceProvenance(repeated.sourceId, SourceKind.REPEAT_MEASUREMENT, true, null),
                    round = round,
                )
                val after = diagnosis()
                ProbeTrace(action, 1, repeated, false, before, after,
                    listOf("repeat_is_not_new_independent_evidence", "cause_remains_undetermined_until_evidence"), decision.attribution)
            }
            ProbeAction.NoAction -> ProbeTrace(action, 0, null, false, before, before, listOf("no_action"), decision.attribution)
        }
    }

    private fun noAction(reason: String) = ProbeDecision(
        ProbeAction.NoAction, reason, cost = 0, legal = true,
        notes = listOf("no_legal_or_valuable_public_action"), attribution = "student_selected_public_probe",
    )
}
