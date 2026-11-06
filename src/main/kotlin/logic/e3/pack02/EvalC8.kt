// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL C8 开发复用对照；只在考官侧组合已冻结学生产生的 R。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path

data class C8Run(
    val fullActivated: Boolean,
    val fullUsesR: Boolean,
    val fullHeldout: Int,
    val controlUsesR: Boolean,
    val controlHeldout: Int,
    val fullStatus: String,
    val controlStatus: String,
    val fullFailure: String?,
    val controlFailure: String?,
    val fullGenerated: Int,
    val fullConsistent: Int,
    val rHeldoutAccuracy: Int,
)

object EvalC8 {
    fun run(): C8Run {
        val path = Files.createTempFile("eval-c8-", ".sqlite")
        try {
            val (p, q) = base(path)
            val world = ActiveWorlds.generate(102, "and_ac")
            val result = ActiveSession(world.studentView(p, q, 3), world.controller(3), path).run()
            val activation = result.selected?.let { world.evaluator().activate(it, path) }
            require(activation?.active == true) { "C8 blocked: R not active (${activation?.failure})" }
            val rDef = activation.definition!!
            val pDef = Pack02Knowledge.resolveActive(path, listOf(p))!!.single()
            val qDef = Pack02Knowledge.resolveActive(path, listOf(q))!!.single()
            val all = ArrayList<Map<String, String>>()
            for (a in listOf("0", "1")) for (b in listOf("0", "1")) for (c in listOf("0", "1")) for (d in listOf("0", "1")) {
                all += mapOf("a" to a, "b" to b, "c" to c, "d" to d)
            }
            val expectedR = { m: Map<String, String> -> m["a"] == "1" && m["c"] == "1" }
            val target = { m: Map<String, String> -> expectedR(m) || m["d"] == "1" }
            val shuffled = all.shuffled(java.util.Random(2026))
            val train = shuffled.take(8).mapIndexed { i, m -> Pack02Sample("c8-tr-$i", m, target(m)) }
            val held = shuffled.drop(8).mapIndexed { i, m -> Pack02Sample("c8-ho-$i", m, target(m)) }
            val rAccuracy = held.count { m ->
                Pack02Knowledge.predict(rDef.body, mapOf("a" to m.values["a"]!!, "b" to m.values["b"]!!, "c" to m.values["c"]!!), listOf(pDef, qDef)) == expectedR(m.values)
            }
            val full = Pack02Knowledge.reuse(
                "c8.full", listOf("a", "b", "c", "d"), train, held, path,
                listOf(p, q, KnowledgeRef(rDef.id, rDef.version, activation.contentHash!!)),
                maxDepth = 3, maxCandidates = 20000, preferredId = rDef.id,
            )
            val control = Pack02Knowledge.reuse(
                "c8.control", listOf("a", "b", "c", "d"), train, held, path,
                listOf(p, q), maxDepth = 3, maxCandidates = 20000,
            )
            return C8Run(
                fullActivated = true,
                fullUsesR = full.usesIds.contains(rDef.id),
                fullHeldout = full.heldoutCorrect,
                controlUsesR = control.usesIds.contains(rDef.id),
                controlHeldout = control.heldoutCorrect,
                fullStatus = full.filterStatus,
                controlStatus = control.filterStatus,
                fullFailure = full.failureCode,
                controlFailure = control.failureCode,
                fullGenerated = full.generated,
                fullConsistent = full.consistent,
                rHeldoutAccuracy = rAccuracy,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun base(path: Path): Pair<KnowledgeRef, KnowledgeRef> {
        val p = Pack02Knowledge.learn(
            "c8.p", listOf("a", "b"),
            listOf(ps("0", "0", false), ps("1", "0", true), ps("0", "1", false), ps("1", "1", true)),
            listOf(ps("0", "2", false), ps("1", "2", true), ps("2", "0", false), ps("2", "1", false)),
            path, maxDepth = 2,
        )
        val q = Pack02Knowledge.learn(
            "c8.q", listOf("b", "c"),
            listOf(qs("0", "0", false), qs("0", "1", true), qs("1", "0", false), qs("1", "1", true)),
            listOf(qs("2", "0", false), qs("2", "1", true), qs("0", "2", false), qs("1", "2", false)),
            path, maxDepth = 2,
        )
        return KnowledgeRef(p.definition!!.id, p.definition.version, p.contentHash!!) to
            KnowledgeRef(q.definition!!.id, q.definition.version, q.contentHash!!)
    }

    private fun ps(a: String, b: String, label: Boolean) = Pack02Sample("c8p-${a}${b}", mapOf("a" to a, "b" to b), label)
    private fun qs(b: String, c: String, label: Boolean) = Pack02Sample("c8q-${b}${c}", mapOf("b" to b, "c" to c), label)
}
