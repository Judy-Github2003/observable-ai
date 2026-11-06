// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 学生端诊断与结构升级。基础学习仍由冻结 C8-03 学生完成；
// 本类只在失败/挑战证据出现后提出假设、比较升级实验，并把选中的候选交回考官验证。
// 边界：学生不看 HELDOUT；CHALLENGE 标签只通过 StudyQueryController 付费获得。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path

data class MRXHypothesis(
    val id: String,
    val claim: String,
    val support: String,
    val against: String,
    val missing: String,
    val action: String,
    val score: Double,
)

data class MRXTrial(
    val action: String,
    val status: String,
    val generated: Int,
    val consistent: Int,
    val queries: Int,
    val challengeQueries: Int,
    val challengeMismatch: Int,
    val selected: StudyCandidate?,
    val costSteps: Int,
    val reason: String,
)

data class MRXStudentResult(
    val baseStatus: String,
    val baseGenerated: Int,
    val baseConsistent: Int,
    val baseClasses: Int,
    val baseQueries: Int,
    val baseChallengeMismatch: Int,
    val baseChallengeQueries: Int,
    val diagnosis: List<MRXHypothesis>,
    val trials: List<MRXTrial>,
    val chosenAction: String,
    val status: String,
    val selected: StudyCandidate?,
    val failure: String?,
    val totalQueries: Int,
    val totalGenerated: Int,
    val totalCalls: Int,
    val wallMillis: Long,
)

/**
 * 只暴露“新建 Controller”的入口；目标函数保持 private，学生不能直接读标签。
 * 每个升级实验使用独立预算，成本分别记录。
 */
class MRXQuerySource(
    private val pool: List<Map<String, String>>,
    private val budget: Int,
    private val target: (Map<String, String>) -> Boolean,
) {
    fun newController(): StudyQueryController = StudyQueryController(pool, budget, target)
}

/**
 * 失败诊断 + 升级实验学生。
 *
 * 证据来源：基础学习状态、组合原因、候选/行为类计数、训练冲突、CHALLENGE 反例。
 * 升级动作：扩大深度（DNF）、加入条件（IF）、加入 CNF、恢复可用知识、扩大候选预算、不升级。
 * 每个动作都在同一公开训练/QUERY 与受限 CHALLENGE 预算下实际运行；不是只写建议。
 */
class MRXStudent(
    private val baseView: StudyView,
    private val querySource: MRXQuerySource,
    private val challengePool: List<Map<String, String>>,
    private val challengeController: StudyQueryController,
    private val allRefs: List<KnowledgeRef>,
    private val dbPath: Path,
    private val forcedAction: String? = null,
) {
    private val baseLibrary: List<CapabilityDefinition> =
        Pack02Knowledge.resolveActive(dbPath, baseView.refs) ?: error("base library inactive")
    private val allLibrary: List<CapabilityDefinition> =
        Pack02Knowledge.resolveActive(dbPath, allRefs) ?: error("all-ref library inactive")

    fun run(): MRXStudentResult {
        val started = System.nanoTime()
        val base = ActiveStudySession(baseView, querySource.newController(), dbPath).run()
        val conflict = trainingConflict(baseView.training)
        var baseMismatch = 0
        var baseChallengeQueries = 0
        if (base.selected != null) {
            val validation = validate(base.selected, baseLibrary, maxQueries = 16)
            baseMismatch = validation.first
            baseChallengeQueries = validation.second
        }
        val diagnosis = diagnose(base, baseMismatch, conflict)
        if (conflict) {
            return result(
                base, baseMismatch, baseChallengeQueries, diagnosis, emptyList(), "NO_ACTION",
                "NO_ACTION", null, "training_conflict", started,
            )
        }
        if (base.status == "IDENTIFIED" && baseMismatch == 0 && forcedAction == null) {
            return result(
                base, baseMismatch, baseChallengeQueries, diagnosis, emptyList(), "NONE",
                "IDENTIFIED", base.selected, null, started,
            )
        }

        val actions = forcedAction?.let { listOf(it) } ?: plannedActions(base, conflict)
        val trials = ArrayList<MRXTrial>()
        for (action in actions) {
            val trial = runAction(action)
            trials += trial
            if (trial.challengeMismatch == 0 && trial.selected != null) break
        }
        val successful = trials.filter { it.selected != null && it.challengeMismatch == 0 }
        val best = successful.minWithOrNull(compareBy({ it.costSteps }, { it.generated }, { it.action }))
        return result(
            base, baseMismatch, baseChallengeQueries, diagnosis, trials,
            best?.action ?: "NO_ACTION",
            if (best != null) "REPAIRED" else "INSUFFICIENT_EVIDENCE",
            best?.selected,
            if (best == null) "no_upgrade_passed_challenge" else null,
            started,
        )
    }

    private fun plannedActions(base: StudyStageResult, conflict: Boolean): List<String> {
        if (conflict) return listOf("NO_ACTION")
        val actions = ArrayList<String>()
        val extra = allRefs.map { it.id }.toSet() - baseView.refs.map { it.id }.toSet()
        if (extra.isNotEmpty()) actions += "KNOWLEDGE"
        if (base.composeReason == "candidate_limit" || base.status == "SEARCH_LIMITED") actions += "BUDGET"
        actions += "DNF"
        actions += "IF"
        actions += "CNF"
        return actions.distinct()
    }

    private fun runAction(action: String): MRXTrial = when (action) {
        "KNOWLEDGE" -> runFrozenAction("KNOWLEDGE", allRefs, baseView.maxCandidates)
        "BUDGET" -> runFrozenAction("BUDGET", baseView.refs, 200_000)
        "DNF" -> runSpaceAction("DNF", MRXOperator.DNF)
        "IF" -> runSpaceAction("IF", MRXOperator.IF)
        "CNF" -> runSpaceAction("CNF", MRXOperator.CNF)
        "NO_ACTION" -> MRXTrial("NO_ACTION", "SKIPPED", 0, 0, 0, 0, 0, null, 0, "explicit_no_action")
        else -> MRXTrial(action, "SKIPPED", 0, 0, 0, 0, 0, null, 0, "unknown_action")
    }

    private fun runFrozenAction(action: String, refs: List<KnowledgeRef>, maxCandidates: Int): MRXTrial {
        val view = StudyView(
            baseView.worldId, baseView.stage, baseView.training, baseView.queryPool, baseView.budget,
            refs, baseView.inputNames, baseView.maxDepth, maxCandidates, baseView.maxCallsPerCapability,
            baseView.strategy, baseView.seed,
        )
        val result = ActiveStudySession(view, querySource.newController(), dbPath).run()
        val selected = result.selected
        val validation = if (selected != null) validate(selected, allLibrary, maxQueries = 8) else 0 to 0
        return MRXTrial(
            action, result.status, result.generated, result.consistent, result.rounds.size,
            validation.second, validation.first, selected, selected?.estimatedSteps ?: 0,
            result.failure ?: if (validation.first > 0) "challenge_mismatch" else "ok",
        )
    }

    private fun runSpaceAction(action: String, operator: MRXOperator): MRXTrial {
        val space = MRXSpace(baseView.inputNames, baseLibrary, baseView.training)
            .withQuery(baseView.queryPool, querySource.newController())
            .withChallenge(challengePool, challengeController)
        val attempt = space.generate(MRXSpaceConfig(operator, maxCandidates = 30_000, literalLimit = 10))
        val selected = attempt.selected
        val validation = if (selected != null) validate(selected, baseLibrary, maxQueries = 8) else 0 to 0
        return MRXTrial(
            action, attempt.status, attempt.generated, attempt.consistent,
            attempt.queries.size + attempt.challengeQueries,
            attempt.challengeQueries + validation.second,
            validation.first, selected, selected?.estimatedSteps ?: 0,
            attempt.failure ?: if (validation.first > 0) "challenge_mismatch" else "ok",
        )
    }

    /** 返回 (mismatch, queriesUsed)；只通过 Controller 读标签。 */
    private fun validate(candidate: StudyCandidate, library: List<CapabilityDefinition>, maxQueries: Int): Pair<Int, Int> {
        var queries = 0
        var mismatch = 0
        for (input in challengePool) {
            if (queries >= maxQueries || challengeController.remaining() <= 0) break
            val prediction = try {
                Pack02Knowledge.predict(candidate.body, input, library)
            } catch (_: Exception) {
                null
            } ?: continue
            val observation = try {
                val frozen = challengeController.freeze(
                    queries, input, mapOf(candidate.canonical to prediction), listOf(candidate.canonical),
                )
                challengeController.reveal(frozen)
            } catch (_: IllegalArgumentException) {
                break
            }
            queries++
            if (observation.label != prediction) {
                mismatch++
                break
            }
        }
        return mismatch to queries
    }

    private fun diagnose(base: StudyStageResult, challengeMismatch: Int, conflict: Boolean): List<MRXHypothesis> {
        val extra = allRefs.map { it.id }.toSet() - baseView.refs.map { it.id }.toSet()
        val out = ArrayList<MRXHypothesis>()
        if (conflict) {
            out += MRXHypothesis(
                "H6", "观察存在冲突或额外未表示属性",
                "训练集中同一输入出现相反标签",
                "没有结构证据能修复标签冲突",
                "需要更多独立观察或标注者信息",
                "NO_ACTION", 1.0,
            )
        }
        if (extra.isNotEmpty()) {
            out += MRXHypothesis(
                "H3", "已有知识不足或依赖不可用",
                "注册表中有未使用的 active 能力: ${extra.joinToString(",")}",
                "当前库仍可能通过更深的扁平表达解决",
                "需要比较“恢复知识”和“增加深度”的实际成本",
                "KNOWLEDGE", if (base.status != "IDENTIFIED") 0.8 else 0.4,
            )
        }
        if (base.composeReason == "candidate_limit" || base.status == "SEARCH_LIMITED") {
            out += MRXHypothesis(
                "H2b", "搜索候选预算不足",
                "组合器以 candidate_limit 停止，生成数=${base.generated}",
                "表达式本身可能仍在空间内",
                "需要更大候选预算的对照实验",
                "BUDGET", 0.9,
            )
        }
        if (base.status == "NO_CANDIDATE" || base.consistent == 0 || challengeMismatch > 0) {
            out += MRXHypothesis(
                "H1", "目标规则不在当前表达空间，或需要新的组合结构",
                "基础学习 status=${base.status}, consistent=${base.consistent}, " +
                    "composeReason=${base.composeReason}, classes=${base.initialClasses}",
                "若扩大深度/加入操作后仍无一致候选，则支持结构不足",
                "需要独立的结构升级实验",
                "IF", 0.6,
            )
            out += MRXHypothesis(
                "H2", "表达空间足够但搜索深度不足",
                "基础搜索在 depth=${baseView.maxDepth} 结束，失败码=${base.failure}",
                "若深度扩展仍无一致候选，则反对",
                "需要 depth+1 的对照实验",
                "DNF", 0.6,
            )
        }
        if (base.status == "IDENTIFIED" && challengeMismatch > 0) {
            out += MRXHypothesis(
                "H4", "公开训练证据不足，导致错误概括",
                "基础候选在 CHALLENGE 上出现反例",
                "反例只说明当前候选错，不说明需要新操作",
                "需要更多反例或更大的查询域",
                "CHALLENGE", 0.5,
            )
            out += MRXHypothesis(
                "H5", "查询域未覆盖真正有区分价值的输入",
                "基础候选在 QUERY 上已唯一，但 CHALLENGE 有反例",
                "若 CHALLENGE 也无法区分候选，则不能仅靠增加查询修复",
                "需要改变查询分布或使用新结构",
                "IF", 0.5,
            )
        }
        return out.sortedByDescending { it.score }
    }

    private fun trainingConflict(training: List<StudyCase>): Boolean {
        val labels = HashMap<String, Boolean>()
        for (case in training) {
            val key = C8Domain.key(case.values)
            val previous = labels.put(key, case.label)
            if (previous != null && previous != case.label) return true
        }
        return false
    }

    private fun result(
        base: StudyStageResult, baseMismatch: Int, baseChallengeQueries: Int,
        diagnosis: List<MRXHypothesis>, trials: List<MRXTrial>, chosenAction: String,
        status: String, selected: StudyCandidate?, failure: String?, started: Long,
    ): MRXStudentResult = MRXStudentResult(
        baseStatus = base.status,
        baseGenerated = base.generated,
        baseConsistent = base.consistent,
        baseClasses = base.initialClasses,
        baseQueries = base.rounds.size,
        baseChallengeMismatch = baseMismatch,
        baseChallengeQueries = baseChallengeQueries,
        diagnosis = diagnosis,
        trials = trials,
        chosenAction = chosenAction,
        status = status,
        selected = selected,
        failure = failure,
        totalQueries = base.rounds.size + baseChallengeQueries + trials.sumOf { it.queries },
        totalGenerated = base.generated + trials.sumOf { it.generated },
        totalCalls = base.stats.interpreterCalls + trials.sumOf { it.generated },
        wallMillis = (System.nanoTime() - started) / 1_000_000,
    )
}
