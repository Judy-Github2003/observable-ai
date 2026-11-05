// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A8 诊断整合测试；使用真实 A6/A7 轨迹，不按世界族生成结论。
package logic.e3

import logic.e3.evaluator.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E3DiagnosticIntegratorTest {

    private val integrator = E3DiagnosticIntegrator()
    private val low = DiagnosticBudget(2, 32, 3000, 10000)
    private val high = DiagnosticBudget(3, 32, 3000, 10000)

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

    private fun probe(world: EvaluatedWorld): ProbeTrace = E3ProbeSession(
        world.public.schema, world.public.observations,
        PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
        world.public.queryPool.map { it.visible },
        high, gateway(world),
    ).let { session -> session.execute(session.plan()) }

    private fun integrate(world: EvaluatedWorld, trace: ProbeTrace? = null): IntegratedDiagnosis =
        integrator.integrate(
            world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible }, low, high, trace,
        )

    @Test
    fun `collision report has multiple findings and never names hidden cause`() {
        val result = integrate(E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0))
        assertTrue(result.report.findings.any { it.kind == FindingKind.OBSERVED_LABEL_COLLISION })
        assertTrue(result.report.findings.any { it.kind == FindingKind.UNDETERMINED })
        val collision = result.report.findings.first { it.kind == FindingKind.OBSERVED_LABEL_COLLISION }
        assertTrue(collision.evidenceIds.isNotEmpty())
        assertTrue(collision.alternativeExplanations.any { it.contains("noise") })
        assertTrue(collision.alternativeExplanations.any { it.contains("hidden") })
        assertFalse(collision.observationConclusion!!.contains("confirmed hidden"))
    }

    @Test
    fun `budget and multiple hypotheses findings coexist without representation claim`() {
        val result = integrate(E3WorldGenerator(107L).generate(WorldFamily.SEARCH_LIMITED, 0))
        assertTrue(result.report.findings.any { it.kind == FindingKind.SEARCH_TRUNCATED })
        assertTrue(result.report.findings.any { it.kind == FindingKind.BUDGET_SENSITIVE })
        assertTrue(result.report.findings.any { it.kind == FindingKind.MULTIPLE_HYPOTHESES })
        assertTrue(result.report.findings.size >= 3)
        assertFalse(result.report.stoppedBecause.contains("representation_insufficient"))
    }

    @Test
    fun `bounded identification is reported with explicit scope`() {
        val result = integrate(E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0))
        val supported = result.report.findings.first { it.kind == FindingKind.SUPPORTED_WITHIN_BOUNDED_H }
        assertTrue(supported.observationConclusion!!.contains("within current bounds"))
        assertTrue(supported.scope.hypothesisSpace.contains("composeRounds"))
        assertTrue(supported.evidenceIds.isNotEmpty())
    }

    @Test
    fun `probe execution is integrated with recorded observation id`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val trace = probe(world)
        val result = integrate(world, trace)
        val probeFinding = result.report.findings.first { it.kind == FindingKind.PROBE_EXECUTED }
        assertEquals(trace.recordedObservationId, probeFinding.evidenceIds.single())
        assertTrue(probeFinding.observationConclusion!!.contains("cost=1"))
        assertEquals(listOf(trace.recordedObservationId), result.stateChange.addedEvidenceIds)
        assertTrue(result.stateChange.changed)
        assertTrue(result.beforeBudget.runs.last().signal.status != result.afterBudget.runs.last().signal.status)
    }

    @Test
    fun `no action after identification is bounded and does not deny future queries`() {
        val world = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)
        val result = integrate(world, probe(world))
        val noAction = result.report.findings.first { it.kind == FindingKind.NO_ACTION_TAKEN }
        assertTrue(noAction.observationConclusion!!.contains("bounded"))
        assertTrue(noAction.alternativeExplanations.any { it.contains("outside the current policy") })
    }

    @Test
    fun `insufficient evidence yields undetermined`() {
        val schema = VisibleSchema(mapOf("a" to listOf("0", "1")))
        val result = integrator.integrate(
            schema, emptyList(), PublicConstants(listOf("0", "1"), "schema_domains"), emptyList(), low, high,
        )
        assertTrue(result.report.findings.any { it.kind == FindingKind.UNDETERMINED })
        assertTrue(result.report.strategySource == E3_STRATEGY_SOURCE)
    }

    @Test
    fun `encoded report has no world family truth or target ast`() {
        val result = integrate(E3WorldGenerator(107L).generate(WorldFamily.SEARCH_LIMITED, 0))
        val json = ContractJson.encodeReport(result.report)
        assertFalse(json.contains("worldKind"))
        assertFalse(json.contains("hiddenFeature"))
        assertFalse(json.contains("targetAst"))
        assertFalse(json.contains("WorldFamily"))
    }
}
