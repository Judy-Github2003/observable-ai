// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 冻结后的四条件正式考试；先校验 SHA，再运行，结果只写一次。
package logic.e3.pack02

import logic.capability.sha256
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRXFormalTest {
    @Test
    fun `frozen four-condition formal exam`() {
        val manifestPath = Path.of("experiments/representation-evolution-e3/reports/mrx-01-freeze.json")
        assertTrue(Files.exists(manifestPath))
        val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        manifest["sources"]!!.jsonObject.forEach { (_, value) ->
            val source = value.jsonObject
            val path = Path.of(source["path"]!!.jsonPrimitive.content)
            assertEquals(source["sha256"]!!.jsonPrimitive.content, sha256(Files.readString(path)), path.toString())
        }
        val records = MRXExam.runFormal()
        val out = Path.of("experiments/representation-evolution-e3/reports/mrx-01-formal.json")
        MRXExam.write(records, out)
        assertTrue(Files.size(out) > 1000)
        assertEquals(28, records.size)
        for (condition in MRXCondition.entries) {
            assertEquals(MRXWorlds.formal.size, records.count { it.condition == condition.wire })
        }
        assertTrue(records.none { it.activation && it.heldoutCorrect < it.heldoutTotal })
        val autonomous = records.filter { it.condition == MRXCondition.AUTONOMOUS.wire }
        assertTrue(autonomous.any { it.activation })
        assertTrue(records.any { it.condition == MRXCondition.BASELINE.wire && !it.activation })
        println("formal records=${records.size} autonomousActive=${autonomous.count { it.activation }}")
        autonomous.forEach {
            println("${it.world} base=${it.baseStatus} mismatch=${it.baseMismatch} chosen=${it.chosenAction} " +
                "expected=${it.expected} held=${it.heldoutCorrect}/${it.heldoutTotal} act=${it.activation} diag=${it.diagnosis}")
        }
    }
}
