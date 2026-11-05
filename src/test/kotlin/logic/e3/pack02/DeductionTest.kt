// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-02 RES-H01 多步演绎与独立检查测试。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeductionTest {
    private val facts = listOf(Fact("f-a", "A"))
    private val rules = listOf(
        Rule("r-ab", "A", "B"),
        Rule("r-bc", "B", "C"),
        Rule("r-cd", "C", "D"),
    )

    @Test
    fun `three step proof is derived and independently checked`() {
        val result = DeductionEngine().derive(facts, rules, "D")
        val success = assertIs<DeductionResult.Success>(result)
        assertEquals(3, success.proof.steps.size)
        assertEquals(listOf("A", "B", "C"), success.proof.steps.map { it.premises.single() })
        assertTrue(ProofChecker().check(facts, rules, success.proof, "D"))
    }

    @Test
    fun `missing premise and disconnected rules fail`() {
        val noA = listOf(Fact("f-x", "X"))
        assertIs<DeductionResult.Failure>(DeductionEngine().derive(noA, rules, "D"))
        val disconnectedRules = listOf(Rule("r-ab", "A", "B"), Rule("r-cd", "C", "D"))
        assertIs<DeductionResult.Failure>(DeductionEngine().derive(facts, disconnectedRules, "D"))
    }

    @Test
    fun `unknown or revoked facts cannot be used`() {
        val unknown = listOf(Fact("f-a", "A", FactStatus.UNKNOWN))
        val failure = assertIs<DeductionResult.Failure>(DeductionEngine().derive(unknown, rules, "D"))
        assertEquals("unknown_premise", failure.code)
        val revoked = listOf(Fact("f-a", "A", FactStatus.REVOKED))
        assertEquals("revoked_fact", assertIs<DeductionResult.Failure>(DeductionEngine().derive(revoked, rules, "D")).code)
    }

    @Test
    fun `wrong intermediate conclusion fails independent checker`() {
        val bad = Proof(listOf(
            ProofStep(listOf("A"), "r-ab", "C"),
        ), "C")
        assertFalse(ProofChecker().check(facts, rules, bad, "C"))
    }

    @Test
    fun `unverified rule cannot be used`() {
        val sourceRules = rules.map { if (it.name == "r-bc") it.copy(sourceVerified = false) else it }
        assertIs<DeductionResult.Failure>(DeductionEngine().derive(facts, sourceRules, "D"))
    }
}
