// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：MRX 下一轮开放探索的最小可审计来源链：学生可见证据 -> 竞争失败假说 -> 下一动作。
// 不读取 HELDOUT/目标真值；考官字段不进入本文件。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

data class MRXNextHypothesis(
    val id: String,
    val title: String,
    val source: String,
    val status: String,
    val supportingEvidenceIds: List<String>,
    val refutingEvidenceIds: List<String>,
    val unknownEvidenceIds: List<String>,
    val note: String,
)

data class MRXNextDecision(
    val step: Int,
    val observedAction: String,
    val observedFeedback: String,
    val evidenceIds: List<String>,
    val hypothesisIds: List<String>,
    val note: String,
)

object MRXNextAnalyzer {
    /** 只用 MRX2Result/账本等学生可见字段；不接收真值、世界 ID 或 HELDOUT。 */
    fun hypotheses(result: MRX2Result, ledger: MRX2Ledger): List<MRXNextHypothesis> {
        fun evidenceFor(predicate: (MRX2Step) -> Boolean): List<String> =
            result.steps.filter(predicate).map { "step:${it.index}:${it.action}:${it.feedback}" }

        val noCandidate = evidenceFor {
            it.feedback.contains("NO_CANDIDATE") || it.feedback.contains("UNVERIFIED")
        }
        val counterexample = evidenceFor { it.counterexamples > 0 || it.feedback.contains("COUNTEREXAMPLE") }
        val unknown = evidenceFor {
            it.feedback.contains("UNKNOWN") || it.feedback.contains("ERROR")
        }
        val budget = if (ledger.remaining() <= 0) listOf("ledger:remaining:0") else emptyList()
        val pass = evidenceFor { it.feedback == "PASS_ON_CHALLENGE" }

        fun status(support: List<String>, refute: List<String>): String = when {
            support.isNotEmpty() && refute.isEmpty() -> "SUPPORTED"
            support.isNotEmpty() && refute.isNotEmpty() -> "CONFLICTED"
            refute.isNotEmpty() -> "REFUTED"
            else -> "UNKNOWN"
        }

        return listOf(
            MRXNextHypothesis(
                "H_STRUCTURAL_COVERAGE", "候选生成/表达空间可能不足",
                "BUILTIN_BASELINE", status(noCandidate, pass),
                noCandidate, pass, if (budget.isEmpty()) listOf("ledger:remaining") else budget,
                "空/无候选只支持搜索覆盖不足的猜想，不能证明全域不可表达",
            ),
            MRXNextHypothesis(
                "H_COUNTEREXAMPLE_PRESSURE", "当前候选被真实反例推翻",
                "BUILTIN_BASELINE", status(counterexample, pass),
                counterexample, pass, emptyList(),
                "反例是候选被淘汰的证据，不等同于搜索空间不足",
            ),
            MRXNextHypothesis(
                "H_BUDGET_LIMIT", "合法查询预算可能限制辨识",
                "BUILTIN_BASELINE", status(budget, pass),
                budget, pass, listOf("ledger:entries:${ledger.entries().size}"),
                "只有账本剩余为 0 才支持；不能把任何失败都归因预算",
            ),
            MRXNextHypothesis(
                "H_UNKNOWN_EXECUTION", "Unknown/执行异常导致证据不可用",
                "BUILTIN_BASELINE", status(unknown, pass),
                unknown, pass, emptyList(),
                "Unknown 不等于 False，也不等于候选被反驳",
            ),
        )
    }

    fun decisions(result: MRX2Result, hypotheses: List<MRXNextHypothesis>): List<MRXNextDecision> =
        result.steps.map { step ->
            val ids = hypotheses.flatMap { h ->
                (h.supportingEvidenceIds + h.refutingEvidenceIds + h.unknownEvidenceIds)
                    .filter { it.contains("step:${step.index}:") }
            }.distinct()
            MRXNextDecision(
                step.index, step.action, step.feedback, ids,
                hypotheses.filter { h ->
                    h.supportingEvidenceIds.any { it.contains("step:${step.index}:") } ||
                        h.refutingEvidenceIds.any { it.contains("step:${step.index}:") }
                }.map { it.id },
                "observed action only; no counterfactual action claim",
            )
        }
}

object MRXNextTraceWriter {
    fun write(
        path: Path,
        runId: String,
        taskId: String,
        worldId: String,
        strategy: String,
        result: MRX2Result,
        hypotheses: List<MRXNextHypothesis>,
        decisions: List<MRXNextDecision>,
        ledger: MRX2Ledger,
    ) {
        Files.createDirectories(path.parent)
        val lines = ArrayList<String>()
        fun emit(obj: JsonObject) { lines += Json.encodeToString(JsonObject.serializer(), obj) }
        emit(buildJsonObject {
            put("type", "run")
            put("runId", runId); put("taskId", taskId); put("world", worldId)
            put("studentCommit", "7f18114"); put("phase", "DEVELOPMENT")
            put("strategy", strategy); put("status", result.status)
            put("selected", result.selected?.canonical ?: "")
            put("diagnosisBefore", buildJsonArray { result.diagnosisBefore.forEach { add(it) } })
            put("diagnosisAfter", buildJsonArray { result.diagnosisAfter.forEach { add(it) } })
            put("ledgerEntries", ledger.entries().size); put("newQueries", ledger.newQueries())
            put("remainingBudget", ledger.remaining())
            put("generatedCandidates", result.cost.generatedCandidates)
            put("interpreterCalls", result.cost.interpreterCalls)
            put("oracleNewQueriesTotal", result.cost.oracleNewQueries)
            put("actionWallMillis", result.cost.actionWallMillis)
            put("totalWallMillis", result.cost.totalWallMillis)
            put("note", "student-visible fields only; no examiner truth or holdout labels")
        })
        result.steps.forEach { step ->
            emit(buildJsonObject {
                put("type", "step"); put("runId", runId); put("step", step.index)
                put("action", step.action); put("feedback", step.feedback)
                put("counterexamples", step.counterexamples); put("oracleNew", step.oracleNew)
                put("oracleReused", step.oracleReused); put("nextAction", step.nextAction)
                put("wallMillis", step.wallMillis); put("note", step.note)
            })
        }
        hypotheses.forEach { h ->
            emit(buildJsonObject {
                put("type", "hypothesis"); put("runId", runId); put("id", h.id)
                put("title", h.title); put("source", h.source); put("status", h.status)
                put("support", buildJsonArray { h.supportingEvidenceIds.forEach { add(it) } })
                put("refute", buildJsonArray { h.refutingEvidenceIds.forEach { add(it) } })
                put("unknown", buildJsonArray { h.unknownEvidenceIds.forEach { add(it) } })
                put("note", h.note)
            })
        }
        decisions.forEach { d ->
            emit(buildJsonObject {
                put("type", "decision"); put("runId", runId); put("step", d.step)
                put("observedAction", d.observedAction); put("feedback", d.observedFeedback)
                put("evidenceIds", buildJsonArray { d.evidenceIds.forEach { add(it) } })
                put("hypothesisIds", buildJsonArray { d.hypothesisIds.forEach { add(it) } })
                put("note", d.note)
            })
        }
        ledger.entries().forEach { entry ->
            emit(buildJsonObject {
                put("type", "ledger"); put("runId", runId); put("input", entry.inputKey)
                put("label", entry.rawLabel); put("queryIndex", entry.queryIndex)
                put("tag", entry.source); put("usedBy", entry.usedBy.size)
            })
        }
        Files.write(path, lines)
    }
}
