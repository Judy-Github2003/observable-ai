// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02B 生命周期与 G0/G1/G2 截断归因；验证剪枝原因可追踪。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertTrue

class MRX2BLifecycleTest {
    @Test
    fun `lifecycle records generation retention and cap prune`() {
        val world = { m: Map<String, String> -> (m["a"] == "1") != (m["b"] == "1") }
        val training = C8Domain.all().filterIndexed { i, _ -> i % 4 == 0 }.mapIndexed { i, m -> StudyCase("l-$i", m, world(m)) }
        val full = C8TestBases.bases.library("FULL")
        val result = MRX2BSpace(
            C8Domain.attributes, full.definitions, training, MRX2BStrategy.PAIRWISE,
            maxCandidates = 8_000, consistentLimit = 1,
        ).generate()
        val trace = result.trace
        println("gen=${trace.size(MRX2BStage.GENERATED)} consistent=${trace.size(MRX2BStage.TRAIN_CONSISTENT)} " +
            "retained=${trace.size(MRX2BStage.RETAINED)} prunes=${trace.prunes.size} " +
            "reasons=${trace.prunes.map { it.reason }.distinct()}")
        assertTrue(trace.size(MRX2BStage.GENERATED) > trace.size(MRX2BStage.RETAINED))
        assertTrue(trace.prunes.any { it.reason == "consistent_cap_1" })
        assertTrue(trace.candidates(MRX2BStage.RETAINED).size == 1)
        assertTrue(trace.candidates(MRX2BStage.SELECTED).size == 1)
    }

    @Test
    fun `no witness is undetermined not not representable`() {
        val full = C8TestBases.bases.library("FULL")
        val empty = MRX2BTrace()
        val audit = MRX2BAuditor.auditAfterExam(
            empty, C8Domain.all(), C8Domain.all(), { true }, full.definitions, null, 8, grammarExhaustive = false,
        )
        assertTrue(audit.g0 == "UNDETERMINED")
        assertTrue(!audit.g4SelectedCorrect)
    }
}
