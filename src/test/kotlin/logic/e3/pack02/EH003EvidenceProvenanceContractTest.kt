package logic.e3.pack02

import logic.capability.CapValue
import logic.capability.Expr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * EH-003：先复制到项目测试目录，旧源码应能编译且行为断言失败（RED）。
 * 所有测试调用真实 MRX2Ledger + StudyQueryController + MRX2DActiveIdentifier。
 * 不触及 HELDOUT、考官真值和冻结科研世界。
 */
class EH003EvidenceProvenanceContractTest {
    private val pool = listOf(
        mapOf("a" to "0", "b" to "0", "c" to "0", "d" to "0"),
        mapOf("a" to "1", "b" to "1", "c" to "1", "d" to "1"),
    )

    private fun candidate(id: String) = StudyCandidate(
        Expr.Const(CapValue.Bool(true)), id, 1, 1, emptyList(), 0, 0,
    )

    private data class Fixture(val ledger: MRX2Ledger, val cost: MRX2Cost)

    private fun fixture(target: (Map<String, String>) -> Boolean = { true }): Fixture {
        val cost = MRX2Cost()
        return Fixture(MRX2Ledger(pool, StudyQueryController(pool, 2, target), 2, cost), cost)
    }

    private fun seed(f: Fixture, index: Int, label: Boolean) {
        val (actual, entry) = f.ledger.query(
            pool[index], mapOf("seed" to label), listOf("seed"), "PREPAID",
        )
        assertEquals(label, actual)
        assertNotNull(entry)
    }

    @Test
    fun `prepaid confirming label with zero prune must be recorded and recognize own evidence`() {
        val f = fixture()
        seed(f, 0, true)
        val paid = f.ledger.newQueries()
        val result = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { _, _ -> true }, f.cost,
        ).identify(listOf(candidate("survivor")))

        assertEquals(1, result.reusedLabels)
        assertEquals(0, result.replayEliminated)
        assertEquals(0, result.newQueries)
        assertEquals(paid, f.ledger.newQueries())
        assertEquals("ONE_SURVIVOR_WITH_EVIDENCE", result.status)
        assertEquals(1, result.observations.size, "zero prune is still an observed label")
        val o = result.observations.single()
        assertTrue(o.replayed)
        assertEquals("QUERY", o.channel)
        assertEquals(C8Domain.key(pool[0]), o.inputKey)
        assertEquals(true, o.label)
        assertEquals(0, o.eliminated)
    }

    @Test
    fun `unknown on prepaid label is replayed but does not confirm candidate`() {
        val f = fixture()
        seed(f, 0, true)
        val result = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { _, _ -> null }, f.cost,
        ).identify(listOf(candidate("unknown")))

        assertEquals("SINGLE_BEHAVIOR_CLASS_PROVISIONAL", result.status)
        assertEquals(1, result.reusedLabels)
        assertEquals(1, result.observations.size, "label access and known confirmation differ")
        assertEquals(0, result.observations.single().eliminated)
        assertEquals(0, result.newQueries)
    }

    @Test
    fun `evidence for later refuted candidate must not transfer to unknown survivor`() {
        val f = fixture { input -> input == pool[0] }
        seed(f, 0, true)
        seed(f, 1, false)
        val result = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { c, _ -> if (c.canonical == "former") true else null }, f.cost,
        ).identify(listOf(candidate("former"), candidate("unknown-survivor")))

        assertEquals("unknown-survivor", result.selected?.canonical)
        assertEquals(2, result.reusedLabels)
        assertEquals(1, result.replayEliminated)
        assertEquals(2, result.observations.size)
        assertEquals("SINGLE_BEHAVIOR_CLASS_PROVISIONAL", result.status)
        assertEquals(0, result.newQueries)
    }

    @Test
    fun `confirming evidence of multiple equivalent candidates is recorded not proof of uniqueness`() {
        val f = fixture()
        seed(f, 0, true)
        val result = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { _, _ -> true }, f.cost,
        ).identify(listOf(candidate("left"), candidate("right")))

        assertEquals(2, result.remainingCandidates)
        assertEquals("SINGLE_BEHAVIOR_CLASS_PROVISIONAL", result.status)
        assertEquals(1, result.observations.size)
        assertEquals(0, result.observations.single().eliminated)
        assertEquals(0, result.newQueries)
    }

    @Test
    fun `two channels preserve distinct provenance even with same input key`() {
        val query = fixture()
        val challenge = fixture()
        seed(query, 0, true)
        seed(challenge, 0, true)
        val result = MRX2DActiveIdentifier(
            listOf(
                MRX2DChannel("QUERY", pool, query.ledger, 2),
                MRX2DChannel("CHALLENGE", pool, challenge.ledger, 2),
            ),
            { _, _ -> true }, MRX2Cost(),
        ).identify(listOf(candidate("survivor")))

        assertEquals(2, result.reusedLabels)
        assertEquals(2, result.observations.size)
        assertEquals(setOf("QUERY", "CHALLENGE"), result.observations.map { it.channel }.toSet())
        assertTrue(result.observations.all { it.replayed && it.eliminated == 0 })
        assertEquals("ONE_SURVIVOR_WITH_EVIDENCE", result.status)
        assertEquals(0, result.newQueries)
    }

    @Test
    fun `pruning case stays valid and new oracle usage remains zero`() {
        val f = fixture()
        seed(f, 0, true)
        val paid = f.ledger.newQueries()
        val result = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { c, _ -> c.canonical == "keep" }, f.cost,
        ).identify(listOf(candidate("keep"), candidate("bad")))
        assertEquals("ONE_SURVIVOR_WITH_EVIDENCE", result.status)
        assertEquals(1, result.replayEliminated)
        assertEquals(1, result.reusedLabels)
        assertEquals(1, result.observations.size)
        assertEquals(1, result.observations.single().eliminated)
        assertEquals(0, result.newQueries)
        assertEquals(paid, f.ledger.newQueries())
    }

    @Test
    fun `singleton without any label is provisional`() {
        val f = fixture()
        val result = MRX2DActiveIdentifier(
            listOf(MRX2DChannel("QUERY", pool, f.ledger, 2)),
            { _, _ -> true }, f.cost,
        ).identify(listOf(candidate("alone")))
        assertEquals("SINGLE_BEHAVIOR_CLASS_PROVISIONAL", result.status)
        assertTrue(result.observations.isEmpty())
        assertEquals(0, result.reusedLabels)
        assertEquals(0, result.newQueries)
    }
}
