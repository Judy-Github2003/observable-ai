// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A8 真实 DiagnosticReport 输出；整合 A6/A7 实跑轨迹。
package logic.e3

import logic.e3.evaluator.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class E3A8SampleExportTest {

    private fun gateway(world: EvaluatedWorld): PublicProbeGateway {
        val byId = world.public.queryPool.associateBy { it.sampleId }
        val oracle = world.publicQuery
        return object : PublicProbeGateway {
            override val allowedQueryIds = oracle.allowedIds
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
                    original.label, "repeat-$sourceId", measurementKind = MeasurementKind.REPLAYED_LABEL)
            }
        }
    }

    @Test
    fun `export integrated reports for public worlds`() {
        val cases = listOf(
            "solvable" to E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0),
            "latent" to E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0),
            "search_limited" to E3WorldGenerator(107L).generate(WorldFamily.SEARCH_LIMITED, 0),
            "ambiguous" to E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0),
        )
        val root = buildJsonObject {
            put("note", "A8 integrated reports from real A6/A7 runs; public inputs only")
            put("cases", buildJsonArray {
                for ((name, world) in cases) {
                    val constants = PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains")
                    val query = world.public.queryPool.map { it.visible }
                    val high = DiagnosticBudget(3, 32, 3000, 10000)
                    val trace = E3ProbeSession(world.public.schema, world.public.observations, constants, query, high, gateway(world))
                        .let { s -> s.execute(s.plan()) }
                    val integrated = E3DiagnosticIntegrator().integrate(
                        world.public.schema, world.public.observations, constants, query,
                        DiagnosticBudget(2, 32, 3000, 10000), high, trace,
                    )
                    addJsonObject {
                        put("case", name)
                        put("strategySource", integrated.report.strategySource)
                        put("stoppedBecause", integrated.report.stoppedBecause)
                        put("verdict", integrated.budget.verdict.name)
                        put("facts", buildJsonObject {
                            put("budgetSensitive", integrated.budget.facts.budgetSensitive)
                            put("multipleBehaviorClasses", integrated.budget.facts.multipleBehaviorClasses)
                            put("noConsistentCandidate", integrated.budget.facts.noConsistentCandidate)
                            put("searchTruncated", integrated.budget.facts.searchTruncated)
                            put("reasonUndetermined", integrated.budget.facts.reasonUndetermined)
                            put("observationCollision", integrated.budget.facts.observationCollision)
                        })
                        put("findings", buildJsonArray {
                            integrated.report.findings.forEach { finding ->
                                addJsonObject {
                                    put("kind", finding.kind.name)
                                    put("evidenceIds", buildJsonArray { finding.evidenceIds.forEach { add(it) } })
                                    put("scope", buildJsonObject {
                                        put("hypothesisSpace", finding.scope.hypothesisSpace)
                                        put("observationDomain", finding.scope.observationDomain)
                                        if (finding.scope.queryDomain == null) put("queryDomain", JsonNull) else put("queryDomain", finding.scope.queryDomain)
                                    })
                                    put("sourceHypotheses", buildJsonArray { finding.sourceHypotheses.forEach { add(it) } })
                                    if (finding.observationConclusion == null) put("observationConclusion", JsonNull) else put("observationConclusion", finding.observationConclusion)
                                    put("assumptions", buildJsonArray { finding.assumptions.forEach { add(it) } })
                                    put("alternativeExplanations", buildJsonArray { finding.alternativeExplanations.forEach { add(it) } })
                                }
                            }
                        })
                        put("probeStatus", trace.executionStatus.name)
                        put("probeCost", trace.cost)
                        put("probeRecordedObservationId", trace.recordedObservationId)
                        put("probeIndependentEvidence", trace.independentEvidence)
                    }
                }
            })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/a8-diagnostic-report.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n")
        assertTrue(Files.size(out) > 0)
    }
}
