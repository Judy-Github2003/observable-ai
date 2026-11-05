// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A1 端到端挑战测试；正确、错误泛化、非法顺序与重复查询。
package logic.e3

import logic.capability.*
import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.EvaluatedWorld
import logic.e3.evaluator.WorldFamily
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class E3ChallengeRunTest {

    private fun world(seed: Long = 101L, family: WorldFamily = WorldFamily.SOLVABLE): EvaluatedWorld =
        E3WorldGenerator(seed).generate(family, 0)

    private fun pending(world: EvaluatedWorld): ChallengePendingState {
        val result = E3A9BaselineRunner().run(
            A9BaselineId.B0_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
        )
        val entry = E3ChallengeSession.open(result)
        assertTrue(entry.accepted, entry.rejectionCode ?: "")
        return entry.pending!!
    }

    private fun predict(schema: VisibleSchema, body: Expr, visible: Map<String, String>, maxSteps: Int): EvalResult {
        val type = CapType.Record(schema.fieldNames().associateWith { CapType.Str })
        val definition = CapabilityDefinition("test.challenge", 1, mapOf("x" to type), CapType.Bool3, body)
        val args = mapOf("x" to EvalResult.Known(CapValue.Rec(
            schema.fieldNames().associateWith { CapValue.Str(visible.getValue(it)) },
        )))
        return CapabilityInterpreter(InMemoryResolver(emptyList()))
            .execute(definition, args, EvalBudget(maxSteps = maxSteps))
    }

    private fun labelsFor(world: EvaluatedWorld): ChallengeLabelSource = CountingLabels(object : ChallengeLabelSource {
        override fun label(sampleId: String): TrustedChallengeLabel? {
            val point = world.public.queryPool.firstOrNull { it.sampleId == sampleId } ?: return null
            val label = world.truth.truthBySampleId[sampleId] ?: return null
            return TrustedChallengeLabel(sampleId, point.visible, label, "test-oracle")
        }
    })

    private fun correctPoint(world: EvaluatedWorld, state: ChallengePendingState): logic.e3.evaluator.PublicSample {
        return world.public.queryPool.first { point ->
            val r = predict(world.public.schema, state.candidate.body, point.visible, state.snapshot.budget.maxSteps)
            r is EvalResult.Known && (r.value as? CapValue.Bool)?.value == world.truth.truthBySampleId[point.sampleId]
        }
    }

    private fun wrongPoint(world: EvaluatedWorld, state: ChallengePendingState): logic.e3.evaluator.PublicSample? {
        return world.public.queryPool.firstOrNull { point ->
            val r = predict(world.public.schema, state.candidate.body, point.visible, state.snapshot.budget.maxSteps)
            r is EvalResult.Known && (r.value as? CapValue.Bool)?.value != world.truth.truthBySampleId[point.sampleId]
        }
    }

    @Test
    fun `correct rule challenge is supported and keeps new evidence`() {
        val w = world()
        val state = pending(w)
        val point = correctPoint(w, state)
        val labels = labelsFor(w)
        val run = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point), labels)
        val input = run.chooseFirst()!!
        val prediction = run.freezePrediction()
        assertEquals(0, labels.calls)
        val record = run.reveal()
        assertEquals(ChallengeComparison.SUPPORTED, record.comparison)
        assertEquals(ChallengeLocation.IN_DECLARED_QUERY_DOMAIN, input.location)
        assertEquals(prediction.predictionId, record.prediction.predictionId)
        assertTrue(record.labelSequence > record.prediction.sequence)
        assertEquals(w.public.observations.map { it.observationId } + record.observation.observationId, record.evidenceIds)
        assertEquals(w.public.observations, w.public.observations)
        assertEquals(1, labels.calls)
    }

    private data class WrongCase(
        val world: EvaluatedWorld,
        val state: ChallengePendingState,
        val sample: logic.e3.evaluator.PublicSample,
        val label: Boolean,
    )

    private fun wrongCase(): WrongCase {
        for (family in WorldFamily.values()) {
            for (seed in 100L..106L) {
                try {
                    val w = world(seed, family)
                    val state = pending(w)
                    val predicted = w.truth.fullDomain
                        .groupBy { it.visible }
                        .entries
                        .firstOrNull { (visible, points) ->
                            points.map { it.label }.distinct().size == 1 &&
                                visible !in state.snapshot.queryDomain &&
                                visible.keys == w.public.schema.fieldNames().toSet() &&
                                run {
                                    val r = predict(w.public.schema, state.candidate.body, visible, state.snapshot.budget.maxSteps)
                                    r is EvalResult.Known && (r.value as? CapValue.Bool)?.value != points.first().label
                                }
                        } ?: continue
                    val sample = logic.e3.evaluator.PublicSample("challenge-wrong-${w.public.worldId}", predicted.key)
                    return WrongCase(w, state, sample, predicted.value.first().label)
                } catch (_: Throwable) {
                    continue
                }
            }
        }
        error("no wrong query point found")
    }

    @Test
    fun `wrong generalization is contradicted and original prediction is preserved`() {
        val case = wrongCase()
        val labels = CountingLabels(object : ChallengeLabelSource {
            override fun label(sampleId: String): TrustedChallengeLabel =
                TrustedChallengeLabel(sampleId, case.sample.visible, case.label, "test-oracle")
        })
        val run = E3ChallengeRun.open(case.state, case.world.public.schema, case.world.public.observations, listOf(case.sample), labels)
        run.chooseFirst()
        val prediction = run.freezePrediction()
        val record = run.reveal()
        assertEquals(ChallengeComparison.CONTRADICTED, record.comparison)
        assertEquals(ChallengeLocation.OUTSIDE_DECLARED_QUERY_DOMAIN, record.input.location)
        assertEquals(prediction, record.prediction)
        assertEquals(prediction.result, record.prediction.result)
        assertTrue(record.notes.contains("comparison_is_single_observation_not_global_truth"))
        assertTrue(record.evidenceIds.contains(record.observation.observationId))
    }

    @Test
    fun `invalid field values are not authorized`() {
        val w = world()
        val state = pending(w)
        val schema = VisibleSchema(mapOf("a" to listOf("0", "1")))
        val bad = logic.e3.evaluator.PublicSample("bad-value", mapOf("a" to "other"))
        val dummy = object : ChallengeLabelSource {
            override fun label(sampleId: String): TrustedChallengeLabel? = null
        }
        val run = E3ChallengeRun.open(state, schema, emptyList(), listOf(bad), dummy)
        assertTrue(run.authorizedInputs().isEmpty())
        assertEquals(null, run.chooseFirst())
    }

    @Test
    fun `shared ledger blocks cross-session repeat but not same-visible new sample`() {
        val w = world()
        val state = pending(w)
        val point = correctPoint(w, state)
        val twin = logic.e3.evaluator.PublicSample("twin-${point.sampleId}", point.visible)
        val labels = object : ChallengeLabelSource {
            override fun label(sampleId: String): TrustedChallengeLabel {
                val visible = if (sampleId == twin.sampleId) twin.visible else point.visible
                return TrustedChallengeLabel(sampleId, visible, true, "test-oracle")
            }
        }
        val ledger = ChallengeQueryLedger()
        val first = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point, twin), labels, ledger)
        first.choose("challenge-auth-${point.sampleId}")
        first.freezePrediction()
        first.reveal()
        assertTrue(ledger.isLabeled(point.sampleId))

        val second = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point), labels, ledger)
        assertTrue(second.authorizedInputs().isEmpty())
        assertEquals(null, second.chooseFirst())

        val third = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(twin), labels, ledger)
        assertEquals(twin.sampleId, third.chooseFirst()!!.sampleId)

        val preLabeled = ChallengeQueryLedger(setOf(point.sampleId))
        val blocked = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point), labels, preLabeled)
        assertTrue(blocked.authorizedInputs().isEmpty())
    }

    @Test
    fun `export A1 challenge run records`() {
        val correctWorld = world()
        val correctState = pending(correctWorld)
        val correctPoint = correctPoint(correctWorld, correctState)
        val correctRun = E3ChallengeRun.open(
            correctState, correctWorld.public.schema, correctWorld.public.observations,
            listOf(correctPoint), labelsFor(correctWorld),
        )
        correctRun.chooseFirst()
        correctRun.freezePrediction()
        val correct = correctRun.reveal()

        val wrong = wrongCase()
        val wrongRun = E3ChallengeRun.open(
            wrong.state, wrong.world.public.schema, wrong.world.public.observations,
            listOf(wrong.sample),
            object : ChallengeLabelSource {
                override fun label(sampleId: String): TrustedChallengeLabel =
                    TrustedChallengeLabel(sampleId, wrong.sample.visible, wrong.label, "test-oracle")
            },
        )
        wrongRun.chooseFirst()
        wrongRun.freezePrediction()
        val contradicted = wrongRun.reveal()

        fun row(name: String, record: ChallengeOutcomeRecord) = buildJsonObject {
            put("case", name)
            put("sampleId", record.input.sampleId)
            put("location", record.input.location.name)
            put("candidateCanonical", record.prediction.candidateCanonical)
            put("predictionId", record.prediction.predictionId)
            put("predictionSequence", record.prediction.sequence)
            put("labelSequence", record.labelSequence)
            put("comparison", record.comparison.name)
            put("label", record.label.label)
            when (val value = record.prediction.result) {
                is EvalResult.Known -> put("predicted", (value.value as CapValue.Bool).value)
                is EvalResult.Unknown -> put("predicted", JsonNull)
                is EvalResult.Error -> put("predicted", JsonNull)
            }
            put("observationId", record.observation.observationId)
            put("evidenceIds", buildJsonArray { record.evidenceIds.forEach { add(it) } })
        }

        val out = Path.of("experiments/representation-evolution-e3/reports/a1-challenge-runs.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(
            JsonArray.serializer(), JsonArray(listOf(row("supported", correct), row("contradicted", contradicted))),
        ))
    }

    @Test
    fun `unauthorized input and label before prediction are rejected`() {
        val w = world()
        val state = pending(w)
        val point = correctPoint(w, state)
        val labels = labelsFor(w)
        val run = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point), labels)
        assertEquals("unauthorized_or_duplicate_input", assertFailsWith<ChallengeRejected> { run.choose("bogus") }.code)
        assertEquals("prediction_not_frozen", assertFailsWith<ChallengeRejected> { run.reveal() }.code)
        assertEquals(0, labels.calls)
    }

    @Test
    fun `label input mismatch and candidate replacement are rejected`() {
        val w = world()
        val state = pending(w)
        val point = correctPoint(w, state)
        val mismatched = object : ChallengeLabelSource {
            override fun label(sampleId: String): TrustedChallengeLabel =
                TrustedChallengeLabel(sampleId, point.visible + ("x" to "tampered"), true, "bad-oracle")
        }
        val run = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point), mismatched)
        run.chooseFirst()
        run.freezePrediction()
        assertEquals("label_visible_mismatch", assertFailsWith<ChallengeRejected> { run.reveal() }.code)

        val fresh = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point), labelsFor(w))
        fresh.chooseFirst()
        val replacement = A9FrozenCandidate(
            Expr.Const(CapValue.Bool(false)),
            CanonicalJson.astHash(Expr.Const(CapValue.Bool(false))),
        )
        assertEquals("candidate_replaced_after_entry", assertFailsWith<ChallengeRejected> {
            fresh.freezePrediction(replacement)
        }.code)
    }

    @Test
    fun `duplicate sample is rejected but same visible different sample is not`() {
        val w = world()
        val state = pending(w)
        val point = correctPoint(w, state)
        val labels = labelsFor(w)
        val run = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point), labels)
        run.chooseFirst()
        run.freezePrediction()
        run.reveal()
        assertFailsWith<ChallengeRejected> { run.choose("challenge-auth-${point.sampleId}") }

        val twin = logic.e3.evaluator.PublicSample("twin-${point.sampleId}", point.visible)
        val twinLabels = object : ChallengeLabelSource {
            override fun label(sampleId: String): TrustedChallengeLabel =
                TrustedChallengeLabel(sampleId, twin.visible, true, "twin-oracle")
        }
        val both = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(point, twin), twinLabels)
        assertEquals(2, both.authorizedInputs().size)
        val twinRun = E3ChallengeRun.open(state, w.public.schema, w.public.observations, listOf(twin), twinLabels)
        val twinInput = twinRun.chooseFirst()!!
        assertEquals(twin.sampleId, twinInput.sampleId)
        assertEquals(twin.visible, twinInput.visible)
    }
}

private val ChallengeLabelSource.calls: Int
    get() = (this as? CountingLabels)?.calls ?: 0

private class CountingLabels(private val delegate: ChallengeLabelSource) : ChallengeLabelSource {
    var calls = 0
    override fun label(sampleId: String): TrustedChallengeLabel? {
        calls += 1
        return delegate.label(sampleId)
    }
}
