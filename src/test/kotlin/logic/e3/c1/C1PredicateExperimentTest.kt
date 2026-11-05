// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-C1 最小谓词学习/验证/注册/第二任务复用测试；目标 AST 不进入生产代码。
package logic.e3.c1

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C1PredicateExperimentTest {

    private fun db(): Path = Files.createTempFile("c1-predicate-", ".sqlite")

    private fun predicateSamples(): Pair<List<C1PredicateSample>, List<C1PredicateSample>> {
        val all = ArrayList<C1PredicateSample>()
        for (a in listOf("0", "1", "2", "3")) {
            for (b in listOf("0", "1", "2", "3")) {
                all += C1PredicateSample("p-$a$b", a, b, a == "1" || b == "1")
            }
        }
        val held = setOf("3,1", "0,0", "2,2", "1,3", "3,3")
        return all.partition { "${it.a},${it.b}" !in held }
    }

    private fun reuseSamples(): Pair<List<C1ReuseSample>, List<C1ReuseSample>> {
        val all = ArrayList<C1ReuseSample>()
        for (a in listOf("0", "1")) {
            for (b in listOf("0", "1")) {
                for (c in listOf("0", "1", "2")) {
                    val p = a == "1" || b == "1"
                    all += C1ReuseSample("r-$a$b$c", a, b, c, p || c == "1")
                }
            }
        }
        val held = setOf("0,0,0", "1,1,2", "1,0,1")
        return all.partition { "${it.a},${it.b},${it.c}" !in held }
    }

    @Test
    fun `learned predicate is registered activated and reused in second task`() {
        val (ptrain, pheld) = predicateSamples()
        val path = db()
        val first = C1Experiment.learnPredicate(ptrain, pheld, path, maxDepth = 3, maxCandidates = 20000)
        assertEquals(true, first.registered, "propose/verification: ${first.failureCode}")
        assertEquals(true, first.activated, "activation: ${first.failureCode}")
        val predicate = assertNotNull(first.predicate)
        assertEquals(predicate.contentHash(), first.contentHash)
        assertEquals(pheld.size, first.heldoutCorrect)
        assertFalse(first.ambiguous, "filter should identify behavior; status=${first.filterStatus}")

        val (rtrain, rheld) = reuseSamples()
        val ref = C1PredicateRef(predicate.id, predicate.version, predicate.contentHash())
        val reuse = C1Experiment.reuseSecondTask("P_REUSE", rtrain, rheld, path, ref, maxDepth = 3, maxCandidates = 20000)
        assertTrue(reuse.usesPredicate, "selected body must call P; status=${reuse.filterStatus} reason=${reuse.composeReason} generated=${reuse.composeGenerated} consistent=${reuse.consistentCount}")
        assertEquals("first_consistent_candidate_using_predicate", reuse.selectionBasis)
        assertEquals(rheld.size, reuse.heldoutCorrect)
        assertEquals(null, reuse.failureCode)

        val noReuse = C1Experiment.reuseSecondTask("NO_PREDICATE", rtrain, rheld, null, null, maxDepth = 3, maxCandidates = 20000)
        assertFalse(noReuse.usesPredicate)
        assertTrue(
            noReuse.failureCode != null || noReuse.heldoutCorrect < rheld.size,
            "NO_PREDICATE unexpectedly solved with depth 4: ${noReuse.heldoutCorrect}/${rheld.size}",
        )
        println("C1 first generated=${first.composeGenerated} status=${first.filterStatus} reuseGenerated=${reuse.composeGenerated} reuseStatus=${reuse.filterStatus} reuseHeld=${reuse.heldoutCorrect}/${reuse.heldoutTotal} noPGenerated=${noReuse.composeGenerated} noPStatus=${noReuse.filterStatus} noPHeld=${noReuse.heldoutCorrect}/${noReuse.heldoutTotal} noPFailure=${noReuse.failureCode}")
        Files.deleteIfExists(path)
    }

    @Test
    fun `failed heldout verification blocks activation`() {
        val (train, held) = predicateSamples()
        val invalidHeld = held.mapIndexed { index, sample ->
            if (index == 0) sample.copy(label = !sample.label) else sample
        }
        val path = db()
        val result = C1Experiment.learnPredicate(train, invalidHeld, path, maxDepth = 3, maxCandidates = 20000)
        assertFalse(result.activated)
        assertNotNull(result.failureCode)
        val inactive = assertNotNull(result.predicate)
        val rejected = C1Experiment.reuseSecondTask(
            "P_REUSE", reuseSamples().first, reuseSamples().second, path,
            C1PredicateRef(inactive.id, inactive.version, inactive.contentHash()),
            maxDepth = 3, maxCandidates = 20000,
        )
        assertEquals("predicate_not_active_or_hash_mismatch", rejected.failureCode)
        assertEquals("NOT_EVALUATED", rejected.filterStatus)
    }

    @Test
    fun `never activated or wrong version-hash cannot enter formal reuse`() {
        val (train, held) = predicateSamples()
        val path = db()
        val first = C1Experiment.learnPredicate(train, held, path, maxDepth = 3, maxCandidates = 20000)
        val predicate = assertNotNull(first.predicate)
        val ref = C1PredicateRef(predicate.id, predicate.version, predicate.contentHash())
        val (rtrain, rheld) = reuseSamples()

        val empty = db()
        assertEquals(
            "predicate_not_active_or_hash_mismatch",
            C1Experiment.reuseSecondTask("P_REUSE", rtrain, rheld, empty, ref, maxDepth = 3, maxCandidates = 20000).failureCode,
        )
        assertEquals(
            "predicate_not_active_or_hash_mismatch",
            C1Experiment.reuseSecondTask("P_REUSE", rtrain, rheld, path, ref.copy(version = ref.version + 1), maxDepth = 3, maxCandidates = 20000).failureCode,
        )
        assertEquals(
            "predicate_not_active_or_hash_mismatch",
            C1Experiment.reuseSecondTask("P_REUSE", rtrain, rheld, path, ref.copy(contentHash = "bad-hash"), maxDepth = 3, maxCandidates = 20000).failureCode,
        )
    }

    @Test
    fun `filter refused is not cached or reused as success`() {
        val conflict = listOf(
            C1PredicateSample("a", "0", "0", true),
            C1PredicateSample("b", "0", "0", false),
        )
        val learn = C1Experiment.learnPredicate(
            conflict, listOf(C1PredicateSample("c", "1", "1", true)), db(), maxDepth = 3, maxCandidates = 20000,
        )
        assertEquals("filter_refused", learn.failureCode)
        assertNull(learn.selectedBody)
        assertFalse(learn.registered)

        val (train, held) = predicateSamples()
        val path = db()
        val first = C1Experiment.learnPredicate(train, held, path, maxDepth = 3, maxCandidates = 20000)
        val predicate = assertNotNull(first.predicate)
        val ambiguousReuse = C1Experiment.reuseSecondTask(
            "P_REUSE",
            listOf(
                C1ReuseSample("r1", "0", "0", "0", true),
                C1ReuseSample("r2", "0", "0", "0", false),
            ),
            listOf(C1ReuseSample("r3", "1", "1", "1", true)),
            path,
            C1PredicateRef(predicate.id, predicate.version, predicate.contentHash()),
            maxDepth = 3, maxCandidates = 20000,
        )
        assertEquals("filter_refused", ambiguousReuse.failureCode)
        assertNull(ambiguousReuse.selectedBody)
    }

    @Test
    fun `wrong predicate dependency is rejected by B0 interpreter`() {
        val (train, held) = predicateSamples()
        val result = C1Experiment.learnPredicate(train, held, db(), maxDepth = 3, maxCandidates = 20000)
        val predicate = assertNotNull(result.predicate)
        val badCall = Expr.Call(
            predicate.id, predicate.version, "wrong-content-hash",
            mapOf("a" to Expr.Arg("a", CapType.Str), "b" to Expr.Arg("b", CapType.Str)),
        )
        val def = CapabilityDefinition(
            "c1.bad", 1, mapOf("a" to CapType.Str, "b" to CapType.Str), CapType.Bool3, badCall,
        )
        val evaluated = CapabilityInterpreter(InMemoryResolver(listOf(predicate))).execute(
            def,
            mapOf("a" to EvalResult.Known(CapValue.Str("1")), "b" to EvalResult.Known(CapValue.Str("1"))),
            EvalBudget(maxSteps = 1000),
        )
        assertTrue(evaluated is EvalResult.Error || evaluated is EvalResult.Unknown)
        assertEquals(false, C1Experiment.bodyUsesPredicate(badCall, predicate))
    }
}
