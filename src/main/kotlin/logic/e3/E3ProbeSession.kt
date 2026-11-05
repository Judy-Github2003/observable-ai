// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A7/A8 最小确定性公开探测；执行门禁、响应校验、重复测量语义与归因分离。
package logic.e3

import java.util.UUID

sealed interface ProbeAction {
    data class RepeatLabel(val objectId: String, val sourceId: String) : ProbeAction
    data class RequestPublicInstance(val sampleId: String) : ProbeAction
    data object NoAction : ProbeAction
}

enum class MeasurementKind { REPLAYED_LABEL, FRESH_MEASUREMENT, PUBLIC_INSTANCE }

data class ProbeObservation(
    val sampleId: String?,
    val objectId: String?,
    val visible: Map<String, String>?,
    val label: Boolean?,
    val sourceId: String,
    val rejectedReason: String? = null,
    val measurementKind: MeasurementKind = MeasurementKind.FRESH_MEASUREMENT,
)

/** 由评测端提供的受控公开查询入口；学生策略不持有 HiddenTruth。 */
interface PublicProbeGateway {
    val allowedQueryIds: Set<String>
    fun visibleOf(sampleId: String): Map<String, String>?
    fun requestPublicInstance(sampleId: String): ProbeObservation
    fun repeatLabel(objectId: String, sourceId: String): ProbeObservation?
}

enum class ProbeExecutionStatus {
    EXECUTED,
    NO_ACTION,
    REJECTED_STALE_OR_FORGED_DECISION,
    REJECTED_ACTION_BUDGET,
    REJECTED_INVALID_ACTION,
    REJECTED_INVALID_RESPONSE,
    DUPLICATE_QUERY,
}

data class ProbeDecision(
    val action: ProbeAction,
    val reason: String,
    val cost: Int,
    val legal: Boolean,
    val notes: List<String>,
    val attribution: String = "student_selected_public_probe",
    val decisionId: String = "",
    val strategySource: String = E3_STRATEGY_SOURCE,
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
    val strategySource: String = E3_STRATEGY_SOURCE,
    val executionStatus: ProbeExecutionStatus = ProbeExecutionStatus.EXECUTED,
    val measurementKind: MeasurementKind? = null,
    val newObservationRecorded: Boolean = false,
    val recordedObservationId: String? = null,
    val addedObservation: Observation? = null,
)

/**
 * 确定性策略：
 * 1. 完整 Known 向量冲突 → RepeatLabel（不宣称噪声）；
 * 2. Filter 仍有多个假设且有合法区分性公开查询 → 选第一个未用过的；
 * 3. 预算截断/无合法动作/已辨识 → NoAction（仅限当前有界空间与策略）。
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
    private val issuedDecisions = HashMap<String, ProbeDecision>()
    private var actionsTaken = 0

    fun diagnosis(): DiagnosticSignal = adapter.diagnose(schema, currentObservations, constants, queryDomain, budget)

    fun plan(): ProbeDecision {
        if (actionsTaken >= maxActions) return issue(noAction("action_budget_exhausted"))
        val diagnosis = diagnosis()
        if (diagnosis.status == DiagnosticSignalStatus.OBSERVATION_COLLISION) {
            val participant = diagnosis.collisionReport.findings.firstOrNull()?.participants?.firstOrNull()
                ?: return issue(noAction("collision_without_participant"))
            return issue(ProbeDecision(
                ProbeAction.RepeatLabel(participant.objectId, participant.sourceId),
                "collision_cause_uncertain_repeat_measurement_not_noise_claim",
                cost = 1,
                legal = true,
                notes = listOf("repeat_is_not_new_independent_evidence"),
            ))
        }
        if (diagnosis.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE) {
            if (gateway == null || gateway.allowedQueryIds.isEmpty()) return issue(noAction("no_legal_public_query"))
            val suggestedIds = diagnosis.suggestedQueryValues.mapNotNull { value ->
                gateway.allowedQueryIds.firstOrNull { id -> gateway.visibleOf(id) == value }
            }.distinct()
            val next = suggestedIds.firstOrNull { it !in usedQueryIds }
                ?: return issue(noAction("no_unused_distinguishing_query"))
            return issue(ProbeDecision(
                ProbeAction.RequestPublicInstance(next),
                "distinguishing_public_query_selected",
                cost = 1,
                legal = true,
                notes = listOf("query_selected_from_filter_suggested_and_gateway_allowed"),
            ))
        }
        if (diagnosis.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE ||
            diagnosis.status == DiagnosticSignalStatus.TRAINING_ONLY_CONSISTENCY
        ) {
            return issue(noAction("no_action_within_current_bounded_space_and_policy"))
        }
        if (diagnosis.search?.reason == "depth_limit" || diagnosis.search?.reason == "candidate_limit" ||
            diagnosis.status == DiagnosticSignalStatus.REFUSED ||
            diagnosis.status == DiagnosticSignalStatus.NO_CANDIDATE
        ) {
            return issue(noAction("search_budget_or_no_candidate_no_valuable_public_action"))
        }
        return issue(noAction("no_valuable_action_within_current_policy"))
    }

    fun execute(decision: ProbeDecision): ProbeTrace {
        val before = diagnosis()
        // 执行门禁：只接受当前会话签发且未消费的决策；外部伪造/过期/篡改一律拒绝。
        val issued = issuedDecisions.remove(decision.decisionId)
        if (issued == null || issued != decision) {
            return rejected(before, decision, ProbeExecutionStatus.REJECTED_STALE_OR_FORGED_DECISION, "stale_or_forged_decision")
        }
        if (actionsTaken >= maxActions) {
            return rejected(before, decision, ProbeExecutionStatus.REJECTED_ACTION_BUDGET, "action_budget_exhausted")
        }
        if (!decision.legal || decision.cost < 0) {
            return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_ACTION, "illegal_decision")
        }
        return when (val action = decision.action) {
            ProbeAction.NoAction -> {
                actionsTaken++
                ProbeTrace(
                    action, 0, null, false, before, before,
                    listOf("no_claim_that_no_valuable_query_exists_in_world"),
                    executionStatus = ProbeExecutionStatus.NO_ACTION,
                )
            }
            is ProbeAction.RequestPublicInstance -> {
                if (decision.cost != 1) return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_ACTION, "invalid_query_cost")
                if (gateway == null || action.sampleId !in gateway.allowedQueryIds) {
                    return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_ACTION, "query_id_not_allowed")
                }
                if (action.sampleId in usedQueryIds) {
                    return rejected(before, decision, ProbeExecutionStatus.DUPLICATE_QUERY, "duplicate_query_not_new_evidence")
                }
                val response = gateway.requestPublicInstance(action.sampleId)
                val error = validatePublicResponse(action.sampleId, response)
                if (error != null) {
                    return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_RESPONSE, error, response, MeasurementKind.PUBLIC_INSTANCE)
                }
                usedQueryIds += action.sampleId
                actionsTaken++
                val round = (currentObservations.maxOfOrNull { it.round } ?: 0) + 1
                val recordId = "probe-${action.sampleId}-$round"
                currentObservations += Observation(
                    observationId = recordId,
                    objectId = response.objectId!!,
                    visible = response.visible!!.mapValues { (_, v) -> ObservedValue.Known(v) },
                    label = response.label,
                    provenance = EvidenceProvenance(response.sourceId, SourceKind.STUDENT_REQUESTED, true, null),
                    round = round,
                )
                ProbeTrace(
                    action, 1, response, true, before, diagnosis(),
                    listOf("new_public_instance_added_to_observations"),
                    executionStatus = ProbeExecutionStatus.EXECUTED,
                    measurementKind = MeasurementKind.PUBLIC_INSTANCE,
                    newObservationRecorded = true,
                    recordedObservationId = recordId,
                    addedObservation = currentObservations.last(),
                )
            }
            is ProbeAction.RepeatLabel -> {
                if (decision.cost != 1) return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_ACTION, "invalid_repeat_cost")
                val original = currentObservations.filter { it.objectId == action.objectId }
                if (original.isEmpty()) return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_ACTION, "repeat_object_not_in_evidence")
                val response = gateway?.repeatLabel(action.objectId, action.sourceId)
                    ?: return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_RESPONSE, "repeat_not_supported_or_rejected")
                val error = validateRepeatResponse(action, original, response)
                if (error != null) {
                    return rejected(before, decision, ProbeExecutionStatus.REJECTED_INVALID_RESPONSE, error, response, response.measurementKind)
                }
                actionsTaken++
                val round = (currentObservations.maxOfOrNull { it.round } ?: 0) + 1
                val recordId = "repeat-${action.objectId}-$round"
                currentObservations += Observation(
                    observationId = recordId,
                    objectId = action.objectId,
                    visible = response.visible!!.mapValues { (_, v) -> ObservedValue.Known(v) },
                    label = response.label,
                    provenance = EvidenceProvenance(response.sourceId, SourceKind.REPEAT_MEASUREMENT, true, null),
                    round = round,
                )
                ProbeTrace(
                    action, 1, response, false, before, diagnosis(),
                    listOf(
                        "repeat_is_not_new_independent_evidence",
                        if (response.measurementKind == MeasurementKind.FRESH_MEASUREMENT) "fresh_repeat_recorded_but_independence_not_proven" else "replayed_label_recorded_not_new_evidence",
                        "cause_remains_undetermined_until_evidence",
                    ),
                    executionStatus = ProbeExecutionStatus.EXECUTED,
                    measurementKind = response.measurementKind,
                    newObservationRecorded = true,
                    recordedObservationId = recordId,
                    addedObservation = currentObservations.last(),
                )
            }
        }
    }

    private fun issue(decision: ProbeDecision): ProbeDecision {
        val withId = decision.copy(decisionId = UUID.randomUUID().toString())
        issuedDecisions[withId.decisionId] = withId
        return withId
    }

    private fun noAction(reason: String) = ProbeDecision(
        ProbeAction.NoAction, reason, cost = 0, legal = true,
        notes = listOf("no_legal_or_valuable_public_action_within_current_policy"),
        attribution = "student_selected_public_probe",
        strategySource = E3_STRATEGY_SOURCE,
    )

    private fun validatePublicResponse(sampleId: String, response: ProbeObservation): String? {
        if (response.sampleId != sampleId) return "response_sample_id_mismatch"
        if (response.objectId.isNullOrBlank()) return "response_object_id_missing"
        if (response.rejectedReason != null) return "response_rejected"
        if (response.label == null) return "response_label_missing"
        val expected = gateway?.visibleOf(sampleId) ?: return "response_unknown_public_point"
        if (response.visible != expected) return "response_visible_mismatch"
        return validateVisible(response.visible)
    }

    private fun validateRepeatResponse(action: ProbeAction.RepeatLabel, original: List<Observation>, response: ProbeObservation): String? {
        if (response.objectId != action.objectId) return "repeat_object_id_mismatch"
        if (response.label == null) return "repeat_label_missing"
        val visible = response.visible ?: return "repeat_visible_missing"
        val expected = original.first().visible.mapValues { (_, v) -> (v as ObservedValue.Known).value }
        if (visible != expected) return "repeat_visible_mismatch"
        return validateVisible(visible)
    }

    private fun validateVisible(visible: Map<String, String>): String? {
        if (visible.keys != schema.fields.keys) return "response_schema_mismatch"
        for ((field, value) in visible) {
            if (value !in schema.fields.getValue(field)) return "response_value_out_of_domain"
        }
        return null
    }

    private fun rejected(
        before: DiagnosticSignal,
        decision: ProbeDecision,
        status: ProbeExecutionStatus,
        note: String,
        observation: ProbeObservation? = null,
        measurementKind: MeasurementKind? = null,
    ) = ProbeTrace(
        decision.action, 0, observation, false, before, before, listOf(note),
        executionStatus = status,
        measurementKind = measurementKind,
    )
}
