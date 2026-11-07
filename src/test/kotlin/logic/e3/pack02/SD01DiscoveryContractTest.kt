// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-01 真实候选发现/挑战验证的 RED→GREEN 契约。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SD01DiscoveryContractTest {
    private fun cases(split: MRXSplit, world: MRXWorld, prefix: String) =
        split.train.mapIndexed { i, m -> StudyCase("$prefix-$i", m, world.truth(m)) }

    @Test
    fun `real W4 candidate is discovered and challenge-validated`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val split = MRXWorlds.splitFor(world, 1449)
        val full = C8TestBases.bases.library("FULL")
        val run = SD01Discovery.discover(
            C8Domain.attributes, full.definitions, cases(split, world, "w4-train"),
            split.query, split.challenge, world.truth,
        )
        println("W4 generated=${run.generated} retained=${run.retained} candidates=${run.candidates.size} " +
            "structure=${run.structure?.kind} checks=${run.structure?.let { "present" }}")
        assertTrue(run.generated > 0)
        assertNotNull(run.structure)
        assertEquals("PROGRAM_INSTANCE", run.structure!!.kind)
        val passing = run.candidates.first { it.status == "CHALLENGE_PASS" }
        assertEquals(16, passing.challengeChecks)
        assertEquals(0, passing.challengeCounterexamples)
    }

    @Test
    fun `independent XOR is an honest negative or limited result`() {
        val world = MRXWorld(
            "sd01-xor", MRXCategory.DEPTH, "independent attributes",
            { m -> (m["a"] == "1") != (m["d"] == "2") },
        )
        val split = MRXWorlds.splitFor(world, 1449)
        val full = C8TestBases.bases.library("FULL")
        val run = SD01Discovery.discover(
            C8Domain.attributes, full.definitions, cases(split, world, "xor-train"),
            split.query, split.challenge, world.truth,
        )
        println("XOR generated=${run.generated} retained=${run.retained} candidates=${run.candidates.size} structure=${run.structure?.structureId?.take(80)}")
        assertTrue(run.generated > 0)
        // 没有正确候选时允许 null；不得为了通过测试硬编码 XOR。
    }

    @Test
    fun `closed program instance is not passed off as reusable macro`() {
        val w4 = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val split = MRXWorlds.splitFor(w4, 1449)
        val full = C8TestBases.bases.library("FULL")
        val run = SD01Discovery.discover(
            C8Domain.attributes, full.definitions, cases(split, w4, "transfer-train"),
            split.query, split.challenge, w4.truth,
        )
        assertNotNull(run.structure)
        assertTrue(run.structure!!.parameterTypes.isEmpty())
        assertEquals("PROGRAM_INSTANCE", run.structure!!.kind)
    }
}
