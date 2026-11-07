// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-03 数据闭合：修复恒假世界 B，重跑 40/20/21 分割与 16 对闭合 LGG。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SD03DataClosureTest {
    private val values = listOf("0", "1", "2")
    private fun all() = values.flatMap { a -> values.flatMap { b -> values.flatMap { c -> values.map { d ->
        mapOf("a" to a, "b" to b, "c" to c, "d" to d)
    } } } }

    private fun worldA(m: Map<String, String>) = m["a"] == "1" && m["d"] == "1"
    private fun worldB(m: Map<String, String>) = m["b"] == "1" && m["c"] == "1"
    private fun oldB(m: Map<String, String>) = m["b"] == "3" && m["c"] == "3"

    private fun pool(world: MRXWorld, runId: String, training: List<Map<String, String>>, challenge: List<Map<String, String>>): List<SD03StudentSource> {
        val cases = training.mapIndexed { i, m -> StudyCase("$runId-$i", m, world.truth(m)) }
        val full = C8TestBases.bases.library("FULL")
        return SD03StudentLoop.findSources(
            runId, C8Domain.attributes, full.definitions, cases,
            StudyQueryController(challenge, challenge.size, world.truth), challenge, limit = 4,
        )
    }

    @Test
    fun `invalid old B is audited and new closed macro candidates are checked`() {
        val all = all()
        assertEquals(0, all.count { oldB(it) }, "old B must be constant false")
        assertTrue(all.any { worldB(it) } && all.any { !worldB(it) })
        val shuffled = C8Domain.all().shuffled(Random(1449))
        val train = shuffled.take(40)
        val challenge = shuffled.drop(40).take(20)
        val heldout = shuffled.drop(60)
        for ((name, split) in listOf("train" to train, "challenge" to challenge, "heldout" to heldout)) {
            assertTrue(split.any { worldA(it) } && split.any { !worldA(it) }, "A $name lacks +/-")
            assertTrue(split.any { worldB(it) } && split.any { !worldB(it) }, "B $name lacks +/-")
        }
        val wA = MRXWorld("closure-a", MRXCategory.DEPTH, "a=1 and d=1", ::worldA)
        val wB = MRXWorld("closure-b", MRXCategory.DEPTH, "b=1 and c=1", ::worldB)
        val a = pool(wA, "closure-a", train, challenge)
        val b = pool(wB, "closure-b", train, challenge)
        println("pools A=${a.size} B=${b.size}; train=${train.size} challenge=${challenge.size} heldout=${heldout.size}")
        val dir = Path.of("docs/research/sd03-data-closure-2026-11-07")
        Files.createDirectories(dir)
        val audits = ArrayList<String>()
        audits += """{"type":"world_audit","oldB_true":${all.count { oldB(it) }},"oldB_false":${all.count { !oldB(it) }},"newB_true":${all.count { worldB(it) }},"newB_false":${all.count { !worldB(it) }}}"""
        audits += """{"type":"split","seed":1449,"train":${train.size},"challenge":${challenge.size},"heldout":${heldout.size},"trainTrueA":${train.count { worldA(it) }},"trainFalseA":${train.count { !worldA(it) }},"trainTrueB":${train.count { worldB(it) }},"trainFalseB":${train.count { !worldB(it) }}}"""
        Files.write(dir.resolve("WORLD-AUDIT.json"), audits)
        if (a.isEmpty() || b.isEmpty()) {
            Files.writeString(dir.resolve("NO_VERIFIED_SOURCE_POOL.json"),
                """{"type":"source_pool","A":${a.size},"B":${b.size},"note":"NO_VERIFIED_PAIR_IN_BOUND; do not force a macro"}""" + "\n")
            return
        }
        val audits2 = ArrayList<String>()
        audits2 += """{"type":"world_audit","oldB_true":${all.count { oldB(it) }},"oldB_false":${all.count { !oldB(it) }},"newB_true":${all.count { worldB(it) }},"newB_false":${all.count { !worldB(it) }}}"""
        audits2 += """{"type":"split","seed":1449,"train":${train.size},"challenge":${challenge.size},"heldout":${heldout.size},"trainTrueA":${train.count { worldA(it) }},"trainFalseA":${train.count { !worldA(it) }},"trainTrueB":${train.count { worldB(it) }},"trainFalseB":${train.count { !worldB(it) }}}"""
        Files.write(dir.resolve("WORLD-AUDIT.json"), audits2)

        var valid = 0
        val lines = ArrayList<String>()
        for ((ia, x) in a.withIndex()) for ((ib, y) in b.withIndex()) {
            val p = SD02AntiUnify.generalize(listOf(
                SD02VerifiedSource(x.sourceId, 1, "sha-${x.canonical.hashCode()}", x.body, emptyMap(), x.validationScope, x.evidenceIds),
                SD02VerifiedSource(y.sourceId, 1, "sha-${y.canonical.hashCode()}", y.body, emptyMap(), y.validationScope, y.evidenceIds),
            ))
            if (p.degenerate) {
                lines += """{"pair":"A$ia-B$ib","valid":false,"reason":"${p.rejectReason}","freeArgs":${p.freeArgs.size}}"""
                continue
            }
            val run = SD03StudentLoop.buildMacroAndCall(listOf(x, y), C8TestBases.bases.library("FULL").definitions)
            val ok = run.macro != null && run.macroCall is EvalResult.Known
            if (ok) valid++
            lines += """{"pair":"A$ia-B$ib","valid":$ok,"holes":${p.holes.size},"freeArgs":${p.freeArgs.size},"reconstruction":${p.reconstructionOk},"macroInputs":${run.macro?.inputs?.size ?: 0},"note":"${run.note}"}"""
        }
        Files.write(dir.resolve("PAIR-LGG.json"), lines)
        println("valid=$valid/16")
        assertTrue(lines.size == 16)
    }
}
