// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-AUTH；H01 证明保留，测试改用受控 VerifiedFact/VerifiedRule 签发器。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeductionTest {
    private val evidence = listOf(VerifiedFact.issueForTest("A"))
    private val verifiedRules = listOf(
        VerifiedRule.issueForTest("r-ab", "A", "B"),
        VerifiedRule.issueForTest("r-bc", "B", "C"),
        VerifiedRule.issueForTest("r-cd", "C", "D"),
    )
    private val facts = evidence.map { it.fact }
    private val rules = verifiedRules.map { it.rule }

    @Test
    fun `three step proof is derived and independently checked`() {
        val result = DeductionEngine().derive(facts, rules, "D")
        val success = assertIs<DeductionResult.Success>(result)
        assertEquals(3, success.proof.steps.size)
        assertEquals(listOf("A", "B", "C"), success.proof.steps.map { it.premises.single() })
        assertTrue(ProofChecker(EvidenceVerifier.TestAccept).check(evidence, verifiedRules, success.proof, "D"))
    }

    @Test
    fun `missing premise and disconnected rules fail`() {
        val noA = listOf(Fact.unverified("f-x", "X"))
        assertIs<DeductionResult.Failure>(DeductionEngine().derive(noA, rules, "D"))
        val disconnectedRules = listOf(
            Rule("r-ab", "A", "B"),
            Rule("r-cd", "C", "D"),
        )
        assertIs<DeductionResult.Failure>(DeductionEngine().derive(facts, disconnectedRules, "D"))
    }

    @Test
    fun `unknown or revoked facts cannot be used`() {
        val unknown = listOf(Fact.unverified("f-a", "A"))
        val failure = assertIs<DeductionResult.Failure>(DeductionEngine().derive(unknown, rules, "D"))
        assertEquals("unknown_premise", failure.code)
        val revoked = listOf(Fact.revoked("f-a", "A"))
        assertEquals("revoked_fact", assertIs<DeductionResult.Failure>(DeductionEngine().derive(revoked, rules, "D")).code)
    }

    @Test
    fun `wrong intermediate conclusion fails independent checker`() {
        val bad = Proof(listOf(ProofStep(listOf("A"), "r-ab", "C")), "C")
        assertFalse(ProofChecker(EvidenceVerifier.TestAccept).check(evidence, verifiedRules, bad, "C"))
    }

    @Test
    fun `unverified or caller-declared rule cannot pass checker`() {
        val callerRules = rules.map { if (it.name == "r-bc") it.copy(sourceVerified = false) else it }
        assertIs<DeductionResult.Failure>(DeductionEngine().derive(facts, callerRules, "D"))
        val fake = rules.map { it.copy(sourceVerified = true) }
        val proof = (DeductionEngine().derive(facts, fake, "D") as DeductionResult.Success).proof
        val missingRule = verifiedRules.filterNot { it.rule.name == "r-bc" }
        assertFalse(ProofChecker(EvidenceVerifier.TestAccept).check(evidence, missingRule, proof, "D"))
    }

    @Test
    fun `tampered evidence digest is rejected`() {
        val bad = VerifiedFact.issueForTest("A", inputDigest = "bad-digest")
        assertFalse(ProofChecker(EvidenceVerifier.TestAccept).check(listOf(bad), verifiedRules, Proof(emptyList(), "A"), "A"))
    }
}
