// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：C8-02 四属性 S/T 实验记录。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class C8StudyTest {
    @Test
    fun `run multi-layer reuse study and preserve result`() {
        val r = C8Study.run()
        val obj = buildJsonObject {
            put("rActive", r.rActive)
            fun stage(s: StudyStage) = buildJsonObject {
                put("name", s.name); put("status", s.status); put("usesR", s.selectedUsesR)
                put("usesS", s.selectedUsesS); put("heldoutCorrect", s.heldoutCorrect)
                put("activated", s.activated); put("failure", s.failure)
                put("generated", s.generated); put("consistent", s.consistent)
            }
            put("s", stage(r.s))
            put("t", r.t?.let { stage(it) } ?: JsonNull)
            put("sActivationId", r.sActivationId)
            put("tActivationId", r.tActivationId)
        }
        Files.writeString(
            Path.of("experiments/representation-evolution-e3/reports/pack02r-c8-02-result.json"),
            Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), obj),
        )
    }
}
