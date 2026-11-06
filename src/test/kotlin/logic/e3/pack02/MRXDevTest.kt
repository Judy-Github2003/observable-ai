// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 W4 与负例开发实验；验证诊断/升级确实运行，而不只是计划。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MRXDevTest {
    private data class Outcome(
        val world: MRXWorld,
        val seed: Int,
        val result: MRXStudentResult,
        val heldout: Int,
        val activated: Boolean,
        val failure: String?,
    )

    private fun run(world: MRXWorld, seed: Int): Outcome {
        val bases = C8TestBases.bases
        val path = bases.newRun("FULL")
        try {
            val full = bases.library("FULL")
            val baseRefs = if (world.libraryMode == "NO-R") bases.library("NO-R").refs else full.refs
            val split = MRXWorlds.splitFor(world, seed)
            val training = ArrayList<StudyCase>()
            split.train.forEachIndexed { i, m -> training += StudyCase("s-$i", m, world.truth(m)) }
            if (world.noise) training += StudyCase("s-conflict", split.train.first(), !world.truth(split.train.first()))
            val view = StudyView(
                "mrx-session-${world.id}", "S", training, split.query, 20, baseRefs,
                C8Domain.attributes, 3, world.maxCandidates, 24, "utility", 0,
            )
            val student = MRXStudent(
                view,
                MRXQuerySource(split.query, 20, world.truth),
                split.challenge,
                StudyQueryController(split.challenge, 24, world.truth),
                full.refs,
                path,
            )
            val result = student.run()
            val selected = result.selected
            val held = selected?.let { candidate ->
                split.heldout.count { input ->
                    Pack02Knowledge.predict(candidate.body, input, full.definitions) == world.truth(input)
                }
            } ?: 0
            val activation = selected?.let { candidate ->
                C8Evaluator(full, path).activate(
                    candidate.body, "S", "mrx.dev.${world.id}.$seed", world.truth,
                    C8Split(seed, split.train, split.query, split.heldout, split.digest), held,
                    buildJsonObject { put("phase", "dev") },
                )
            }
            return Outcome(world, seed, result, held, activation?.active == true, activation?.failure ?: result.failure)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `W4 diagnoses structure and repairs beyond frozen baseline`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        // 选择 CHALLENGE 中含 R&&d==2 的种子，使开发诊断有可用反例；正式世界另行预注册。
        val seed = (1446..20_000).first { candidate ->
            val split = MRXSplit.freeze(candidate)
            MRXWorlds.coverage(world, split).isEmpty() &&
                split.challenge.any { it["a"] == "1" && it["c"] == "1" && it["d"] == "2" }
        }
        val outcome = run(world, seed)
        println("W4 seed=$seed base=${outcome.result.baseStatus} mismatch=${outcome.result.baseChallengeMismatch} " +
            "chosen=${outcome.result.chosenAction} status=${outcome.result.status} held=${outcome.heldout}/27 " +
            "trials=${outcome.result.trials.map { it.action + ":" + it.status + ":" + it.challengeMismatch }}")
        outcome.result.diagnosis.forEach { println("  ${it.id} score=${it.score} action=${it.action} :: ${it.support}") }
        assertTrue(outcome.result.baseChallengeMismatch > 0, "base should be contradicted by challenge")
        assertTrue(outcome.result.trials.isNotEmpty(), "student must actually run upgrade experiments")
        assertTrue(outcome.result.chosenAction in setOf("DNF", "IF"))
        assertEquals(27, outcome.heldout)
        assertTrue(outcome.activated)
    }

    @Test
    fun `ambiguous world does not trigger unnecessary structural upgrade`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-ambig-1" }
        val (seed, _) = MRXWorlds.findSeed(world, from = 1857)
        val outcome = run(world, seed)
        println("AMBIG seed=$seed base=${outcome.result.baseStatus} mismatch=${outcome.result.baseChallengeMismatch} " +
            "chosen=${outcome.result.chosenAction} status=${outcome.result.status} held=${outcome.heldout}/27")
        assertEquals(0, outcome.result.baseChallengeMismatch)
        assertEquals("NONE", outcome.result.chosenAction)
        assertTrue(outcome.result.trials.isEmpty())
        assertEquals(26, outcome.heldout)
    }

    @Test
    fun `missing knowledge is repaired by restoring active capability`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-miss-or-r-d1" }
        val (seed, _) = MRXWorlds.findSeed(world, from = 2020)
        val outcome = run(world, seed)
        println("MISS seed=$seed base=${outcome.result.baseStatus} chosen=${outcome.result.chosenAction} " +
            "status=${outcome.result.status} held=${outcome.heldout}/27 trials=${outcome.result.trials.map { it.action }}")
        assertTrue(outcome.result.chosenAction in setOf("KNOWLEDGE", "DNF"))
        assertEquals(27, outcome.heldout)
        assertTrue(outcome.activated)
    }

    @Test
    fun `noise is not misattributed to structural insufficiency`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-noise-or-r-d1" }
        val (seed, _) = MRXWorlds.findSeed(world, from = 2227)
        val outcome = run(world, seed)
        println("NOISE seed=$seed base=${outcome.result.baseStatus} chosen=${outcome.result.chosenAction} " +
            "status=${outcome.result.status} diagnosis=${outcome.result.diagnosis.map { it.id }}")
        assertEquals("NO_ACTION", outcome.result.chosenAction)
        assertTrue(outcome.result.trials.isEmpty())
        assertTrue(outcome.result.selected == null)
    }
}
