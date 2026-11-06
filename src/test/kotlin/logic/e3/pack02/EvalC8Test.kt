// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL C8 复用对照记录。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvalC8Test {
    @Test
    fun `development C8 reuse comparison runs`() {
        val r = EvalC8.run()
        assertTrue(r.fullActivated, "r=$r")
        assertTrue(r.fullUsesR, "C8 full did not use R: $r")
        val obj = buildJsonObject {
            put("fullActivated", r.fullActivated)
            put("fullUsesR", r.fullUsesR)
            put("fullHeldout", r.fullHeldout)
            put("controlUsesR", r.controlUsesR)
            put("controlHeldout", r.controlHeldout)
            put("fullStatus", r.fullStatus)
            put("controlStatus", r.controlStatus)
            put("fullFailure", r.fullFailure)
            put("controlFailure", r.controlFailure)
            put("fullGenerated", r.fullGenerated)
            put("fullConsistent", r.fullConsistent)
            put("rHeldoutAccuracy", r.rHeldoutAccuracy)
        }
        Files.writeString(
            Path.of("experiments/representation-evolution-e3/reports/pack02r-eval-c8.json"),
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj),
        )
    }
}
