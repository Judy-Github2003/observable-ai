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
    private fun rData(): Pair<List<Pack02Sample>, List<Pack02Sample>> = Pair(
        listOf(
            sample("r1", "0", "0", "0", label = false),
            sample("r2", "1", "0", "0", label = false),
            sample("r3", "0", "1", "1", label = false),
            sample("r4", "1", "1", "1", label = true),
        ),
        listOf(
            sample("rh1", "0", "1", "0", label = false),
            sample("rh2", "1", "1", "0", label = false),
            sample("rh3", "0", "0", "1", label = false),
            sample("rh4", "1", "0", "1", label = true),
        ),
    )

    private fun ref(trace: Pack02Trace): KnowledgeRef {
        val def = assertNotNull(trace.definition)
        return KnowledgeRef(def.id, def.version, assertNotNull(trace.contentHash))
    }

    @Test
    fun `C4 original bounded search remains ambiguous and does not activate`() {
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
        assertFalse(r.active, "R unexpectedly activated: ${r.failureCode}")
        assertEquals("AMBIGUOUS", r.filterStatus)
        assertFalse(r.searchExhausted)
        assertFalse(r.usesIds.contains("pack02.r"))

        val truncated = Pack02Knowledge.learn(
            "pack02.truncated", listOf("a", "b"), pTraining(), pHeldout(), path, maxDepth = 3, maxCandidates = 1,
        )
        assertFalse(truncated.active)
        assertEquals("search_budget_exhausted", truncated.failureCode)
        Files.deleteIfExists(path)
    }
    @Test
    fun `execution fact rejects inactive R and non-true results`() {
        val path = Files.createTempFile("pack02-fact-", ".sqlite")
        val p = Pack02Knowledge.learn("pack02.p", listOf("a", "b"), pTraining(), pHeldout(), path, maxDepth = 2)
        val q = Pack02Knowledge.learn("pack02.q", listOf("b", "c"), qTraining(), qHeldout(), path, maxDepth = 2)
        val (rtrain, rheld) = rData()
        val r = Pack02Knowledge.learn("pack02.r", listOf("a", "b", "c"), rtrain, rheld, path, libraryRefs = listOf(ref(p), ref(q)), maxDepth = 3, maxCandidates = 20000)
        assertFalse(r.active, "R unexpectedly active")
        val pRef = ref(p)
        val activeEvidence = assertNotNull(ExecutionFactAdapter.fromExecution(path, pRef, mapOf("a" to "1", "b" to "0")))
        assertTrue(activeEvidence.isValid())
        assertEquals("pack02.p", activeEvidence.fact.proposition)
        val missing = KnowledgeRef("pack02.r", 1, "missing")
        assertTrue(ExecutionFactAdapter.fromExecution(path, missing, mapOf("a" to "1", "b" to "0")) == null)
        assertTrue(ExecutionFactAdapter.fromExecution(path, pRef, mapOf("a" to "0", "b" to "0")) == null)
        assertTrue(ExecutionFactAdapter.fromExecution(path, pRef.copy(contentHash = "bad"), mapOf("a" to "1", "b" to "0")) == null)
        assertTrue(ActiveEvidenceVerifier(path).verify(activeEvidence))
        val registry = SqliteCapabilityRegistry(path)
        try {
            assertTrue(CapabilityRevocationService(registry).revoke(pRef.id, pRef.version, "auth-test", "auth-test").ok)
        } finally {
            registry.close()
        }
        assertFalse(ActiveEvidenceVerifier(path).verify(activeEvidence), "revoked capability must invalidate old evidence")
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
        assertFalse(r.active, "R must remain inactive")
        val train = listOf(
            sample("c1", "0", "0", "0", "0", false),
            sample("c2", "1", "0", "1", "0", true),
            sample("c3", "0", "1", "0", "1", true),
            sample("c4", "1", "1", "1", "0", true),
        )
        val held = listOf(
            sample("ch1", "0", "0", "1", "0", false),
            sample("ch2", "1", "1", "0", "0", false),
            sample("ch3", "0", "1", "1", "1", true),
            sample("ch4", "1", "0", "0", "0", false),
        )
        val missingR = KnowledgeRef("pack02.r", 1, "missing")
        val full = Pack02Knowledge.reuse("full", listOf("a", "b", "c", "d"), train, held, path, listOf(ref(p), ref(q), missingR), maxDepth = 3, maxCandidates = 20000, preferredId = "pack02.r")
        assertEquals("inactive_or_hash_mismatch", full.failureCode)
        assertFalse(full.usesIds.contains("pack02.r"))

        val noR = Pack02Knowledge.reuse("no-r", listOf("a", "b", "c", "d"), train, held, path, listOf(ref(p), ref(q)), maxDepth = 3, maxCandidates = 20000)
        assertFalse(noR.usesIds.contains("pack02.r"))

        val badRef = KnowledgeRef("pack02.r", 1, "bad-hash")
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
