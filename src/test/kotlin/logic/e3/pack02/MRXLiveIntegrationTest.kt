// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：MRX 实时假说真实学生轨迹；检查 before/after 时间顺序与无未来证据。
package logic.e3.pack02

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRXLiveIntegrationTest {
    private fun run(world: MRXWorld, id: String): MRX2Result {
        val split = MRXWorlds.splitFor(world, 1449)
        val full = C8TestBases.bases.library("FULL")
        val path = C8TestBases.bases.newRun("FULL")
        try {
            val training = split.train.mapIndexed { i, m -> StudyCase("l-$i", m, world.truth(m)) }
            val view = StudyView(
                id, "S", training, split.query, 20, full.refs,
                C8Domain.attributes, 3, 50_000, 24, "utility", 0,
            )
            val ledger = MRX2Ledger(split.query, StudyQueryController(split.query, 40, world.truth), 40, MRX2Cost())
            return MRX2Student(
                view, MRXQuerySource(split.query, 20, world.truth), ledger, full.refs, path,
                MRX2Strategy.LIVE_HYPOTHESIS,
            ).run()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `real live snapshots precede observations and update before next action`() {
        val w4 = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val xor = MRXWorld(
            "anonymous-xor", MRXCategory.DEPTH, "independent attributes",
            { m -> (m["a"] == "1") != (m["d"] == "2") },
        )
        for ((world, id) in listOf(w4 to "session-a", xor to "session-b")) {
            val result = run(world, id)
            val out = java.nio.file.Path.of("docs/research/mrx-live-2026-11-07", "TRACE-$id.jsonl")
            MRXLiveTraceWriter.write(out, id, result)
            assertTrue(Files.exists(out))
            println("$id status=${result.status} actions=${result.steps.joinToString(">") { it.action }} live=${result.liveTrace.size}")
            assertTrue(result.liveTrace.isNotEmpty())
            var previousVersion = 0
            for (record in result.liveTrace) {
                assertEquals(record.stepIndex, record.observationAfter!!.stepIndex)
                assertTrue(record.snapshotBefore.version >= previousVersion)
                assertEquals(record.snapshotBefore.version, record.decision.snapshotVersion)
                val beforeIds = record.snapshotBefore.hypotheses.flatMap { it.supportIds + it.refuteIds + it.unknownIds }
                assertTrue(record.observationAfter!!.id !in beforeIds, "before snapshot must not contain its future observation")
                assertTrue(record.observationAfter!!.id in record.snapshotAfter!!.hypotheses.flatMap { it.supportIds + it.refuteIds + it.unknownIds })
                assertTrue(record.snapshotAfter!!.version > record.snapshotBefore.version)
                previousVersion = record.snapshotAfter!!.version
                println("  step=${record.stepIndex} before=v${record.snapshotBefore.version} after=v${record.snapshotAfter!!.version} " +
                    "decision=${record.decision.chosenAction} hyp=${record.snapshotAfter!!.hypotheses.joinToString(",") { "${it.id}:${it.status}" }}")
            }
            assertTrue(result.liveTrace.size >= 1)
        }
    }
}
