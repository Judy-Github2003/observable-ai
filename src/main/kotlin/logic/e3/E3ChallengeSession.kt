// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-M1.1a 最小挑战入口；只验证既有有界结论能否进入待挑战状态，不执行挑战。
package logic.e3

import logic.capability.CanonicalJson

enum class ChallengeTrustStatus {
    /** 只验证了对象字段自洽；没有可信执行端的来源证明。 */
    FIELD_CONSISTENT_ONLY,
    /** 预留给未来可信执行端核验；本阶段不会产生此状态。 */
    TRUSTED_EXECUTION_VERIFIED,
}

data class ChallengePendingState(
    val snapshot: A9FrozenSearchRecord,
    val candidate: A9FrozenCandidate,
    val boundedScope: EvidenceScope,
    val evidenceIds: List<String>,
    val trustStatus: ChallengeTrustStatus,
    val notes: List<String>,
)

data class ChallengeEntryResult(
    val pending: ChallengePendingState?,
    val rejectionCode: String?,
    val notes: List<String> = emptyList(),
) {
    val accepted: Boolean get() = pending != null
}

/**
 * M1.1a：
 * - 只接受 A9BaselineResult 中的 BOUNDED_IDENTIFIED；
 * - 复用 A9FrozenSearchRecord / A9FrozenCandidate，不新建候选表示；
 * - 只做字段自洽校验，不访问 Oracle、不预测、不修改证据；
 * - 不提供可信执行核验 API，因此本轮不会返回 TRUSTED_EXECUTION_VERIFIED。
 */
object E3ChallengeSession {

    fun open(result: A9BaselineResult): ChallengeEntryResult {
        if (result.diagnosticClaim != A9DiagnosticClaim.BOUNDED_IDENTIFIED) {
            return reject("not_bounded_identified")
        }
        val snapshot = result.searchRecord ?: return reject("missing_search_record")
        if (result.queryDomain != snapshot.queryDomain) return reject("query_domain_mismatch")
        if (snapshot.publicEvidenceIds.isEmpty()) return reject("missing_evidence")
        if (snapshot.queryDomain.isEmpty()) return reject("empty_query_domain")
        if (!validBudget(snapshot.budget)) return reject("invalid_budget")
        val scope = snapshot.boundedScope ?: return reject("missing_bounded_scope")
        if (result.boundedScope != scope) return reject("scope_mismatch")
        if (result.candidateBodies.size != 1 || result.candidateCanonicals.size != 1) {
            return reject("multiple_selected_candidates")
        }
        if (snapshot.consistentCandidates.isEmpty()) return reject("empty_consistent_candidates")
        if (snapshot.consistentCandidates.any { CanonicalJson.astHash(it.body) != it.canonical }) {
            return reject("snapshot_candidate_hash_mismatch")
        }
        if (snapshot.consistentCandidates.any { it.canonical !in snapshot.composedCandidateHashes }) {
            return reject("snapshot_candidate_not_in_composed")
        }
        val body = result.candidateBodies.single()
        val canonical = result.candidateCanonicals.single()
        if (CanonicalJson.astHash(body) != canonical) return reject("candidate_canonical_mismatch")
        if (snapshot.consistentCandidates.none { it.canonical == canonical }) {
            return reject("candidate_not_in_snapshot")
        }
        if (canonical !in snapshot.composedCandidateHashes) return reject("selected_not_in_composed_candidates")
        if (result.evidenceIds.isEmpty() || result.evidenceIds.any { it !in snapshot.publicEvidenceIds }) {
            return reject("evidence_not_in_snapshot")
        }
        if (snapshot.candidateCount <= 0 || snapshot.composedCandidateHashes.isEmpty()) {
            return reject("empty_candidate_space")
        }

        val pending = ChallengePendingState(
            snapshot = snapshot,
            candidate = A9FrozenCandidate(body, canonical),
            boundedScope = scope,
            evidenceIds = result.evidenceIds,
            trustStatus = ChallengeTrustStatus.FIELD_CONSISTENT_ONLY,
            notes = listOf(
                "entry_only_no_prediction_no_oracle_call",
                "field_consistency_only_trusted_execution_not_verified",
            ),
        )
        return ChallengeEntryResult(pending, rejectionCode = null)
    }

    private fun reject(code: String) = ChallengeEntryResult(
        pending = null,
        rejectionCode = code,
        notes = listOf("challenge_entry_rejected:$code"),
    )

    private fun validBudget(budget: DiagnosticBudget): Boolean =
        budget.maxComposeRounds >= 1 &&
            budget.maxNodes >= 1 &&
            budget.maxCandidates >= 1 &&
            budget.maxSteps >= 1
}
