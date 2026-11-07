package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic contracts only, not scientific evidence of active learning benefit. */
class SD03E2ContractTest {
    private val old=E1Program.Branch("a",E1Program.Constant(true),E1Program.Constant(false))
    private fun evidence() = listOf(
        E1Example("t0",mapOf("a" to "1","b" to "0"),true),
        E1Example("t1",mapOf("a" to "0","b" to "1"),false),
        E1Example("q0",mapOf("a" to "1","b" to "1"),false))
    private fun atoms(ex:List<E1Example>)=listOf("a","b").map { col ->
        E1Atom(col,ex.associate { it.id to (it.fields[col]=="1") },"ev:$col")
    }
    @Test fun `counterexample repairs only a non-root subtree`() {
        val ex=evidence(); val a=atoms(ex)
        val r=SD03ActiveQueryLocalRepairE2.repair(old,ex,a,"q0")
        assertEquals(E2RepairStatus.LOCAL_REPAIRED,r.status)
        assertEquals("T",r.changedPath)
        assertFalse(r.fullRefitUsed)
        assertEquals(old.whenFalse,(r.program as E1Program.Branch).whenFalse)
        assertTrue(SD03ActiveQueryLocalRepairE2.correct(r.program,ex,a))
    }
    @Test fun `labels agreeing with tree do not force repair`() {
        val ex=evidence().take(2)
        val r=SD03ActiveQueryLocalRepairE2.repair(old,ex,atoms(ex),"t0")
        assertEquals(E2RepairStatus.NO_COUNTEREXAMPLE,r.status)
        assertEquals(old,r.program)
    }
    @Test fun `a root-only tree cannot be misreported as local`() {
        val ex=evidence(); val a=atoms(ex)
        val r=SD03ActiveQueryLocalRepairE2.repair(E1Program.Constant(true),ex,a,"q0")
        assertEquals(E2RepairStatus.LOCAL_UNAVAILABLE,r.status)
    }
    @Test fun `already incorrect on old evidence prevents local certification`() {
        val ex=evidence().map { if(it.id=="t1") it.copy(label=true) else it }
        val r=SD03ActiveQueryLocalRepairE2.repair(old,ex,atoms(ex),"q0")
        assertEquals(E2RepairStatus.LOCAL_UNAVAILABLE,r.status)
    }
    @Test fun `Unknown query prediction never becomes false`() {
        val ex=evidence();val a=atoms(ex).map { if(it.id=="a") it.copy(predictions=it.predictions + ("q0" to null)) else it }
        val r=SD03ActiveQueryLocalRepairE2.repair(old,ex,a,"q0")
        assertEquals(E2RepairStatus.UNKNOWN_PATH,r.status)
    }
    @Test fun `predictive disagreement queries are selected without label`() {
        val ex=evidence().take(2)
        val u=listOf(E2Unlabelled("u0",mapOf("a" to "1","b" to "0"),mapOf("a" to true,"b" to false)))
        val m=listOf(E2Model("m1",old),E2Model("m2",E1Program.Branch("b",E1Program.Constant(true),E1Program.Constant(false))))
        assertEquals("u0",SD03ActiveQueryLocalRepairE2.choose(u,m,old,ex,atoms(ex)).queryId)
    }
    @Test fun `no disagreement abstains rather than fabricating information gain`() {
        val ex=evidence().take(2)
        val u=listOf(E2Unlabelled("u0",mapOf("a" to "1","b" to "0"),mapOf("a" to true,"b" to false)))
        assertEquals(null,SD03ActiveQueryLocalRepairE2.choose(u,listOf(E2Model("only",old)),old,ex,atoms(ex)).queryId)
    }
    @Test fun `missing atom preview is rejected`() {
        val ex=evidence().take(2)
        val failed=runCatching { SD03ActiveQueryLocalRepairE2.choose(listOf(E2Unlabelled("u",emptyMap(),mapOf("a" to true))),
            listOf(E2Model("a",old),E2Model("b",E1Program.Constant(false))),old,ex,atoms(ex)) }.isFailure
        assertTrue(failed)
    }
}
