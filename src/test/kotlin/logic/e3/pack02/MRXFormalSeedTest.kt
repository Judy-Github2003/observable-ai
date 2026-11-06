// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 正式世界的数据/种子预注册检查；不运行学生。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertTrue

class MRXFormalSeedTest {
    @Test
    fun `formal worlds have frozen seeds and coverage`() {
        MRXWorlds.formal.forEachIndexed { index, world ->
            val (seed, split) = MRXWorlds.findSeed(world, from = 7100 + index * 137)
            println("${world.id} seed=$seed digest=${split.digest} train=${split.train.size} query=${split.query.size} challenge=${split.challenge.size} heldout=${split.heldout.size}")
            assertTrue(MRXWorlds.coverage(world, split).isEmpty())
            if (world.ambiguousException != null) assertTrue(world.ambiguousException in split.heldout)
        }
    }
}
