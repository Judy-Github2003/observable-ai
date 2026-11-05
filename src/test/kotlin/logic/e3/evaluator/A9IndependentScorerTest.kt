// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 独立评分入口测试/原始输出；隐藏真值只在评测端。
package logic.e3.evaluator

import logic.capability.*
import logic.e3.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A9IndependentScorerTest {

    private fun world(): EvaluatedWorld = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)

    private fun boundedSubmission(world: EvaluatedWorld, claimGlobal: Boolean = false, causal: Set<A9CausalClaim> = emptySet()): A9StudentSubmission {
        val result = E3A9BaselineRunner().run(
            A9BaselineId.B0_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
        )
        return result.toSubmission(world.public.queryPool.map { it.visible }, causal)
            .copy(claimedGlobal = claimGlobal)
    }

    @Test
    fun `independent scorer evaluates frozen candidate and hidden error`() {
        val world = world()
        val scorer = A9IndependentScorer(world.truth)
        val result = scorer.evaluate(world.public.schema, world.public.observations, boundedSubmission(world))
        assertEquals(A9EvaluationStatus.EVALUATED, result.status)
        assertEquals(true, result.boundedIdentificationValidity)
        assertEquals(0.0, result.heldoutPredictionError)
        assertFalse(result.epistemicOverclaim)
    }

    @Test
    fun `valid bounded claim with hidden error is not overclaim`() {
        val world = world()
        val inverted = world.truth.copy(heldOut = world.truth.heldOut.map { it.copy(label = !it.label) })
        val result = A9IndependentScorer(inverted).evaluate(
            world.public.schema, world.public.observations, boundedSubmission(world),
        )
        assertTrue(result.boundedIdentificationValidity!!)
        assertFalse(result.epistemicOverclaim)
        assertTrue(result.heldoutPredictionError!! > 0.0)
    }

    @Test
    fun `one candidate matching oracle is not bounded identification if other classes remain`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val record = E3A9BaselineRunner().run(
            A9BaselineId.B0_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
        )
        val frozen = record.searchRecord!!.consistentCandidates.first()
        val submission = A9StudentSubmission(
            A9BaselineId.B0_ONLY, A9DiagnosticClaim.BOUNDED_IDENTIFIED,
            listOf(frozen.body), listOf(frozen.canonical), record.boundedScope,
            world.public.queryPool.map { it.visible }, record.evidenceIds, record.searchRecord,
        )
        val result = A9IndependentScorer(world.truth).evaluate(
            world.public.schema, world.public.observations, submission,
        )
        assertTrue(result.behaviorClassCount!! > 1)
        assertFalse(result.boundedIdentificationValidity!!)
        assertFalse(result.epistemicOverclaim)
    }

    @Test
    fun `latent truth conflict is preserved instead of overwritten`() {
        val world = world()
        val query = world.public.queryPool.first().visible
        val original = world.truth.fullDomain.first { it.visible == query }
        val conflicted = world.truth.copy(fullDomain = world.truth.fullDomain + original.copy(label = !original.label))
        val result = A9IndependentScorer(conflicted).evaluate(
            world.public.schema, world.public.observations, boundedSubmission(world),
        )
        assertEquals(A9EvaluationStatus.EVALUATED, result.status)
        assertTrue(result.truthConflictDetected)
        assertNull(result.queryTruthAgreement)
    }

    @Test
    fun `structural overclaim does not rely on single claimedGlobal`() {
        val world = world()
        val result = A9IndependentScorer(world.truth).evaluate(
            world.public.schema,
            world.public.observations,
            boundedSubmission(world, causal = setOf(A9CausalClaim.HIDDEN_ATTRIBUTE, A9CausalClaim.NOISE)),
        )
        assertTrue(result.epistemicOverclaim)
        assertTrue(result.overclaimReasons.any { it.contains("HIDDEN_ATTRIBUTE") })
        assertTrue(result.overclaimReasons.any { it.contains("NOISE") })
    }

    @Test
    fun `missing candidate or heldout returns not evaluated without zero fill`() {
        val world = world()
        val scorer = A9IndependentScorer(world.truth)
        val noCandidate = boundedSubmission(world).copy(candidateBodies = emptyList(), candidateCanonicals = emptyList())
        val a = scorer.evaluate(world.public.schema, world.public.observations, noCandidate)
        assertEquals(A9EvaluationStatus.NOT_EVALUATED, a.status)
        assertNull(a.boundedIdentificationValidity)
        assertNull(a.heldoutPredictionError)

        val noHeldout = A9IndependentScorer(world.truth.copy(heldOut = emptyList()))
            .evaluate(world.public.schema, world.public.observations, boundedSubmission(world))
        assertEquals(A9EvaluationStatus.NOT_EVALUATED, noHeldout.status)
        assertEquals("no_heldout_points", noHeldout.notEvaluatedReason)
        assertNull(noHeldout.heldoutPredictionError)
    }

    @Test
    fun `candidate hash mismatch is not evaluated`() {
        val world = world()
        val original = boundedSubmission(world)
        val broken = original.copy(
            candidateBodies = listOf(Expr.Const(CapValue.Bool(false))),
            candidateCanonicals = original.candidateCanonicals,
        )
        val result = A9IndependentScorer(world.truth).evaluate(world.public.schema, world.public.observations, broken)
        assertEquals(A9EvaluationStatus.NOT_EVALUATED, result.status)
        assertEquals("selected_candidate_hash_mismatch", result.notEvaluatedReason)
    }

    @Test
    fun `export independent scoring examples`() {
        val world = world()
        val scorer = A9IndependentScorer(world.truth)
        val submissions = listOf(
            "valid" to boundedSubmission(world),
            "global_overclaim" to boundedSubmission(world, claimGlobal = true),
            "causal_overclaim" to boundedSubmission(world, causal = setOf(A9CausalClaim.HIDDEN_ATTRIBUTE)),
            "no_candidate" to boundedSubmission(world).copy(candidateBodies = emptyList(), candidateCanonicals = emptyList()),
        )
        val rows = submissions.map { (name, submission) ->
            val result = scorer.evaluate(world.public.schema, world.public.observations, submission)
            name to result
        }.toMutableList()
        val queryVisible = world.public.queryPool.first().visible
        val originalTruth = world.truth.fullDomain.first { it.visible == queryVisible }
        val conflictedTruth = world.truth.copy(
            fullDomain = world.truth.fullDomain + originalTruth.copy(label = !originalTruth.label),
        )
        rows += "latent_truth_conflict" to A9IndependentScorer(conflictedTruth).evaluate(
            world.public.schema, world.public.observations, boundedSubmission(world),
        )
        val out = Path.of("experiments/representation-evolution-e3/reports/a9-independent-scoring.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows.map { (name, result) ->
            buildJsonObject {
                put("case", name)
                put("status", result.status.name)
                if (result.boundedIdentificationValidity == null) put("boundedIdentificationValidity", JsonNull) else put("boundedIdentificationValidity", result.boundedIdentificationValidity)
                if (result.heldoutPredictionError == null) put("heldoutPredictionError", JsonNull) else put("heldoutPredictionError", result.heldoutPredictionError)
                if (result.queryTruthAgreement == null) put("queryTruthAgreement", JsonNull) else put("queryTruthAgreement", result.queryTruthAgreement)
                if (result.behaviorClassCount == null) put("behaviorClassCount", JsonNull) else put("behaviorClassCount", result.behaviorClassCount)
                if (result.consistentCandidateCount == null) put("consistentCandidateCount", JsonNull) else put("consistentCandidateCount", result.consistentCandidateCount)
                put("truthConflictDetected", result.truthConflictDetected)
                put("epistemicOverclaim", result.epistemicOverclaim)
                put("overclaimReasons", buildJsonArray { result.overclaimReasons.forEach { add(it) } })
                put("evaluatedQueryPoints", result.evaluatedQueryPoints)
                put("evaluatedHeldoutPoints", result.evaluatedHeldoutPoints)
                if (result.notEvaluatedReason == null) put("notEvaluatedReason", JsonNull) else put("notEvaluatedReason", result.notEvaluatedReason)
            }
        })) + "\n")
    }
}
