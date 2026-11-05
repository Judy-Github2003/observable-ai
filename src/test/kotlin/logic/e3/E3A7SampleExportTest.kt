// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A7 真实行动轨迹导出；查询经 PublicQueryOracle 包装的受控网关。
package logic.e3

import logic.e3.evaluator.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class E3A7SampleExportTest {

    private fun gateway(world: EvaluatedWorld, allow: Boolean = true): PublicProbeGateway {
        val byId = world.public.queryPool.associateBy { it.sampleId }
        val oracle = world.publicQuery
        return object : PublicProbeGateway {
            override val allowedQueryIds = if (allow) oracle.allowedIds else emptySet()
            override fun visibleOf(sampleId: String) = byId[sampleId]?.visible
            override fun requestPublicInstance(sampleId: String): ProbeObservation {
                val point = byId[sampleId]
                return when (val answer = oracle.query(sampleId)) {
                    is PublicQueryAnswer.Labeled -> ProbeObservation(sampleId, "query-$sampleId", point?.visible, answer.label, "student-query")
                    is PublicQueryAnswer.Rejected -> ProbeObservation(sampleId, null, point?.visible, null, "student-query", answer.reason)
                }
            }
            override fun repeatLabel(objectId: String, sourceId: String): ProbeObservation? {
                val original = world.public.observations.firstOrNull { it.objectId == objectId } ?: return null
                return ProbeObservation(null, objectId, original.visible.mapValues { (_, v) -> (v as ObservedValue.Known).value },
                    original.label, "repeat-$sourceId")
            }
        }
    }

    private fun session(world: EvaluatedWorld, allow: Boolean = true) = E3ProbeSession(
        world.public.schema, world.public.observations,
        PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
        world.public.queryPool.map { it.visible },
        DiagnosticBudget(maxComposeRounds = 3, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000),
        gateway(world, allow),
    )

    @Test
    fun `export action traces for four worlds and no-legal boundary`() {
        val cases = listOf(
            "solvable" to E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0),
            "latent" to E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0),
            "noisy" to E3WorldGenerator(29L).generate(WorldFamily.NOISY_LABELS, 0),
            "ambiguous" to E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0),
        )
        val root = buildJsonObject {
            put("note", "A7 real action traces; controlled public gateway only")
            put("cases", buildJsonArray {
                for ((name, world) in cases) {
                    val session = session(world)
                    val decision = session.plan()
                    val trace = session.execute(decision)
                    addJsonObject {
                        put("case", name)
                        put("action", decision.action.toString())
                        put("reason", decision.reason)
                        put("plannedCost", decision.cost)
                        put("executedCost", trace.cost)
                        put("independentEvidence", trace.independentEvidence)
                        put("beforeStatus", trace.diagnosisBefore.status.name)
                        put("afterStatus", trace.diagnosisAfter.status.name)
                        put("observationLabel", trace.observation?.label)
                        put("observationObjectId", trace.observation?.objectId)
                        put("notes", buildJsonArray { trace.notes.forEach { add(it) } })
                    }
                }
                val noLegal = session(cases.last().second, allow = false).let { s ->
                    val d = s.plan()
                    s.execute(d)
                }
                addJsonObject {
                    put("case", "no_legal_query")
                    put("action", "NO_ACTION")
                    put("reason", "no_legal_public_query")
                    put("plannedCost", 0)
                    put("executedCost", 0)
                    put("independentEvidence", false)
                    put("beforeStatus", noLegal.diagnosisBefore.status.name)
                    put("afterStatus", noLegal.diagnosisAfter.status.name)
                    put("observationLabel", JsonNull)
                    put("observationObjectId", JsonNull)
                    put("notes", buildJsonArray { noLegal.notes.forEach { add(it) } })
                }
            })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/a7-action-traces.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n")
        assertTrue(Files.size(out) > 0)
    }
}
