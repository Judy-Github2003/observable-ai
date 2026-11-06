// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-C8-03 正式多世界考试；先核对冻结 SHA，再运行，结果只写一次。
package logic.e3.pack02

import logic.capability.sha256
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C8FormalExamTest {
    @Test
    fun `formal six-world exam matches frozen student and exam`() {
        val manifestPath = Path.of("experiments/representation-evolution-e3/reports/pack02r-c8-03-freeze.json")
        assertTrue(Files.exists(manifestPath), "freeze manifest is required before the formal exam")
        val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject

        val studentPath = Path.of(manifest["student_source"]!!.jsonPrimitive.content)
        val examPath = Path.of(manifest["exam_source"]!!.jsonPrimitive.content)
        assertEquals(
            manifest["student_sha256"]!!.jsonPrimitive.content,
            sha256(Files.readString(studentPath)),
            "student code changed after freeze",
        )
        assertEquals(
            manifest["exam_sha256"]!!.jsonPrimitive.content,
            sha256(Files.readString(examPath)),
            "exam harness changed after freeze",
        )

        val split = C8Data.freeze(C8Experiment.SPLIT_SEED)
        assertEquals(manifest["split_seed"]!!.jsonPrimitive.int, C8Experiment.SPLIT_SEED)
        assertEquals(manifest["split_digest"]!!.jsonPrimitive.content, split.digest)
        assertTrue(C8Data.coverage(split).isEmpty(), C8Data.coverage(split).toString())
        val budget = manifest["budget"]!!.jsonObject
        assertEquals(C8Experiment.BUDGET.maxDepth, budget["maxDepth"]!!.jsonPrimitive.int)
        assertEquals(C8Experiment.BUDGET.maxNodes, budget["maxNodes"]!!.jsonPrimitive.int)
        assertEquals(C8Experiment.BUDGET.maxCandidates, budget["maxCandidates"]!!.jsonPrimitive.int)
        assertEquals(C8Experiment.BUDGET.maxCallsPerCapability, budget["maxCallsPerCapability"]!!.jsonPrimitive.int)
        assertEquals(C8Experiment.BUDGET.queryBudget, budget["queryBudget"]!!.jsonPrimitive.int)

        val runs = C8Experiment.runFormal()
        val summary = C8Json.summary(runs)
        val meta = buildJsonObject {
            put("experiment", "PACK-02R-C8-03")
            put("phase", "formal")
            put("frozen_git_head", manifest["git_head"]!!.jsonPrimitive.content)
            put("student_sha256", manifest["student_sha256"]!!.jsonPrimitive.content)
            put("exam_sha256", manifest["exam_sha256"]!!.jsonPrimitive.content)
            put("split_seed", split.seed)
            put("split_digest", split.digest)
            put("budget", budget)
            put("summary", summary)
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack02r-c8-03-formal.json")
        C8Json.write(out, runs, meta)
        assertTrue(Files.size(out) > 5000)

        assertEquals(18, runs.size)
        assertEquals(6, runs.count { it.group == "FULL" })
        assertEquals(6, runs.count { it.group == "NO-R" })
        assertEquals(6, runs.count { it.group == "BASE" })
        for (run in runs) {
            for (stage in listOfNotNull(run.s, run.t, run.noS)) {
                if (stage.result.status == "IDENTIFIED") {
                    assertNotNull(stage.result.selected, "${run.world}/${run.group}/${stage.stage}")
                } else {
                    assertNull(stage.result.selected)
                }
                if (stage.activation?.active == true) {
                    assertEquals(stage.heldoutTotal, stage.heldoutCorrect, "${run.world}/${run.group}/${stage.stage}")
                    assertEquals(0, stage.audit?.mismatches, "${run.world}/${run.group}/${stage.stage}")
                } else {
                    assertNull(stage.audit)
                }
            }
        }
        assertEquals(0, summary["wrongActivations"]!!.jsonPrimitive.int)
    }
}
