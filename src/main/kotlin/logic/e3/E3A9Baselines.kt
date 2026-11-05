// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 最小可执行基线；只接收公开证据/受控网关，不读取 HiddenTruth/WorldFamily。
package logic.e3

enum class A9BaselineId {
    B0_ONLY,
    COLLISION_ONLY,
    REPEAT_ONLY,
    E3_DIAGNOSTIC,
    ALWAYS_UNDETERMINED,
}

enum class A9Claim {
    BOUNDED_IDENTIFIED,
    OBSERVATION_COLLISION,
    REPEATED_MEASUREMENT,
    NO_ACTION,
    UNDETERMINED,
}

data class A9BaselineResult(
    val baseline: A9BaselineId,
    val claim: A9Claim,
    val evidenceIds: List<String>,
    val queryIds: List<String>,
    val cost: Int,
    val notes: List<String>,
    /** 基线的任何输出都不得声称全局正确。 */
    val claimedGlobal: Boolean = false,
)

/**
 * A9 基线只做最小可执行比较：
 * - B0_ONLY：A5 有界 Composer/Filter；
 * - COLLISION_ONLY：A4 冲突检测，不做搜索；
 * - REPEAT_ONLY：冲突时 A7 RepeatLabel；
 * - E3_DIAGNOSTIC：A6/A7/A8 综合；
 * - ALWAYS_UNDETERMINED：覆盖率对照下界。
 *
 * 不包含完整评测指标/批量运行；A9 协议另行冻结。
 */
class E3A9BaselineRunner {

    fun run(
        baseline: A9BaselineId,
        schema: VisibleSchema,
        observations: List<Observation>,
        constants: PublicConstants,
        queryDomain: List<Map<String, String>>,
        gateway: PublicProbeGateway? = null,
    ): A9BaselineResult {
        val low = DiagnosticBudget(2, 32, 3000, 10000)
        val high = DiagnosticBudget(3, 32, 3000, 10000)
        return when (baseline) {
            A9BaselineId.ALWAYS_UNDETERMINED -> A9BaselineResult(
                baseline, A9Claim.UNDETERMINED, emptyList(), emptyList(), 0,
                listOf("always_abstain_control"),
            )
            A9BaselineId.B0_ONLY -> {
                val signal = E3B0DiagnosticAdapter().diagnose(schema, observations, constants, queryDomain, high)
                val claim = if (signal.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE) {
                    A9Claim.BOUNDED_IDENTIFIED
                } else A9Claim.UNDETERMINED
                A9BaselineResult(
                    baseline, claim, signal.usableObservationIds, emptyList(), 0,
                    listOf("b0_filter_only", "status=${signal.status}"),
                )
            }
            A9BaselineId.COLLISION_ONLY -> {
                val collision = ObservableCollisionDetector.detect(schema, observations)
                if (collision.findings.isEmpty()) {
                    A9BaselineResult(baseline, A9Claim.UNDETERMINED, emptyList(), emptyList(), 0,
                        listOf("no_collision_no_search_performed"))
                } else {
                    val ids = collision.findings.flatMap { it.participants }.map { it.observationId }.distinct()
                    A9BaselineResult(baseline, A9Claim.OBSERVATION_COLLISION, ids, emptyList(), 0,
                        listOf("collision_detected_only", "cause_not_determined"))
                }
            }
            A9BaselineId.REPEAT_ONLY -> {
                val collision = ObservableCollisionDetector.detect(schema, observations)
                if (collision.findings.isEmpty()) {
                    A9BaselineResult(baseline, A9Claim.UNDETERMINED, emptyList(), emptyList(), 0,
                        listOf("no_collision_no_repeat"))
                } else {
                    val session = E3ProbeSession(schema, observations, constants, queryDomain, high, gateway)
                    val decision = session.plan()
                    val trace = session.execute(decision)
                    val claim = when (trace.executionStatus) {
                        ProbeExecutionStatus.EXECUTED -> A9Claim.REPEATED_MEASUREMENT
                        ProbeExecutionStatus.NO_ACTION -> A9Claim.NO_ACTION
                        else -> A9Claim.UNDETERMINED
                    }
                    A9BaselineResult(
                        baseline, claim, listOfNotNull(trace.recordedObservationId), emptyList(), trace.cost,
                        listOf("repeat_only", "status=${trace.executionStatus}", "independent=${trace.independentEvidence}"),
                    )
                }
            }
            A9BaselineId.E3_DIAGNOSTIC -> {
                val session = E3ProbeSession(schema, observations, constants, queryDomain, high, gateway)
                val probe = session.execute(session.plan())
                val integrated = E3DiagnosticIntegrator().integrate(
                    schema, observations, constants, queryDomain, low, high, probe,
                )
                val kinds = integrated.report.findings.map { it.kind }.toSet()
                val claim = when {
                    FindingKind.OBSERVED_LABEL_COLLISION in kinds -> A9Claim.OBSERVATION_COLLISION
                    FindingKind.SUPPORTED_WITHIN_BOUNDED_H in kinds -> A9Claim.BOUNDED_IDENTIFIED
                    else -> A9Claim.UNDETERMINED
                }
                val ids = integrated.report.findings.flatMap { it.evidenceIds }.distinct()
                A9BaselineResult(
                    baseline, claim, ids, listOfNotNull(probe.recordedObservationId).filter { probe.executionStatus == ProbeExecutionStatus.EXECUTED },
                    probe.cost, listOf("integrated_report", "strategy=${integrated.report.strategySource}"),
                )
            }
        }
    }
}
