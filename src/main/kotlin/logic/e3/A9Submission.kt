// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 学生输出/冻结搜索记录契约；评测端可重算，不把自报字段当事实。
package logic.e3

import logic.capability.Expr

enum class A9CausalClaim {
    GLOBAL_RULE,
    HIDDEN_ATTRIBUTE,
    NOISE,
    WORLD_CAUSE,
}

data class A9FrozenCandidate(val body: Expr, val canonical: String)

data class A9FrozenSearchRecord(
    val runId: String,
    val budget: DiagnosticBudget,
    val constants: PublicConstants,
    val queryDomain: List<Map<String, String>>,
    val composedCandidateHashes: List<String>,
    val consistentCandidates: List<A9FrozenCandidate>,
    val reason: String?,
    val generated: Int,
    val candidateCount: Int,
    val frozenAtStage: String,
    val publicEvidenceIds: List<String> = emptyList(),
    val boundedScope: EvidenceScope? = null,
)

data class A9StudentSubmission(
    val baseline: A9BaselineId,
    val diagnosticClaim: A9DiagnosticClaim,
    val candidateBodies: List<Expr>,
    val candidateCanonicals: List<String>,
    val boundedScope: EvidenceScope?,
    val queryDomain: List<Map<String, String>>,
    val evidenceIds: List<String>,
    val searchRecord: A9FrozenSearchRecord? = null,
    val claimedGlobal: Boolean = false,
    val causalClaims: Set<A9CausalClaim> = emptySet(),
)

fun A9BaselineResult.toSubmission(
    queryDomain: List<Map<String, String>> = this.queryDomain,
    causalClaims: Set<A9CausalClaim> = emptySet(),
): A9StudentSubmission = A9StudentSubmission(
    baseline = baseline,
    diagnosticClaim = diagnosticClaim,
    candidateBodies = candidateBodies,
    candidateCanonicals = candidateCanonicals,
    boundedScope = boundedScope,
    queryDomain = queryDomain,
    evidenceIds = evidenceIds,
    searchRecord = searchRecord,
    claimedGlobal = claimedGlobal,
    causalClaims = causalClaims,
)
data class A9ExecutionContext(
    val runId: String,
    val evidenceIds: List<String>,
    val budget: DiagnosticBudget,
    val allowedQueryIds: Set<String>,
    val allowedQueryVisibles: Map<String, Map<String, String>> = emptyMap(),
    val stageOrder: List<String>,
    val hiddenScoringStage: String = "hidden_scoring",
)

data class A9TrustedActionEvent(
    val runId: String,
    val stage: String,
    val actionKind: A9ActionKind,
    val requestedSampleId: String? = null,
    val repeatedObjectId: String? = null,
    val recordedObservation: Observation? = null,
    val measurementKind: MeasurementKind? = null,
    val independentEvidence: Boolean = false,
    val actualCost: Int = 0,
)
