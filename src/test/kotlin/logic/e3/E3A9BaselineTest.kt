// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 基线/预算/评分 smoke 测试；不做 A10 批量。
package logic.e3

import logic.e3.evaluator.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E3A9BaselineTest {

    private fun gateway(world: EvaluatedWorld): PublicProbeGateway {
        val byId = world.public.queryPool.associateBy { it.sampleId }
        val oracle = world.publicQuery
        return object : PublicProbeGateway {
            override val allowedQueryIds = oracle.allowedIds
            override fun visibleOf(sampleId: String) = byId[sampleId]?.visible
            override fun requestPublicInstance(sampleId: String): ProbeObservation {
                val point = byId[sampleId]
                return when (val a = oracle.query(sampleId)) {
                    is PublicQueryAnswer.Labeled -> ProbeObservation(sampleId, "query-$sampleId", point?.visible, a.label, "student-query")
                    is PublicQueryAnswer.Rejected -> ProbeObservation(sampleId, null, point?.visible, null, "student-query", a.reason)
                }
            }
            override fun repeatLabel(objectId: String, sourceId: String): ProbeObservation? {
                val original = world.public.observations.firstOrNull { it.objectId == objectId } ?: return null
                return ProbeObservation(null, objectId, original.visible.mapValues { (_, v) -> (v as ObservedValue.Known).value },
                    original.label, "repeat-$sourceId", measurementKind = MeasurementKind.REPLAYED_LABEL)
            }
        }
    }

    private fun runner(): E3A9BaselineRunner = E3A9BaselineRunner()

    @Test
    fun `all baselines run on public worlds and never claim global truth`() {
        val worlds = listOf(
            "solvable" to E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0),
            "latent" to E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0),
            "ambiguous" to E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0),
        )
        val rows = ArrayList<JsonObject>()
        for ((name, world) in worlds) {
            for (baseline in A9BaselineId.entries) {
                val result = runner().run(
                    baseline, world.public.schema, world.public.observations,
                    PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
                    world.public.queryPool.map { it.visible }, gateway(world),
                )
                assertFalse(result.claimedGlobal)
                rows += buildJsonObject {
                    put("case", name)
                    put("baseline", baseline.name)
                    put("diagnosticClaim", result.diagnosticClaim.name)
                    put("action", result.action?.actionKind?.name)
                    put("requestedSampleId", result.action?.requestedSampleId)
                    put("repeatedObjectId", result.action?.repeatedObjectId)
                    put("recordedObservationId", result.action?.recordedObservationId)
                    put("measurementKind", result.action?.measurementKind?.name)
                    put("independentEvidence", result.action?.independentEvidence ?: false)
                    put("actualCost", result.action?.actualCost ?: 0)
                    put("composerCalls", result.computation.composerCalls)
                    put("generatedCandidates", result.computation.generatedCandidates)
                    put("filterCandidateEvaluations", result.computation.filterCandidateEvaluations)
                    put("interpreterExecutions", result.computation.interpreterExecutions)
                    put("publicQueries", result.computation.publicQueries)
                    put("queryCost", result.computation.queryCost)
                    put("elapsedNanos", result.computation.elapsedNanos)
                    put("evidenceCount", result.evidenceIds.size)
                    put("claimedGlobal", result.claimedGlobal)
                    put("notes", buildJsonArray { result.notes.forEach { add(it) } })
                }
                if (baseline == A9BaselineId.ALWAYS_UNDETERMINED) {
                    assertEquals(A9DiagnosticClaim.UNDETERMINED, result.diagnosticClaim)
                    assertEquals(0, result.computation.composerCalls)
                    assertFalse(A9Scorer.score(result, A9CaseTruth(null, null)).diagnosticCoverage)
                }
            }
        }
        assertEquals(15, rows.size)
        val out = Path.of("experiments/representation-evolution-e3/reports/a9-baseline-smoke.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n")
    }

    @Test
    fun `same single budget can have different cumulative composer work`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val args = arrayOf(world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible }, gateway(world))
        val b0 = runner().run(A9BaselineId.B0_ONLY, args[0] as VisibleSchema, args[1] as List<Observation>, args[2] as PublicConstants, args[3] as List<Map<String, String>>, args[4] as PublicProbeGateway)
        val e3 = runner().run(A9BaselineId.E3_DIAGNOSTIC, args[0] as VisibleSchema, args[1] as List<Observation>, args[2] as PublicConstants, args[3] as List<Map<String, String>>, args[4] as PublicProbeGateway)
        assertTrue(e3.computation.composerCalls > b0.computation.composerCalls)
        assertTrue(e3.computation.generatedCandidates > b0.computation.generatedCandidates)
        assertTrue(e3.computation.filterCandidateEvaluations > b0.computation.filterCandidateEvaluations)
    }

    @Test
    fun `bounded claim remains valid even when heldout prediction is wrong`() {
        val result = A9BaselineResult(
            A9BaselineId.B0_ONLY, A9DiagnosticClaim.BOUNDED_IDENTIFIED, null, emptyList(), null,
            E3UsageSnapshot(1, 10, 10, 10, 0, 0, 1), emptyList(),
        )
        val score = A9Scorer.score(result, A9CaseTruth(boundedClaimValid = true, heldoutPredictionError = 0.5))
        assertEquals(true, score.boundedClaimValidity)
        assertEquals(0.5, score.heldoutPredictionError)
        assertFalse(score.epistemicOverclaim)
    }

    @Test
    fun `repeat action does not increase diagnostic coverage`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val result = runner().run(
            A9BaselineId.REPEAT_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible }, gateway(world),
        )
        assertEquals(A9DiagnosticClaim.UNDETERMINED, result.diagnosticClaim)
        assertNotNull(result.action)
        assertEquals(A9ActionKind.REPEAT_LABEL, result.action!!.actionKind)
        assertNull(result.action!!.requestedSampleId)
        assertNotNull(result.action!!.repeatedObjectId)
        assertFalse(A9Scorer.score(result, A9CaseTruth(null, null)).diagnosticCoverage)
    }

    @Test
    fun `repeat is not recorded as public request`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val result = runner().run(
            A9BaselineId.REPEAT_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible }, gateway(world),
        )
        assertEquals(A9ActionKind.REPEAT_LABEL, result.action!!.actionKind)
        assertNull(result.action!!.requestedSampleId)
        assertEquals("REPLAYED_LABEL", result.action!!.measurementKind?.name)
    }
}
