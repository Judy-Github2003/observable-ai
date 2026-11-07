// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-03 真实学生来源与参数化宏机制契约（G0/G1 有界，G2 只到可执行调用）。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SD03StudentLoopTest {
    private fun runTask(world: MRXWorld, runId: String): SD03StudentSource? {
        val training = C8Domain.all().take(40)
        val challenge = C8Domain.all().drop(40)
        val cases = training.mapIndexed { i, m -> StudyCase("$runId-$i", m, world.truth(m)) }
        val full = C8TestBases.bases.library("FULL")
        val controller = StudyQueryController(challenge, challenge.size, world.truth)
        return SD03StudentLoop.findSource(
            runId, C8Domain.attributes, full.definitions, cases, controller, challenge,
        )
    }

    @Test
    fun `two real student sources feed typed macro mechanism`() {
        val w1 = MRXWorld("sd03-a", MRXCategory.DEPTH, "a=1 and d=1",
            { m -> m["a"] == "1" && m["d"] == "1" })
        val w2 = MRXWorld("sd03-b", MRXCategory.DEPTH, "b=3 and c=3",
            { m -> m["b"] == "3" && m["c"] == "3" })
        val s1 = runTask(w1, "run-a")
        val s2 = runTask(w2, "run-b")
        println("sources a=${s1?.canonical?.take(100)} b=${s2?.canonical?.take(100)}")
        val dir = Path.of("docs/research/sd-03-2026-11-07")
        Files.createDirectories(dir)
        if (s1 == null || s2 == null) {
            Files.writeString(dir.resolve("TRACE-SD03.jsonl"),
                """{"type":"source_search","sourceA":${s1 != null},"sourceB":${s2 != null},"note":"NO_VERIFIED_PAIR_IN_BOUND"}""" + "\n")
            return
        }
        assertEquals("CHALLENGE(41)", s1.validationScope)
        assertTrue(s1.challengeChecks > 0)
        assertTrue(s1.canonical != s2.canonical)
        val run = SD03StudentLoop.buildMacroAndCall(listOf(s1, s2), C8TestBases.bases.library("FULL").definitions)
        println("pattern=${run.pattern?.pattern} holes=${run.pattern?.holes} macro=${run.macro?.id} call=${run.macroCall} note=${run.note}")
        assertNotNull(run.pattern)
        if (run.pattern!!.degenerate || run.macro == null || run.macroCall !is EvalResult.Known) {
            Files.writeString(dir.resolve("TRACE-SD03.jsonl"),
                """{"type":"negative","sourceA":true,"sourceB":true,"patternDegenerate":${run.pattern!!.degenerate},"reason":"${run.pattern!!.rejectReason ?: run.note}"}""" + "\n")
            return
        }
        assertTrue(!run.pattern!!.degenerate)
        assertNotNull(run.macro)
        assertTrue(run.macroCall is EvalResult.Known)
        val lines = ArrayList<String>()
        lines += """{"type":"source","id":"${s1.sourceId}","run":"${s1.runId}","canonicalHash":"${s1.canonical.hashCode()}","scope":"${s1.validationScope}","checks":${s1.challengeChecks},"counterexamples":${s1.challengeCounterexamples}}"""
        lines += """{"type":"source","id":"${s2.sourceId}","run":"${s2.runId}","canonicalHash":"${s2.canonical.hashCode()}","scope":"${s2.validationScope}","checks":${s2.challengeChecks},"counterexamples":${s2.challengeCounterexamples}}"""
        lines += """{"type":"pattern","id":"${run.pattern!!.patternId}","holes":${run.pattern!!.holes.size},"reconstruction":${run.pattern!!.reconstructionOk},"degenerate":${run.pattern!!.degenerate}}"""
        lines += """{"type":"macro","id":"${run.macro!!.id}@${run.macro!!.version}","hash":"${run.macro!!.contentHash()}","note":"${run.note}"}"""
        lines += """{"type":"macro_call","result":"${run.macroCall}"}"""
        Files.write(dir.resolve("TRACE-SD03.jsonl"), lines)
    }
}
