// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 学生输出契约；候选 AST/结论/范围/查询域冻结后交给独立评测端。
package logic.e3

import logic.capability.Expr

enum class A9CausalClaim {
    GLOBAL_RULE,
    HIDDEN_ATTRIBUTE,
    NOISE,
    WORLD_CAUSE,
}

data class A9StudentSubmission(
    val baseline: A9BaselineId,
    val diagnosticClaim: A9DiagnosticClaim,
    val candidateBodies: List<Expr>,
    val candidateCanonicals: List<String>,
    val boundedScope: EvidenceScope?,
    val queryDomain: List<Map<String, String>>,
    val evidenceIds: List<String>,
    val claimedGlobal: Boolean = false,
    val causalClaims: Set<A9CausalClaim> = emptySet(),
)

fun A9BaselineResult.toSubmission(
    queryDomain: List<Map<String, String>>,
    causalClaims: Set<A9CausalClaim> = emptySet(),
): A9StudentSubmission = A9StudentSubmission(
    baseline = baseline,
    diagnosticClaim = diagnosticClaim,
    candidateBodies = candidateBodies,
    candidateCanonicals = candidateCanonicals,
    boundedScope = boundedScope,
    queryDomain = queryDomain,
    evidenceIds = evidenceIds,
    claimedGlobal = claimedGlobal,
    causalClaims = causalClaims,
)
