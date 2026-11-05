// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A6 搜索空间覆盖与预算诊断；只比较真实 B0 搜索轨迹，不读取评测端 truth/family。
package logic.e3

import logic.capability.*

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

data class BudgetRun(
    val budget: DiagnosticBudget,
    val signal: DiagnosticSignal,
    val behaviorClasses: List<BehaviorClass>,
)

data class BudgetDiagnosis(
    val verdict: BudgetVerdict,
    val runs: List<BudgetRun>,
    val budgetChanges: List<String>,
    val scope: EvidenceScope?,
    val notes: List<String>,
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
    ): BudgetDiagnosis {
        val adapter = E3B0DiagnosticAdapter()
        val runs = listOf(low, high).map { budget ->
            val signal = adapter.diagnose(schema, observations, constants, queryDomain, budget)
            val classes = behaviorClasses(schema, signal.survivorBodies, queryDomain, observations)
            BudgetRun(budget, signal, classes)
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
        val verdict = when {
            DiagnosticSignalStatus.INVALID_INPUT in bad -> BudgetVerdict.INVALID_INPUT
            DiagnosticSignalStatus.NO_COMPLETE_OBSERVATIONS in bad -> BudgetVerdict.NO_COMPLETE_OBSERVATIONS
            DiagnosticSignalStatus.OBSERVATION_COLLISION in bad -> BudgetVerdict.OBSERVATION_COLLISION
            runs.any { it.signal.search?.reason == "candidate_limit" } &&
                runs.any { it.signal.status == DiagnosticSignalStatus.REFUSED || it.signal.status == DiagnosticSignalStatus.NO_CANDIDATE } ->
                BudgetVerdict.SEARCH_TRUNCATED_UNDETERMINED
            (runs.any { it.signal.survivorHashes.isEmpty() } && runs.any { it.signal.survivorHashes.isNotEmpty() }) ->
                BudgetVerdict.BUDGET_SENSITIVE_CANDIDATE_SET
            runs.any { it.behaviorClasses.size > 1 } -> BudgetVerdict.MULTIPLE_CANDIDATES_REMAIN
            runs.any { it.signal.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE } ->
                BudgetVerdict.MULTIPLE_CANDIDATES_REMAIN
            runs.map { it.signal.survivorHashes.toSet() }.distinct().size > 1 ->
                BudgetVerdict.BUDGET_SENSITIVE_CANDIDATE_SET
            runs.all {
                it.signal.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE &&
                    it.signal.survivorHashes.size == 1
            } && runs.map { it.signal.survivorHashes }.distinct().size == 1 ->
                BudgetVerdict.STABLE_IDENTIFIED_BOUNDED
            runs.all { it.signal.survivorHashes.isEmpty() } &&
                runs.any { it.signal.search?.reason == "depth_limit" || it.signal.search?.reason == null } ->
                BudgetVerdict.SEARCH_TRUNCATED_UNDETERMINED
            runs.all { it.signal.survivorHashes.isEmpty() } -> BudgetVerdict.NO_CONSISTENT_CANDIDATE
            else -> BudgetVerdict.UNDETERMINED
        }
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
        )
    }

    private fun behaviorClasses(
        schema: VisibleSchema,
        bodies: List<Expr>,
        queryDomain: List<Map<String, String>>,
        observations: List<Observation>,
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
                when (val result = interpreter.execute(definition, args)) {
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
