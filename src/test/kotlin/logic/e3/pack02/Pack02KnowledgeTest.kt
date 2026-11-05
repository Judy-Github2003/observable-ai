// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-02 C4/C8 测试；正式入口只使用注册表激活引用。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Pack02KnowledgeTest {
    private fun sample(id: String, a: String = "0", b: String = "0", c: String = "0", d: String = "0", label: Boolean) =
        Pack02Sample(id, mapOf("a" to a, "b" to b, "c" to c, "d" to d), label)

    private fun pTraining() = listOf(
        sample("p1", "0", "0", label = false), sample("p2", "1", "0", label = true),
        sample("p3", "0", "1", label = false), sample("p4", "1", "1", label = true),
    )
    private fun pHeldout() = listOf(
        sample("ph1", "0", "1", label = false), sample("ph2", "1", "1", label = true),
        sample("ph3", "0", "2", label = false), sample("ph4", "1", "2", label = true),
    )
    private fun qTraining() = listOf(
        sample("q1", b = "0", c = "0", label = false), sample("q2", b = "0", c = "1", label = true),
        sample("q3", b = "1", c = "0", label = false), sample("q4", b = "1", c = "1", label = true),
    )
    private fun qHeldout() = listOf(
        sample("qh1", b = "1", c = "0", label = false), sample("qh2", b = "1", c = "1", label = true),
        sample("qh3", b = "2", c = "0", label = false), sample("qh4", b = "2", c = "1", label = true),
    )
    private fun rData(): Pair<List<Pack02Sample>, List<Pack02Sample>> {
        val held = setOf("1,1,1", "2,0,1", "1,2,0", "0,1,2")
        val train = ArrayList<Pack02Sample>()
        val hold = ArrayList<Pack02Sample>()
        for (a in listOf("0", "1", "2")) for (b in listOf("0", "1", "2")) for (c in listOf("0", "1", "2")) {
            val key = "$a,$b,$c"
            val s = sample("r-$a$b$c", a, b, c, label = a == "1" && c == "1")
            if (key in held) hold += s else train += s
        }
        return train to hold
    }

    private fun ref(trace: Pack02Trace): KnowledgeRef {
        val def = assertNotNull(trace.definition)
        return KnowledgeRef(def.id, def.version, assertNotNull(trace.contentHash))
    }

    @Test
    fun `C4 composes active base predicates and enforces search budget`() {
        val path = Files.createTempFile("pack02-", ".sqlite")
        val p = Pack02Knowledge.learn("pack02.p", listOf("a", "b"), pTraining(), pHeldout(), path, maxDepth = 2)
        assertTrue(p.active, "P: ${p.failureCode}")
        val q = Pack02Knowledge.learn("pack02.q", listOf("b", "c"), qTraining(), qHeldout(), path, maxDepth = 2)
        assertTrue(q.active, "Q: ${q.failureCode}")
        val (rtrain, rheld) = rData()
        val r = Pack02Knowledge.learn(
            "pack02.r", listOf("a", "b", "c"), rtrain, rheld, path,
            libraryRefs = listOf(ref(p), ref(q)), maxDepth = 3, maxCandidates = 20000,
        )
        assertTrue(r.active, "R: ${r.failureCode} status=${r.filterStatus} generated=${r.generated} reason=${r.composeReason}")
        assertTrue(r.usesIds.containsAll(listOf("pack02.p", "pack02.q")))
        assertEquals(4, r.heldoutCorrect)

        val truncated = Pack02Knowledge.learn(
            "pack02.truncated", listOf("a", "b"), pTraining(), pHeldout(), path, maxDepth = 3, maxCandidates = 1,
        )
        assertFalse(truncated.active)
        assertEquals("search_budget_exhausted", truncated.failureCode)
        Files.deleteIfExists(path)
    }

    @Test
    fun `execution fact requires active R and true result and rejects revoked reference`() {
        val path = Files.createTempFile("pack02-fact-", ".sqlite")
        val p = Pack02Knowledge.learn("pack02.p", listOf("a", "b"), pTraining(), pHeldout(), path, maxDepth = 2)
        val q = Pack02Knowledge.learn("pack02.q", listOf("b", "c"), qTraining(), qHeldout(), path, maxDepth = 2)
        val (rtrain, rheld) = rData()
        val r = Pack02Knowledge.learn("pack02.r", listOf("a", "b", "c"), rtrain, rheld, path, libraryRefs = listOf(ref(p), ref(q)), maxDepth = 3, maxCandidates = 20000)
        assertTrue(r.active, "R: ${r.failureCode}")
        val rRef = ref(r)
        val input = mapOf("a" to "1", "b" to "0", "c" to "1")
        val fact = assertNotNull(ExecutionFactAdapter.fromExecution(path, rRef, input))
        assertEquals(FactStatus.VERIFIED, fact.status)
        assertEquals(rRef.id + "@" + rRef.version + "#" + rRef.contentHash, fact.capabilityRef)
        assertNotNull(fact.inputDigest)
        assertTrue(ExecutionFactAdapter.fromResult(rRef, input, EvalResult.Known(CapValue.Bool(false))) == null)
        assertTrue(ExecutionFactAdapter.fromResult(rRef, input, EvalResult.Unknown("no")) == null)
        assertTrue(ExecutionFactAdapter.fromResult(rRef, input, EvalResult.Error("bad")) == null)

        val rules = listOf(Rule("r-s", "R", "S"), Rule("s-t", "S", "T"))
        val proof = (DeductionEngine().derive(listOf(fact), rules, "T") as DeductionResult.Success).proof
        assertTrue(ProofChecker().check(listOf(fact), rules, proof, "T"))

        val registry = SqliteCapabilityRegistry(path)
        try {
            val revoked = CapabilityRevocationService(registry).revoke(rRef.id, rRef.version, "test", "test")
            assertTrue(revoked.ok)
        } finally {
            registry.close()
        }
        assertTrue(ExecutionFactAdapter.fromExecution(path, rRef, input) == null)
        Files.deleteIfExists(path)
    }

    @Test
    fun `C8 uses only active registry references and no-composite excludes R`() {
        val path = Files.createTempFile("pack02-c8-", ".sqlite")
        val p = Pack02Knowledge.learn("pack02.p", listOf("a", "b"), pTraining(), pHeldout(), path, maxDepth = 2)
        val q = Pack02Knowledge.learn("pack02.q", listOf("b", "c"), qTraining(), qHeldout(), path, maxDepth = 2)
        val (rtrain, rheld) = rData()
        val r = Pack02Knowledge.learn(
            "pack02.r", listOf("a", "b", "c"), rtrain, rheld, path,
            libraryRefs = listOf(ref(p), ref(q)), maxDepth = 3, maxCandidates = 20000,
        )
        assertTrue(r.active, "R: ${r.failureCode}")
        val train = listOf(
            sample("c1", "0", "0", "0", "0", false), sample("c2", "1", "0", "1", "0", true),
            sample("c3", "0", "1", "0", "1", true), sample("c4", "1", "1", "1", "0", true),
            sample("c5", "1", "0", "0", "1", true), sample("c6", "0", "0", "1", "0", false),
            sample("c7", "1", "1", "0", "0", false), sample("c8", "0", "1", "1", "0", false),
        )
        val held = listOf(
            sample("ch1", "1", "1", "1", "0", true), sample("ch2", "0", "0", "0", "0", false),
        )
        val refs = listOf(ref(p), ref(q), ref(r))
        val full = Pack02Knowledge.reuse("full", listOf("a", "b", "c", "d"), train, held, path, refs, maxDepth = 3, maxCandidates = 20000, preferredId = "pack02.r")
        assertTrue(full.usesIds.contains("pack02.r"), "full: ${full.failureCode} ${full.filterStatus}")
        assertEquals(held.size, full.heldoutCorrect)

        val noR = Pack02Knowledge.reuse("no-r", listOf("a", "b", "c", "d"), train, held, path, listOf(ref(p), ref(q)), maxDepth = 3, maxCandidates = 20000)
        assertTrue(noR.failureCode != null || noR.heldoutCorrect < held.size)
        assertFalse(noR.active)

        val badRef = ref(r).copy(contentHash = "bad-hash")
        assertEquals("inactive_or_hash_mismatch", Pack02Knowledge.reuse("bad", listOf("a", "b", "c", "d"), train, held, path, listOf(badRef)).failureCode)
        val record = buildJsonObject {
            put("pack", "PACK-02")
            fun traceJson(t: Pack02Trace) = buildJsonObject {
                put("generated", t.generated)
                put("reason", t.composeReason)
                put("searchExhausted", t.searchExhausted)
                put("status", t.filterStatus)
                put("active", t.active)
                put("heldout", t.heldoutCorrect)
                put("uses", t.usesIds.joinToString(","))
                put("failure", t.failureCode)
            }
            put("p", traceJson(p))
            put("q", traceJson(q))
            put("r", traceJson(r))
            put("full", traceJson(full))
            put("noComposite", traceJson(noR))
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack02-run.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), record))
        Files.deleteIfExists(path)
    }
}
