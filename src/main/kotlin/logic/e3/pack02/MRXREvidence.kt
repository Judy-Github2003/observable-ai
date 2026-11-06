// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 证据账本；修复“0 次检查=通过”、重复查询、未验证与通过混淆。
package logic.e3.pack02

import logic.capability.CapValue
import logic.capability.EvalResult
import logic.capability.Expr
import logic.capability.CapabilityDefinition
import java.nio.file.Path

enum class MRXREvidenceStatus {
    UNVERIFIED,
    PARTIAL,
    PASS,
    COUNTEREXAMPLE,
    BUDGET_EXHAUSTED,
    ERROR,
}

data class MRXREvidenceEntry(
    val inputKey: String,
    val label: Boolean,
    val queryIndex: Int,
    val usedBy: MutableSet<String> = linkedSetOf(),
)

data class MRXRValidation(
    val status: MRXREvidenceStatus,
    val checks: Int,
    val counterexamples: Int,
    val labelsAvailable: Int,
    val queriesSpent: Int,
    val usedEntries: List<MRXREvidenceEntry>,
    val note: String,
) {
    val isPass: Boolean get() = status == MRXREvidenceStatus.PASS
    val hasCounterexample: Boolean get() = status == MRXREvidenceStatus.COUNTEREXAMPLE
    val isVerified: Boolean get() = checks > 0 && status != MRXREvidenceStatus.UNVERIFIED
}

/**
 * CHALLENGE 证据账本：
 * - 标签只经 StudyQueryController 付费获得；
 * - 相同输入第二次使用直接读账本，不重复付费；
 * - 0 次检查永远是 UNVERIFIED，不是 PASS；
 * - 预算不足与部分检查是独立状态；
 * - HELDOUT 不得进入本账本（构造时只接收 CHALLENGE 池）。
 */
class MRXRChallengeLedger(
    private val pool: List<Map<String, String>>,
    private val controller: StudyQueryController,
    private val budget: Int,
) {
    private val entries = LinkedHashMap<String, MRXREvidenceEntry>()
    private var spent = 0
    private var duplicateErrors = 0
    private var oracleErrors = 0

    fun labelsAvailable(): Int = entries.size
    fun queriesSpent(): Int = spent
    fun remaining(): Int = (budget - spent).coerceAtLeast(0)
    fun duplicateErrorCount(): Int = duplicateErrors
    fun oracleErrorCount(): Int = oracleErrors
    fun allEntries(): List<MRXREvidenceEntry> = entries.values.toList()

    /** 读取已有标签；未查询返回 null，不产生成本。 */
    fun labelOf(input: Map<String, String>): Boolean? = entries[C8Domain.key(input)]?.label

    /** 付费查询一个新输入；已有标签直接复用。 */
    fun query(input: Map<String, String>): Pair<Boolean?, MRXREvidenceStatus> {
        val key = C8Domain.key(input)
        entries[key]?.let { return it.label to MRXREvidenceStatus.PASS }
        if (spent >= budget) return null to MRXREvidenceStatus.BUDGET_EXHAUSTED
        val observation = try {
            val frozen = controller.freeze(
                spent, input, mapOf("__ledger" to false), listOf("__ledger"),
            )
            controller.reveal(frozen)
        } catch (_: IllegalArgumentException) {
            duplicateErrors++
            return null to MRXREvidenceStatus.ERROR
        } catch (_: Exception) {
            oracleErrors++
            return null to MRXREvidenceStatus.ERROR
        }
        spent++
        entries[key] = MRXREvidenceEntry(key, observation.label, spent - 1)
        return observation.label to MRXREvidenceStatus.PASS
    }

    /**
     * 检查候选在已有标签上的表现；可按需查询最多 maxNew 个新输入。
     * 返回状态严格区分 UNVERIFIED / PARTIAL / PASS / COUNTEREXAMPLE / BUDGET_EXHAUSTED / ERROR。
     */
    fun validate(
        candidateId: String,
        predictor: (Map<String, String>) -> Boolean?,
        tag: String,
        maxNew: Int,
    ): MRXRValidation {
        var checks = 0
        var counterexamples = 0
        val used = ArrayList<MRXREvidenceEntry>()
        // 先复用已有证据，不付费。
        for ((key, entry) in entries) {
            val input = pool.firstOrNull { C8Domain.key(it) == key } ?: continue
            val predicted = predictor(input) ?: continue
            checks++
            entry.usedBy += tag
            used += entry
            if (predicted != entry.label) counterexamples++
        }
        if (counterexamples > 0) {
            return MRXRValidation(
                MRXREvidenceStatus.COUNTEREXAMPLE, checks, counterexamples, entries.size, spent, used,
                "existing_challenge_labels_contradict",
            )
        }
        // 再按池顺序查询新输入；最多 maxNew 个。
        var newQueries = 0
        for (input in pool) {
            if (checks >= pool.size) break
            if (newQueries >= maxNew || remaining() <= 0) break
            val key = C8Domain.key(input)
            if (entries.containsKey(key)) continue
            val predicted = predictor(input) ?: continue
            val (label, status) = query(input)
            if (label == null) {
                return MRXRValidation(
                    if (status == MRXREvidenceStatus.BUDGET_EXHAUSTED) MRXREvidenceStatus.BUDGET_EXHAUSTED else MRXREvidenceStatus.ERROR,
                    checks, 0, entries.size, spent, used,
                    "query_failed_${status.name.lowercase()}",
                )
            }
            newQueries++
            checks++
            entries[key]?.usedBy?.add(tag)
            entries[key]?.let { used += it }
            if (predicted != label) counterexamples++
            if (counterexamples > 0) break
        }
        val allLabels = entries.size
        val status = when {
            counterexamples > 0 -> MRXREvidenceStatus.COUNTEREXAMPLE
            checks == 0 -> MRXREvidenceStatus.UNVERIFIED
            allLabels >= pool.size && checks >= pool.size -> MRXREvidenceStatus.PASS
            remaining() <= 0 && checks < pool.size -> MRXREvidenceStatus.BUDGET_EXHAUSTED
            else -> MRXREvidenceStatus.PARTIAL
        }
        return MRXRValidation(
            status, checks, counterexamples, allLabels, spent, used,
            when (status) {
                MRXREvidenceStatus.PASS -> "all_challenge_inputs_checked"
                MRXREvidenceStatus.PARTIAL -> "some_challenge_inputs_checked"
                MRXREvidenceStatus.BUDGET_EXHAUSTED -> "challenge_budget_exhausted_before_full_check"
                else -> "no_evidence"
            },
        )
    }

    /** 在候选集合上按已有挑战标签淘汰；必要时查询最能区分候选的输入。 */
    fun filter(
        candidates: List<StudyCandidate>,
        predictor: (StudyCandidate, Map<String, String>) -> Boolean?,
        tag: String,
        maxNew: Int,
    ): Pair<List<StudyCandidate>, MRXRValidation> {
        var current = candidates
        var newQueries = 0
        while (true) {
            val withLabels = pool.filter { entries.containsKey(C8Domain.key(it)) }
            current = current.filter { candidate ->
                withLabels.all { input ->
                    val label = entries.getValue(C8Domain.key(input)).label
                    val predicted = predictor(candidate, input)
                    predicted == null || predicted == label
                }
            }
            if (current.isEmpty()) {
                return emptyList<StudyCandidate>() to MRXRValidation(
                    MRXREvidenceStatus.COUNTEREXAMPLE, 0, 0, entries.size, spent, emptyList(), "all_survivors_refuted",
                )
            }
            val selected = current.minWith(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
            // 关键修复：只剩一个候选还不等于已验证；必须把剩余 CHALLENGE 标签补齐，
            // 直到 PASS / COUNTEREXAMPLE / 预算耗尽，0 次检查不能当作通过。
            val validation = validate(
                selected.canonical,
                { input -> predictor(selected, input) },
                tag,
                maxNew = maxOf(0, minOf(maxNew - newQueries, remaining())),
            )
            if (validation.status == MRXREvidenceStatus.COUNTEREXAMPLE) {
                current = current - selected
                continue
            }
            if (current.size <= 1 || newQueries >= maxNew || remaining() <= 0) {
                return current to validation
            }
            val distinguishing = pool.firstOrNull { input ->
                if (entries.containsKey(C8Domain.key(input))) return@firstOrNull false
                val values = current.mapNotNull { predictor(it, input) }.toSet()
                values.size > 1
            } ?: return current to validation
            val predicted = current.associateWith { predictor(it, distinguishing) }
            query(distinguishing)
            newQueries++
            val label = labelOf(distinguishing) ?: return current to validation
            current = current.filter { predicted[it] == label }
        }
    }
}
