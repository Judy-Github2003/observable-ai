// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02C 主动辨识：保留候选后从合法池选查询，并检查 G4 是否修复。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertTrue

class MRX2CActiveTest {
    private fun run(world: (Map<String, String>) -> Boolean, strategy: MRX2BStrategy): MRX2CIdentification {
        val full = C8TestBases.bases.library("FULL")
        val training = C8Domain.all().filterIndexed { i, _ -> i % 5 == 0 }
            .mapIndexed { i, m -> StudyCase("c-$i", m, world(m)) }
        val space = MRX2BSpace(C8Domain.attributes, full.definitions, training, strategy).generate()
        val retained = space.trace.candidates(MRX2BStage.RETAINED)
        val trainingKeys = training.map { C8Domain.key(it.values) }.toSet()
        val legal = C8Domain.all().filter { C8Domain.key(it) !in trainingKeys }.take(40)
        val cost = MRX2Cost()
        val ledger = MRX2Ledger(legal, StudyQueryController(legal, 16, world), 16, cost)
        val identifier = MRX2CActiveIdentifier(
            legal, ledger,
            { candidate, input -> Pack02Knowledge.predict(candidate.body, input, full.definitions) },
            cost,
        )
        val result = identifier.identify(retained, 16)
        println("${strategy.wire} retained=${retained.size} status=${result.status} classes=${result.initialClasses}->${result.remainingClasses} " +
            "q=${result.queries} selected=${result.selected?.canonical?.take(80)}")
        val selectedHeldout = result.selected?.let { c ->
            C8Domain.all().count { input -> Pack02Knowledge.predict(c.body, input, full.definitions) == world(input) }
        }
        println("  selectedFullDomain=$selectedHeldout/81 witnessG4=${selectedHeldout == 81}")
        return result
    }

    @Test
    fun `W4 retained candidates can be actively identified`() {
        val world = { m: Map<String, String> ->
            if (m["a"] == "1" && m["c"] == "1") m["d"] == "1" else m["d"] == "0"
        }
        val result = run(world, MRX2BStrategy.PAIRWISE)
        assertTrue(result.initialCandidates > 1)
        assertTrue(result.status in setOf("IDENTIFIED_ON_OBSERVABLE_POOL", "CURRENTLY_UNIDENTIFIABLE", "BUDGET_EXHAUSTED"))
    }

    @Test
    fun `independent XOR is not reducible to one attribute`() {
        val world = { m: Map<String, String> -> (m["a"] == "1") != (m["d"] == "2") }
        val result = run(world, MRX2BStrategy.PAIRWISE)
        assertTrue(result.initialCandidates > 1)
    }
}
