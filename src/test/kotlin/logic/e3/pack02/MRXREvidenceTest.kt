// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 证据账本回归；覆盖 0 检查、复用、部分、反例、预算、重复异常。
package logic.e3.pack02

import logic.capability.CapValue
import logic.capability.Expr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MRXREvidenceTest {
    private val pool = listOf(
        mapOf("a" to "0", "b" to "0", "c" to "0", "d" to "0"),
        mapOf("a" to "1", "b" to "1", "c" to "1", "d" to "1"),
        mapOf("a" to "2", "b" to "2", "c" to "2", "d" to "2"),
    )
    private val target = { m: Map<String, String> -> m["a"] == "1" }

    @Test
    fun `zero checks is unverified not pass`() {
        val controller = StudyQueryController(pool, 3, target)
        val ledger = MRXRChallengeLedger(pool, controller, 3)
        val result = ledger.validate("c", { true }, "t", maxNew = 0)
        assertEquals(MRXREvidenceStatus.UNVERIFIED, result.status)
        assertEquals(0, result.checks)
        assertFalse(result.isPass)
    }

    @Test
    fun `labels are reused without paying twice`() {
        val controller = StudyQueryController(pool, 3, target)
        val ledger = MRXRChallengeLedger(pool, controller, 3)
        val first = ledger.query(pool[0])
        assertTrue(first.first != null)
        val spent = ledger.queriesSpent()
        val second = ledger.query(pool[0])
        assertEquals(first.first, second.first)
        assertEquals(spent, ledger.queriesSpent())
        assertEquals(1, ledger.labelsAvailable())
    }

    @Test
    fun `partial is not pass and all labels become pass`() {
        val controller = StudyQueryController(pool, 3, target)
        val ledger = MRXRChallengeLedger(pool, controller, 3)
        val first = ledger.validate("c", { target(it) }, "t", maxNew = 1)
        assertEquals(MRXREvidenceStatus.PARTIAL, first.status)
        val second = ledger.validate("c", { target(it) }, "t", maxNew = 2)
        assertEquals(MRXREvidenceStatus.PASS, second.status)
        assertEquals(3, second.checks)
    }

    @Test
    fun `counterexample is distinct from pass`() {
        val controller = StudyQueryController(pool, 3, target)
        val ledger = MRXRChallengeLedger(pool, controller, 3)
        val result = ledger.validate("c", { false }, "t", maxNew = 3)
        assertEquals(MRXREvidenceStatus.COUNTEREXAMPLE, result.status)
        assertTrue(result.counterexamples > 0)
        assertFalse(result.isPass)
    }

    @Test
    fun `duplicate controller exception is error not pass`() {
        val controller = StudyQueryController(pool, 3, target)
        val frozen = controller.freeze(0, pool[0], mapOf("x" to true), listOf("x"))
        controller.reveal(frozen)
        val ledger = MRXRChallengeLedger(pool, controller, 3)
        val (label, status) = ledger.query(pool[0])
        assertEquals(null, label)
        assertEquals(MRXREvidenceStatus.ERROR, status)
        assertTrue(ledger.duplicateErrorCount() > 0)
    }

    @Test
    fun `budget exhaustion is a distinct state`() {
        val controller = StudyQueryController(pool, 1, target)
        val ledger = MRXRChallengeLedger(pool, controller, 1)
        ledger.query(pool[0])
        val (label, status) = ledger.query(pool[1])
        assertEquals(null, label)
        assertEquals(MRXREvidenceStatus.BUDGET_EXHAUSTED, status)
    }

    @Test
    fun `filter uses existing labels and does not include heldout`() {
        val heldout = mapOf("a" to "1", "b" to "2", "c" to "1", "d" to "2")
        val controller = StudyQueryController(pool, 3, target)
        val ledger = MRXRChallengeLedger(pool, controller, 3)
        ledger.query(pool[1])
        val candidates = listOf(
            StudyCandidate(Expr.Const(CapValue.Bool(true)), "true", 1, 1, emptyList(), 0, 0),
            StudyCandidate(Expr.Const(CapValue.Bool(false)), "false", 1, 1, emptyList(), 0, 0),
        )
        val (filtered, validation) = ledger.filter(
            candidates, { candidate, _ -> candidate.canonical == "true" }, "trial", maxNew = 0,
        )
        assertTrue(filtered.isNotEmpty())
        assertTrue(validation.checks > 0)
        assertFalse(MRXWorlds.splitFor(MRXWorlds.formal[0], 7101).heldout.any { C8Domain.key(it) in ledger.allEntries().map { e -> e.inputKey } })
    }
}
