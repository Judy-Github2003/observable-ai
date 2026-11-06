// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL-A 隔离测试；学生只拿 StudentView，标签由 QueryController 控制。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `view isolates family target and heldout`() {
        val world = ActiveWorlds.generate(7, "and_ac")
        val path = Files.createTempFile("active-view-", ".sqlite")
        val (p, q) = base(path)
        val view = world.studentView(p, q, 3)
        assertEquals("session-7", view.worldId)
        assertFalse(view.worldId.contains("and_ac"))
        val fields = view.javaClass.declaredFields.map { it.name }
        assertFalse(fields.any { it in setOf("family", "heldout", "target", "oracle") }, fields.toString())
        assertTrue(view.queryPool.none { it.containsKey("label") })
        Files.deleteIfExists(path)
    }

    @Test
    fun `query controller enforces freeze pool duplicate and budget`() {
        val world = ActiveWorlds.generate(7, "and_ac")
        val controller = world.controller(1)
        val input = world.queryPool.first()
        val frozen = controller.freeze(0, input, mapOf("c" to true), listOf("c"))
        assertFailsWith<IllegalArgumentException> { controller.reveal(controller.freeze(1, world.queryPool[1], mapOf("x" to true), listOf("x"))) }
        val obs = controller.reveal(frozen)
        assertTrue(obs.label == world.oracleForTestOnly(input))
        assertFailsWith<IllegalArgumentException> { controller.freeze(1, input, mapOf("c" to true), listOf("c")) }
        val heldoutInput = world.heldoutForTestOnly().first().values
        assertFailsWith<IllegalArgumentException> { controller.freeze(1, heldoutInput, mapOf("c" to true), listOf("c")) }
        assertFailsWith<IllegalArgumentException> { controller.freeze(1, world.queryPool[1], mapOf("c" to true), listOf("c")) }
        assertEquals(0, controller.remaining())
    }

    @Test
    fun `active session identifies and evaluator activates`() {
        val path = Files.createTempFile("active-", ".sqlite")
        val (p, q) = base(path)
        val world = ActiveWorlds.generate(7, "and_ac")
        val view = world.studentView(p, q, 3)
        val result = ActiveSession(view, world.controller(3), path).run()
        assertEquals("IDENTIFIED", result.status, "result=$result")
        assertTrue(result.rounds.isNotEmpty())
        val activation = world.evaluator().activate(assertNotNull(result.selected), path)
        assertTrue(activation.active, "activation=$activation")
        val record = buildJsonObject {
            put("world", result.worldId)
            put("status", result.status)
            put("generated", result.generated)
            put("initialCandidates", result.initialCandidates)
            put("initialClasses", result.initialClasses)
            put("rounds", result.rounds.size)
            put("remainingCandidates", result.remainingCandidates.size)
            put("remainingClasses", result.remainingClasses)
            put("activation", activation.active)
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack02r-active-run.json")
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), record))
        Files.deleteIfExists(path)
    }

    @Test
    fun `baseline and negative worlds do not fake activation`() {
        val path = Files.createTempFile("active-neg-", ".sqlite")
        val (p, q) = base(path)
        val world = ActiveWorlds.generate(7, "and_ac")
        val fixed = ActiveSession(world.studentView(p, q, 3), world.controller(3), path, strategy = "fixed").run()
        assertTrue(fixed.rounds.size <= 3)
        val outside = ActiveSession(
            ActiveWorlds.generate(7, "outside_b2").studentView(p, q, 2),
            ActiveWorlds.generate(7, "outside_b2").controller(2), path,
        ).run()
        assertFalse(outside.status == "IDENTIFIED")
        val limited = ActiveSession(world.studentView(p, q, 2), world.controller(2), path, maxCandidates = 1).run()
        assertEquals("SEARCH_LIMITED", limited.status)
        Files.deleteIfExists(path)
    }
}
