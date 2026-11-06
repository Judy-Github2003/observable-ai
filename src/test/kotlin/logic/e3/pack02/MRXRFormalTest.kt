// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 冻结后的六条件正式考试；先校验 SHA，再运行并写 JSON。
package logic.e3.pack02

import logic.capability.sha256
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRXRFormalTest {
    @Test
    fun `frozen six-condition MRX-01R exam`() {
        val manifestPath = Path.of("experiments/representation-evolution-e3/reports/mrx-01r-freeze.json")
        assertTrue(Files.exists(manifestPath))
        val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        manifest["sources"]!!.jsonObject.forEach { (_, value) ->
            val source = value.jsonObject
            val path = Path.of(source["path"]!!.jsonPrimitive.content)
            assertEquals(source["sha256"]!!.jsonPrimitive.content, sha256(Files.readString(path)), path.toString())
        }
        val records = MRXRExam.runFormal()
        val out = Path.of("experiments/representation-evolution-e3/reports/mrx-01r-formal.json")
        MRXRExam.write(records, out)
        assertTrue(Files.size(out) > 5000)
        assertEquals(120, records.size)
        for (condition in MRXRCondition.entries) {
            assertEquals(MRXRWorlds.formal.size, records.count { it.condition == condition.wire })
        }
        assertTrue(records.none { it.activation && it.heldoutCorrect < it.heldoutTotal })
        assertTrue(records.any { it.activation })
        for (condition in listOf("FIXED", "EVIDENCE", "VALUE")) {
            assertTrue(records.filter { it.condition == condition }.sumOf { it.evidenceEntries } > 0)
        }
        records.groupBy { it.condition }.forEach { (condition, group) ->
            println("$condition runs=${group.size} active=${group.count { it.activation }} " +
                "queries=${group.sumOf { it.queries }} ev=${group.sumOf { it.evidenceEntries }}/${group.sumOf { it.evidenceReused }}")
        }
    }
}
