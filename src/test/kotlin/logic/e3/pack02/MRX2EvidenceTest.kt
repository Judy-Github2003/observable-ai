// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02 第一关证据/成本回归；严格区分未验证、Unknown、部分、PASS_ON_CHALLENGE、反例、预算。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MRX2EvidenceTest {
    private val pool = listOf(
        mapOf("a" to "0", "b" to "0", "c" to "0", "d" to "0"),
        mapOf("a" to "1", "b" to "1", "c" to "1", "d" to "1"),
        mapOf("a" to "2", "b" to "2", "c" to "2", "d" to "2"),
    )
    private val target = { m: Map<String, String> -> m["a"] == "1" }

    private fun ledger(budget: Int = 3, controller: StudyQueryController = StudyQueryController(pool, budget, target)) =
        MRX2Ledger(pool, controller, budget, MRX2Cost())

    @Test
    fun `zero checks is no valid prediction not pass`() {
        val validation = ledger().validate("c", { true }, "t", maxNew = 0, meter = MRX2Cost())
        assertEquals(MRX2ValidationStatus.NO_VALID_PREDICTION, validation.status)
        assertFalse(validation.status == MRX2ValidationStatus.PASS_ON_CHALLENGE)
    }

    @Test
    fun `unknown prediction is not pass and pays no oracle`() {
        val cost = MRX2Cost()
        val result = ledger().validate("c", { null }, "t", maxNew = 3, meter = cost)
        assertEquals(MRX2ValidationStatus.UNKNOWN_PREDICTION, result.status)
        assertEquals(0, cost.oracleNewQueries)
    }

    @Test
    fun `pass requires full challenge scope`() {
        val cost = MRX2Cost()
        val l = MRX2Ledger(pool, StudyQueryController(pool, 3, target), 3, cost)
        val partial = l.validate("c", { target(it) }, "t", maxNew = 1, meter = cost)
        assertEquals(MRX2ValidationStatus.PARTIAL_ON_CHALLENGE, partial.status)
        val full = l.validate("c", { target(it) }, "t", maxNew = 3, meter = cost)
        assertEquals(MRX2ValidationStatus.PASS_ON_CHALLENGE, full.status)
        assertEquals("CHALLENGE_POOL(3)", full.scope)
        assertEquals(3, full.checks)
        assertEquals(3, full.covered)
    }

    @Test
    fun `reuse preserves source query index and does not pay again`() {
        val cost = MRX2Cost()
        val l = MRX2Ledger(pool, StudyQueryController(pool, 3, target), 3, cost)
        val entry = assertNotNull(l.query(pool[0], mapOf("c" to true), listOf("c"), "first").second)
        assertEquals(0, entry.queryIndex)
        assertEquals("trusted_oracle", entry.source)
        val spent = cost.oracleNewQueries
        val result = l.validate("c", { true }, "second", maxNew = 0, meter = cost)
        assertEquals(spent, cost.oracleNewQueries)
        assertTrue(result.status != MRX2ValidationStatus.PASS_ON_CHALLENGE)
        assertTrue(l.entries().single().usedBy.contains("second"))
    }

    @Test
    fun `counterexample is distinct from pass`() {
        val result = ledger().validate("c", { false }, "t", maxNew = 3, meter = MRX2Cost())
        assertEquals(MRX2ValidationStatus.COUNTEREXAMPLE, result.status)
        assertTrue(result.counterexamples > 0)
    }

    @Test
    fun `budget exhaustion is distinct`() {
        val cost = MRX2Cost()
        val l = MRX2Ledger(pool, StudyQueryController(pool, 1, target), 1, cost)
        val result = l.validate("c", { target(it) }, "t", maxNew = 3, meter = cost)
        assertEquals(MRX2ValidationStatus.BUDGET_EXHAUSTED, result.status)
        assertTrue(result.covered < pool.size)
    }

    @Test
    fun `controller duplicate is error not pass`() {
        val controller = StudyQueryController(pool, 3, target)
        val frozen = controller.freeze(0, pool[0], mapOf("x" to true), listOf("x"))
        controller.reveal(frozen)
        val l = MRX2Ledger(pool, controller, 3, MRX2Cost())
        val (label, _) = l.query(pool[0], mapOf("c" to true), listOf("c"), "t")
        assertEquals(null, label)
        assertTrue(l.errorCount() > 0)
    }

    @Test
    fun `placeholder predictions are rejected`() {
        val l = ledger()
        assertFailsWith<IllegalArgumentException> {
            l.query(pool[0], emptyMap(), emptyList(), "t")
        }
        assertFailsWith<IllegalArgumentException> {
            l.query(pool[0], mapOf("a" to true), listOf("b"), "t")
        }
    }

    @Test
    fun `cost counters separate new oracle reuse generated and interpreter`() {
        val cost = MRX2Cost()
        val l = MRX2Ledger(pool, StudyQueryController(pool, 3, target), 3, cost)
        l.validate("c", { target(it) }, "t", maxNew = 2, meter = cost)
        cost.generatedCandidates += 100
        assertTrue(cost.oracleNewQueries <= 2)
        assertTrue(cost.oracleReuseUses >= 0)
        assertTrue(cost.interpreterCalls >= 3)
        assertTrue(cost.generatedCandidates == 100)
        assertTrue(cost.oracleNewQueries + cost.oracleReuseUses != cost.generatedCandidates)
    }
}
