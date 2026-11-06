// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：记录 eval-101 的 B0 outcome 失败码与覆盖计数。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class EvalDiagnosticsTest {
    @Test
    fun `record eval101 verification outcome`() {
        val d = EvalDiagnostics.eval101()
        val obj = buildJsonObject {
            put("status", d.status)
            put("selected", d.selected)
            put("outcomeCode", d.outcomeCode)
            put("verdict", d.verdict)
            put("caseCount", d.caseCount)
            put("passedCount", d.passedCount)
            put("definiteCount", d.definiteCount)
            put("trueCount", d.trueCount)
            put("falseCount", d.falseCount)
            put("recorded", d.recorded)
        }
        Files.writeString(
            Path.of("experiments/representation-evolution-e3/reports/pack02r-eval101-diagnostic.json"),
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj),
        )
    }
}
