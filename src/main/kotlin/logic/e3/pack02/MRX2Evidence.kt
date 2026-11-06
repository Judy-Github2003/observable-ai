// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02 第一关：证据语义与真实成本。标签、冻结预测、解释器结果、覆盖范围分开记录；
// 禁止用占位预测冒充学生预测，禁止把 Unknown 当 PASS。
package logic.e3.pack02

enum class MRX2ValidationStatus {
    UNVERIFIED,
    UNKNOWN_PREDICTION,
    NO_VALID_PREDICTION,
    PARTIAL_ON_CHALLENGE,
    PASS_ON_CHALLENGE,
    COUNTEREXAMPLE,
    BUDGET_EXHAUSTED,
    ERROR,
}

data class MRX2EvidenceEntry(
    val inputKey: String,
    val rawLabel: Boolean,
    val queryIndex: Int,
    val source: String,
    val candidatePredictions: Map<String, Boolean>,
    val usedBy: MutableSet<String> = linkedSetOf(),
)

data class MRX2Validation(
    val status: MRX2ValidationStatus,
    val scope: String,
    val checks: Int,
    val covered: Int,
    val counterexamples: Int,
    val unknownPrediction: Boolean,
    val note: String,
)

data class MRX2Cost(
    var oracleNewQueries: Int = 0,
    var oracleReuseUses: Int = 0,
    var generatedCandidates: Int = 0,
    var interpreterCalls: Int = 0,
    var executionSteps: Long = 0,
    var verificationCalls: Int = 0,
    var actionWallMillis: Long = 0,
    var totalWallMillis: Long = 0,
    var processWallMillis: Long = 0,
)

/**
 * MRX-02 证据账本：
 * - query() 必须携带真实候选预测，不接受 "__ledger" 之类占位；
 * - 每个条目保留原始标签、查询序号、来源、冻结预测和使用记录；
 * - PASS 只能是 PASS_ON_CHALLENGE，并带 scope；
 * - Unknown / Error / 预算耗尽 / 无反例都不是 PASS。
 */
class MRX2Ledger(
    private val pool: List<Map<String, String>>,
    private val controller: StudyQueryController,
    private val budget: Int,
    private val meter: MRX2Cost,
) {
    private val entries = LinkedHashMap<String, MRX2EvidenceEntry>()
    private var spent = 0
    private var errors = 0

    fun entries(): List<MRX2EvidenceEntry> = entries.values.toList()
    fun newQueries(): Int = spent
    fun remaining(): Int = (budget - spent).coerceAtLeast(0)
    fun errorCount(): Int = errors

    fun query(
        input: Map<String, String>,
        predictions: Map<String, Boolean>,
        candidateIds: List<String>,
        tag: String,
    ): Pair<Boolean?, MRX2EvidenceEntry?> {
        require(candidateIds.isNotEmpty()) { "no_candidate_predictions" }
        require(candidateIds.toSet() == predictions.keys) { "prediction_coverage_mismatch" }
        val key = C8Domain.key(input)
        entries[key]?.let { entry ->
            entry.usedBy += tag
            meter.oracleReuseUses++
            return entry.rawLabel to entry
        }
        if (spent >= budget) return null to null
        val observation = try {
            val frozen = controller.freeze(spent, input, predictions, candidateIds)
            controller.reveal(frozen)
        } catch (_: IllegalArgumentException) {
            errors++
            return null to null
        } catch (_: Exception) {
            errors++
            return null to null
        }
        spent++
        meter.oracleNewQueries++
        val entry = MRX2EvidenceEntry(
            key, observation.label, spent - 1, "trusted_oracle", predictions.toMap(), linkedSetOf(tag),
        )
        entries[key] = entry
        return observation.label to entry
    }

    /**
     * 只检查候选在已有/新增 CHALLENGE 标签上的表现。
     * predictor 返回 null 表示 Unknown/Error；此类检查记为 unknown，不计入 PASS。
     */
    fun validate(
        candidateId: String,
        predictor: (Map<String, String>) -> Boolean?,
        tag: String,
        maxNew: Int,
        meter: MRX2Cost,
    ): MRX2Validation {
        var checks = 0
        var counter = 0
        var unknown = false
        var newQueries = 0
        var usedLabels = 0
        for (input in pool) {
            val key = C8Domain.key(input)
            val prediction = predictor(input)
            meter.interpreterCalls++
            if (prediction == null) {
                unknown = true
                continue
            }
            val entry = entries[key]
            if (entry != null) {
                checks++
                usedLabels++
                meter.oracleReuseUses++
                entry.usedBy += tag
                if (entry.rawLabel != prediction) counter++
                continue
            }
            if (newQueries >= maxNew || remaining() <= 0) continue
            val (label, created) = query(input, mapOf(candidateId to prediction), listOf(candidateId), tag)
            newQueries++
            if (label == null || created == null) continue
            checks++
            usedLabels++
            if (label != prediction) counter++
        }
        val covered = entries.size
        val status = when {
            counter > 0 -> MRX2ValidationStatus.COUNTEREXAMPLE
            checks == 0 && unknown -> MRX2ValidationStatus.UNKNOWN_PREDICTION
            checks == 0 -> MRX2ValidationStatus.NO_VALID_PREDICTION
            covered < pool.size -> if (remaining() <= 0) MRX2ValidationStatus.BUDGET_EXHAUSTED else MRX2ValidationStatus.PARTIAL_ON_CHALLENGE
            checks < pool.size -> MRX2ValidationStatus.PARTIAL_ON_CHALLENGE
            else -> MRX2ValidationStatus.PASS_ON_CHALLENGE
        }
        return MRX2Validation(
            status,
            "CHALLENGE_POOL(${pool.size})",
            checks, covered, counter, unknown,
            when (status) {
                MRX2ValidationStatus.PASS_ON_CHALLENGE -> "all_challenge_inputs_known_and_matching"
                MRX2ValidationStatus.PARTIAL_ON_CHALLENGE -> "coverage=$covered/${pool.size}"
                MRX2ValidationStatus.UNKNOWN_PREDICTION -> "candidate_prediction_unknown"
                MRX2ValidationStatus.NO_VALID_PREDICTION -> "no_valid_prediction"
                MRX2ValidationStatus.COUNTEREXAMPLE -> "at_least_one_counterexample"
                MRX2ValidationStatus.BUDGET_EXHAUSTED -> "challenge_budget_exhausted"
                MRX2ValidationStatus.ERROR -> "query_error"
                MRX2ValidationStatus.UNVERIFIED -> "unverified"
            },
        )
    }

    fun filter(
        candidates: List<StudyCandidate>,
        predictor: (StudyCandidate, Map<String, String>) -> Boolean?,
        tag: String,
        maxNew: Int,
        meter: MRX2Cost,
    ): Pair<List<StudyCandidate>, MRX2Validation> {
        var current = candidates
        var queries = 0
        while (true) {
            val labelled = pool.filter { entries.containsKey(C8Domain.key(it)) }
            current = current.filter { candidate ->
                labelled.all { input ->
                    val prediction = predictor(candidate, input)
                    meter.interpreterCalls++
                    prediction == null || prediction == entries.getValue(C8Domain.key(input)).rawLabel
                }
            }
            if (current.isEmpty()) {
                return emptyList<StudyCandidate>() to MRX2Validation(
                    MRX2ValidationStatus.COUNTEREXAMPLE, "CHALLENGE_POOL(${pool.size})",
                    0, entries.size, 0, false, "all_survivors_refuted",
                )
            }
            val selected = current.minWith(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
            val validation = validate(
                selected.canonical, { input -> predictor(selected, input) }, tag,
                maxOf(0, minOf(maxNew - queries, remaining())), meter,
            )
            if (validation.status == MRX2ValidationStatus.COUNTEREXAMPLE) {
                current = current - selected
                continue
            }
            if (current.size <= 1 || queries >= maxNew || remaining() <= 0) return current to validation
            val distinguishing = pool.firstOrNull { input ->
                if (entries.containsKey(C8Domain.key(input))) return@firstOrNull false
                current.mapNotNull { predictor(it, input) }.toSet().size > 1
            } ?: return current to validation
            val predictions = current.mapNotNull { candidate ->
                predictor(candidate, distinguishing)?.let { candidate.canonical to it }
            }.toMap()
            meter.interpreterCalls += current.size
            if (predictions.size != current.size) return current to validation
            val (label, _) = query(distinguishing, predictions, current.map { it.canonical }, tag)
            queries++
            if (label == null) return current to validation
            current = current.filter { predictions[it.canonical] == label }
        }
    }
}
