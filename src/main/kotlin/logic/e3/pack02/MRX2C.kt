// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02C 主动辨识闭环 + 考官端精确覆盖审计。候选保留后先查询，
// 不再按成本直接选；Unknown 不当作 false，也不当作一致。
package logic.e3.pack02

import logic.capability.*

data class MRX2CIdentification(
    val status: String,
    val selected: StudyCandidate?,
    val initialCandidates: Int,
    val initialClasses: Int,
    val remainingCandidates: Int,
    val remainingClasses: Int,
    val queries: Int,
    val reusedLabels: Int,
    val observablePool: Int,
    val note: String,
)

class MRX2CActiveIdentifier(
    private val legalPool: List<Map<String, String>>,
    private val ledger: MRX2Ledger,
    private val predictor: (StudyCandidate, Map<String, String>) -> Boolean?,
    private val meter: MRX2Cost,
) {
    fun identify(candidatesIn: List<StudyCandidate>, budget: Int): MRX2CIdentification {
        var candidates = candidatesIn
        var queries = 0
        var reused = 0
        val signatures = HashMap<String, String>()
        fun signature(candidate: StudyCandidate): String = buildString {
            for (input in legalPool) {
                val p = predictor(candidate, input)
                append(if (p == null) '?' else if (p) '1' else '0')
            }
        }
        for (candidate in candidates) signatures[candidate.canonical] = signature(candidate)
        val initialClasses = candidates.groupBy { signatures.getValue(it.canonical) }.size
        while (true) {
            if (candidates.isEmpty()) return result(
                "ALL_REFUTED", null, candidatesIn.size, initialClasses, 0, 0, queries, reused, "all_candidates_refuted",
            )
            val classes = candidates.groupBy { signatures.getValue(it.canonical) }
            if (classes.size == 1) {
                val selected = candidates.minWith(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
                return result(
                    "IDENTIFIED_ON_OBSERVABLE_POOL", selected, candidatesIn.size, initialClasses,
                    candidates.size, 1, queries, reused, "single_behavior_class",
                )
            }
            if (queries >= budget || ledger.remaining() <= 0) {
                return result(
                    "BUDGET_EXHAUSTED", null, candidatesIn.size, initialClasses, candidates.size, classes.size,
                    queries, reused, "query_budget",
                )
            }
            var unknownBlocked = false
            var best: Map<String, String>? = null
            var bestScore = -1
            for (input in legalPool) {
                val key = C8Domain.key(input)
                if (ledger.entries().any { it.inputKey == key }) continue
                val predictions = ArrayList<Pair<String, Boolean>>()
                var blocked = false
                for (candidate in candidates) {
                    val p = predictor(candidate, input)
                    meter.interpreterCalls++
                    if (p == null) { blocked = true; break }
                    predictions += candidate.canonical to p
                }
                if (blocked) { unknownBlocked = true; continue }
                val trueCount = predictions.count { it.second }
                val falseCount = predictions.size - trueCount
                if (trueCount == 0 || falseCount == 0) continue
                val score = trueCount * falseCount
                if (score > bestScore) { bestScore = score; best = input }
            }
            if (best == null) {
                return result(
                    if (unknownBlocked) "UNKNOWN_BLOCKED" else "CURRENTLY_UNIDENTIFIABLE",
                    null, candidatesIn.size, initialClasses, candidates.size, classes.size,
                    queries, reused, if (unknownBlocked) "unknown_prediction_blocks_split" else "no_distinguishing_input",
                )
            }
            val predictions = LinkedHashMap<String, Boolean>()
            for (candidate in candidates) predictions[candidate.canonical] = predictor(candidate, best) ?: false
            val before = candidates.size
            val (label, entry) = ledger.query(
                best, predictions, candidates.map { it.canonical }, "ACTIVE_IDENTIFY",
            )
            if (label == null || entry == null) {
                return result(
                    "BUDGET_EXHAUSTED", null, candidatesIn.size, initialClasses, candidates.size, classes.size,
                    queries, reused, "oracle_query_failed_or_budget",
                )
            }
            queries++
            if (entry.usedBy.size > 1) reused++
            candidates = candidates.filter { predictions[it.canonical] == label }
            if (candidates.size == before) {
                return result(
                    "CURRENTLY_UNIDENTIFIABLE", null, candidatesIn.size, initialClasses, candidates.size,
                    candidates.groupBy { signatures.getValue(it.canonical) }.size, queries, reused, "no_elimination",
                )
            }
        }
    }

    private fun result(
        status: String, selected: StudyCandidate?, initialCandidates: Int, initialClasses: Int,
        remainingCandidates: Int, remainingClasses: Int, queries: Int, reused: Int, note: String,
    ) = MRX2CIdentification(
        status, selected, initialCandidates, initialClasses, remainingCandidates, remainingClasses,
        queries, reused, legalPool.size, note,
    )
}

enum class MRX2CCoverStatus {
    FEASIBLE_WITHIN_BUDGET,
    PROVED_IMPOSSIBLE_WITHIN_BUDGET,
    OBSERVATIONALLY_INDISTINGUISHABLE,
    UNKNOWN_SEARCH_LIMIT,
}

data class MRX2CCoverResult(
    val status: MRX2CCoverStatus,
    val queryKeys: List<String>,
    val searchNodes: Int,
    val wrongCandidates: Int,
    val note: String,
)

/** 考官专属：精确有界覆盖；贪心失败不等于不可行，超限必须 UNKNOWN。 */
object MRX2CExactCover {
    fun audit(
        witnessPredictions: Map<String, Boolean?>,
        wrong: List<Map<String, Boolean?>>,
        legalInputKeys: List<String>,
        budget: Int,
        nodeLimit: Int = 100_000,
    ): MRX2CCoverResult {
        require(budget >= 0 && nodeLimit >= 0)
        val n = wrong.size
        if (n > 62) return MRX2CCoverResult(
            MRX2CCoverStatus.UNKNOWN_SEARCH_LIMIT, emptyList(), 0, n, "too_many_wrong_candidates",
        )
        if (n == 0) return MRX2CCoverResult(
            MRX2CCoverStatus.FEASIBLE_WITHIN_BUDGET, emptyList(), 0, 0, "no_wrong_candidates",
        )
        val masks = LinkedHashMap<Long, String>()
        for (key in legalInputKeys.distinct()) {
            val w = witnessPredictions[key] ?: continue
            var mask = 0L
            for ((index, candidate) in wrong.withIndex()) {
                val other = candidate[key]
                if (other != null && other != w) mask = mask or (1L shl index)
            }
            if (mask != 0L) masks.putIfAbsent(mask, key)
        }
        val all = (1L shl n) - 1L
        var union = 0L
        for (mask in masks.keys) union = union or mask
        if ((union and all) != all) return MRX2CCoverResult(
            MRX2CCoverStatus.OBSERVATIONALLY_INDISTINGUISHABLE, emptyList(), 0, n, "wrong_candidate_has_no_legal_distinguishing_input",
        )
        var nodes = 0
        var limited = false
        val seen = HashSet<Pair<Long, Int>>()
        val path = ArrayList<String>()
        val found = ArrayList<String>()
        fun dfs(remaining: Long, left: Int): Boolean {
            if (remaining == 0L) { found.clear(); found.addAll(path); return true }
            if (left == 0) return false
            if (nodes >= nodeLimit) { limited = true; return false }
            nodes++
            if (!seen.add(remaining to left)) return false
            var target = -1
            var smallest = Int.MAX_VALUE
            for (i in 0 until n) {
                if (remaining and (1L shl i) == 0L) continue
                val count = masks.count { (mask, _) -> mask and (1L shl i) != 0L }
                if (count < smallest) { smallest = count; target = i }
            }
            if (target < 0) return false
            for ((mask, key) in masks) {
                if (mask and (1L shl target) == 0L) continue
                path.add(key)
                if (dfs(remaining and mask.inv(), left - 1)) return true
                path.removeAt(path.lastIndex)
                if (limited) return false
            }
            return false
        }
        val possible = dfs(all, budget)
        return when {
            possible -> MRX2CCoverResult(
                MRX2CCoverStatus.FEASIBLE_WITHIN_BUDGET, found, nodes, n, "valid_distinguishing_set_found",
            )
            limited -> MRX2CCoverResult(
                MRX2CCoverStatus.UNKNOWN_SEARCH_LIMIT, emptyList(), nodes, n, "node_limit_no_impossibility_claim",
            )
            else -> MRX2CCoverResult(
                MRX2CCoverStatus.PROVED_IMPOSSIBLE_WITHIN_BUDGET, emptyList(), nodes, n, "exact_search_exhausted",
            )
        }
    }
}
