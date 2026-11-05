// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A1 最小主动挑战闭环；选择合法输入、冻结预测、受控标签揭示、比较并保留证据。
package logic.e3

import logic.capability.*
import logic.e3.evaluator.PublicSample

enum class ChallengeLocation {
    IN_DECLARED_QUERY_DOMAIN,
    OUTSIDE_DECLARED_QUERY_DOMAIN,
}

/** 学生可见的挑战输入；由挑战运行器从授权查询池生成，不含标签。 */
data class ChallengeInput(
    val authorizationId: String,
    val sampleId: String,
    val visible: Map<String, String>,
    val location: ChallengeLocation,
)

/** 预测冻结事件；predictionId/sequence 由运行器生成，学生不能自行指定。 */
data class ChallengePrediction(
    val predictionId: String,
    val authorizationId: String,
    val input: Map<String, String>,
    val candidateCanonical: String,
    val result: EvalResult,
    val sequence: Long,
)

/** 受控评测端返回的公开标签。 */
data class TrustedChallengeLabel(
    val sampleId: String,
    val visible: Map<String, String>,
    val label: Boolean,
    val sourceId: String,
)

/** 评测端标签源；运行器只在预测冻结后调用它。 */
interface ChallengeLabelSource {
    fun label(sampleId: String): TrustedChallengeLabel?
}

enum class ChallengeComparison { SUPPORTED, CONTRADICTED, INCOMPARABLE }

data class ChallengeOutcomeRecord(
    val input: ChallengeInput,
    val prediction: ChallengePrediction,
    val label: TrustedChallengeLabel,
    val observation: Observation,
    val comparison: ChallengeComparison,
    val labelSequence: Long,
    val evidenceIds: List<String>,
    val notes: List<String>,
)

class ChallengeRejected(val code: String) : IllegalStateException(code)

/**
 * 单次挑战状态机：
 * OPEN -> CHOSEN -> FROZEN -> REVEALED -> RECORDED。
 * 标签源只在 FROZEN 后调用；重复查询按 sampleId 而非 visible 判定。
 */
class E3ChallengeRun private constructor(
    private val pending: ChallengePendingState,
    private val schema: VisibleSchema,
    private val observations: List<Observation>,
    private val queryPool: List<PublicSample>,
    private val labels: ChallengeLabelSource,
) {
    private var selected: ChallengeInput? = null
    private var prediction: ChallengePrediction? = null
    private var outcome: ChallengeOutcomeRecord? = null
    private var sequence = 0L
    private val usedSampleIds = HashSet<String>()

    companion object {
        fun open(
            pending: ChallengePendingState,
            schema: VisibleSchema,
            observations: List<Observation>,
            queryPool: List<PublicSample>,
            labels: ChallengeLabelSource,
        ): E3ChallengeRun = E3ChallengeRun(pending, schema, observations, queryPool, labels)
    }

    /** 简单确定性策略：按公开池顺序选择第一个未用、字段合法的输入。 */
    fun chooseFirst(): ChallengeInput? {
        val input = authorizedInputs().firstOrNull() ?: return null
        select(input)
        return input
    }

    fun choose(authorizationId: String): ChallengeInput {
        val input = authorizedInputs().firstOrNull { it.authorizationId == authorizationId }
            ?: throw ChallengeRejected("unauthorized_or_duplicate_input")
        select(input)
        return input
    }

    fun freezePrediction(candidateOverride: A9FrozenCandidate? = null): ChallengePrediction {
        val input = selected ?: throw ChallengeRejected("input_not_selected")
        if (prediction != null) throw ChallengeRejected("prediction_already_frozen")
        if (candidateOverride != null && candidateOverride != pending.candidate) {
            throw ChallengeRejected("candidate_replaced_after_entry")
        }
        val result = executeCandidate(pending.candidate.body, input.visible)
        sequence += 1
        val frozen = ChallengePrediction(
            predictionId = "challenge-pred-$sequence",
            authorizationId = input.authorizationId,
            input = input.visible,
            candidateCanonical = pending.candidate.canonical,
            result = result,
            sequence = sequence,
        )
        prediction = frozen
        return frozen
    }

    fun reveal(): ChallengeOutcomeRecord {
        val frozen = prediction ?: throw ChallengeRejected("prediction_not_frozen")
        val input = selected ?: throw ChallengeRejected("input_not_selected")
        if (outcome != null) throw ChallengeRejected("challenge_already_revealed")
        if (frozen.authorizationId != input.authorizationId || frozen.input != input.visible) {
            throw ChallengeRejected("prediction_request_mismatch")
        }
        if (frozen.candidateCanonical != pending.candidate.canonical) {
            throw ChallengeRejected("prediction_candidate_mismatch")
        }
        if (usedSampleIds.contains(input.sampleId)) throw ChallengeRejected("duplicate_query")
        val label = labels.label(input.sampleId)
            ?: throw ChallengeRejected("label_unavailable")
        if (label.sampleId != input.sampleId) throw ChallengeRejected("label_sample_mismatch")
        if (label.visible != input.visible) throw ChallengeRejected("label_visible_mismatch")

        sequence += 1
        val round = (observations.maxOfOrNull { it.round } ?: 0) + 1
        val observation = Observation(
            observationId = "challenge-obs-${input.sampleId}-$sequence",
            objectId = "challenge-object-${input.sampleId}",
            visible = label.visible.mapValues { (_, value) -> ObservedValue.Known(value) },
            label = label.label,
            provenance = EvidenceProvenance(label.sourceId, SourceKind.STUDENT_REQUESTED, true, null),
            round = round,
        )
        if (observations.any { it.observationId == observation.observationId }) {
            throw ChallengeRejected("duplicate_observation_id")
        }
        usedSampleIds += input.sampleId
        val record = ChallengeOutcomeRecord(
            input = input,
            prediction = frozen,
            label = label,
            observation = observation,
            comparison = compare(frozen.result, label.label),
            labelSequence = sequence,
            evidenceIds = observations.map { it.observationId } + observation.observationId,
            notes = listOf(
                "prediction_frozen_before_label",
                "designer_primitive_first_eligible_input",
                "comparison_is_single_observation_not_global_truth",
            ),
        )
        outcome = record
        return record
    }

    fun authorizedInputs(): List<ChallengeInput> = queryPool
        .asSequence()
        .filter { it.sampleId !in usedSampleIds }
        .filter { sample ->
            sample.visible.keys == schema.fieldNames().toSet() &&
                sample.visible.values.all { it.isNotBlank() }
        }
        .distinctBy { it.sampleId }
        .map { sample ->
            ChallengeInput(
                authorizationId = "challenge-auth-${sample.sampleId}",
                sampleId = sample.sampleId,
                visible = sample.visible,
                location = if (sample.visible in pending.snapshot.queryDomain) {
                    ChallengeLocation.IN_DECLARED_QUERY_DOMAIN
                } else {
                    ChallengeLocation.OUTSIDE_DECLARED_QUERY_DOMAIN
                },
            )
        }
        .toList()

    private fun select(input: ChallengeInput) {
        if (selected != null) throw ChallengeRejected("input_already_selected")
        selected = input
    }

    private fun executeCandidate(body: Expr, point: Map<String, String>): EvalResult {
        val type = CapType.Record(schema.fieldNames().associateWith { CapType.Str })
        val inputs = mapOf("x" to type)
        val definition = CapabilityDefinition("challenge.candidate", 1, inputs, CapType.Bool3, body)
        val args = mapOf(
            "x" to EvalResult.Known(CapValue.Rec(
                schema.fieldNames().associateWith { CapValue.Str(point.getValue(it)) },
            )),
        )
        return CapabilityInterpreter(InMemoryResolver(emptyList()))
            .execute(definition, args, EvalBudget(maxSteps = pending.snapshot.budget.maxSteps))
    }

    private fun compare(result: EvalResult, label: Boolean): ChallengeComparison = when (result) {
        is EvalResult.Known -> {
            val value = result.value
            if (value is CapValue.Bool) {
                if (value.value == label) ChallengeComparison.SUPPORTED else ChallengeComparison.CONTRADICTED
            } else {
                ChallengeComparison.INCOMPARABLE
            }
        }
        is EvalResult.Unknown, is EvalResult.Error -> ChallengeComparison.INCOMPARABLE
    }
}
