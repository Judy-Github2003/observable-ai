package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic, local and non-scientific contracts. */
class SD03E21ContractTest {
    private val examples = listOf(
        E1Example("t0",mapOf("a" to "1","b" to "0"),true),
        E1Example("t1",mapOf("a" to "0","b" to "1"),false),
    )
    private val atoms = listOf("a","b").map { f ->
        E1Atom(f,examples.associate { it.id to (it.fields[f]=="1") },"public:$f")
    }
    private val current=E1Program.Branch("a",E1Program.Constant(true),E1Program.Constant(false))
    private val opposite=E1Program.Branch("a",E1Program.Constant(false),E1Program.Constant(true))
    private val q0=E2Unlabelled("q0",mapOf("a" to "1","b" to "0"),mapOf("a" to true,"b" to false))

    @Test fun `unfiltered disagreement may include refuted models`() {
        val d=SD03E21EvidenceQuery.select(E21QueryPolicy.E2_UNFILTERED_DISAGREEMENT,
            listOf(q0),listOf(E2Model("current",current),E2Model("wrong",opposite)),current,examples,atoms)
        assertEquals("q0",d.queryId)
        assertEquals(1,d.agreeingTrue)
        assertEquals(1,d.agreeingFalse)
    }
    @Test fun `version space excludes a TRAIN refuted alternative`() {
        val d=SD03E21EvidenceQuery.select(E21QueryPolicy.TRAIN_CONSISTENT_VERSIONSPACE,
            listOf(q0),listOf(E2Model("current",current),E2Model("wrong",opposite)),current,examples,atoms)
        assertEquals(null,d.queryId)
        assertEquals(1,d.rejectedInconsistentModels)
        assertEquals("NO_TWO_DISTINCT_ELIGIBLE_PROGRAMS",d.reason)
    }
    @Test fun `missing preview and duplicate public IDs fail closed`() {
        assertTrue(runCatching { SD03E21EvidenceQuery.select(E21QueryPolicy.TRAIN_CONSISTENT_VERSIONSPACE,
            listOf(q0.copy(atomPredictions=mapOf("a" to true))), emptyList(),current,examples,atoms) }.isFailure)
        assertTrue(runCatching { SD03E21EvidenceQuery.select(E21QueryPolicy.TRAIN_CONSISTENT_VERSIONSPACE,
            listOf(q0), emptyList(),current,examples+examples.first(),atoms) }.isFailure)
    }
    @Test fun `unknown TRAIN prediction is not a negative label`() {
        val edited=atoms.map { if(it.id=="a") it.copy(predictions=it.predictions+("t0" to null)) else it }
        val d=SD03E21EvidenceQuery.select(E21QueryPolicy.TRAIN_CONSISTENT_VERSIONSPACE,
            listOf(q0),listOf(E2Model("current",current)),current,examples,edited)
        assertEquals(null,d.queryId)
        assertTrue(d.rejectedUnknownModels>0)
    }
    @Test fun `a non-root changed subtree retains the untouched sibling`() {
        val updated=E1Program.Branch("a",
            E1Program.Branch("b",E1Program.Constant(false),E1Program.Constant(true)),
            E1Program.Constant(false))
        assertTrue(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(current,updated,"T"))
        assertFalse(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(current,updated,"F"))
        assertFalse(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(current,updated,""))
    }
    @Test fun `changing a sibling or ancestor is not local`() {
        val altered=E1Program.Branch("a",E1Program.Constant(false),E1Program.Constant(true))
        assertFalse(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(current,altered,"T"))
        val differentRoot=E1Program.Branch("b",E1Program.Constant(false),E1Program.Constant(false))
        assertFalse(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(current,differentRoot,"T"))
    }
    @Test fun `actual E2 local repair must produce new evidence consistent proposal`() {
        val old=E1Program.Branch("a",E1Program.Constant(true),E1Program.Constant(false))
        val more=examples+E1Example("q0",mapOf("a" to "1","b" to "1"),false)
        val srcAtoms=listOf("a","b").map { f -> E1Atom(f,
            more.associate {it.id to (it.fields[f]=="1")},"public:$f") }
        val repaired=SD03ActiveQueryLocalRepairE2.repair(old,more,srcAtoms,"q0")
        assertEquals(E2RepairStatus.LOCAL_REPAIRED,repaired.status)
        assertTrue(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(old,repaired.program,repaired.changedPath))
        assertTrue(SD03ActiveQueryLocalRepairE2.correct(repaired.program,more,srcAtoms))
    }
}
