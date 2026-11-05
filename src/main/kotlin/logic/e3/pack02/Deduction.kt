// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-02 RES-H01 最小受限演绎；事实/规则/证明步骤可独立核验。
package logic.e3.pack02

enum class FactStatus { VERIFIED, UNKNOWN, REVOKED }
data class Fact(val id: String, val proposition: String, val status: FactStatus = FactStatus.VERIFIED, val source: String = "public")
data class Rule(val name: String, val premise: String, val conclusion: String, val sourceVerified: Boolean = true)
data class ProofStep(
    val premises: List<String>,
    val rule: String,
    val conclusion: String,
)
data class Proof(val steps: List<ProofStep>, val conclusion: String)
sealed interface DeductionResult {
    data class Success(val proof: Proof) : DeductionResult
    data class Failure(val code: String, val detail: String? = null) : DeductionResult
}

/**
 * 通用前向演绎：只选择前提已成立且规则来源有效的规则，不硬编码具体 A/B/C/D 链。
 * 所有中间结论来自规则，不允许调用者直接注入结论。
 */
class DeductionEngine {
    fun derive(facts: List<Fact>, rules: List<Rule>, goal: String): DeductionResult {
        val known = linkedMapOf<String, Fact>()
        for (fact in facts) {
            when (fact.status) {
                FactStatus.VERIFIED -> known[fact.proposition] = fact
                FactStatus.UNKNOWN -> Unit
                FactStatus.REVOKED -> return DeductionResult.Failure("revoked_fact", fact.proposition)
            }
        }
        if (goal in known) return DeductionResult.Success(Proof(emptyList(), goal))
        val steps = ArrayList<ProofStep>()
        var changed = true
        while (changed) {
            changed = false
            for (rule in rules) {
                if (!rule.sourceVerified) continue
                if (rule.premise !in known || rule.conclusion in known) continue
                steps += ProofStep(listOf(rule.premise), rule.name, rule.conclusion)
                known[rule.conclusion] = Fact("derived-${rule.conclusion}", rule.conclusion, FactStatus.VERIFIED, "rule:${rule.name}")
                changed = true
                if (rule.conclusion == goal) {
                    return DeductionResult.Success(Proof(steps.toList(), goal))
                }
            }
        }
        return DeductionResult.Failure(
            when {
                facts.any { it.status == FactStatus.UNKNOWN } -> "unknown_premise"
                else -> "goal_unreachable"
            },
            goal,
        )
    }
}

/** 独立检查器：只接受初始事实 + 规则 + 证明步骤，不信任引擎给出的结论。 */
class ProofChecker {
    fun check(facts: List<Fact>, rules: List<Rule>, proof: Proof, goal: String): Boolean {
        val known = facts.filter { it.status == FactStatus.VERIFIED }.map { it.proposition }.toMutableSet()
        if (facts.any { it.status == FactStatus.UNKNOWN || it.status == FactStatus.REVOKED }) return false
        for (step in proof.steps) {
            if (step.premises.isEmpty() || step.premises.any { it !in known }) return false
            val rule = rules.firstOrNull { it.name == step.rule } ?: return false
            if (!rule.sourceVerified || rule.premise !in step.premises || rule.conclusion != step.conclusion) return false
            if (step.conclusion in known) return false
            known += step.conclusion
        }
        return proof.conclusion == goal && goal in known
    }
}
