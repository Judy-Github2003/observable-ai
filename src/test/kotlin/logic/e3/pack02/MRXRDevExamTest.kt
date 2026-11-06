// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 六条件 runner 开发自检；只用 dev 世界，不碰正式世界。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRXRDevExamTest {
    @Test
    fun `six conditions run on dev worlds`() {
        val records = MRXRExam.run(MRXRWorlds.dev, MRXRWorlds.devSeeds)
        val out = Path.of("experiments/representation-evolution-e3/reports/mrx-01r-dev.json")
        MRXRExam.write(records, out)
        assertTrue(Files.size(out) > 1000)
        assertEquals(MRXRWorlds.dev.size * MRXRCondition.entries.size, records.size)
        assertTrue(records.any { it.condition == "C8-03" })
        assertTrue(records.any { it.condition == "EVIDENCE" })
        assertTrue(records.none { it.activation && it.heldoutCorrect < it.heldoutTotal })
        records.forEach {
            println("${it.world} ${it.condition} action=${it.chosenAction} held=${it.heldoutCorrect}/${it.heldoutTotal} " +
                "act=${it.activation} q=${it.queries} ev=${it.evidenceEntries}/${it.evidenceReused} diag=${it.diagnosis}")
        }
    }
}
