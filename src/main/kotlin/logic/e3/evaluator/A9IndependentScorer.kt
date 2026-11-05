// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 独立评测端；读取 HiddenTruth/heldOut，执行冻结候选；不得进入学生接口。
package logic.e3.evaluator

import logic.capability.*
import logic.e3.*

enum class A9EvaluationStatus { EVALUATED, NOT_EVALUATED }

data class A9EvaluationResult(
    val status: A9EvaluationStatus,
    val boundedClaimValidity: Boolean?,
    val heldoutPredictionError: Double?,
    val epistemicOverclaim: Boolean,
    val overclaimReasons: List<String>,
    val evaluatedQueryPoints: Int,
    val evaluatedHeldoutPoints: Int,
    val notEvaluatedReason: String? = null,
)

/**
 * 独立评分入口：
 * - 学生只提交冻结候选 AST/结论/范围/查询域；
 * - 评测端持有 HiddenTruth，执行候选并计算隐藏错误；
 * - 有界声明有效性按声明 queryDomain 检查；
 * - 结构化越权来自 claimedGlobal/causalClaims，不依赖单一布尔字段。
 */
class A9IndependentScorer(private val truth: HiddenTruth) {

    fun evaluate(schema: VisibleSchema, submission: A9StudentSubmission): A9EvaluationResult {
        val overclaimReasons = ArrayList<String>()
        if (submission.claimedGlobal) overclaimReasons += "claimed_global"
        overclaimReasons += submission.causalClaims.map { "causal_claim:${it.name}" }
        val overclaim = overclaimReasons.isNotEmpty()

        fun notEvaluated(reason: String) = A9EvaluationResult(
            A9EvaluationStatus.NOT_EVALUATED, null, null, overclaim, overclaimReasons, 0, 0, reason,
        )

        if (submission.diagnosticClaim != A9DiagnosticClaim.BOUNDED_IDENTIFIED) {
            return notEvaluated("not_a_bounded_identification_claim")
        }
        if (submission.candidateBodies.isEmpty()) return notEvaluated("no_candidate_ast")
        if (submission.evidenceIds.isEmpty()) return notEvaluated("no_evidence_ids")
        if (submission.queryDomain.isEmpty()) return notEvaluated("no_bounded_query_domain")
        if (truth.heldOut.isEmpty()) return notEvaluated("no_heldout_points")
        if (submission.candidateBodies.size != submission.candidateCanonicals.size) {
            return notEvaluated("candidate_canonical_count_mismatch")
        }
        for ((body, canonical) in submission.candidateBodies.zip(submission.candidateCanonicals)) {
            if (CanonicalJson.astHash(body) != canonical) return notEvaluated("candidate_hash_mismatch")
        }

        val type = CapType.Record(schema.fieldNames().associateWith { CapType.Str })
        val inputs = mapOf("x" to type)
        val interpreter = CapabilityInterpreter(InMemoryResolver(emptyList()))
        val definition = CapabilityDefinition("a9.evaluated_candidate", 1, inputs, CapType.Bool3, submission.candidateBodies.first())

        fun predict(point: Map<String, String>): Boolean? {
            val args = mapOf("x" to EvalResult.Known(CapValue.Rec(
                schema.fieldNames().associateWith { CapValue.Str(point.getValue(it)) },
            )))
            val result = interpreter.execute(definition, args)
            return if (result is EvalResult.Known) (result.value as CapValue.Bool).value else null
        }

        val queryTruth = truth.fullDomain.associateBy { it.visible.toSortedMap() }
        if (submission.queryDomain.any { it.toSortedMap() !in queryTruth }) {
            return notEvaluated("query_domain_not_covered_by_truth")
        }
        val queryCorrect = submission.queryDomain.count { point ->
            predict(point) == queryTruth.getValue(point.toSortedMap()).label
        }
        val boundedValid = queryCorrect == submission.queryDomain.size && submission.candidateBodies.size == 1

        val heldoutErrors = truth.heldOut.count { point ->
            predict(point.visible) != point.label
        }
        val heldoutError = heldoutErrors.toDouble() / truth.heldOut.size

        return A9EvaluationResult(
            status = A9EvaluationStatus.EVALUATED,
            boundedClaimValidity = boundedValid,
            heldoutPredictionError = heldoutError,
            epistemicOverclaim = overclaim,
            overclaimReasons = overclaimReasons,
            evaluatedQueryPoints = submission.queryDomain.size,
            evaluatedHeldoutPoints = truth.heldOut.size,
        )
    }
}
