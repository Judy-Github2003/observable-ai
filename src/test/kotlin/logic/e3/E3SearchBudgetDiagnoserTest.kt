// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A6 预算诊断固定种子断言；要求真实候选/预算/等价类，而非仅不崩溃。
package logic.e3

import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.WorldFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E3SearchBudgetDiagnoserTest {

    private val diagnoser = E3SearchBudgetDiagnoser()
    private val low = DiagnosticBudget(maxComposeRounds = 2, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000)
    private val high = DiagnosticBudget(maxComposeRounds = 3, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000)

    private fun run(family: WorldFamily, seed: Long): BudgetDiagnosis {
        val world = E3WorldGenerator(seed).generate(family, 0)
        return diagnoser.diagnose(
            world.public.schema,
            world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
            low,
            high,
        )
    }

    @Test
    fun `solvable shows budget sensitive new candidate without truth claim`() {
        val result = run(WorldFamily.SOLVABLE, 101L)
        assertEquals(BudgetVerdict.BUDGET_SENSITIVE_CANDIDATE_SET, result.verdict)
        assertEquals(DiagnosticSignalStatus.REFUSED, result.runs[0].signal.status)
        assertEquals(DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE, result.runs[1].signal.status)
        assertEquals(0, result.runs[0].signal.survivorHashes.size)
        assertEquals(1, result.runs[1].signal.survivorHashes.size)
        assertTrue(result.notes.any { it.contains("not truth identification") })
        assertEquals("depth_limit", result.runs[1].signal.search!!.reason)
    }

    @Test
    fun `search limited remains budget sensitive with multiple classes at high budget`() {
        val result = run(WorldFamily.SEARCH_LIMITED, 107L)
        assertEquals(BudgetVerdict.BUDGET_SENSITIVE_CANDIDATE_SET, result.verdict)
        assertEquals(DiagnosticSignalStatus.REFUSED, result.runs[0].signal.status)
        assertEquals(DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE, result.runs[1].signal.status)
        assertTrue(result.runs[1].behaviorClasses.size >= 2)
        assertTrue(result.runs[1].signal.survivorHashes.isNotEmpty())
        assertTrue(result.notes.any { it.contains("does_not_prove_representation_insufficiency") })
    }

    @Test
    fun `ambiguous remains multiple candidates at both budgets`() {
        val result = run(WorldFamily.AMBIGUOUS_EVIDENCE, 109L)
        assertEquals(BudgetVerdict.MULTIPLE_CANDIDATES_REMAIN, result.verdict)
        assertTrue(result.runs.all { it.signal.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE })
        assertTrue(result.runs.all { it.behaviorClasses.size == 2 })
        assertTrue(result.runs.all { it.signal.filter!!.survivorCount >= 2 })
    }

    @Test
    fun `latent collision is not searched at either budget`() {
        val result = run(WorldFamily.LATENT_ALIASING, 103L)
        assertEquals(BudgetVerdict.OBSERVATION_COLLISION, result.verdict)
        assertTrue(result.runs.all { it.signal.status == DiagnosticSignalStatus.OBSERVATION_COLLISION })
        assertTrue(result.runs.all { it.signal.search == null && it.signal.filter == null })
        assertTrue(result.runs[0].signal.collisionReport.findings.isNotEmpty())
    }

    @Test
    fun `high budget failure is not reported as representation insufficiency`() {
        val result = run(WorldFamily.AMBIGUOUS_EVIDENCE, 109L)
        assertTrue(result.runs.all { it.signal.search?.reason == "depth_limit" })
        assertTrue(result.notes.any { it.contains("does_not_prove_representation_insufficiency") })
        assertTrue(result.scope!!.queryDomain!!.contains("evaluated"))
    }
}
