// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02D P0：每轮先回放已付费证据，再用同一不可变预测快照选查询；
// Unknown 不转 False；ERROR 与 BUDGET 分离；行为类唯一只给 provisional/one-survivor。
package logic.e3.pack02

import logic.capability.*

data class MRX2DChannel(
    val name: String,
    val pool: List<Map<String, String>>,
    val ledger: MRX2Ledger,
    val budget: Int,
)

data class MRX2DObservation(
    val channel: String,
    val inputKey: String,
    val label: Boolean,
    val queryIndex: Int,
    val replayed: Boolean,
    val eliminated: Int,
)

data class MRX2DResult(
    val status: String,
    val selected: StudyCandidate?,
    val initialCandidates: Int,
    val initialClasses: Int,
    val remainingCandidates: Int,
    val remainingClasses: Int,
    val replayEliminated: Int,
    val reusedLabels: Int,
    val newQueries: Int,
    val observations: List<MRX2DObservation>,
    val note: String,
)

/**
 * 保守的候选辨识：
 * - replay：先读取账本已有标签并淘汰矛盾候选，再计算行为类；
 * - snapshot：一次计算候选×输入预测，后续选择查询/冻结都用同一份；
 * - Unknown 进入 `unknown` 集合，不能被淘汰，也不能当 false；
 * - 同一个输入不重复付费；已有标签计入 reused；
 * - 一行为类只给 PROVISIONAL，单候选给 ONE_SURVIVOR_WITH_EVIDENCE，不声称全域正确。
 */
class MRX2DActiveIdentifier(
    private val channels: List<MRX2DChannel>,
    private val predictor: (StudyCandidate, Map<String, String>) -> Boolean?,
    private val meter: MRX2Cost,
) {
    fun identify(candidatesIn: List<StudyCandidate>): MRX2DResult {
        var candidates = candidatesIn
        val initialClasses = classes(candidates)
        val observations = ArrayList<MRX2DObservation>()
        var replayEliminated = 0
        var reused = 0
        var newQueries = 0

        // 1) 证据回放：已有标签先淘汰；Unknown 单独保留。
        for (channel in channels) {
            for (entry in channel.ledger.entries()) {
                val input = channel.pool.firstOrNull { C8Domain.key(it) == entry.inputKey } ?: continue
                val before = candidates.size
                val unknown = HashSet<String>()
                candidates = candidates.filter { candidate ->
                    val prediction = predictor(candidate, input)
                    meter.interpreterCalls++
                    if (prediction == null) {
                        unknown += candidate.canonical
                        true
                    } else {
                        prediction == entry.rawLabel
                    }
                }
                reused += before - candidates.size
                replayEliminated += before - candidates.size
                if (before != candidates.size) {
                    observations += MRX2DObservation(
                        channel.name, entry.inputKey, entry.rawLabel, entry.queryIndex, true,
                        before - candidates.size,
                    )
                }
                if (unknown.isNotEmpty()) {
                    // Unknown 不淘汰，但记录为待调查；不影响仍然 Known 的候选。
                }
            }
        }
        if (candidates.isEmpty()) {
            return result(
                "ALL_REFUTED", null, candidatesIn.size, initialClasses, 0, 0,
                replayEliminated, reused, newQueries, observations, "evidence_replay_refuted_all",
            )
        }

        while (true) {
            val currentClasses = classes(candidates)
            if (currentClasses == 1) {
                val selected = candidates.minWithOrNull(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
                return result(
                    if (candidates.size == 1 && observations.isNotEmpty()) "ONE_SURVIVOR_WITH_EVIDENCE"
                    else "SINGLE_BEHAVIOR_CLASS_PROVISIONAL",
                    selected, candidatesIn.size, initialClasses, candidates.size, 1,
                    replayEliminated, reused, newQueries, observations,
                    if (candidates.size == 1) "single_candidate_with_evidence" else "behaviorally_equivalent_on_legal_channels",
                )
            }
            // 2) 选中一个查询；预测只从快照读，不重新执行、不使用 ?: false。
            val snapshot = HashMap<String, Map<String, Boolean?>>()
            for (candidate in candidates) {
                val row = LinkedHashMap<String, Boolean?>()
                for (channel in channels) for (input in channel.pool) {
                    row[C8Domain.key(input)] = predictor(candidate, input)
                    meter.interpreterCalls++
                }
                snapshot[candidate.canonical] = row
            }
            var bestChannel: MRX2DChannel? = null
            var bestInput: Map<String, String>? = null
            var bestScore = -1
            var unknownBlocked = false
            for (channel in channels) {
                for (input in channel.pool) {
                    val key = C8Domain.key(input)
                    if (channel.ledger.entries().any { it.inputKey == key }) continue
                    val predictions = ArrayList<Boolean>()
                    var blocked = false
                    for (candidate in candidates) {
                        val p = snapshot.getValue(candidate.canonical).getValue(key)
                        if (p == null) { blocked = true; break }
                        predictions += p
                    }
                    if (blocked) { unknownBlocked = true; continue }
                    val trueCount = predictions.count { it }
                    val falseCount = predictions.size - trueCount
                    if (trueCount == 0 || falseCount == 0) continue
                    val score = trueCount * falseCount
                    if (score > bestScore) { bestScore = score; bestChannel = channel; bestInput = input }
                }
            }
            if (bestChannel == null || bestInput == null) {
                return result(
                    if (unknownBlocked) "UNKNOWN_BLOCKED" else "CURRENTLY_UNIDENTIFIABLE",
                    null, candidatesIn.size, initialClasses, candidates.size, currentClasses,
                    replayEliminated, reused, newQueries, observations,
                    if (unknownBlocked) "unknown_prediction_blocks_split" else "no_unqueried_distinguishing_input",
                )
            }
            val key = C8Domain.key(bestInput)
            val predictions = LinkedHashMap<String, Boolean>()
            for (candidate in candidates) {
                predictions[candidate.canonical] = snapshot.getValue(candidate.canonical).getValue(key)!!
            }
            val (label, entry) = bestChannel.ledger.query(bestInput, predictions, candidates.map { it.canonical }, "ACTIVE")
            if (label == null || entry == null) {
                val status = if (bestChannel.ledger.remaining() <= 0) "BUDGET_EXHAUSTED" else "ERROR"
                return result(
                    status, null, candidatesIn.size, initialClasses, candidates.size, currentClasses,
                    replayEliminated, reused, newQueries, observations,
                    if (status == "BUDGET_EXHAUSTED") "channel_budget_exhausted" else "controller_error",
                )
            }
            newQueries++
            val before = candidates.size
            candidates = candidates.filter { predictions[it.canonical] == label }
            observations += MRX2DObservation(
                bestChannel.name, key, label, entry.queryIndex, false, before - candidates.size,
            )
        }
    }

    private fun classes(candidates: List<StudyCandidate>): Int {
        if (candidates.isEmpty()) return 0
        val signatures = candidates.map { candidate ->
            buildString {
                for (channel in channels) for (input in channel.pool) {
                    val p = predictor(candidate, input)
                    meter.interpreterCalls++
                    append(if (p == null) '?' else if (p) '1' else '0')
                }
            }
        }
        return signatures.toSet().size
    }

    private fun result(
        status: String, selected: StudyCandidate?, initialCandidates: Int, initialClasses: Int,
        remainingCandidates: Int, remainingClasses: Int, replayEliminated: Int, reused: Int,
        newQueries: Int, observations: List<MRX2DObservation>, note: String,
    ) = MRX2DResult(
        status, selected, initialCandidates, initialClasses, remainingCandidates, remainingClasses,
        replayEliminated, reused, newQueries, observations, note,
    )
}
