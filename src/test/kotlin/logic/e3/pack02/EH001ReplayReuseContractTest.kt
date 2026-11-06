package logic.e3.pack02

import logic.capability.CapValue
import logic.capability.Expr
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EH-001（工程测试）：严格分开「复用已有 Oracle 标签条目」与「淘汰候选」计数。
 *
 * 这是供 DeepSeek 复制到 src/test/kotlin/logic/e3/pack02/ 的新测试，
 * 预期在 MRX-02D 原实现上出现 RED；不使用 HELDOUT 或考试目标泄漏。
 */
class EH001ReplayReuseContractTest {
    private val pool = listOf(
        mapOf("a" to "0", "b" to "0", "c" to "0", "d" to "0"),
        mapOf("a" to "1", "b" to "1", "c" to "1", "d" to "1"),
    )

    private fun candidate(id: String): StudyCandidate = StudyCandidate(
        Expr.Const(CapValue.Bool(true)), id, 1, 1, emptyList(), 0, 0,
    )

    private data class Fixture(val cost: MRX2Cost, val ledger: MRX2Ledger)

    private fun fixture(): Fixture {
        val cost = MRX2Cost()
        val ledger = MRX2Ledger(
            pool, StudyQueryController(pool, 2, { true }), 2, cost,
        )
        return Fixture(cost, ledger)
    }

    private fun seed(fixture: Fixture, count: Int) {
        for (i in 0 until count) {
            val (label, entry) = fixture.ledger.query(
                pool[i], mapOf("keep" to true), listOf("keep"), "SEED",
            )
            assertEquals(true, label)
            check(entry != null)
        }
    }

    @Test
    fun `one confirming paid label is still one reused label`() {
        val f = fixture()
        seed(f, 1)
        val beforePaid = f.ledger.newQueries()
        val identifier = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { _, _ -> true }, f.cost,
        )
        val result = identifier.identify(listOf(candidate("keep")))
        assertEquals(1, result.reusedLabels, "one previously purchased label must count even without pruning")
        assertEquals(0, result.replayEliminated)
        assertEquals(0, result.newQueries)
        assertEquals(beforePaid, f.ledger.newQueries())
    }

    @Test
    fun `two confirming paid labels count two reused labels not zero`() {
        val f = fixture()
        seed(f, 2)
        val beforePaid = f.ledger.newQueries()
        val identifier = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { _, _ -> true }, f.cost,
        )
        val result = identifier.identify(listOf(candidate("keep")))
        assertEquals(2, result.reusedLabels)
        assertEquals(0, result.replayEliminated)
        assertEquals(0, result.newQueries)
        assertEquals(beforePaid, f.ledger.newQueries())
    }

    @Test
    fun `one paid label can eliminate two candidates without counting two reuses`() {
        val f = fixture()
        seed(f, 1)
        val beforePaid = f.ledger.newQueries()
        val identifier = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { candidate, _ -> candidate.canonical == "keep" }, f.cost,
        )
        val result = identifier.identify(
            listOf(candidate("keep"), candidate("wrong-a"), candidate("wrong-b")),
        )
        assertEquals(1, result.reusedLabels, "one entry reused, irrespective of eliminated candidate count")
        assertEquals(2, result.replayEliminated)
        assertEquals(0, result.newQueries)
        assertEquals(beforePaid, f.ledger.newQueries())
    }
}
