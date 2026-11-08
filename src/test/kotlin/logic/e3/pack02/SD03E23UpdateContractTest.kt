package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic contracts; not evidence of Challenge, heldout or new autonomous ability. */
class SD03E23UpdateContractTest {
    private val original=E1Program.Branch("a",E1Program.Constant(true),E1Program.Constant(false))
    private fun observations(newLabel:Boolean)=listOf(
        E1Example("t0",mapOf("a" to "1","b" to "0"),true),
        E1Example("t1",mapOf("a" to "0","b" to "1"),false),
        E1Example("q0",mapOf("a" to "1","b" to "1"),newLabel))
    private fun atoms(ex:List<E1Example>)=listOf("a","b").map { k ->
        E1Atom(k,ex.associate { e -> e.id to (e.fields[k]=="1") },"pub:$k") }

    @Test fun `confirming evidence keeps old structure`() {
        val ex=observations(true)
        for (p in listOf(E23UpdatePolicy.KEEP_IF_AGREE,E23UpdatePolicy.LOCAL_FIRST)) {
            val r=SD03E23EvidenceUpdates.update(p,original,ex,atoms(ex),"q0")
            assertEquals(E23UpdateStatus.CONFIRM_KEEP,r.status)
            assertFalse(r.programChanged)
            assertEquals(0,r.fullRefitCalls)
            assertEquals(0,r.localAttempts)
            assertTrue(r.allPublicConsistent)
        }
    }
    @Test fun `full refit always attempts reconstruction even for agreeing label`() {
        val ex=observations(true)
        val r=SD03E23EvidenceUpdates.update(E23UpdatePolicy.FULL_REFIT,original,ex,atoms(ex),"q0")
        assertEquals(1,r.fullRefitCalls)
        assertTrue(r.allPublicConsistent)
    }
    @Test fun `real counterexample triggers nonroot local replacement`() {
        val ex=observations(false)
        val r=SD03E23EvidenceUpdates.update(E23UpdatePolicy.LOCAL_FIRST,original,ex,atoms(ex),"q0")
        assertEquals(E23UpdateStatus.LOCAL_REPAIRED,r.status)
        assertEquals("T",r.localChangedPath)
        assertTrue(r.allPublicConsistent)
        assertFalse(r.programChanged.not())
        assertEquals(0,r.fullRefitCalls)
        assertTrue(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(original,r.program,r.localChangedPath))
    }
    @Test fun `unknown does not become false`() {
        val ex=observations(false)
        val a=atoms(ex).map { if(it.id=="a") it.copy(predictions=it.predictions+("q0" to null)) else it }
        val r=SD03E23EvidenceUpdates.update(E23UpdatePolicy.LOCAL_FIRST,original,ex,a,"q0")
        assertEquals(E23UpdateStatus.UNKNOWN_CURRENT_PREDICTION,r.status)
        assertEquals(original,r.program)
    }
    @Test fun `old public inconsistency is explicit`() {
        val ex=observations(false).map{ if(it.id=="t1") it.copy(label=true) else it }
        val r=SD03E23EvidenceUpdates.update(E23UpdatePolicy.LOCAL_FIRST,original,ex,atoms(ex),"q0")
        assertEquals(E23UpdateStatus.OLD_PUBLIC_INCONSISTENT,r.status)
        assertEquals(original,r.program)
    }
    @Test fun `no new evidence id does not refit`() {
        val ex=observations(false)
        val r=SD03E23EvidenceUpdates.update(E23UpdatePolicy.FULL_REFIT,original,ex,atoms(ex),"missing")
        assertEquals(E23UpdateStatus.INVALID_NEW_EVIDENCE,r.status)
        assertEquals(0,r.fullRefitCalls)
    }
    @Test fun `matched contradictory query is certified on all disclosed examples`() {
        val ex=observations(false)
        for(p in E23UpdatePolicy.entries) {
            val r=SD03E23EvidenceUpdates.update(p,original,ex,atoms(ex),"q0")
            assertTrue(r.allPublicConsistent)
            assertEquals(false,SD03EvidenceCompositionE1.predict(r.program,
                mapOf("a" to true,"b" to true)))
        }
    }
}
