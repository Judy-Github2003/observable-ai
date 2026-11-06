// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：EVAL-02 补充考试记录；保留覆盖选择和学生结果。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class EvalExam2Test {
    @Test
    fun `run supplementary frozen exam with coverage gates`() {
        val (runs, selections) = EvalExam2.runAll()
        assertTrue(runs.isNotEmpty())
        val obj = buildJsonObject {
            put("selections", buildJsonArray {
                selections.forEach { s ->
                    add(buildJsonObject {
                        put("family", s.spec.family)
                        put("seed", s.seed)
                        put("budget", s.spec.budget)
                        put("coverage", s.coverage)
                    })
                }
            })
            put("runs", buildJsonArray {
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
            })
        }
        Files.writeString(
            Path.of("experiments/representation-evolution-e3/reports/pack02r-eval02-runs.json"),
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj),
        )
    }
}
