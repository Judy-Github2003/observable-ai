// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A7 探测策略测试；查询只走 PublicQueryOracle 包装的受控网关。
package logic.e3

import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.EvaluatedWorld
import logic.e3.evaluator.PublicQueryAnswer
import logic.e3.evaluator.WorldFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E3ProbeSessionTest {

    private fun gateway(world: EvaluatedWorld, allowQueries: Boolean = true): PublicProbeGateway {
        val byId = world.public.queryPool.associateBy { it.sampleId }
        val oracle = world.publicQuery
        return object : PublicProbeGateway {
            override val allowedQueryIds: Set<String> =
                if (allowQueries) oracle.allowedIds else emptySet()

            override fun visibleOf(sampleId: String): Map<String, String>? = byId[sampleId]?.visible

            override fun requestPublicInstance(sampleId: String): ProbeObservation {
                val point = byId[sampleId]
                return when (val answer = oracle.query(sampleId)) {
                    is PublicQueryAnswer.Labeled -> ProbeObservation(
                        sampleId, "query-$sampleId", point?.visible, answer.label, "student-query",
                    )
                    is PublicQueryAnswer.Rejected -> ProbeObservation(
                        sampleId, null, point?.visible, null, "student-query", answer.reason,
                    )
                }
            }

            override fun repeatLabel(objectId: String, sourceId: String): ProbeObservation {
                val original = world.public.observations.firstOrNull { it.objectId == objectId }
                    ?: return ProbeObservation(null, objectId, null, null, "repeat-$sourceId", "unknown_object")
                return ProbeObservation(
                    null, objectId,
                    original.visible.mapValues { (_, v) -> (v as ObservedValue.Known).value },
                    original.label, "repeat-$sourceId",
                    measurementKind = MeasurementKind.REPLAYED_LABEL,
                )
            }
        }
    }

    private fun session(world: EvaluatedWorld, allowQueries: Boolean = true, maxActions: Int = 1): E3ProbeSession =
        E3ProbeSession(
            world.public.schema,
            world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
            DiagnosticBudget(maxComposeRounds = 3, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000),
            gateway(world, allowQueries),
            maxActions,
        )

    @Test
    fun `solvable identified chooses no action`() {
        val world = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)
        val decision = session(world).plan()
        assertTrue(decision.action is ProbeAction.NoAction)
        assertEquals(0, decision.cost)
    }

    @Test
    fun `latent collision repeats measurement without noise claim`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val session = session(world)
        val decision = session.plan()
        assertTrue(decision.action is ProbeAction.RepeatLabel)
        assertTrue(decision.reason.contains("collision_cause_uncertain"))
        assertFalse(decision.reason.contains("noise_confirmed"))
        val trace = session.execute(decision)
        assertFalse(trace.independentEvidence)
        assertEquals(1, trace.cost)
        assertEquals(DiagnosticSignalStatus.OBSERVATION_COLLISION, trace.diagnosisAfter.status)
        assertTrue(trace.notes.contains("repeat_is_not_new_independent_evidence"))
    }

    @Test
    fun `noisy labels repeats measurement but does not declare noise`() {
        val world = E3WorldGenerator(29L).generate(WorldFamily.NOISY_LABELS, 0)
        val session = session(world)
        val decision = session.plan()
        assertTrue(decision.action is ProbeAction.RepeatLabel)
        assertFalse(decision.reason.contains("noise_confirmed"))
        val trace = session.execute(decision)
        assertFalse(trace.independentEvidence)
        assertEquals(DiagnosticSignalStatus.OBSERVATION_COLLISION, trace.diagnosisAfter.status)
    }

    @Test
    fun `ambiguous evidence requests a legal distinguishing public instance`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val session = session(world)
        val decision = session.plan()
        assertTrue(decision.action is ProbeAction.RequestPublicInstance)
        assertEquals(1, decision.cost)
        val trace = session.execute(decision)
        assertTrue(trace.independentEvidence)
        assertEquals(1, trace.cost)
        assertTrue(trace.observation?.label != null)
    }

    @Test
    fun `no legal query or exhausted budget chooses no action`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val noGateway = E3ProbeSession(
            world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
            DiagnosticBudget(maxComposeRounds = 3, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000),
            null,
        )
        assertTrue(noGateway.plan().action is ProbeAction.NoAction)

        val exhausted = E3ProbeSession(
            world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
            DiagnosticBudget(maxComposeRounds = 1, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000),
            gateway(world),
        )
        assertTrue(exhausted.plan().action is ProbeAction.NoAction)
    }

    @Test
    fun `duplicate query is not counted as new independent evidence`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val session = session(world, maxActions = 2)
        val decision = session.plan()
        val first = session.execute(decision)
        val second = session.execute(decision)
        assertTrue(first.executionStatus == ProbeExecutionStatus.EXECUTED)
        assertEquals(ProbeExecutionStatus.REJECTED_STALE_OR_FORGED_DECISION, second.executionStatus)
        assertFalse(second.independentEvidence)
        assertEquals(0, second.cost)
        assertTrue(second.notes.any { it.contains("stale_or_forged_decision") })
    }

    @Test
    fun `forged stale and over-budget decisions are rejected`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val session = session(world)
        val decision = session.plan()
        val forged = decision.copy(decisionId = "forged")
        assertEquals(ProbeExecutionStatus.REJECTED_STALE_OR_FORGED_DECISION, session.execute(forged).executionStatus)
        // 篡改同一 token 不得消费合法决策。
        val tampered = decision.copy(reason = "tampered")
        assertEquals(ProbeExecutionStatus.REJECTED_STALE_OR_FORGED_DECISION, session.execute(tampered).executionStatus)
        assertEquals(ProbeExecutionStatus.EXECUTED, session.execute(decision).executionStatus)

        val maxZero = session(world, maxActions = 0)
        val blocked = maxZero.plan()
        assertEquals(ProbeExecutionStatus.REJECTED_ACTION_BUDGET, maxZero.execute(blocked).executionStatus)
    }

    @Test
    fun `invalid gateway response is not added to evidence`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val good = gateway(world)
        val bad = object : PublicProbeGateway {
            override val allowedQueryIds = good.allowedQueryIds
            override fun visibleOf(sampleId: String) = good.visibleOf(sampleId)
            override fun requestPublicInstance(sampleId: String): ProbeObservation =
                good.requestPublicInstance(sampleId).copy(sampleId = "wrong-id")
            override fun repeatLabel(objectId: String, sourceId: String) = good.repeatLabel(objectId, sourceId)
        }
        val session = E3ProbeSession(
            world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
            DiagnosticBudget(3, 32, 3000, 10000), bad,
        )
        val trace = session.execute(session.plan())
        assertEquals(ProbeExecutionStatus.REJECTED_INVALID_RESPONSE, trace.executionStatus)
        assertFalse(trace.newObservationRecorded)
    }

    @Test
    fun `repeat measurement kind and new record are distinguished from independence`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val session = session(world)
        val trace = session.execute(session.plan())
        assertEquals(MeasurementKind.REPLAYED_LABEL, trace.measurementKind)
        assertTrue(trace.newObservationRecorded)
        assertFalse(trace.independentEvidence)
        assertTrue(trace.recordedObservationId != null)
    }

    @Test
    fun `no action after identification is bounded to current policy`() {
        val world = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)
        val session = session(world)
        val decision = session.plan()
        assertTrue(decision.reason.contains("current_bounded_space_and_policy"))
        val trace = session.execute(decision)
        assertEquals(ProbeExecutionStatus.NO_ACTION, trace.executionStatus)
        assertTrue(trace.notes.any { it.contains("no_claim_that_no_valuable_query_exists") })
        assertEquals("designer_primitive", decision.strategySource)
        assertEquals("student_selected_public_probe", decision.attribution)
    }

    @Test
    fun `costs and attribution are recorded`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val session = session(world)
        val trace = session.execute(session.plan())
        assertEquals(1, trace.cost)
        assertEquals("student_selected_public_probe", trace.attribution)
        assertEquals(DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE, trace.diagnosisBefore.status)
    }
}
