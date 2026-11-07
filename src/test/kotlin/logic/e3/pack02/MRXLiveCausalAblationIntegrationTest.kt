// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：因果消融：真实 LIVE 轨迹 + 同证据上的 FROZEN/NO_HYP 决策重放（重放不是端到端配对）。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRXLiveCausalAblationIntegrationTest {
    private fun run(world: MRXWorld, sessionId: String, path: Path): MRX2Result {
        val split = MRXWorlds.splitFor(world, 1449)
        val full = C8TestBases.bases.library("FULL")
        val db = C8TestBases.bases.newRun("FULL")
        try {
            val training = split.train.mapIndexed { i, m -> StudyCase("c-$i", m, world.truth(m)) }
            val view = StudyView(
                sessionId, "S", training, split.query, 20, full.refs,
                C8Domain.attributes, 3, 50_000, 24, "utility", 0,
            )
            val ledger = MRX2Ledger(split.query, StudyQueryController(split.query, 40, world.truth), 40, MRX2Cost())
            val result = MRX2Student(
                view, MRXQuerySource(split.query, 20, world.truth), ledger, full.refs, db,
                MRX2Strategy.LIVE_HYPOTHESIS,
            ).run()
            MRXLiveTraceWriter.write(path, sessionId, result)
            return result
        } finally {
            Files.deleteIfExists(db)
        }
    }

    @Test
    fun `real live trace plus frozen and no-hyp decision replay`() {
        val w4 = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val xor = MRXWorld(
            "anonymous-independent", MRXCategory.DEPTH, "independent attributes",
            { m -> (m["a"] == "1") != (m["d"] == "2") },
        )
        val dir = Path.of("docs/research/mrx-causal-ablation-2026-11-07")
        Files.createDirectories(dir)
        for ((world, session) in listOf(w4 to "causal-session-a", xor to "causal-session-b")) {
            val result = run(world, session, dir.resolve("TRACE-$session.jsonl"))
            val frozen = result.liveTrace.firstOrNull()?.snapshotBefore ?: MRXLiveEngine.initial()
            val tried = HashSet<String>()
            var differences = 0
            for (record in result.liveTrace) {
                val liveDecision = record.decision
                val frozenDecision = MRXLiveAblation.decide(
                    "LIVE_FROZEN_BELIEF", record.snapshotBefore, frozen,
                    MRXLiveActionModel.evaluations(), 40, tried,
                )
                val noHyp = MRXLiveAblation.decide(
                    "LIVE_NO_HYP", record.snapshotBefore, frozen,
                    MRXLiveActionModel.evaluations(), 40, tried,
                )
                println("$session step=${record.stepIndex} live=${liveDecision.chosenAction} " +
                    "frozen=${frozenDecision.chosenAction} noHyp=${noHyp.chosenAction} " +
                    "feedback=${record.observationAfter?.feedback}")
                if (liveDecision.chosenAction != frozenDecision.chosenAction) differences++
                tried += record.decision.chosenAction
            }
            assertTrue(result.liveTrace.isNotEmpty())
            assertTrue(Files.exists(dir.resolve("TRACE-$session.jsonl")))
            println("$session liveSteps=${result.liveTrace.size} liveVsFrozenDifferences=$differences status=${result.status}")
        }
    }
}
