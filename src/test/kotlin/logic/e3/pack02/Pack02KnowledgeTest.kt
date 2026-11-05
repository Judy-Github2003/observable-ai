// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-02 C4/C8 联合测试；基础谓词经 B0 注册/验证/激活，复合与复用不读隐藏目标。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
        for (a in listOf("0", "1", "2")) {
            for (b in listOf("0", "1", "2")) {
                for (c in listOf("0", "1", "2")) {
                    val key = "$a,$b,$c"
                    val sample = sample("r-$a$b$c", a, b, c, label = a == "1" && c == "1")
                    if (key in held) hold += sample else train += sample
                }
            }
        }
        return train to hold
    }

    @Test
    fun `C4 composes two activated base predicates into an active composite`() {
        val path = Files.createTempFile("pack02-", ".sqlite")
        val p = Pack02Knowledge.learn("pack02.p", listOf("a", "b"), pTraining(), pHeldout(), emptyList(), path, maxDepth = 2)
        assertTrue(p.active, "P: ${p.failureCode} status=${p.filterStatus} consistent=${p.consistent} generated=${p.generated}")
        val q = Pack02Knowledge.learn("pack02.q", listOf("b", "c"), qTraining(), qHeldout(), emptyList(), path, maxDepth = 2)
        assertTrue(q.active, "Q: ${q.failureCode}")
        val pDef = assertNotNull(p.definition)
        val qDef = assertNotNull(q.definition)
        val r = Pack02Knowledge.learn("pack02.r", listOf("a", "b", "c"), rData().first, rData().second, listOf(pDef, qDef), path, maxDepth = 4, maxCandidates = 20000)
        assertTrue(r.active, "R: ${r.failureCode} status=${r.filterStatus} consistent=${r.consistent} generated=${r.generated}")
        assertTrue(r.usesIds.containsAll(listOf("pack02.p", "pack02.q")))
        assertTrue(r.heldoutCorrect == r.heldoutTotal)
        // 联合：R 的真实执行结果作为事实，再由通用规则链做两步演绎。
        val rTrue = Pack02Knowledge.predict(r.selectedBody!!, mapOf("a" to "1", "b" to "0", "c" to "1"), listOf(pDef, qDef))
        assertTrue(rTrue == true)
        val jointRules = listOf(Rule("r-s", "R", "S"), Rule("s-t", "S", "T"))
        val joint = DeductionEngine().derive(listOf(Fact("f-r", "R")), jointRules, "T")
        val proof = (joint as DeductionResult.Success).proof
        assertTrue(ProofChecker().check(listOf(Fact("f-r", "R")), jointRules, proof, "T"))
        Files.deleteIfExists(path)
    }

    @Test
    fun `C8 reuses composite knowledge in a new task with controls`() {
        val path = Files.createTempFile("pack02-c8-", ".sqlite")
        val p = Pack02Knowledge.learn("pack02.p", listOf("a", "b"), pTraining(), pHeldout(), emptyList(), path, maxDepth = 2)
        val q = Pack02Knowledge.learn("pack02.q", listOf("b", "c"), qTraining(), qHeldout(), emptyList(), path, maxDepth = 2)
        val pDef = assertNotNull(p.definition); val qDef = assertNotNull(q.definition)
        val r = Pack02Knowledge.learn("pack02.r", listOf("a", "b", "c"), rData().first, rData().second, listOf(pDef, qDef), path, maxDepth = 4, maxCandidates = 20000)
        val rDef = assertNotNull(r.definition)
        val active = SqliteCapabilityRegistry(path).use { it.activeCapabilities() }
        assertTrue(active.any { it.id == "pack02.r" }, "R must be active in registry")

        val train = listOf(
            sample("c1", "0", "0", "0", "0", false),
            sample("c2", "1", "0", "1", "0", true),
            sample("c3", "0", "1", "0", "1", true),
            sample("c4", "1", "1", "1", "0", true),
            sample("c5", "1", "0", "0", "1", true),
            sample("c6", "0", "0", "1", "0", false),
            sample("c7", "1", "1", "0", "0", false),
            sample("c8", "0", "1", "1", "0", false),
        )
        val held = listOf(
            sample("ch1", "1", "1", "1", "0", true),
            sample("ch2", "0", "0", "0", "0", false),
        )
        val full = Pack02Knowledge.reuse("full", listOf("a", "b", "c", "d"), train, held, active, maxDepth = 3, preferredId = "pack02.r")
        assertTrue(full.usesIds.contains("pack02.r"), "full must call R; status=${full.filterStatus} ids=${full.usesIds}")
        assertEquals(held.size, full.heldoutCorrect, "status=${full.filterStatus} ids=${full.usesIds} body=${full.selectedCanonical}")

        val noComposite = Pack02Knowledge.reuse("no-r", listOf("a", "b", "c", "d"), train, held, listOf(pDef, qDef), maxDepth = 3)
        assertTrue(noComposite.failureCode != null || noComposite.heldoutCorrect < held.size)
        val record = buildJsonObject {
            put("pack", "PACK-02")
            put("p", buildJsonObject { put("generated", p.generated); put("status", p.filterStatus); put("active", p.active); put("heldout", p.heldoutCorrect) })
            put("q", buildJsonObject { put("generated", q.generated); put("status", q.filterStatus); put("active", q.active); put("heldout", q.heldoutCorrect) })
            put("r", buildJsonObject { put("generated", r.generated); put("status", r.filterStatus); put("active", r.active); put("heldout", r.heldoutCorrect); put("uses", r.usesIds.joinToString(",")) })
            put("full", buildJsonObject { put("generated", full.generated); put("status", full.filterStatus); put("uses", full.usesIds.joinToString(",")); put("heldout", full.heldoutCorrect); put("failure", full.failureCode) })
            put("noComposite", buildJsonObject { put("generated", noComposite.generated); put("status", noComposite.filterStatus); put("uses", noComposite.usesIds.joinToString(",")); put("heldout", noComposite.heldoutCorrect); put("failure", noComposite.failureCode) })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack02-run.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), record))
        Files.deleteIfExists(path)
    }
}
