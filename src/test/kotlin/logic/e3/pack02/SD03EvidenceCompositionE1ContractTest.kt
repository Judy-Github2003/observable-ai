package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic engineering fixtures only. Never count as blind scientific evidence. */
class SD03EvidenceCompositionE1ContractTest {
    private fun binaryExamples(target: (Boolean,Boolean,Boolean)-> Boolean): Pair<List<E1Example>,List<E1Atom>> {
        val cases = ArrayList<E1Example>()
        for (a in listOf(false,true)) for (b in listOf(false,true)) for (c in listOf(false,true)) {
            val v = mapOf("a" to a.toString(),"b" to b.toString(),"c" to c.toString())
            cases += E1Example("${a}_${b}_$c",v,target(a,b,c))
        }
        val atoms = listOf("a","b","c").map { field ->
            E1Atom(field,cases.associate { it.id to (it.fields[field]=="true") },"evidence:$field")
        }
        return cases to atoms
    }

    @Test fun `learn nested two branch composition without supplied program`() {
        val (cases,atoms) = binaryExamples { a,b,c -> if(a) b else c }
        val r = SD03EvidenceCompositionE1.propose(cases,atoms,
            E1Config(maxDepth=2,beamWidthPerNode=3,maxPartitionAttempts=200))
        assertEquals(E1Status.TRAIN_CONSISTENT_PROPOSAL,r.status)
        assertEquals(cases.size,r.trainCorrect)
        assertTrue(r.program is E1Program.Branch)
        assertTrue(r.decisionEvidence.all { step -> step.supportingTrainingIds.all { it in cases.map { c -> c.id } } })
    }

    @Test fun `XOR can be learned with zero gain at root`() {
        val (cases,atoms) = binaryExamples { a,b,_ -> a != b }
        val r = SD03EvidenceCompositionE1.propose(cases,atoms,E1Config(maxDepth=2,beamWidthPerNode=3))
        assertEquals(E1Status.TRAIN_CONSISTENT_PROPOSAL,r.status)
        assertEquals(8,r.trainCorrect)
    }

    @Test fun `shallow model does not falsely claim full fit`() {
        val (cases,atoms) = binaryExamples { a,b,c -> if(a) b else c }
        val r = SD03EvidenceCompositionE1.propose(cases,atoms,E1Config(maxDepth=1))
        assertEquals(E1Status.TRAIN_INCONSISTENT_PROPOSAL,r.status)
        assertTrue(r.trainCorrect < r.trainTotal)
    }

    @Test fun `contradictory labels for identical inputs stop proposal`() {
        val cases = listOf(E1Example("t1",mapOf("x" to "1"),true), E1Example("t2",mapOf("x" to "1"),false))
        val atoms = listOf(E1Atom("x",mapOf("t1" to true,"t2" to true),"ev:x"))
        val r=SD03EvidenceCompositionE1.propose(cases,atoms)
        assertEquals(E1Status.CONFLICTING_TRAIN_LABELS,r.status)
        assertEquals(null,r.program)
    }

    @Test fun `Unknown is never coerced to false`() {
        val (cases,atoms) = binaryExamples { a,_,_ -> a }
        val edited = atoms.map { atom -> if(atom.id=="a") atom.copy(predictions=atom.predictions.mapValues { null }) else atom }
        val r=SD03EvidenceCompositionE1.propose(cases,edited,E1Config(maxDepth=1))
        assertTrue(r.unknownPredictionsSkipped>0)
        assertEquals(E1Status.TRAIN_INCONSISTENT_PROPOSAL,r.status)
    }

    @Test fun `hard split budget is visible and does not certify success`() {
        val (cases,atoms)=binaryExamples { a,b,c -> if(a)b else c }
        val r=SD03EvidenceCompositionE1.propose(cases,atoms,E1Config(maxDepth=3,maxPartitionAttempts=1))
        assertTrue(r.budgetHit)
        assertEquals(E1Status.SEARCH_BUDGET_EXHAUSTED,r.status)
        assertTrue(r.splitAttempts<=1)
    }

    @Test fun `missing atom coverage is rejected before search`() {
        val (cases,atoms)=binaryExamples { a,_,_ -> a }
        val corrupted=atoms.map { if(it.id=="a") it.copy(predictions=it.predictions - cases.first().id) else it }
        assertTrue(runCatching { SD03EvidenceCompositionE1.propose(cases,corrupted) }.isFailure)
    }

    @Test fun `literal test prediction stays local to selected path`() {
        val tree=E1Program.Branch("a",E1Program.Constant(true),E1Program.Constant(false))
        assertEquals(true,SD03EvidenceCompositionE1.predict(tree,mapOf("a" to true)))
        assertEquals(false,SD03EvidenceCompositionE1.predict(tree,mapOf("a" to false)))
        assertEquals(null,SD03EvidenceCompositionE1.predict(tree,mapOf("a" to null)))
    }
}
