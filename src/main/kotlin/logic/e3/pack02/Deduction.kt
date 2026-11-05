// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-AUTH 认证链；Fact 不能由普通调用者伪造，ProofChecker 只接受 verifier 证据。
package logic.e3.pack02

import logic.capability.sha256

enum class FactStatus { VERIFIED, UNKNOWN, REVOKED }

/** 普通事实容器：构造器私有；未认证事实只能通过工厂创建，VERIFIED 只能由可信适配器/验证器创建。 */
class Fact private constructor(
    val id: String,
    val proposition: String,
    val status: FactStatus,
    val source: String,
    val capabilityRef: String?,
    val inputDigest: String?,
) {
    companion object {
        fun unverified(id: String, proposition: String, source: String = "public"): Fact =
            Fact(id, proposition, FactStatus.UNKNOWN, source, null, null)

        fun revoked(id: String, proposition: String, source: String = "public"): Fact =
            Fact(id, proposition, FactStatus.REVOKED, source, null, null)

        internal fun verifiedForTest(
            id: String,
            proposition: String,
            source: String,
            capabilityRef: String? = null,
            inputDigest: String? = null,
        ): Fact = Fact(id, proposition, FactStatus.VERIFIED, source, capabilityRef, inputDigest)

        internal fun derived(proposition: String, ruleName: String): Fact =
            Fact("derived-$proposition", proposition, FactStatus.VERIFIED, "rule:$ruleName", null, null)
    }
}

data class Rule(val name: String, val premise: String, val conclusion: String, val sourceVerified: Boolean = true)

/** 已认证规则：只能由可信规则注册表/测试证据签发器创建；普通 Rule 不能进入 ProofChecker。 */
class VerifiedRule private constructor(val rule: Rule) {
    companion object {
        internal fun issueForTest(name: String, premise: String, conclusion: String): VerifiedRule =
            VerifiedRule(Rule(name, premise, conclusion, sourceVerified = true))
    }
}

/**
 * 已认证执行证据：绑定 capability ref、输入、输入摘要与命题。
 * 公开构造器不存在；fromExecution/受控测试签发器之外不能构造。
 */
class VerifiedFact private constructor(
    val fact: Fact,
    val ref: KnowledgeRef,
    val capabilityRef: String,
    val inputDigest: String,
    val input: Map<String, String>,
    val executionId: String,
) {
    fun isValid(): Boolean {
        if (fact.status != FactStatus.VERIFIED) return false
        if (fact.capabilityRef != capabilityRef || fact.inputDigest != inputDigest) return false
        if (inputDigest != digest(input)) return false
        if (fact.proposition.isBlank() || capabilityRef.isBlank() || executionId.isBlank()) return false
        if (ref.id.isBlank() || ref.contentHash.isBlank()) return false
        return true
    }

    companion object {
        internal fun issue(
            fact: Fact,
            ref: KnowledgeRef,
            capabilityRef: String,
            inputDigest: String,
            input: Map<String, String>,
            executionId: String,
        ): VerifiedFact = VerifiedFact(fact, ref, capabilityRef, inputDigest, input, executionId)

        internal fun issueForTest(
            proposition: String,
            capabilityRef: String = "test:capability",
            input: Map<String, String> = emptyMap(),
            inputDigest: String = digest(input),
        ): VerifiedFact {
            val fact = Fact.verifiedForTest(
                "fact-$proposition", proposition, "test", capabilityRef, inputDigest,
            )
            return VerifiedFact(
                fact, KnowledgeRef("test", 1, "test"), capabilityRef, inputDigest, input, "test-execution",
            )
        }

        internal fun digest(input: Map<String, String>): String =
            sha256(input.toSortedMap().entries.joinToString("|") { it.key + "=" + it.value })
    }
}

/** 证据验证器：ProofChecker 不信任 VerifiedFact 的构造来源，必须显式选择验证策略。 */
interface EvidenceVerifier {
    fun verify(evidence: VerifiedFact): Boolean

    object RejectAll : EvidenceVerifier {
        override fun verify(evidence: VerifiedFact): Boolean = false
    }

    /** 仅限测试/受控测试签发器；生产路径应使用 ActiveEvidenceVerifier。 */
    object TestAccept : EvidenceVerifier {
        override fun verify(evidence: VerifiedFact): Boolean = evidence.isValid()
    }
}

/** 生产验证：执行证据的 ref 必须仍在当前 active 快照中，撤销后旧凭据失效。 */
class ActiveEvidenceVerifier(private val dbPath: java.nio.file.Path) : EvidenceVerifier {
    override fun verify(evidence: VerifiedFact): Boolean {
        if (!evidence.isValid()) return false
        return Pack02Knowledge.resolveActive(dbPath, listOf(evidence.ref))?.size == 1
    }
}

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
 * 这里只产生候选证明轨迹；最终是否可信由 ProofChecker + VerifiedFact/VerifiedRule 决定。
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
                known[rule.conclusion] = Fact.derived(rule.conclusion, rule.name)
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

/** 独立检查器：只接受已认证事实、已认证规则和证明步骤；普通 Fact/Rule 会被类型系统拒绝。 */
class ProofChecker(private val verifier: EvidenceVerifier = EvidenceVerifier.RejectAll) {
    fun check(facts: List<VerifiedFact>, rules: List<VerifiedRule>, proof: Proof, goal: String): Boolean {
        if (facts.any { !verifier.verify(it) }) return false
        val known = facts.map { it.fact.proposition }.toMutableSet()
        for (step in proof.steps) {
            if (step.premises.isEmpty() || step.premises.any { it !in known }) return false
            val rule = rules.firstOrNull { it.rule.name == step.rule } ?: return false
            if (rule.rule.premise !in step.premises || rule.rule.conclusion != step.conclusion) return false
            if (step.conclusion in known) return false
            known += step.conclusion
        }
        return proof.conclusion == goal && goal in known
    }
}
