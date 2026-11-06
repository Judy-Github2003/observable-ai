// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 开发验证：证据账本驱动的 W4/歧义/缺失知识决策与三策略。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MRXRDevTest {
    private data class Outcome(
        val result: MRXRResult,
        val heldout: Int,
        val activated: Boolean,
    )

    private fun run(world: MRXWorld, seed: Int, strategy: MRXRStrategy): Outcome {
        val bases = C8TestBases.bases
        val path = bases.newRun("FULL")
        try {
            val full = bases.library("FULL")
            val baseRefs = if (world.libraryMode == "NO-R") bases.library("NO-R").refs else full.refs
            val split = MRXWorlds.splitFor(world, seed)
            val training = split.train.mapIndexed { i, m -> StudyCase("r-$i", m, world.truth(m)) }
            val view = StudyView(
                "mrxr-${world.id}", "S", training, split.query, 20, baseRefs,
                C8Domain.attributes, 3, world.maxCandidates, 24, "utility", 0,
            )
            val ledger = MRXRChallengeLedger(
                split.challenge, StudyQueryController(split.challenge, 24, world.truth), 24,
            )
            val result = MRXRStudent(
                view, MRXQuerySource(split.query, 20, world.truth), ledger, full.refs, path, strategy,
            ).run()
            val selected = result.selected
            val held = selected?.let { candidate ->
                split.heldout.count { input -> Pack02Knowledge.predict(candidate.body, input, full.definitions) == world.truth(input) }
            } ?: 0
            val activation = selected?.let { candidate ->
                C8Evaluator(full, path).activate(
                    candidate.body, "S", "mrxr.${world.id}.${strategy.wire}", world.truth,
                    C8Split(seed, split.train, split.query, split.heldout, split.digest), held,
                    kotlinx.serialization.json.buildJsonObject {},
                )
            }
            return Outcome(result, held, activation?.active == true)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `ledger driven W4 repair and evidence reuse`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val seed = (1446..20_000).first { candidate ->
            val split = MRXSplit.freeze(candidate)
            MRXWorlds.coverage(world, split).isEmpty() &&
                split.challenge.any { it["a"] == "1" && it["c"] == "1" && it["d"] == "2" }
        }
        val outcome = run(world, seed, MRXRStrategy.EVIDENCE)
        println("W4 ${outcome.result.baseStatus} val=${outcome.result.baseValidation} checks=${outcome.result.baseChecks} " +
            "chosen=${outcome.result.chosenAction} status=${outcome.result.status} held=${outcome.heldout} " +
            "trials=${outcome.result.trials.map { it.action + ":" + it.status }} reused=${outcome.result.evidenceReused}")
        assertTrue(outcome.result.baseCounterexamples > 0)
        assertTrue(outcome.result.chosenAction in setOf("DNF", "IF", "CNF"))
        assertEquals(27, outcome.heldout)
        assertTrue(outcome.activated)
        assertTrue(outcome.result.evidenceReused > 0)
    }

    @Test
    fun `ambiguous world has verified pass and no unnecessary action`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-ambig-1" }
        val seed = 1857
        val outcome = run(world, seed, MRXRStrategy.VALUE)
        println("AMBIG ${outcome.result.baseValidation} checks=${outcome.result.baseChecks} " +
            "chosen=${outcome.result.chosenAction} trials=${outcome.result.trials.size} held=${outcome.heldout}")
        assertEquals("PASS", outcome.result.baseValidation)
        assertEquals(0, outcome.result.baseCounterexamples)
        assertEquals("NONE", outcome.result.chosenAction)
        assertTrue(outcome.result.trials.isEmpty())
        assertEquals(26, outcome.heldout)
    }

    @Test
    fun `three strategies run on missing knowledge and can differ`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-miss-or-r-d1" }
        val seed = 2020
        val fixed = run(world, seed, MRXRStrategy.FIXED)
        val evidence = run(world, seed, MRXRStrategy.EVIDENCE)
        val value = run(world, seed, MRXRStrategy.VALUE)
        println("MISS base=${fixed.result.baseStatus} val=${fixed.result.baseValidation} checks=${fixed.result.baseChecks} diag=${fixed.result.diagnosis.map { it.id }} ident=${fixed.result.identifiability}")
        println("EVID chosen=${evidence.result.chosenAction} held=${evidence.heldout} trials=${evidence.result.trials.map { it.action }}")
        println("VALUE chosen=${value.result.chosenAction} held=${value.heldout} trials=${value.result.trials.map { it.action }}")
        assertTrue(fixed.activated)
        assertTrue(evidence.activated)
        assertTrue(value.activated)
        assertTrue(evidence.result.chosenAction in setOf("KNOWLEDGE", "DNF"))
    }
}
