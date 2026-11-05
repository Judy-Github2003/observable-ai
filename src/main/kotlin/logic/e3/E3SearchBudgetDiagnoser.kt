// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A6 搜索空间覆盖与预算诊断；只比较真实 B0 搜索轨迹，不读取评测端 truth/family。
package logic.e3

import logic.capability.*

enum class CandidateEvidenceState { NOT_RUN, RUN_WITH_ZERO_SURVIVORS, SEARCH_TRUNCATED, RUN_WITH_SURVIVORS }

enum class BudgetVerdict {
    INVALID_INPUT,
    NO_COMPLETE_OBSERVATIONS,
    OBSERVATION_COLLISION,
    STABLE_IDENTIFIED_BOUNDED,
    BUDGET_SENSITIVE_CANDIDATE_SET,
    MULTIPLE_CANDIDATES_REMAIN,
    NO_CONSISTENT_CANDIDATE,
    SEARCH_TRUNCATED_UNDETERMINED,
    UNDETERMINED,
}

data class BehaviorClass(
    val behaviorSignature: String,
    val candidateHashes: List<String>,
)

data class CandidateLayers(
    val composerGenerated: Int,
    val consistentWithEvidence: Int,
    val behaviorClasses: Int,
    val filterRepresentatives: Int,
)

data class BudgetFacts(
    val budgetSensitive: Boolean,
    val multipleBehaviorClasses: Boolean,
    val noConsistentCandidate: Boolean,
    val searchTruncated: Boolean,
    val reasonUndetermined: Boolean,
    val observationCollision: Boolean,
)

data class BudgetRun(
    val budget: DiagnosticBudget,
    val signal: DiagnosticSignal,
    val behaviorClasses: List<BehaviorClass>,
    val behaviorEvalMaxSteps: Int,
    val layers: CandidateLayers,
    val candidateEvidenceState: CandidateEvidenceState,
)

data class BudgetDiagnosis(
    val verdict: BudgetVerdict,
    val runs: List<BudgetRun>,
    val budgetChanges: List<String>,
    val scope: EvidenceScope?,
    val notes: List<String>,
    val facts: BudgetFacts,
)

/**
 * 对同一公开证据跑低预算与有限提高预算：
 * - 候选集合来自真实 Composer；
 * - 行为等价类按候选在公开 queryDomain 上的实际输出签名分组；
 * - 不把“高预算找到新候选”当成真实规则，也不把“高预算仍失败”当成表示不足。
 */
class E3SearchBudgetDiagnoser {

    fun diagnose(
        schema: VisibleSchema,
        observations: List<Observation>,
        constants: PublicConstants,
        queryDomain: List<Map<String, String>>,
        low: DiagnosticBudget,
        high: DiagnosticBudget,
        meter: E3UsageMeter? = null,
    ): BudgetDiagnosis {
        val adapter = E3B0DiagnosticAdapter()
        val runs = listOf(low, high).map { budget ->
            val signal = adapter.diagnose(schema, observations, constants, queryDomain, budget, meter)
            val classes = behaviorClasses(schema, signal.consistentCandidateBodies, queryDomain, observations, budget.maxSteps, meter)
            BudgetRun(
                budget = budget,
                signal = signal,
                behaviorClasses = classes,
                behaviorEvalMaxSteps = budget.maxSteps,
                layers = CandidateLayers(
                    composerGenerated = signal.composedCandidateHashes.size,
                    consistentWithEvidence = signal.consistentCandidateHashes.size,
                    behaviorClasses = classes.size,
                    filterRepresentatives = signal.filter?.survivorCount ?: 0,
                ),
                candidateEvidenceState = evidenceState(signal),
            )
        }
        val changes = listOf(
            "rounds:${low.maxComposeRounds}->${high.maxComposeRounds}",
            "nodes:${low.maxNodes}->${high.maxNodes}",
            "candidates:${low.maxCandidates}->${high.maxCandidates}",
            "steps:${low.maxSteps}->${high.maxSteps}",
        )
        val notes = ArrayList<String>()
        notes += "high_budget_new_candidates_do_not_prove_the_true_world_rule"
        notes += "high_budget_failure_does_not_prove_representation_insufficiency"
        notes += "compose_rounds_are_not_ast_depth"

        val bad = runs.map { it.signal.status }
        val facts = BudgetFacts(
            budgetSensitive = runs.map { it.signal.composedCandidateHashes.toSet() }.distinct().size > 1 ||
                runs.map { it.signal.consistentCandidateHashes.toSet() }.distinct().size > 1,
            multipleBehaviorClasses = runs.any { it.behaviorClasses.size > 1 },
            noConsistentCandidate = runs.all { it.candidateEvidenceState == CandidateEvidenceState.RUN_WITH_ZERO_SURVIVORS },
            searchTruncated = runs.any {
                it.signal.search?.reason == "depth_limit" || it.signal.search?.reason == "candidate_limit"
            },
            reasonUndetermined = runs.any { it.signal.search?.reason == null },
            observationCollision = DiagnosticSignalStatus.OBSERVATION_COLLISION in bad,
        )
        val verdict = classifyVerdict(runs, facts)
        if (verdict == BudgetVerdict.NO_CONSISTENT_CANDIDATE) {
            notes += "no_consistent_candidate_within_these_budgets_and_query_domain; not proof of representation insufficiency"
        }
        if (verdict == BudgetVerdict.BUDGET_SENSITIVE_CANDIDATE_SET) {
            notes += "candidate_set_changed_with_budget; this is budget sensitivity, not truth identification"
        }
        if (verdict == BudgetVerdict.STABLE_IDENTIFIED_BOUNDED) {
            notes += "stable_only_within_current_hypothesis_space_and_query_domain"
        }
        return BudgetDiagnosis(
            verdict = verdict,
            runs = runs,
            budgetChanges = changes,
            scope = runs.mapNotNull { it.signal.boundedScope }.firstOrNull(),
            notes = notes,
            facts = facts,
        )
    }

    private fun evidenceState(signal: DiagnosticSignal): CandidateEvidenceState = when {
        signal.status == DiagnosticSignalStatus.INVALID_INPUT ||
            signal.status == DiagnosticSignalStatus.NO_COMPLETE_OBSERVATIONS ||
            signal.status == DiagnosticSignalStatus.OBSERVATION_COLLISION -> CandidateEvidenceState.NOT_RUN
        signal.consistentCandidateHashes.isNotEmpty() -> CandidateEvidenceState.RUN_WITH_SURVIVORS
        signal.search?.reason == "depth_limit" || signal.search?.reason == "candidate_limit" -> CandidateEvidenceState.SEARCH_TRUNCATED
        else -> CandidateEvidenceState.RUN_WITH_ZERO_SURVIVORS
    }

    /** 独立可测的主状态分类；保留 facts 作为同时成立的事实。 */
    internal fun classifyVerdict(runs: List<BudgetRun>, facts: BudgetFacts): BudgetVerdict {
        val bad = runs.map { it.signal.status }
        return when {
            facts.observationCollision -> BudgetVerdict.OBSERVATION_COLLISION
            DiagnosticSignalStatus.INVALID_INPUT in bad -> BudgetVerdict.INVALID_INPUT
            DiagnosticSignalStatus.NO_COMPLETE_OBSERVATIONS in bad -> BudgetVerdict.NO_COMPLETE_OBSERVATIONS
            runs.all {
                it.candidateEvidenceState == CandidateEvidenceState.SEARCH_TRUNCATED &&
                    it.signal.consistentCandidateHashes.isEmpty()
            } -> BudgetVerdict.SEARCH_TRUNCATED_UNDETERMINED
            runs.all {
                it.signal.filter?.reason == "no_consistent_candidate" &&
                    it.candidateEvidenceState == CandidateEvidenceState.RUN_WITH_ZERO_SURVIVORS
            } -> BudgetVerdict.NO_CONSISTENT_CANDIDATE
            runs.all { it.signal.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE } && facts.multipleBehaviorClasses ->
                BudgetVerdict.MULTIPLE_CANDIDATES_REMAIN
            facts.budgetSensitive -> BudgetVerdict.BUDGET_SENSITIVE_CANDIDATE_SET
            facts.searchTruncated && runs.any { it.signal.status == DiagnosticSignalStatus.REFUSED || it.signal.status == DiagnosticSignalStatus.NO_CANDIDATE } ->
                BudgetVerdict.SEARCH_TRUNCATED_UNDETERMINED
            facts.multipleBehaviorClasses -> BudgetVerdict.MULTIPLE_CANDIDATES_REMAIN
            runs.any { it.signal.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE } ->
                BudgetVerdict.MULTIPLE_CANDIDATES_REMAIN
            runs.all {
                it.signal.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE &&
                    it.signal.survivorHashes.size == 1
            } && runs.map { it.signal.survivorHashes }.distinct().size == 1 ->
                BudgetVerdict.STABLE_IDENTIFIED_BOUNDED
            runs.all { it.signal.consistentCandidateHashes.isEmpty() } -> BudgetVerdict.NO_CONSISTENT_CANDIDATE
            else -> BudgetVerdict.UNDETERMINED
        }
    }

    private fun behaviorClasses(
        schema: VisibleSchema,
        bodies: List<Expr>,
        queryDomain: List<Map<String, String>>,
        observations: List<Observation>,
        maxSteps: Int,
        meter: E3UsageMeter?,
    ): List<BehaviorClass> {
        val points = if (queryDomain.isNotEmpty()) {
            queryDomain
        } else {
            observations.mapNotNull { obs ->
                if (obs.label == null || obs.visible.values.any { it is ObservedValue.Unknown }) null
                else obs.visible.mapValues { (_, v) -> (v as ObservedValue.Known).value }
            }
        }
        if (points.isEmpty()) return emptyList()
        meter?.recordInterpreter(bodies.size * points.size)
        val type = CapType.Record(schema.fieldNames().associateWith { CapType.Str })
        val inputs = mapOf("x" to type)
        val interpreter = CapabilityInterpreter(InMemoryResolver(emptyList()))
        val bySignature = LinkedHashMap<String, MutableList<String>>()
        for (body in bodies) {
            val definition = CapabilityDefinition("a6.behavior", 1, inputs, CapType.Bool3, body)
            val signature = points.joinToString("|") { point ->
                val args = mapOf("x" to EvalResult.Known(CapValue.Rec(
                    schema.fieldNames().associateWith { CapValue.Str(point.getValue(it)) },
                )))
                when (val result = interpreter.execute(definition, args, EvalBudget(maxSteps = maxSteps))) {
                    is EvalResult.Known -> "K:" + (result.value as CapValue.Bool).value
                    is EvalResult.Unknown -> "U:" + result.reason
                    is EvalResult.Error -> "E:" + result.code
                }
            }
            bySignature.getOrPut(signature) { ArrayList() } += bodyHash(body)
        }
        return bySignature.map { (signature, hashes) -> BehaviorClass(signature, hashes.sorted()) }
    }

    private fun bodyHash(body: Expr): String = CanonicalJson.astHash(body)
}
