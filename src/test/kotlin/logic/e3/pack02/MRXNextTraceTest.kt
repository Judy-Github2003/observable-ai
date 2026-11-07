// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：MRX next 的真实 W4/XOR 学生轨迹 + 可见证据/竞争假说 JSONL；不读 HELDOUT。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class MRXNextTraceTest {
    private data class Run(
        val world: String, val strategy: String, val result: MRX2Result,
        val ledger: MRX2Ledger, val hypotheses: List<MRXNextHypothesis>,
        val decisions: List<MRXNextDecision>, val path: Path,
    )

    private fun run(world: MRXWorld, strategy: MRX2Strategy): Run {
        val split = MRXWorlds.splitFor(world, 1449)
        val full = C8TestBases.bases.library("FULL")
        val path = C8TestBases.bases.newRun("FULL")
        try {
            val training = split.train.mapIndexed { i, m -> StudyCase("n-$i", m, world.truth(m)) }
            val view = StudyView(
                "mrx-next-${world.id}", "S", training, split.query, 20, full.refs,
                C8Domain.attributes, 3, 50_000, 24, "utility", 0,
            )
            val ledger = MRX2Ledger(
                split.query, StudyQueryController(split.query, 40, world.truth), 40, MRX2Cost(),
            )
            val result = MRX2Student(
                view, MRXQuerySource(split.query, 20, world.truth), ledger, full.refs, path,
                strategy,
            ).run()
            val hs = MRXNextAnalyzer.hypotheses(result, ledger)
            val ds = MRXNextAnalyzer.decisions(result, hs)
            val out = Path.of(
                "docs/research/mrx-next-2026-11-07",
                "TRACE-${world.id}-${strategy.wire}.jsonl",
            )
            MRXNextTraceWriter.write(out, "mrx-next-${world.id}-${strategy.wire}", "MRX-NEXT-2026-11-07", world.id, strategy.wire, result, hs, ds, ledger)
            println("$world ${strategy.wire} status=${result.status} steps=${result.steps.size} " +
                "actions=${result.steps.joinToString(">") { it.action }} " +
                "hyp=${hs.joinToString(",") { "${it.id}:${it.status}" }}")
            return Run(world.id, strategy.wire, result, ledger, hs, ds, out)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `real W4 and XOR traces carry evidence-linked hypotheses`() {
        val w4 = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val xor = MRXWorld(
            "mrx-next-xor-a-d", MRXCategory.DEPTH, "a1 XOR d2",
            { m -> (m["a"] == "1") != (m["d"] == "2") },
        )
        val runs = ArrayList<Run>()
        for (world in listOf(w4, xor)) {
            for (strategy in listOf(MRX2Strategy.STATIC, MRX2Strategy.FIXED, MRX2Strategy.DYNAMIC)) {
                runs += run(world, strategy)
            }
        }
        for (run in runs) {
            assertTrue(Files.exists(run.path))
            assertTrue(run.hypotheses.size >= 2)
            assertTrue(run.hypotheses.all { h ->
                (h.supportingEvidenceIds + h.refutingEvidenceIds + h.unknownEvidenceIds)
                    .all { it.startsWith("step:") || it.startsWith("ledger:") }
            })
            assertTrue(run.decisions.isNotEmpty())
            val text = Files.readString(run.path)
            assertTrue(!text.contains("HELDOUT") && !text.contains("heldout"))
        }
        val dynamic = runs.filter { it.strategy == "DYNAMIC" }
        assertTrue(dynamic.all { it.result.steps.any { s -> s.counterexamples > 0 } || it.result.steps.any { s -> s.feedback.contains("NO_CANDIDATE") } })
    }
}
