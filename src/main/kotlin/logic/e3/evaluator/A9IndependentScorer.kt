// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 独立评分；有界辨识按 H/公开证据/查询域的行为类判定，不直接以 Oracle 一致为成功。
package logic.e3.evaluator

import logic.capability.*
import logic.e3.*

enum class A9EvaluationStatus { EVALUATED, NOT_EVALUATED }

data class A9EvaluationResult(
    val status: A9EvaluationStatus,
    val boundedIdentificationValidity: Boolean?,
    val queryTruthAgreement: Boolean?,
    val heldoutPredictionError: Double?,
    val epistemicOverclaim: Boolean,
    val overclaimReasons: List<String>,
    val behaviorClassCount: Int?,
    val consistentCandidateCount: Int?,
    val truthConflictDetected: Boolean,
    val evaluatedQueryPoints: Int,
    val evaluatedHeldoutPoints: Int,
    val evaluationMaxSteps: Int = 0,
    val notEvaluatedReason: String? = null,
)

/**
 * 独立评分入口：
 * 1. 重算冻结搜索记录（H/公开证据/预算/查询域），拒绝不可验证的自报；
 * 2. 有界辨识 = 一致候选在声明查询域上只有 1 个行为等价类；
 * 3. queryTruthAgreement 单独计算；LATENT 同 visible 多真值时标记冲突，不静默覆盖；
 * 4. heldoutPredictionError 独立；
 * 5. 结构化越权独立。
 */
class A9IndependentScorer(private val truth: HiddenTruth) {

    fun evaluate(
        schema: VisibleSchema,
        observations: List<Observation>,
        submission: A9StudentSubmission,
        event: A9TrustedActionEvent? = null,
        context: A9ExecutionContext? = null,
    ): A9EvaluationResult {
        var trustedEvidence = observations
        if (event != null) {
            val expectedContext = context ?: return A9EvaluationResult(
                A9EvaluationStatus.NOT_EVALUATED, null, null, null, false, emptyList(),
                null, null, false, 0, 0, 0, "missing_execution_context",
            )
            val replay = A9TrustedReplay.reconstruct(schema, observations, event, expectedContext)
            if (replay.second != null) {
                return A9EvaluationResult(
                    A9EvaluationStatus.NOT_EVALUATED, null, null, null, false, emptyList(),
                    null, null, false, 0, 0, 0, "trusted_event_invalid:${replay.second}",
                )
            }
            trustedEvidence = replay.first
        }
        val overclaimReasons = ArrayList<String>()
        if (submission.claimedGlobal) overclaimReasons += "claimed_global"
        overclaimReasons += submission.causalClaims.map { "causal_claim:${it.name}" }
        val overclaim = overclaimReasons.isNotEmpty()

        fun notEvaluated(reason: String) = A9EvaluationResult(
            A9EvaluationStatus.NOT_EVALUATED, null, null, null, overclaim, overclaimReasons,
            null, null, false, 0, 0, 0, reason,
        )

        if (submission.diagnosticClaim != A9DiagnosticClaim.BOUNDED_IDENTIFIED) {
            return notEvaluated("not_a_bounded_identification_claim")
        }
        val record = submission.searchRecord ?: return notEvaluated("missing_frozen_search_record")
        if (record.queryDomain != submission.queryDomain) return notEvaluated("search_record_query_domain_mismatch")
        if (record.boundedScope != submission.boundedScope) return notEvaluated("search_record_scope_mismatch")
        if (record.publicEvidenceIds != trustedEvidence.map { it.observationId }) return notEvaluated("search_record_evidence_mismatch")
        if (context != null) {
            if (record.runId != context.runId) return notEvaluated("search_record_run_id_mismatch")
            if (record.budget != context.budget) return notEvaluated("search_record_budget_mismatch")
            val frozenIndex = context.stageOrder.indexOf(record.frozenAtStage)
            val hiddenIndex = context.stageOrder.indexOf(context.hiddenScoringStage)
            if (frozenIndex < 0 || hiddenIndex < 0 || frozenIndex >= hiddenIndex) {
                return notEvaluated("candidate_not_frozen_before_hidden_scoring")
            }
        }
        if (submission.candidateBodies.isEmpty()) return notEvaluated("no_selected_candidate")
        if (submission.evidenceIds.isEmpty()) return notEvaluated("no_evidence_ids")
        if (submission.queryDomain.isEmpty()) return notEvaluated("no_bounded_query_domain")
        if (truth.heldOut.isEmpty()) return notEvaluated("no_heldout_points")

        val recomputed = E3B0DiagnosticAdapter().diagnose(
            schema, trustedEvidence, record.constants, record.queryDomain, record.budget,
        )
        if (record.composedCandidateHashes != recomputed.composedCandidateHashes ||
            record.consistentCandidates.map { it.canonical } != recomputed.consistentCandidateHashes ||
            record.reason != recomputed.search?.reason ||
            record.generated != (recomputed.search?.generated ?: 0) ||
            record.candidateCount != (recomputed.search?.candidateCount ?: 0)
        ) {
            return notEvaluated("frozen_search_record_mismatch")
        }
        val consistent = recomputed.consistentCandidateBodies.zip(recomputed.consistentCandidateHashes)
        if (consistent.isEmpty()) return notEvaluated("no_consistent_candidate")
        val selectedBody = submission.candidateBodies.first()
        val selectedHash = submission.candidateCanonicals.firstOrNull()
            ?: return notEvaluated("missing_selected_candidate_hash")
        if (selectedHash !in consistent.map { it.second }) return notEvaluated("selected_candidate_not_consistent")
        if (CanonicalJson.astHash(selectedBody) != selectedHash) return notEvaluated("selected_candidate_hash_mismatch")

        val type = CapType.Record(schema.fieldNames().associateWith { CapType.Str })
        val inputs = mapOf("x" to type)
        val interpreter = CapabilityInterpreter(InMemoryResolver(emptyList()))

        fun predict(body: Expr, point: Map<String, String>): Boolean? {
            val definition = CapabilityDefinition("a9.evaluated_candidate", 1, inputs, CapType.Bool3, body)
            val args = mapOf("x" to EvalResult.Known(CapValue.Rec(
                schema.fieldNames().associateWith { CapValue.Str(point.getValue(it)) },
            )))
            return when (val result = interpreter.execute(definition, args)) {
                is EvalResult.Known -> (result.value as CapValue.Bool).value
                else -> null
            }
        }

        fun signature(body: Expr): String = submission.queryDomain.joinToString("|") { point ->
            when (predict(body, point)) {
                true -> "T"
                false -> "F"
                null -> "U"
            }
        }

        val classes = consistent.map { it.first }.map(::signature).toSet()
        val boundedValidity = classes.size == 1
        val selectedSignature = signature(selectedBody)

        val truthByVisible = truth.fullDomain.groupBy { it.visible.toSortedMap() }
        var truthConflict = false
        var queryTruthAgreement: Boolean? = null
        if (submission.queryDomain.all { truthByVisible.containsKey(it.toSortedMap()) }) {
            val labels = submission.queryDomain.map { point ->
                truthByVisible.getValue(point.toSortedMap()).map { it.label }.toSet()
            }
            truthConflict = labels.any { it.size > 1 }
            if (!truthConflict) {
                queryTruthAgreement = submission.queryDomain.indices.all { index ->
                    val expected = labels[index].single()
                    predict(selectedBody, submission.queryDomain[index]) == expected
                }
            }
        }

        val heldoutErrors = truth.heldOut.count { point -> predict(selectedBody, point.visible) != point.label }
        val heldoutError = heldoutErrors.toDouble() / truth.heldOut.size

        return A9EvaluationResult(
            status = A9EvaluationStatus.EVALUATED,
            boundedIdentificationValidity = boundedValidity && selectedSignature == classes.singleOrNull(),
            queryTruthAgreement = queryTruthAgreement,
            heldoutPredictionError = heldoutError,
            epistemicOverclaim = overclaim,
            overclaimReasons = overclaimReasons,
            behaviorClassCount = classes.size,
            consistentCandidateCount = consistent.size,
            truthConflictDetected = truthConflict,
            evaluatedQueryPoints = submission.queryDomain.size,
            evaluatedHeldoutPoints = truth.heldOut.size,
            evaluationMaxSteps = record.budget.maxSteps,
        )
    }
}
