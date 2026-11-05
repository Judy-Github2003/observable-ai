// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A8 将 A4 冲突、A5/A6 搜索事实、A7 行动轨迹整合为可追溯 DiagnosticReport。
package logic.e3

data class IntegratedDiagnosis(
    val report: DiagnosticReport,
    val budget: BudgetDiagnosis,
    val probe: ProbeTrace?,
    val signal: DiagnosticSignal,
)

/**
 * 只根据真实 DiagnosticSignal/BudgetDiagnosis/ProbeTrace 组合 findings；
 * 不按 WorldFamily/target rule/HiddenTruth 生成结论。
 */
class E3DiagnosticIntegrator {

    fun integrate(
        schema: VisibleSchema,
        observations: List<Observation>,
        constants: PublicConstants,
        queryDomain: List<Map<String, String>>,
        low: DiagnosticBudget,
        high: DiagnosticBudget,
        probe: ProbeTrace? = null,
    ): IntegratedDiagnosis {
        val budget = E3SearchBudgetDiagnoser().diagnose(schema, observations, constants, queryDomain, low, high)
        val signal = budget.runs.last().signal
        val findings = ArrayList<DiagnosticFinding>()
        val collision = signal.collisionReport

        if (collision.findings.isNotEmpty()) {
            val ids = collision.findings.flatMap { it.participants }.map { it.observationId }.distinct()
            findings += DiagnosticFinding(
                kind = FindingKind.OBSERVED_LABEL_COLLISION,
                evidenceIds = ids,
                scope = collision.scope,
                sourceHypotheses = listOf("a deterministic function of current visible features fits all labels"),
                observationConclusion = "same complete visible vector has both true and false observed labels",
                assumptions = listOf("labels are for this observation context"),
                alternativeExplanations = listOf(
                    "label noise", "unobserved context or hidden attribute", "recording error", "non-deterministic process",
                ),
            )
            findings += DiagnosticFinding(
                kind = FindingKind.UNDETERMINED,
                evidenceIds = ids,
                scope = collision.scope,
                sourceHypotheses = listOf("collision cause is not identifiable from these observations alone"),
                observationConclusion = "collision alone does not decide between noise/context/hidden representation",
                assumptions = listOf("no independent repeated measurement or external context is available"),
                alternativeExplanations = listOf("noise", "hidden attribute", "context change", "data error"),
            )
        }

        val usableIds = signal.usableObservationIds
        val scope = signal.boundedScope ?: EvidenceScope("bounded run", "public observations")
        if (budget.facts.searchTruncated) {
            findings += DiagnosticFinding(
                kind = FindingKind.SEARCH_TRUNCATED,
                evidenceIds = usableIds,
                scope = scope,
                sourceHypotheses = listOf("target could be inside the current typed-IR hypothesis space"),
                observationConclusion = "search stopped by compose rounds/candidates, not by exhaustion of all expressions",
                assumptions = listOf("current IR/library and constants are fixed"),
                alternativeExplanations = listOf(
                    "target outside current hypothesis space", "query domain insufficient", "implementation defect or insufficient budget",
                ),
            )
        }
        if (budget.facts.budgetSensitive) {
            findings += DiagnosticFinding(
                kind = FindingKind.BUDGET_SENSITIVE,
                evidenceIds = usableIds,
                scope = scope,
                sourceHypotheses = listOf("candidate set depends on the compose/eval budget"),
                observationConclusion = "candidate set changed between low and increased budget",
                assumptions = listOf("only budget changed; observations/query domain held fixed"),
                alternativeExplanations = listOf("search truncation", "representation gap", "unstable query domain", "implementation defect"),
            )
        }
        if (budget.facts.multipleBehaviorClasses) {
            findings += DiagnosticFinding(
                kind = FindingKind.MULTIPLE_HYPOTHESES,
                evidenceIds = usableIds,
                scope = scope,
                sourceHypotheses = listOf("multiple behavior classes fit the public evidence"),
                observationConclusion = "more than one distinguishable candidate remains on the public query domain",
                assumptions = listOf("public query domain is the current discriminating domain"),
                alternativeExplanations = listOf("insufficient distinguishing queries", "noise", "representation mismatch"),
            )
        }
        if (budget.facts.noConsistentCandidate) {
            findings += DiagnosticFinding(
                kind = FindingKind.UNDETERMINED,
                evidenceIds = usableIds,
                scope = scope,
                sourceHypotheses = listOf("no consistent candidate was found in the tested budgets"),
                observationConclusion = "no consistent candidate under these budgets and query domain",
                assumptions = listOf("budgets and query domain are fixed as recorded"),
                alternativeExplanations = listOf(
                    "target outside hypothesis space", "budget too small", "query domain insufficient", "noise or inconsistency",
                ),
            )
        }
        if (findings.none { it.kind == FindingKind.OBSERVED_LABEL_COLLISION } &&
            signal.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE
        ) {
            findings += DiagnosticFinding(
                kind = FindingKind.SUPPORTED_WITHIN_BOUNDED_H,
                evidenceIds = usableIds,
                scope = scope,
                sourceHypotheses = listOf("one behavior class remains within the bounded hypothesis space"),
                observationConclusion = "candidate is identifiable within current bounds and query domain only",
                assumptions = listOf("current IR depth/nodes/candidates and public query domain"),
                alternativeExplanations = listOf("global rule may differ outside the query domain", "unobserved inputs may distinguish candidates"),
            )
        }
        if (findings.isEmpty()) {
            findings += DiagnosticFinding(
                kind = FindingKind.UNDETERMINED,
                evidenceIds = usableIds,
                scope = scope,
                sourceHypotheses = listOf("no conclusive diagnostic signal"),
                observationConclusion = "evidence is insufficient for a bounded identification",
                assumptions = listOf("current observations and policy"),
                alternativeExplanations = listOf("more observations/queries are needed", "current hypothesis space may be inadequate"),
            )
        }

        if (probe != null) {
            val probeEvidence = probe.recordedObservationId?.let { listOf(it) } ?: usableIds
            findings += when {
                probe.executionStatus == ProbeExecutionStatus.EXECUTED && probe.newObservationRecorded -> DiagnosticFinding(
                    kind = FindingKind.PROBE_EXECUTED,
                    evidenceIds = probeEvidence,
                    scope = scope,
                    sourceHypotheses = listOf("A7 selected a legal public action"),
                    observationConclusion = "action=${probe.action}; cost=${probe.cost}; measurement=${probe.measurementKind}",
                    assumptions = listOf("gateway returned a validated public response"),
                    alternativeExplanations = listOf("the new observation may still not distinguish all hypotheses"),
                )
                probe.executionStatus == ProbeExecutionStatus.NO_ACTION -> DiagnosticFinding(
                    kind = FindingKind.NO_ACTION_TAKEN,
                    evidenceIds = probeEvidence,
                    scope = scope,
                    sourceHypotheses = listOf("no action was selected within current policy and budget"),
                    observationConclusion = "NoAction within the current bounded space and public policy",
                    assumptions = listOf("current policy/gateway/budget"),
                    alternativeExplanations = listOf("a valuable query may exist outside the current policy or observation vocabulary"),
                )
                else -> DiagnosticFinding(
                    kind = FindingKind.UNDETERMINED,
                    evidenceIds = probeEvidence,
                    scope = scope,
                    sourceHypotheses = listOf("probe was rejected or did not produce valid evidence"),
                    observationConclusion = "probe status=${probe.executionStatus}",
                    assumptions = listOf("execution gate/response validation"),
                    alternativeExplanations = listOf("stale/forged decision", "invalid gateway response", "no action"),
                )
            }
        }

        val report = DiagnosticReport(
            findings = findings,
            proposedProbe = ProbeRequest.NoAction,
            stoppedBecause = "budget=${budget.verdict}; probe=${probe?.executionStatus ?: "not_run"}",
            strategySource = E3_STRATEGY_SOURCE,
        )
        return IntegratedDiagnosis(report, budget, probe, signal)
    }
}
