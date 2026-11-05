// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 公平基线/预算计量/评分契约；结论与动作分离，不读取 HiddenTruth。
package logic.e3

import logic.capability.Expr

enum class A9BaselineId { B0_ONLY, COLLISION_ONLY, REPEAT_ONLY, E3_DIAGNOSTIC, ALWAYS_UNDETERMINED }

/** 诊断结论；动作本身不是知识结论。 */
enum class A9DiagnosticClaim { BOUNDED_IDENTIFIED, OBSERVATION_COLLISION, UNDETERMINED }

enum class A9ActionKind { REQUEST_PUBLIC_INSTANCE, REPEAT_LABEL, NO_ACTION }

data class A9ActionRecord(
    val actionKind: A9ActionKind,
    val requestedSampleId: String?,
    val repeatedObjectId: String?,
    val recordedObservationId: String?,
    val measurementKind: MeasurementKind?,
    val independentEvidence: Boolean,
    val actualCost: Int,
    val actualClaim: String,
)

data class A9BaselineResult(
    val baseline: A9BaselineId,
    val diagnosticClaim: A9DiagnosticClaim,
    val action: A9ActionRecord?,
    val evidenceIds: List<String>,
    val boundedScope: EvidenceScope?,
    val computation: E3UsageSnapshot,
    val notes: List<String>,
    val claimedGlobal: Boolean = false,
    val candidateBodies: List<Expr> = emptyList(),
    val candidateCanonicals: List<String> = emptyList(),
)

/** 评测端 truth；只供独立评分，不进入基线。 */
data class A9CaseTruth(
    val boundedClaimValid: Boolean?,
    val heldoutPredictionError: Double?,
)

data class A9Score(
    val boundedClaimValidity: Boolean?,
    val heldoutPredictionError: Double?,
    val epistemicOverclaim: Boolean,
    val diagnosticCoverage: Boolean,
    val collisionCoverage: Boolean,
)

object A9Scorer {
    /**
     * 有界辨识后来隐藏预测错误，不算 epistemic overclaim；
     * overclaim 只由 claimedGlobal/越权原因声称触发。
     */
    fun score(result: A9BaselineResult, truth: A9CaseTruth): A9Score = A9Score(
        boundedClaimValidity = if (result.diagnosticClaim == A9DiagnosticClaim.BOUNDED_IDENTIFIED) truth.boundedClaimValid else null,
        heldoutPredictionError = if (result.diagnosticClaim == A9DiagnosticClaim.BOUNDED_IDENTIFIED) truth.heldoutPredictionError else null,
        epistemicOverclaim = result.claimedGlobal,
        diagnosticCoverage = result.diagnosticClaim == A9DiagnosticClaim.BOUNDED_IDENTIFIED,
        collisionCoverage = result.diagnosticClaim == A9DiagnosticClaim.OBSERVATION_COLLISION,
    )
}

class E3A9BaselineRunner {

    fun run(
        baseline: A9BaselineId,
        schema: VisibleSchema,
        observations: List<Observation>,
        constants: PublicConstants,
        queryDomain: List<Map<String, String>>,
        gateway: PublicProbeGateway? = null,
    ): A9BaselineResult {
        val meter = E3UsageMeter()
        val low = DiagnosticBudget(2, 32, 3000, 10000)
        val high = DiagnosticBudget(3, 32, 3000, 10000)
        val result = when (baseline) {
            A9BaselineId.ALWAYS_UNDETERMINED -> A9BaselineResult(
                baseline, A9DiagnosticClaim.UNDETERMINED, null, emptyList(), null, meter.snapshot(),
                listOf("always_abstain_control"),
            )
            A9BaselineId.B0_ONLY -> {
                val signal = E3B0DiagnosticAdapter().diagnose(schema, observations, constants, queryDomain, high, meter)
                val claim = if (signal.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE) {
                    A9DiagnosticClaim.BOUNDED_IDENTIFIED
                } else A9DiagnosticClaim.UNDETERMINED
                A9BaselineResult(
                    baseline, claim, null, signal.usableObservationIds, signal.boundedScope, meter.snapshot(),
                    listOf("b0_filter_only", "status=${signal.status}"),
                    candidateBodies = if (claim == A9DiagnosticClaim.BOUNDED_IDENTIFIED) signal.consistentCandidateBodies else emptyList(),
                    candidateCanonicals = if (claim == A9DiagnosticClaim.BOUNDED_IDENTIFIED) signal.consistentCandidateHashes else emptyList(),
                )
            }
            A9BaselineId.COLLISION_ONLY -> {
                val collision = ObservableCollisionDetector.detect(schema, observations)
                if (collision.findings.isEmpty()) {
                    A9BaselineResult(baseline, A9DiagnosticClaim.UNDETERMINED, null, emptyList(), collision.scope,
                        meter.snapshot(), listOf("no_collision_no_search_performed"))
                } else {
                    val ids = collision.findings.flatMap { it.participants }.map { it.observationId }.distinct()
                    A9BaselineResult(baseline, A9DiagnosticClaim.OBSERVATION_COLLISION, null, ids, collision.scope,
                        meter.snapshot(), listOf("collision_detected_only", "cause_not_determined"))
                }
            }
            A9BaselineId.REPEAT_ONLY -> {
                val collision = ObservableCollisionDetector.detect(schema, observations)
                if (collision.findings.isEmpty()) {
                    A9BaselineResult(baseline, A9DiagnosticClaim.UNDETERMINED, null, emptyList(), collision.scope,
                        meter.snapshot(), listOf("no_collision_no_repeat"))
                } else {
                    val session = E3ProbeSession(schema, observations, constants, queryDomain, high, gateway, meter = meter)
                    val trace = session.execute(session.plan())
                    A9BaselineResult(
                        baseline = baseline,
                        diagnosticClaim = A9DiagnosticClaim.UNDETERMINED,
                        action = actionRecord(trace),
                        evidenceIds = listOfNotNull(trace.recordedObservationId),
                        boundedScope = collision.scope,
                        computation = meter.snapshot(),
                        notes = listOf("repeat_only", "status=${trace.executionStatus}", "independent=${trace.independentEvidence}"),
                    )
                }
            }
            A9BaselineId.E3_DIAGNOSTIC -> {
                val session = E3ProbeSession(schema, observations, constants, queryDomain, high, gateway, meter = meter)
                val probe = session.execute(session.plan())
                val integrated = E3DiagnosticIntegrator().integrate(
                    schema, observations, constants, queryDomain, low, high, probe, meter,
                )
                val kinds = integrated.report.findings.map { it.kind }.toSet()
                val claim = when {
                    FindingKind.OBSERVED_LABEL_COLLISION in kinds -> A9DiagnosticClaim.OBSERVATION_COLLISION
                    FindingKind.SUPPORTED_WITHIN_BOUNDED_H in kinds -> A9DiagnosticClaim.BOUNDED_IDENTIFIED
                    else -> A9DiagnosticClaim.UNDETERMINED
                }
                val ids = integrated.report.findings.flatMap { it.evidenceIds }.distinct()
                A9BaselineResult(
                    baseline, claim, actionRecord(probe), ids, integrated.signal.boundedScope, meter.snapshot(),
                    listOf("integrated_report", "strategy=${integrated.report.strategySource}"),
                    candidateBodies = if (claim == A9DiagnosticClaim.BOUNDED_IDENTIFIED) integrated.signal.consistentCandidateBodies else emptyList(),
                    candidateCanonicals = if (claim == A9DiagnosticClaim.BOUNDED_IDENTIFIED) integrated.signal.consistentCandidateHashes else emptyList(),
                )
            }
        }
        return result
    }

    private fun actionRecord(trace: ProbeTrace): A9ActionRecord = when (val action = trace.action) {
        is ProbeAction.RequestPublicInstance -> A9ActionRecord(
            A9ActionKind.REQUEST_PUBLIC_INSTANCE, action.sampleId, null,
            trace.recordedObservationId, trace.measurementKind, trace.independentEvidence, trace.cost,
            trace.diagnosisAfter.status.name,
        )
        is ProbeAction.RepeatLabel -> A9ActionRecord(
            A9ActionKind.REPEAT_LABEL, null, action.objectId,
            trace.recordedObservationId, trace.measurementKind, trace.independentEvidence, trace.cost,
            trace.diagnosisAfter.status.name,
        )
        ProbeAction.NoAction -> A9ActionRecord(
            A9ActionKind.NO_ACTION, null, null, null, null, false, 0, trace.diagnosisAfter.status.name,
        )
    }
}
