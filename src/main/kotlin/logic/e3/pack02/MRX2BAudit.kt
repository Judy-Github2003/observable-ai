// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02B 考官端 G0–G4 归因；完整 81 点真值只在考试结束后使用，不进入学生。
package logic.e3.pack02

import logic.capability.*

data class MRX2BAuditReport(
    val g0: String,
    val g1GeneratedCorrect: Boolean,
    val g2RetainedCorrect: Boolean,
    val g3: String,
    val g4SelectedCorrect: Boolean,
    val generatedCorrect: Int,
    val retainedCorrect: Int,
    val wrongRetained: Int,
    val separableWrong: Int,
    val queriesNeeded: Int,
    val queryBudget: Int,
    val correctPrunes: List<MRX2BPrune>,
    val note: String,
)

object MRX2BAuditor {
    /**
     * 只允许考官在考试结束后调用。truth 是完整 81 点目标；学生侧没有此函数。
     * grammarExhaustive=true 只能来自可复核的完整有界穷举；否则 G0 只能是 WITNESS_FOUND/UNDETERMINED。
     */
    fun auditAfterExam(
        trace: MRX2BTrace,
        domain: List<Map<String, String>>,
        legalQueries: List<Map<String, String>>,
        truth: (Map<String, String>) -> Boolean,
        library: List<CapabilityDefinition>,
        selected: StudyCandidate?,
        queryBudget: Int,
        grammarExhaustive: Boolean = false,
    ): MRX2BAuditReport {
        fun predict(candidate: StudyCandidate, input: Map<String, String>): Boolean? =
            try { Pack02Knowledge.predict(candidate.body, input, library) } catch (_: Exception) { null }
        fun exact(candidate: StudyCandidate): Boolean =
            domain.all { predict(candidate, it) == truth(it) }

        val generated = trace.candidates(MRX2BStage.GENERATED)
        val retained = trace.candidates(MRX2BStage.RETAINED)
        val correctGenerated = generated.filter(::exact)
        val correctRetained = retained.filter(::exact)
        val witness = correctGenerated.firstOrNull()
        val wrongRetained = retained.filterNot(::exact)
        val g0 = when {
            correctGenerated.isNotEmpty() -> "WITNESS_FOUND"
            grammarExhaustive -> "NOT_REPRESENTABLE_IN_BOUND"
            else -> "UNDETERMINED"
        }
        val separable = if (witness == null) 0 else wrongRetained.count { wrong ->
            legalQueries.any { input ->
                val p = predict(witness, input)
                val q = predict(wrong, input)
                p != null && q != null && p != q
            }
        }
        val g3 = when {
            witness == null -> "NOT_APPLICABLE"
            wrongRetained.isEmpty() -> "NO_WRONG_RETAINED"
            separable < wrongRetained.size -> "SOME_CANDIDATES_INDISTINGUISHABLE"
            else -> "WRONG_CANDIDATES_SEPARABLE"
        }
        // 联合查询预算：贪心选择能淘汰最多错误候选的合法输入。
        var active = wrongRetained.toSet()
        var queries = 0
        if (witness != null) {
            while (active.isNotEmpty() && queries < queryBudget) {
                val best = legalQueries.maxByOrNull { input ->
                    active.count { wrong ->
                        val p = predict(witness, input)
                        val q = predict(wrong, input)
                        p != null && q != null && p != q
                    }
                } ?: break
                val eliminated = active.filter { wrong ->
                    val p = predict(witness, best)
                    val q = predict(wrong, best)
                    p != null && q != null && p != q
                }
                if (eliminated.isEmpty()) break
                active = active - eliminated.toSet()
                queries++
            }
        }
        val correctIds = correctGenerated.map { it.canonical }.toSet()
        return MRX2BAuditReport(
            g0 = g0,
            g1GeneratedCorrect = correctGenerated.isNotEmpty(),
            g2RetainedCorrect = correctRetained.isNotEmpty(),
            g3 = if (g3 == "WRONG_CANDIDATES_SEPARABLE" && active.isNotEmpty() && queries >= queryBudget) {
                "INSUFFICIENT_QUERY_BUDGET"
            } else g3,
            g4SelectedCorrect = selected?.let(::exact) ?: false,
            generatedCorrect = correctGenerated.size,
            retainedCorrect = correctRetained.size,
            wrongRetained = wrongRetained.size,
            separableWrong = separable,
            queriesNeeded = queries,
            queryBudget = queryBudget,
            correctPrunes = trace.prunes.filter { it.canonical in correctIds },
            note = if (witness == null) "no_correct_witness_generated" else "witness_available",
        )
    }
}
