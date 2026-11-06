// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02C 协议分割下的主动辨识；legal 只含 QUERY+CHALLENGE，不含 HELDOUT。
package logic.e3.pack02

import logic.capability.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MRX2CProtocolTest {
    private fun run(world: MRXWorld, seed: Int): Pair<MRX2CIdentification, Int> {
        val full = C8TestBases.bases.library("FULL")
        val split = MRXWorlds.splitFor(world, seed)
        val training = split.train.mapIndexed { i, m -> StudyCase("p-$i", m, world.truth(m)) }
        val space = MRX2BSpace(C8Domain.attributes, full.definitions, training, MRX2BStrategy.PAIRWISE).generate()
        val retained = space.trace.candidates(MRX2BStage.RETAINED)
        val legal = split.query + split.challenge
        val cost = MRX2Cost()
        val ledger = MRX2Ledger(legal, StudyQueryController(legal, 16, world.truth), 16, cost)
        val identifier = MRX2CActiveIdentifier(
            legal, ledger,
            { candidate, input -> Pack02Knowledge.predict(candidate.body, input, full.definitions) },
            cost,
        )
        val result = identifier.identify(retained, 16)
        val held = result.selected?.let { c ->
            split.heldout.count { input -> Pack02Knowledge.predict(c.body, input, full.definitions) == world.truth(input) }
        } ?: 0
        println("${world.id} retained=${retained.size} status=${result.status} classes=${result.initialClasses}->${result.remainingClasses} " +
            "q=${result.queries} held=$held/${split.heldout.size} deps=${result.selected?.dependencyIds}")
        return result to held
    }

    @Test
    fun `W4 protocol split active identification`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val (result, held) = run(world, 1449)
        assertEquals(27, held)
        assertTrue(result.status == "IDENTIFIED_ON_OBSERVABLE_POOL")
    }

    @Test
    fun `independent xor protocol split active identification`() {
        val world = MRXWorld(
            "mrx2c-xor-a-d", MRXCategory.DEPTH, "a1 XOR d2",
            { m -> (m["a"] == "1") != (m["d"] == "2") }, expectedUpgrades = setOf("DNF"),
        )
        val (result, held) = run(world, 1449)
        // 合法 QUERY+CHALLENGE 上唯一，不等于 HELDOUT 全域正确；此处保留失败证据。
        assertTrue(result.status == "IDENTIFIED_ON_OBSERVABLE_POOL")
        assertTrue(held < 27)
    }
}
