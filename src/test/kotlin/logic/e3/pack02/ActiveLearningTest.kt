// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-ACTIVE 主动辨识、隔离、冻结和激活测试。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActiveLearningTest {
    private fun sample(id: String, a: String = "0", b: String = "0", c: String = "0", label: Boolean) =
        Pack02Sample(id, mapOf("a" to a, "b" to b, "c" to c), label)

    private fun base(path: Path): Pair<KnowledgeRef, KnowledgeRef> {
        val p = Pack02Knowledge.learn(
            "active.p", listOf("a", "b"),
            listOf(sample("p1", "0", "0", label = false), sample("p2", "1", "0", label = true),
                sample("p3", "0", "1", label = false), sample("p4", "1", "1", label = true)),
            listOf(sample("ph1", "0", "2", label = false), sample("ph2", "1", "2", label = true),
                sample("ph3", "2", "0", label = false), sample("ph4", "2", "1", label = false)),
            path, maxDepth = 2,
        )
        val q = Pack02Knowledge.learn(
            "active.q", listOf("b", "c"),
            listOf(sample("q1", b = "0", c = "0", label = false), sample("q2", b = "0", c = "1", label = true),
                sample("q3", b = "1", c = "0", label = false), sample("q4", b = "1", c = "1", label = true)),
            listOf(sample("qh1", b = "2", c = "0", label = false), sample("qh2", b = "2", c = "1", label = true),
                sample("qh3", b = "0", c = "2", label = false), sample("qh4", b = "1", c = "2", label = false)),
            path, maxDepth = 2,
        )
        assertTrue(p.active, "P: ${p.failureCode}")
        assertTrue(q.active, "Q: ${q.failureCode}")
        return KnowledgeRef(p.definition!!.id, p.definition.version, p.contentHash!!) to
            KnowledgeRef(q.definition!!.id, q.definition.version, q.contentHash!!)
    }

    @Test
    fun `worlds are disjoint and deterministic by seed`() {
        val w1 = ActiveWorlds.generate(7, "and_ac")
        val w2 = ActiveWorlds.generate(7, "and_ac")
        val w3 = ActiveWorlds.generate(8, "and_ac")
        assertEquals(w1.training, w2.training)
        assertTrue(w1.training != w3.training || w1.queryPool != w3.queryPool)
        val trainKeys = w1.training.map { listOf(it.values["a"], it.values["b"], it.values["c"]).joinToString(",") }.toSet()
        val queryKeys = w1.queryPool.map { listOf(it["a"], it["b"], it["c"]).joinToString(",") }.toSet()
        val heldKeys = w1.heldout.map { listOf(it.values["a"], it.values["b"], it.values["c"]).joinToString(",") }.toSet()
        assertTrue(trainKeys.intersect(queryKeys).isEmpty())
        assertTrue(trainKeys.intersect(heldKeys).isEmpty())
        assertTrue(queryKeys.intersect(heldKeys).isEmpty())
        assertEquals(6, w1.training.size)
        assertEquals(6, w1.heldout.size)
        assertTrue(w1.queryPool.isNotEmpty())
    }

    @Test
    fun `active session freezes and updates candidates`() {
        val path = Files.createTempFile("active-", ".sqlite")
        val (p, q) = base(path)
        val world = ActiveWorlds.generate(7, "and_ac")
        val session = ActiveSession(world, path, p, q, budget = 3)
        val result = session.run()
        assertEquals("IDENTIFIED", result.status, "result=$result")
        assertTrue(result.activation?.active == true, "activation=${result.activation}")
        assertTrue(result.generated > 0)
        assertTrue(result.rounds.isNotEmpty(), "status=${result.status}")
        for (round in result.rounds) {
            assertTrue(round.query.predictions.isNotEmpty())
            assertEquals(round.query.candidateIds.size, round.query.predictions.size)
        }
        assertTrue(result.remainingCandidates.size <= result.initialCandidates)
        assertTrue(result.rounds.size <= 3)
        val record = buildJsonObject {
            put("world", result.worldId)
            put("status", result.status)
            put("generated", result.generated)
            put("initialCandidates", result.initialCandidates)
            put("initialClasses", result.initialClasses)
            put("rounds", buildJsonArray {
                result.rounds.forEach { r ->
                    add(buildJsonObject {
                        put("sequence", r.query.sequence)
                        put("input", r.query.input.toString())
                        put("label", r.label)
                        put("source", r.source)
                        put("candidateIds", r.query.candidateIds.size)
                        put("predictions", r.query.predictions.toString())
                    })
                }
            })
            put("remainingCandidates", result.remainingCandidates.size)
            put("remainingClasses", result.remainingClasses)
            put("activation", result.activation?.active)
            put("activationFailure", result.activation?.failure)
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack02r-active-run.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), record))
        val fresh = ActiveWorlds.generate(11, "and_ac")
        val defs = Pack02Knowledge.activeSnapshot(path).filter { it.id in setOf("active.p", "active.q") }
        val reuseHits = fresh.heldout.count { s -> Pack02Knowledge.predict(result.activation!!.definition!!.body, s.values, defs) == s.label }
        val c8 = buildJsonObject {
            put("status", "development_heldout_reuse")
            put("heldout", fresh.heldout.size)
            put("correct", reuseHits)
            put("rActivation", result.activation?.active)
        }
        Files.writeString(Path.of("experiments/representation-evolution-e3/reports/pack02r-active-c8.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), c8))
        assertEquals(fresh.heldout.size, reuseHits, "c8 reuse hits=$reuseHits")
        Files.deleteIfExists(path)
    }

    @Test
    fun `fixed baseline runs under the same budget`() {
        val path = Files.createTempFile("active-fixed-", ".sqlite")
        val (p, q) = base(path)
        val world = ActiveWorlds.generate(7, "and_ac")
        val fixed = ActiveSession(world, path, p, q, budget = 3, strategy = "fixed").run()
        assertTrue(fixed.rounds.size <= 3)
        assertTrue(fixed.initialCandidates > 0)
        val brec = buildJsonObject {
            put("strategy", "fixed")
            put("status", fixed.status)
            put("rounds", fixed.rounds.size)
            put("initialCandidates", fixed.initialCandidates)
            put("remainingCandidates", fixed.remainingCandidates.size)
            put("initialClasses", fixed.initialClasses)
            put("remainingClasses", fixed.remainingClasses)
            put("activation", fixed.activation?.active)
        }
        val bout = Path.of("experiments/representation-evolution-e3/reports/pack02r-active-baseline.json")
        Files.createDirectories(bout.parent)
        Files.writeString(bout, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), brec))
        Files.deleteIfExists(path)
    }

    @Test
    fun `outside family and search limit do not fake activation`() {
        val path = Files.createTempFile("active-", ".sqlite")
        val (p, q) = base(path)
        val outside = ActiveSession(ActiveWorlds.generate(7, "outside_b2"), path, p, q, budget = 2).run()
        assertTrue(outside.status == "NO_CANDIDATE" || !outside.selected?.ids.isNullOrEmpty())
        assertFalse(outside.status == "IDENTIFIED" && outside.activation?.active == true, "outside=$outside")
        val limited = ActiveSession(ActiveWorlds.generate(7, "and_ac"), path, p, q, budget = 2, maxCandidates = 1).run()
        assertEquals("SEARCH_LIMITED", limited.status)
        assertFalse(limited.activation?.active == true)
        val shortBudget = ActiveSession(ActiveWorlds.generate(7, "and_ac"), path, p, q, budget = 1).run()
        assertEquals("BUDGET_EXHAUSTED", shortBudget.status)
        val states = buildJsonObject {
            put("outside", outside.status)
            put("searchLimited", limited.status)
            put("shortBudget", shortBudget.status)
        }
        Files.writeString(Path.of("experiments/representation-evolution-e3/reports/pack02r-active-statuses.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), states))
        Files.deleteIfExists(path)
    }
}
