package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic kernel contracts only. Green tests are NOT public-world learning evidence. */
class SD03E22ContractTest {
    private val tree = E1Program.Branch("a", E1Program.Constant(true), E1Program.Constant(false))
    private val train = listOf(
        E1Example("t0", mapOf("a" to "1", "b" to "0"), true),
        E1Example("t1", mapOf("a" to "0", "b" to "1"), false),
    )
    private fun atom(ex: List<E1Example>) = listOf("a", "b").map { f ->
        E1Atom(f, ex.associate { it.id to (it.fields[f] == "1") }, "ev:$f")
    }
    private val pool = listOf(
        E2Unlabelled("q0", mapOf("a" to "1", "b" to "1"), mapOf("a" to true, "b" to true)),
        E2Unlabelled("q1", mapOf("a" to "0", "b" to "0"), mapOf("a" to false, "b" to false)),
    )
    @Test fun `find TRAIN consistent counterfactuals without reading query labels`() {
        val build = SD03E22ConsistentConjectures.generate(tree, train, atom(train), pool)
        assertEquals(E22BuildStatus.READY, build.status)
        assertTrue(build.alternatives.isNotEmpty())
        val valuations = train.associate { e -> e.id to atom(train).associate {a -> a.id to a.predictions[e.id] } }
        assertTrue(build.alternatives.all { alt -> train.all { e ->
            SD03EvidenceCompositionE1.predict(alt.program, valuations.getValue(e.id)) == e.label
        } })
        assertTrue(build.alternatives.any { c ->
            SD03EvidenceCompositionE1.predict(c.program, pool.first().atomPredictions) !=
                SD03EvidenceCompositionE1.predict(tree, pool.first().atomPredictions)
        })
        assertTrue(build.alternatives.all { it.supportingPublicIds.isNotEmpty() })
    }
    @Test fun `complete query matrix retains every row with explicit ranks and reasons`() {
        val alternatives = SD03E22ConsistentConjectures.generate(tree,train,atom(train),pool).alternatives
        val m = SD03E22ConsistentConjectures.matrix(tree,alternatives,pool,emptySet(),train,atom(train))
        assertEquals(2,m.rows.size)
        assertTrue(m.modelCount > 1)
        assertNotNull(m.selectedQueryId)
        assertEquals(1,m.rows.single {it.selected}.rank)
        assertTrue(m.rows.all { it.votes.size==m.modelCount })
        assertTrue(m.rows.all { it.trueVotes+it.falseVotes+it.unknownVotes==m.modelCount })
    }
    @Test fun `revealed input stays in audit matrix but cannot be selected again`() {
        val alternatives = SD03E22ConsistentConjectures.generate(tree,train,atom(train),pool).alternatives
        val m = SD03E22ConsistentConjectures.matrix(tree,alternatives,pool,setOf("q0"),train,atom(train))
        assertEquals(E22RowReason.ALREADY_QUERIED,m.rows.single {it.queryId=="q0"}.reason)
        assertFalse(m.rows.single {it.queryId=="q0"}.selected)
    }
    @Test fun `TRAIN inconsistent original cannot generate certificate`() {
        val wrong = E1Program.Constant(true)
        val b = SD03E22ConsistentConjectures.generate(wrong,train,atom(train),pool)
        assertEquals(E22BuildStatus.BASE_NOT_TRAIN_CERTIFIED,b.status)
        assertTrue(b.alternatives.isEmpty())
    }
    @Test fun `unknown TRAIN atom prediction remains unknown and does not justify split`() {
        val corrupted = atom(train).map { a ->
            if (a.id=="b") a.copy(predictions=a.predictions+("t0" to null)) else a
        }
        val b = SD03E22ConsistentConjectures.generate(tree,train,corrupted,pool)
        assertTrue(b.rejectedUnknown>0)
        assertTrue(b.alternatives.none { it.leafPath == "T" && it.splitAtomId == "b" })
    }
    @Test fun `depth budget prevents hidden expansion of tree grammar`() {
        val b = SD03E22ConsistentConjectures.generate(tree,train,atom(train),pool,
            E22Config(maxDepth=1))
        assertEquals(E22BuildStatus.NO_COUNTERFACTUAL_DIVERSITY,b.status)
        assertTrue(b.rejectedStructuralBudget>0)
    }
    @Test fun `no opposite query branch gives honest no divergence`() {
        val noOpportunity=listOf(E2Unlabelled("q2",mapOf("a" to "1","b" to "0"),
            mapOf("a" to true,"b" to false)))
        val b=SD03E22ConsistentConjectures.generate(tree,train,atom(train),noOpportunity)
        assertTrue(b.alternatives.isEmpty())
    }
    @Test fun `query rankings are deterministic under public example permutation`() {
        val a = SD03E22ConsistentConjectures.generate(tree,train,atom(train),pool)
        val b = SD03E22ConsistentConjectures.generate(tree,train.reversed(),atom(train),pool.reversed())
        val x = SD03E22ConsistentConjectures.matrix(tree,a.alternatives,pool,emptySet(),train,atom(train))
        val y = SD03E22ConsistentConjectures.matrix(tree,b.alternatives,pool.reversed(),emptySet(),train,atom(train))
        assertEquals(x.selectedQueryId,y.selectedQueryId)
        assertEquals(x.rows.map {it.queryId to it.disagreementScore},
            y.rows.map {it.queryId to it.disagreementScore})
    }
    @Test fun `unverified external competitor cannot cast a vote`() {
        val wrong=E22Conjecture("bad",E1Program.Constant(true),"","b",listOf("t0"),listOf("q0"))
        assertTrue(runCatching { SD03E22ConsistentConjectures.matrix(
            tree,listOf(wrong),pool,emptySet(),train,atom(train)) }.isFailure)
    }
    @Test fun `duplicate query or missing atom coverage fails closed`() {
        assertTrue(runCatching { SD03E22ConsistentConjectures.generate(tree,train,atom(train),pool+pool.first()) }.isFailure)
        val broken=listOf(pool.first().copy(atomPredictions=mapOf("a" to true)))
        assertTrue(runCatching { SD03E22ConsistentConjectures.generate(tree,train,atom(train),broken) }.isFailure)
    }
}
