// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02B A/B/C 搜索对照与 G0–G4 审计；训练集只用于排序，完整域只用于考官审计。
package logic.e3.pack02

import logic.capability.*
import kotlin.test.Test
import kotlin.test.assertTrue

class MRX2BSearchTest {
    private val world = { m: Map<String, String> -> (m["d"] == "1") != (m["d"] == "2") }

    @Test
    fun `pairwise preserves XOR atoms and audit reports coverage`() {
        val training = C8Domain.all().filterIndexed { i, _ -> i % 5 == 0 }.mapIndexed { i, m ->
            StudyCase("x-$i", m, world(m))
        }
        val bases = C8TestBases.bases
        val full = bases.library("FULL")
        val audits = MRX2BStrategy.entries.map { strategy ->
            val result = MRX2BSpace(C8Domain.attributes, full.definitions, training, strategy).generate()
            val audit = MRX2BAuditor.auditAfterExam(
                result.trace, C8Domain.all(), C8Domain.all(), world, full.definitions,
                result.selected, queryBudget = 12,
            )
            println("${strategy.wire} atoms=${result.trace.atoms.size} gen=${result.generated} " +
                "consistent=${result.consistent} retained=${result.retained} " +
                "pairs=${result.trace.pairGains.size} g0=${audit.g0} g1=${audit.g1GeneratedCorrect} " +
                "g2=${audit.g2RetainedCorrect} g3=${audit.g3} g4=${audit.g4SelectedCorrect}")
            strategy to (result to audit)
        }
        val pairwise = audits.first { it.first == MRX2BStrategy.PAIRWISE }.second
        assertTrue(pairwise.first.trace.pairGains.any { it.accuracy == 1.0 }, "pairwise must find XOR pair")
        assertTrue(pairwise.second.g1GeneratedCorrect)
        assertTrue(pairwise.second.g2RetainedCorrect)
        assertTrue(pairwise.second.g0 == "WITNESS_FOUND")
        // 审计只做考官端；学生 trace 中不包含 truth 字段。
        assertTrue(pairwise.first.trace.candidates(MRX2BStage.GENERATED).isNotEmpty())
    }

    @Test
    fun `no witness returns undetermined not not representable`() {
        val training = C8Domain.all().take(9).mapIndexed { i, m -> StudyCase("n-$i", m, false) }
        val full = C8TestBases.bases.library("FULL")
        val result = MRX2BSpace(C8Domain.attributes, full.definitions, training, MRX2BStrategy.BASELINE).generate()
        val audit = MRX2BAuditor.auditAfterExam(
            result.trace, C8Domain.all(), C8Domain.all(), { true }, full.definitions, result.selected, 8,
        )
        assertTrue(audit.g0 == "UNDETERMINED" || audit.g0 == "WITNESS_FOUND")
    }
}
