// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 正式世界种子/覆盖率预注册检查；不运行学生。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertTrue

class MRXRSeedTest {
    @Test
    fun `formal worlds have coverage and frozen seed rule`() {
        assertTrue(MRXRWorlds.formal.size >= 20)
        MRXRWorlds.formal.forEachIndexed { index, world ->
            val (seed, split) = MRXRWorlds.findSeed(world, from = 8100 + index * 151)
            println("${world.id} seed=$seed train=${split.train.size} query=${split.query.size} challenge=${split.challenge.size} held=${split.heldout.size}")
            assertTrue(MRXWorlds.coverage(world, split).isEmpty(), "${world.id}: ${MRXWorlds.coverage(world, split)}")
            if (world.ambiguousException != null) assertTrue(world.ambiguousException in split.heldout)
        }
    }
}
