// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02D P0 证据回放/快照/Unknown/ERROR 的回归测试。
package logic.e3.pack02

import logic.capability.CapValue
import logic.capability.Expr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRX2DEvidenceReplayTest {
    private val pool = listOf(
        mapOf("a" to "0", "b" to "0", "c" to "0", "d" to "0"),
        mapOf("a" to "1", "b" to "1", "c" to "1", "d" to "1"),
    )
    private fun candidate(id: String) = StudyCandidate(
        Expr.Const(CapValue.Bool(true)), id, 1, 1, emptyList(), 0, 0,
    )

    @Test
    fun `paid labels are replayed before new queries`() {
        val cost = MRX2Cost()
        val controller = StudyQueryController(pool, 2, { true })
        val ledger = MRX2Ledger(pool, controller, 2, cost)
        ledger.query(pool[0], mapOf("true" to true), listOf("true"), "seed")
        val channel = MRX2DChannel("QUERY", pool, ledger, 2)
        val id = MRX2DActiveIdentifier(listOf(channel), { c, _ -> c.canonical == "true" }, cost)
        val result = id.identify(listOf(candidate("true"), candidate("false")))
        println("replay status=${result.status} replayElim=${result.replayEliminated} new=${result.newQueries}")
        assertEquals(1, result.replayEliminated)
        assertEquals(0, result.newQueries)
        assertEquals("ONE_SURVIVOR_WITH_EVIDENCE", result.status)
    }

    @Test
    fun `same behavior never becomes pass`() {
        val cost = MRX2Cost()
        val channel = MRX2DChannel("QUERY", pool, MRX2Ledger(pool, StudyQueryController(pool, 2, { true }), 2, cost), 2)
        val id = MRX2DActiveIdentifier(listOf(channel), { _, _ -> true }, cost)
        val result = id.identify(listOf(candidate("a"), candidate("b")))
        assertEquals("SINGLE_BEHAVIOR_CLASS_PROVISIONAL", result.status)
        assertTrue(result.status != "PASS_ON_CHALLENGE")
    }

    @Test
    fun `unknown blocks split and is not false`() {
        val cost = MRX2Cost()
        val channel = MRX2DChannel("QUERY", pool, MRX2Ledger(pool, StudyQueryController(pool, 2, { true }), 2, cost), 2)
        val id = MRX2DActiveIdentifier(listOf(channel), { c, _ ->
            if (c.canonical == "unknown") null else true
        }, cost)
        val result = id.identify(listOf(candidate("known"), candidate("unknown")))
        assertEquals("UNKNOWN_BLOCKED", result.status)
        assertTrue(result.selected == null)
    }

    @Test
    fun `controller error is not budget exhaustion`() {
        val cost = MRX2Cost()
        val controller = StudyQueryController(pool, 2, { true })
        val frozen = controller.freeze(0, pool[0], mapOf("x" to true), listOf("x"))
        controller.reveal(frozen)
        val ledger = MRX2Ledger(pool, controller, 2, cost)
        val channel = MRX2DChannel("QUERY", pool, ledger, 2)
        val id = MRX2DActiveIdentifier(listOf(channel), { c, input ->
            c.canonical == "a" && input == pool[0] || c.canonical == "b" && input == pool[1]
        }, cost)
        val result = id.identify(listOf(candidate("a"), candidate("b")))
        println("error status=${result.status} note=${result.note}")
        assertEquals("ERROR", result.status)
    }
}
