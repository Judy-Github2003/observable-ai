// 合并实现（DeepSeek Harness, 2026-11-09）：增量槽位板（参考实现用的是批量 SubmissionGuard）。
package observable.ai.abdmnv.m1

enum class CombinedSlotState { EMPTY, SUBMITTED, ABSTAIN, UNDETERMINED }

data class CombinedSubmissionSummary(
    val submitted: Int,
    val matched: Int,
    val contradicted: Int,
    val unscorable: Int,
    val abstained: Int,
    val undetermined: Int,
    val precision: Double?,
)

class CombinedSlotBoard(
    private val slots: List<Slot>,
    /** 可信概念来源；未提供时 submit(ref) 无法校验 kind/sort，必须用 submitConcept 或先注入 catalog。 */
    private val resolver: ConceptCatalog? = null,
) {
    private val state = MutableList(slots.size) { CombinedSlotState.EMPTY }
    private val conceptAt = arrayOfNulls<ConceptRef>(slots.size)
    private val usedConcepts = mutableMapOf<String, Int>()
    private val verdict = arrayOfNulls<ScoreStatus>(slots.size)
    private val reasonAt = arrayOfNulls<String>(slots.size)

    val size: Int get() = slots.size

    /** GATE-04：只拿到 ref 时必须能解析出可信概念，否则拒绝；不可仅凭调用者参数。 */
    fun submit(index: Int, ref: ConceptRef): String? {
        val catalog = resolver ?: return "UNVERIFIED_CONCEPT"
        val concept = catalog.resolve(ref) ?: return "UNKNOWN_CONCEPT"
        return submitConcept(index, concept)
    }

    /** 显式提交概念对象：校验 kind/sort 与槽位一致后写入。 */
    fun submitConcept(index: Int, concept: Concept): String? {
        if (index !in slots.indices) return "SUBMISSION_OVERFLOW"
        if (state[index] != CombinedSlotState.EMPTY) return "SLOT_DUPLICATE"
        val slot = slots[index]
        if (concept.kind != slot.kind) return "KIND_MISMATCH"
        if (concept.output != slot.sort) return "SORT_MISMATCH"
        val ref = concept.ref
        val key = "${ref.id}@${ref.version}"
        if (usedConcepts.containsKey(key)) return "CONCEPT_DUPLICATE"
        state[index] = CombinedSlotState.SUBMITTED
        conceptAt[index] = ref
        usedConcepts[key] = index
        return null
    }

    fun abstain(index: Int): String? {
        if (index !in slots.indices) return "SUBMISSION_OVERFLOW"
        if (state[index] != CombinedSlotState.EMPTY) return "SLOT_DUPLICATE"
        state[index] = CombinedSlotState.ABSTAIN
        return null
    }

    fun undetermined(index: Int, reason: String): String? {
        if (index !in slots.indices) return "SUBMISSION_OVERFLOW"
        if (state[index] != CombinedSlotState.EMPTY) return "SLOT_DUPLICATE"
        state[index] = CombinedSlotState.UNDETERMINED
        reasonAt[index] = reason
        return null
    }

    fun recordVerdict(index: Int, status: ScoreStatus) {
        require(index in slots.indices) { "slot out of range" }
        require(state[index] == CombinedSlotState.SUBMITTED) { "verdict without submission" }
        verdict[index] = status
    }

    fun stateAt(index: Int): CombinedSlotState = state[index]
    fun conceptAt(index: Int): ConceptRef? = conceptAt[index]
    fun reasonAt(index: Int): String? = reasonAt[index]

    fun summary(): CombinedSubmissionSummary {
        val submitted = state.count { it == CombinedSlotState.SUBMITTED }
        val matched = state.indices.count { state[it] == CombinedSlotState.SUBMITTED && verdict[it] == ScoreStatus.MATCH }
        val contradicted = state.indices.count { state[it] == CombinedSlotState.SUBMITTED && verdict[it] == ScoreStatus.CONTRADICTION }
        val unscorable = state.indices.count { state[it] == CombinedSlotState.SUBMITTED && verdict[it] == ScoreStatus.UNSCORABLE }
        val abstained = state.count { it == CombinedSlotState.ABSTAIN || it == CombinedSlotState.EMPTY }
        val undetermined = state.count { it == CombinedSlotState.UNDETERMINED }
        return CombinedSubmissionSummary(
            submitted, matched, contradicted, unscorable, abstained, undetermined,
            if (submitted == 0) null else matched.toDouble() / submitted,
        )
    }
}
