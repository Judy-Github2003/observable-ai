// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 W4/负例开发证据写入 JSON，并断言实际运行结果。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRXDevelopmentTest {
    @Test
    fun `W4 and negative development worlds`() {
        val outcomes = MRXDevelopment.run()
        MRXDevelopment.write(outcomes, Path.of("experiments/representation-evolution-e3/reports/mrx-01-development.json"))
        assertTrue(Files.size(Path.of("experiments/representation-evolution-e3/reports/mrx-01-development.json")) > 500)
        val w4 = outcomes.first { it.world == "mrx-depth-w4" }
        assertTrue(w4.chosenAction in setOf("DNF", "IF"))
        assertEquals(27, w4.heldoutCorrect)
        assertTrue(w4.activation)
        val ambig = outcomes.first { it.world == "mrx-ambig-1" }
        assertEquals("NONE", ambig.chosenAction)
        assertEquals(26, ambig.heldoutCorrect)
        assertTrue(!ambig.activation)
        val missing = outcomes.first { it.world == "mrx-miss-or-r-d1" }
        assertTrue(missing.chosenAction in setOf("KNOWLEDGE", "DNF"))
        assertTrue(missing.activation)
        val noise = outcomes.first { it.world == "mrx-noise-or-r-d1" }
        assertEquals("NO_ACTION", noise.chosenAction)
        assertTrue(!noise.activation)
    }
}
