// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：MRX-02E 第三步动态决策。只消费真实执行器产生的反馈快照；
// STATIC 只消费首败前冻结的队列，DYNAMIC 每次从当前快照重算。
package logic.e3.pack02

/** 一条真实行动反馈；必须由执行器/账本构造，UNIT_FIXTURE 只允许纯策略单测。 */
data class MRX2EActionFeedback(
    val action: String,
    val status: String,
    val evidenceVersionBefore: Int,
    val evidenceVersionAfter: Int,
    val candidateCountBefore: Int?,
    val candidateCountAfter: Int?,
    val oracleNew: Int,
    val oracleReused: Int,
    val source: String,
)

data class MRX2EPlannerInput(
    val strategy: String, // FIXED / STATIC / DYNAMIC
    val actionHistory: List<MRX2EActionFeedback>,
    val allowedNextActions: List<String>,
    val frozenInitialQueue: List<String>,
    val remainingOracleBudget: Int,
    val oracleRequiredActions: Set<String>,
    val evidenceVersion: Int,
    val evidenceDigest: String,
    val candidateDigest: String,
    val trainingConflict: Boolean,
    val hasLegalExperiment: Boolean,
)

data class MRX2EPlanDecision(
    val action: String,
    val reason: String,
    val evidenceVersionUsed: Int,
)

class MRX2EPlanner {
    fun choose(input: MRX2EPlannerInput): MRX2EPlanDecision {
        fun stop(why: String) = MRX2EPlanDecision("STOP", why, input.evidenceVersion)
        if (input.trainingConflict) return stop("TRAINING_CONFLICT")
        if (!input.hasLegalExperiment || input.allowedNextActions.isEmpty()) return stop("NO_LEGAL_EXPERIMENT")

        val legal = input.allowedNextActions.filter { action ->
            input.remainingOracleBudget > 0 || action !in input.oracleRequiredActions
        }
        if (legal.isEmpty()) return stop("NO_LEGAL_AFFORDABLE_ACTION")

        if (input.strategy == "STATIC") {
            // 队列在第一条失败反馈之前冻结；只按已执行步数消费，绝不重排。
            val next = input.frozenInitialQueue.drop(input.actionHistory.size).firstOrNull { it in legal }
            return if (next == null) stop("STATIC_QUEUE_EXHAUSTED")
            else MRX2EPlanDecision(next, "FROZEN_INITIAL_QUEUE", input.evidenceVersion)
        }

        if (input.strategy == "FIXED") {
            val next = input.frozenInitialQueue.firstOrNull { it in legal } ?: return stop("FIXED_QUEUE_EXHAUSTED")
            return MRX2EPlanDecision(next, "FIXED_ORDER", input.evidenceVersion)
        }

        // DYNAMIC：以最近真实反馈的状态、证据版本和候选摘要重新计算，不读世界 ID。
        val last = input.actionHistory.lastOrNull()
        if (last == null) {
            return MRX2EPlanDecision(legal.first(), "NO_PRIOR_FEEDBACK_FIRST_LEGAL", input.evidenceVersion)
        }
        when (last.status) {
            "UNKNOWN", "UNKNOWN_PREDICTION", "UNKNOWN_BLOCKED" -> return stop("UNKNOWN_NEEDS_NEW_OBSERVATION")
            "ERROR", "CONTROLLER_ERROR" -> return stop("EXECUTION_ERROR")
            "BUDGET_EXHAUSTED" -> {
                val free = legal.filter { it !in input.oracleRequiredActions }
                return if (free.isEmpty()) stop("NO_AFFORDABLE_ACTION_AFTER_BUDGET")
                else MRX2EPlanDecision(free.first(), "BUDGET_SAFE_ACTION", input.evidenceVersion)
            }
            "TRAINING_CONFLICT" -> return stop("TRAINING_CONFLICT")
        }

        // 同一动作不能在没有新证据/候选摘要变化时重复执行。
        fun alreadyTriedWithoutChange(action: String): Boolean {
            val previous = input.actionHistory.lastOrNull { it.action == action } ?: return false
            return previous.evidenceVersionAfter == input.evidenceVersion && previous.candidateCountAfter == input.actionHistory.last().candidateCountAfter
        }

        val preferred = when (last.status) {
            // 反例说明当前结构/候选被推翻：优先换条件结构，而不是继续扩大同类搜索。
            "COUNTEREXAMPLE" -> listOf("IF", "CNF", "DNF", "BUDGET")
            // 无候选说明当前空间没有产生结果：换一种组合语法/候选覆盖，而非重复原动作。
            "NO_CANDIDATE", "ALL_REFUTED" -> listOf("CNF", "DNF", "IF", "BUDGET")
            else -> listOf("IF", "CNF", "DNF", "BUDGET")
        }
        val chosen = preferred.firstOrNull { it in legal && !alreadyTriedWithoutChange(it) }
            ?: legal.firstOrNull { !alreadyTriedWithoutChange(it) }
            ?: return stop("NO_NEW_ACTION_WITHOUT_NEW_EVIDENCE")
        return MRX2EPlanDecision(
            chosen,
            "DYNAMIC_AFTER_${last.status}_V${last.evidenceVersionAfter}",
            input.evidenceVersion,
        )
    }
}
