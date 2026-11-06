// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 冻结基线运行；写 JSON 并断言实验完整性，不修改冻结学生。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRXBaselineTest {
    @Test
    fun `frozen C8-03 baseline over new worlds`() {
        val records = MRXBaseline.run()
        val out = Path.of("experiments/representation-evolution-e3/reports/mrx-01-baseline.json")
        MRXBaseline.write(records, out)
        assertTrue(Files.size(out) > 1000)
        assertTrue(MRXWorlds.baseline.size >= 20)
        assertEquals(MRXWorlds.baseline.size * 2, records.size)
        assertTrue(records.all { it.heldoutTotal == 27 })
        assertTrue(records.none { it.activation && it.heldoutCorrect < it.heldoutTotal })
        assertTrue(MRXWorlds.baseline.map { it.category }.toSet().size >= 6)
        assertTrue(records.any { it.activation })
        assertTrue(records.any { it.status == "NO_CANDIDATE" || it.status == "SEARCH_LIMITED" })
    }
}
