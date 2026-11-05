// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A6 预算诊断固定种子断言；要求真实候选/预算/等价类，而非仅不崩溃。
package logic.e3

import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.WorldFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(result.runs.all { it.behaviorClasses.size >= 2 })
        assertTrue(result.runs.all { it.signal.filter!!.consistentCount >= it.signal.filter!!.survivorCount })
    }

    @Test
    fun `behavior classes use the run eval budget and candidate layers are preserved`() {
        val result = run(WorldFamily.SOLVABLE, 101L)
        for (run in result.runs) {
            assertEquals(run.budget.maxSteps, run.behaviorEvalMaxSteps)
            assertEquals(run.signal.composedCandidateHashes.size, run.layers.composerGenerated)
            assertEquals(run.signal.consistentCandidateHashes.size, run.layers.consistentWithEvidence)
            assertEquals(run.signal.filter?.survivorCount ?: 0, run.layers.filterRepresentatives)
            assertTrue(run.layers.consistentWithEvidence >= run.layers.filterRepresentatives)
        }
    }

    @Test
    fun `empty query domain is training-only not independent identification`() {
        val world = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)
        val signal = E3B0DiagnosticAdapter().diagnose(
            world.public.schema,
            world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            emptyList(),
        )
        assertEquals(DiagnosticSignalStatus.TRAINING_ONLY_CONSISTENCY, signal.status)
        assertTrue(signal.notes.any { it.contains("query_domain_empty_training_only") })
        assertTrue(signal.boundedScope!!.queryDomain!!.contains("no independent query domain"))
    }

    @Test
    fun `no consistent candidate verdict is reachable`() {
        val base = run(WorldFamily.SEARCH_LIMITED, 107L)
        val runs = base.runs.map { run ->
            run.copy(
                signal = run.signal.copy(
                    status = DiagnosticSignalStatus.REFUSED,
                    filter = FilterTrace("REFUSED", "no_consistent_candidate", 0, 0, 0, 0),
                    survivorHashes = emptyList(),
                    consistentCandidateHashes = emptyList(),
                    consistentCandidateBodies = emptyList(),
                ),
                behaviorClasses = emptyList(),
                candidateEvidenceState = CandidateEvidenceState.RUN_WITH_ZERO_SURVIVORS,
            )
        }
        val facts = BudgetFacts(
            budgetSensitive = false,
            multipleBehaviorClasses = false,
            noConsistentCandidate = true,
            searchTruncated = false,
            reasonUndetermined = false,
            observationCollision = false,
        )
        assertEquals(BudgetVerdict.NO_CONSISTENT_CANDIDATE, diagnoser.classifyVerdict(runs, facts))
    }

    @Test
    fun `collision is not run and is not classified as zero consistent`() {
        val result = run(WorldFamily.LATENT_ALIASING, 103L)
        assertFalse(result.facts.noConsistentCandidate)
        assertTrue(result.runs.all { it.candidateEvidenceState == CandidateEvidenceState.NOT_RUN })
        assertEquals(BudgetVerdict.OBSERVATION_COLLISION, result.verdict)
    }

    @Test
    fun `truncated search is distinguished from zero survivors`() {
        val result = run(WorldFamily.SEARCH_LIMITED, 107L)
        assertEquals(CandidateEvidenceState.SEARCH_TRUNCATED, result.runs[0].candidateEvidenceState)
        assertFalse(result.facts.noConsistentCandidate)
    }

    @Test
    fun `two truncated searches are not classified as no consistent candidate`() {
        val base = run(WorldFamily.SEARCH_LIMITED, 107L)
        val runs = base.runs.map { run ->
            run.copy(
                signal = run.signal.copy(
                    status = DiagnosticSignalStatus.REFUSED,
                    filter = FilterTrace("REFUSED", "no_consistent_candidate", 0, 0, 0, 0),
                    survivorHashes = emptyList(),
                    consistentCandidateHashes = emptyList(),
                    consistentCandidateBodies = emptyList(),
                ),
                behaviorClasses = emptyList(),
                candidateEvidenceState = CandidateEvidenceState.SEARCH_TRUNCATED,
            )
        }
        val facts = BudgetFacts(false, false, false, true, false, false)
        assertEquals(BudgetVerdict.SEARCH_TRUNCATED_UNDETERMINED, diagnoser.classifyVerdict(runs, facts))
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
