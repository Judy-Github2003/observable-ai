// 实现者：DeepSeek Harness，2026-11-09，ABDMNV-01 M1 切片。
// 槽位提交协议（v2 §6.1）与 R1–R6 匹配（v2 §6.3）。匹配器只做判定，不生成概念。
package logic.e3.abdmnv

import logic.capability.CapValue
import logic.capability.EvalResult

enum class AbdmnvVerdict { MATCH, PARTIAL, CONTRADICTION, TYPE_ERROR, EXEC_ERROR, DEPTH_LIMIT, UNSCORABLE }

data class AbdmnvScore(
    val verdict: AbdmnvVerdict,
    val covered: Int,
    val total: Int,
    val contradictions: Int,
    val detail: String,
)

class AbdmnvScorer(
    private val symbols: AbdmnvSymbols,
    private val tauCoverage: Double = 0.90,
) {
    private val checker = AbdmnvTypeChecker(symbols)

    /** 类别/复合概念在对象集上的 R1–R6。目标 T 必须在 V 上全定义（否则 UNSCORABLE）。 */
    fun scoreOnObjects(concept: AbdmnvConcept, target: AbdmnvConcept, world: AbdmnvWorld, validation: List<String>): AbdmnvScore {
        val typeCode = checker.checkConcept(concept)
        if (typeCode != null) {
            return AbdmnvScore(
                if (typeCode == "DEPTH_LIMIT") AbdmnvVerdict.DEPTH_LIMIT else AbdmnvVerdict.TYPE_ERROR,
                0, validation.size, 0, typeCode
            )
        }
        val targetCode = checker.checkConcept(target)
        if (targetCode != null) return AbdmnvScore(AbdmnvVerdict.UNSCORABLE, 0, validation.size, 0, "target_invalid:$targetCode")

        val evaluator = AbdmnvEvaluator(world, symbols.concepts)
        var covered = 0
        var contradictions = 0
        var sawTrue = false
        var sawFalse = false
        for (obj in validation) {
            val cv = evalUnary(concept, obj, evaluator)
            if (cv is EvalResult.Error) return AbdmnvScore(AbdmnvVerdict.EXEC_ERROR, covered, validation.size, contradictions, cv.code)
            val tv = evalUnary(target, obj, evaluator)
            if (tv is EvalResult.Error) return AbdmnvScore(AbdmnvVerdict.UNSCORABLE, covered, validation.size, contradictions, "target_error:${tv.code}")
            if (tv !is EvalResult.Known || (tv.value as? CapValue.Bool) == null) {
                return AbdmnvScore(AbdmnvVerdict.UNSCORABLE, covered, validation.size, contradictions, "target_not_total")
            }
            val truth = (tv.value as CapValue.Bool).value
            when (cv) {
                is EvalResult.Unknown -> Unit
                is EvalResult.Known -> {
                    val actual = (cv.value as? CapValue.Bool)?.value
                        ?: return AbdmnvScore(AbdmnvVerdict.TYPE_ERROR, covered, validation.size, contradictions, "concept_not_bool")
                    covered++
                    if (actual) sawTrue = true else sawFalse = true
                    if (actual != truth) contradictions++
                }

                is EvalResult.Error -> return AbdmnvScore(AbdmnvVerdict.EXEC_ERROR, covered, validation.size, contradictions, cv.code)
            }
        }
        if (contradictions > 0) return AbdmnvScore(AbdmnvVerdict.CONTRADICTION, covered, validation.size, contradictions, "counterexample")
        val coverage = if (validation.isEmpty()) 0.0 else covered.toDouble() / validation.size
        if (coverage < tauCoverage) return AbdmnvScore(AbdmnvVerdict.PARTIAL, covered, validation.size, 0, "coverage=$coverage")
        if (!sawTrue || !sawFalse) return AbdmnvScore(AbdmnvVerdict.PARTIAL, covered, validation.size, 0, "non_trivial_failed")
        return AbdmnvScore(AbdmnvVerdict.MATCH, covered, validation.size, 0, "ok")
    }

    /** 关系概念在对象对上的 R1–R6。 */
    fun scoreOnPairs(concept: AbdmnvConcept, target: AbdmnvConcept, world: AbdmnvWorld, pairs: List<Pair<String, String>>): AbdmnvScore {
        val typeCode = checker.checkConcept(concept)
        if (typeCode != null) {
            return AbdmnvScore(
                if (typeCode == "DEPTH_LIMIT") AbdmnvVerdict.DEPTH_LIMIT else AbdmnvVerdict.TYPE_ERROR,
                0, pairs.size, 0, typeCode
            )
        }
        val evaluator = AbdmnvEvaluator(world, symbols.concepts)
        var covered = 0
        var contradictions = 0
        var sawTrue = false
        var sawFalse = false
        for ((a, b) in pairs) {
            val cv = evalBinary(concept, a, b, evaluator)
            val tv = evalBinary(target, a, b, evaluator)
            if (cv is EvalResult.Error) return AbdmnvScore(AbdmnvVerdict.EXEC_ERROR, covered, pairs.size, contradictions, cv.code)
            if (tv !is EvalResult.Known) return AbdmnvScore(AbdmnvVerdict.UNSCORABLE, covered, pairs.size, contradictions, "target_not_total")
            val truth = (tv.value as CapValue.Bool).value
            when (cv) {
                is EvalResult.Unknown -> Unit
                is EvalResult.Known -> {
                    val actual = (cv.value as? CapValue.Bool)?.value
                        ?: return AbdmnvScore(AbdmnvVerdict.TYPE_ERROR, covered, pairs.size, contradictions, "concept_not_bool")
                    covered++
                    if (actual) sawTrue = true else sawFalse = true
                    if (actual != truth) contradictions++
                }

                else -> Unit
            }
        }
        if (contradictions > 0) return AbdmnvScore(AbdmnvVerdict.CONTRADICTION, covered, pairs.size, contradictions, "counterexample")
        val coverage = if (pairs.isEmpty()) 0.0 else covered.toDouble() / pairs.size
        if (coverage < tauCoverage) return AbdmnvScore(AbdmnvVerdict.PARTIAL, covered, pairs.size, 0, "coverage=$coverage")
        if (!sawTrue || !sawFalse) return AbdmnvScore(AbdmnvVerdict.PARTIAL, covered, pairs.size, 0, "non_trivial_failed")
        return AbdmnvScore(AbdmnvVerdict.MATCH, covered, pairs.size, 0, "ok")
    }

    private fun evalUnary(c: AbdmnvConcept, obj: String, evaluator: AbdmnvEvaluator): EvalResult {
        val body = c.body as? AbdmnvBody.Lambda ?: return abdError("not_unary")
        return evaluator.eval(body.body, mapOf(body.params[0] to abdStr(obj)))
    }

    private fun evalBinary(c: AbdmnvConcept, a: String, b: String, evaluator: AbdmnvEvaluator): EvalResult {
        val body = c.body as? AbdmnvBody.Lambda ?: return abdError("not_binary")
        if (body.params.size != 2) return abdError("not_binary")
        return evaluator.eval(body.body, mapOf(body.params[0] to abdStr(a), body.params[1] to abdStr(b)))
    }
}

// ---------------------------------------------------------------------------
// 槽位提交协议
// ---------------------------------------------------------------------------

data class AbdmnvSlot(val id: String, val kind: AbdmnvKind, val sort: AbdmnvSort)

enum class AbdmnvSlotStatus { EMPTY, SUBMITTED, ABSTAIN, UNDETERMINED }

data class AbdmnvCounts(
    val submitted: Int,
    val matched: Int,
    val contradicted: Int,
    val unscorable: Int,
    val abstained: Int,
    val undetermined: Int,
    val precision: Double?,
)

class AbdmnvBoard(private val slots: List<AbdmnvSlot>) {
    private val status = MutableList(slots.size) { AbdmnvSlotStatus.EMPTY }
    private val conceptAt = arrayOfNulls<String>(slots.size)
    private val slotOfConcept = mutableMapOf<String, Int>()
    private val verdictAt = arrayOfNulls<AbdmnvVerdict>(slots.size)
    private val reasonAt = arrayOfNulls<String>(slots.size)
    private val abstainAt = BooleanArray(slots.size)
    private val undeterminedAt = BooleanArray(slots.size)

    val slotCount: Int get() = slots.size

    fun submit(slotIndex: Int, conceptId: String, version: Int = 1): String? {
        if (slotIndex !in slots.indices) return "SUBMISSION_OVERFLOW"
        if (status[slotIndex] != AbdmnvSlotStatus.EMPTY) return "SLOT_DUPLICATE"
        if (slotOfConcept.containsKey(conceptId)) return "CONCEPT_DUPLICATE"
        status[slotIndex] = AbdmnvSlotStatus.SUBMITTED
        conceptAt[slotIndex] = "$conceptId@$version"
        slotOfConcept[conceptId] = slotIndex
        return null
    }

    fun submitBySlotId(slotId: String, conceptId: String, version: Int = 1): String? {
        val idx = slots.indexOfFirst { it.id == slotId }
        if (idx < 0) return "UNKNOWN_SLOT"
        return submit(idx, conceptId, version)
    }

    fun abstain(slotIndex: Int): String? {
        if (slotIndex !in slots.indices) return "SUBMISSION_OVERFLOW"
        if (status[slotIndex] != AbdmnvSlotStatus.EMPTY) return "SLOT_DUPLICATE"
        status[slotIndex] = AbdmnvSlotStatus.ABSTAIN
        abstainAt[slotIndex] = true
        return null
    }

    fun undetermined(slotIndex: Int, reason: String): String? {
        if (slotIndex !in slots.indices) return "SUBMISSION_OVERFLOW"
        if (status[slotIndex] != AbdmnvSlotStatus.EMPTY) return "SLOT_DUPLICATE"
        status[slotIndex] = AbdmnvSlotStatus.UNDETERMINED
        undeterminedAt[slotIndex] = true
        reasonAt[slotIndex] = reason
        return null
    }

    fun recordVerdict(slotIndex: Int, verdict: AbdmnvVerdict) {
        require(slotIndex in slots.indices) { "slot out of range" }
        require(status[slotIndex] == AbdmnvSlotStatus.SUBMITTED) { "verdict without submission" }
        verdictAt[slotIndex] = verdict
    }

    fun slotStatus(index: Int): AbdmnvSlotStatus = status[index]
    fun conceptAtSlot(index: Int): String? = conceptAt[index]
    fun verdictAtSlot(index: Int): AbdmnvVerdict? = verdictAt[index]
    fun reasonAtSlot(index: Int): String? = reasonAt[index]

    fun counts(): AbdmnvCounts {
        val submitted = status.count { it == AbdmnvSlotStatus.SUBMITTED }
        val matched = status.indices.count { status[it] == AbdmnvSlotStatus.SUBMITTED && verdictAt[it] == AbdmnvVerdict.MATCH }
        val contradicted = status.indices.count { status[it] == AbdmnvSlotStatus.SUBMITTED && verdictAt[it] == AbdmnvVerdict.CONTRADICTION }
        val unscorable = status.indices.count { status[it] == AbdmnvSlotStatus.SUBMITTED && verdictAt[it] == AbdmnvVerdict.UNSCORABLE }
        val abstained = status.count { it == AbdmnvSlotStatus.ABSTAIN || it == AbdmnvSlotStatus.EMPTY }
        val undetermined = status.count { it == AbdmnvSlotStatus.UNDETERMINED }
        val precision = if (submitted == 0) null else matched.toDouble() / submitted
        return AbdmnvCounts(submitted, matched, contradicted, unscorable, abstained, undetermined, precision)
    }
}
