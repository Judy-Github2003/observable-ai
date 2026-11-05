// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 最小基线 smoke test/真实输出；不做 A10 批量实验。
package logic.e3

import logic.e3.evaluator.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `all baselines run on public worlds and never claim global truth`() {
        val worlds = listOf(
            "solvable" to E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0),
            "latent" to E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0),
            "ambiguous" to E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0),
        )
        val runner = E3A9BaselineRunner()
        val rows = ArrayList<JsonObject>()
        for ((name, world) in worlds) {
            for (baseline in A9BaselineId.entries) {
                val result = runner.run(
                    baseline,
                    world.public.schema,
                    world.public.observations,
                    PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
                    world.public.queryPool.map { it.visible },
                    gateway(world),
                )
                assertFalse(result.claimedGlobal)
                assertTrue(result.cost >= 0)
                rows += buildJsonObject {
                    put("case", name)
                    put("baseline", baseline.name)
                    put("claim", result.claim.name)
                    put("cost", result.cost)
                    put("evidenceCount", result.evidenceIds.size)
                    put("queryCount", result.queryIds.size)
                    put("claimedGlobal", result.claimedGlobal)
                    put("notes", buildJsonArray { result.notes.forEach { add(it) } })
                }
                if (baseline == A9BaselineId.ALWAYS_UNDETERMINED) {
                    assertEquals(A9Claim.UNDETERMINED, result.claim)
                    assertEquals(0, result.cost)
                }
                if (baseline == A9BaselineId.COLLISION_ONLY && name == "latent") {
                    assertEquals(A9Claim.OBSERVATION_COLLISION, result.claim)
                }
            }
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/a9-baseline-smoke.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows)) + "\n")
    }
}
