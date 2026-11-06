// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL-B 多世界考试记录；学生代码冻结于 4c2e906。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class EvalExamTest {
    @Test
    fun `run multi-world exam and preserve raw results`() {
        val runs = EvalExam.runAll()
        assertTrue(runs.isNotEmpty())
        val arr = buildJsonArray {
            runs.forEach { r ->
                add(buildJsonObject {
                    put("world", r.world)
                    put("family", r.family)
                    put("strategy", r.strategy)
                    put("seed", r.seed)
                    put("status", r.status)
                    put("generated", r.generated)
                    put("initialCandidates", r.initialCandidates)
                    put("initialClasses", r.initialClasses)
                    put("queries", r.queries)
                    put("remainingCandidates", r.remainingCandidates)
                    put("remainingClasses", r.remainingClasses)
                    put("activated", r.activated)
                    put("heldoutCorrect", r.heldoutCorrect)
                    put("failure", r.failure)
                })
            }
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack02r-eval-runs.json")
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), arr))
    }
}
