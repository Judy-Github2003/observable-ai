// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 竞争假说、可辨识性与 FIXED/EVIDENCE/VALUE 行动选择；
// 证据统一走 MRXRChallengeLedger，未验证不再被当成通过。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path

enum class MRXRStrategy(val wire: String) {
    FIXED("FIXED"),
    EVIDENCE("EVIDENCE"),
    VALUE("VALUE"),
}

data class MRXRHypothesis(
    val id: String,
    val claim: String,
    val score: Double,
    val predictions: Map<String, Boolean>,
    val support: String,
    val against: String,
    val missing: String,
    val test: String,
)

data class MRXRIdentifiability(
    val top: List<String>,
    val indistinguishable: Boolean,
    val distinguishingActions: List<String>,
    val note: String,
)

data class MRXRTrial(
    val action: String,
    val status: String,
    val selected: StudyCandidate?,
    val validationStatus: String,
    val checks: Int,
    val counterexamples: Int,
    val generated: Int,
    val queries: Int,
    val costSteps: Int,
    val reason: String,
)

data class MRXRResult(
    val strategy: String,
    val baseStatus: String,
    val baseValidation: String,
    val baseChecks: Int,
    val baseCounterexamples: Int,
    val diagnosis: List<MRXRHypothesis>,
    val identifiability: MRXRIdentifiability,
    val trials: List<MRXRTrial>,
    val chosenAction: String,
    val status: String,
    val selected: StudyCandidate?,
    val evidenceEntries: Int,
    val evidenceReused: Int,
    val queries: Int,
    val generated: Int,
    val calls: Int,
    val wallMillis: Long,
)

/**
 * MRX-01R 学生：
 * - H1/H2/H2b/H3/H4/H5/H6 竞争假说；
 * - 先判断 top 假说在可用行动上是否可区分；
 * - 按 FIXED / EVIDENCE / VALUE 选择实验；
 * - 每个实验后按证据更新假说分数，再决定下一步；
 * - 未验证 / 部分验证 / 通过 / 反例严格分开。
 */
class MRXRStudent(
    private val baseView: StudyView,
    private val querySource: MRXQuerySource,
    private val ledger: MRXRChallengeLedger,
    private val allRefs: List<KnowledgeRef>,
    private val dbPath: Path,
    private val strategy: MRXRStrategy = MRXRStrategy.EVIDENCE,
    private val forcedAction: String? = null,
) {
    private val baseLibrary = Pack02Knowledge.resolveActive(dbPath, baseView.refs) ?: error("base inactive")
    private val allLibrary = Pack02Knowledge.resolveActive(dbPath, allRefs) ?: error("all inactive")
    private val history = HashMap<String, Int>()
    private var evidenceReused = 0

    fun run(): MRXRResult {
        val started = System.nanoTime()
        val base = ActiveStudySession(baseView, querySource.newController(), dbPath).run()
        val baseSelected = base.selected
        val baseValidation = baseSelected?.let { candidate ->
            ledger.validate(candidate.canonical, { input -> predict(candidate, input, baseLibrary) }, "base", 16)
        }
        val baseStatus = baseValidation?.status?.name ?: "UNVERIFIED"
        val baseChecks = baseValidation?.checks ?: 0
        val baseCounter = baseValidation?.counterexamples ?: 0
        val conflict = trainingConflict(baseView.training)
        var diagnosis = diagnose(base, baseCounter, conflict)
        var identifiability = analyzeIdentifiability(diagnosis)

        if (base.status == "IDENTIFIED" && baseSelected != null && baseValidation != null &&
            baseValidation.status == MRXREvidenceStatus.PASS && forcedAction == null
        ) {
            return result(
                started, base, baseStatus, baseChecks, baseCounter, diagnosis, identifiability,
                emptyList(), "NONE", "IDENTIFIED", baseSelected, base.stats.interpreterCalls,
            )
        }

        val trials = ArrayList<MRXRTrial>()
        var chosen = "NO_ACTION"
        var selected: StudyCandidate? = null
        var calls = base.stats.interpreterCalls
        var generated = base.generated
        val actions = if (forcedAction != null) listOf(forcedAction) else chooseActions(diagnosis, identifiability)
        for (action in actions.take(3)) {
            val trial = runAction(action)
            trials += trial
            calls += trial.queries
            generated += trial.generated
            history[action] = (history[action] ?: 0) + if (trial.status == "PASS") 1 else 0
            diagnosis = updateDiagnosis(diagnosis, action, trial.status == "PASS")
            identifiability = analyzeIdentifiability(diagnosis)
            if (trial.status == "PASS" && trial.selected != null) {
                chosen = action
                selected = trial.selected
                break
            }
        }
        val status = when {
            selected != null -> "REPAIRED"
            conflict -> "NO_ACTION"
            trials.any { it.validationStatus == "IMPOSSIBLE" } -> "INSUFFICIENT_EVIDENCE"
            else -> "INSUFFICIENT_EVIDENCE"
        }
        return result(
            started, base, baseStatus, baseChecks, baseCounter, diagnosis, identifiability,
            trials, chosen, status, selected, calls,
        )
    }

    private fun chooseActions(diagnosis: List<MRXRHypothesis>, ident: MRXRIdentifiability): List<String> {
        if (ident.indistinguishable && ident.distinguishingActions.isEmpty()) return listOf("NO_ACTION")
        val catalog = listOf("KNOWLEDGE", "BUDGET", "DNF", "IF", "CNF", "NO_ACTION")
        return when (strategy) {
            MRXRStrategy.FIXED -> catalog
            MRXRStrategy.EVIDENCE -> catalog.filter { action ->
                diagnosis.any { it.predictions[action] == true && it.score >= 0.3 }
            }.sortedWith(compareByDescending<String> { action ->
                diagnosis.filter { it.predictions[action] == true }.sumOf { it.score }
            }.thenBy { actionCost(it) }).ifEmpty { listOf("NO_ACTION") }
            MRXRStrategy.VALUE -> catalog.sortedByDescending { action ->
                val support = diagnosis.filter { it.predictions[action] == true }.sumOf { it.score }
                val discrimination = ident.distinguishingActions.count { it == action }
                val success = history[action] ?: 0
                ((1 + discrimination) * support + success * 0.5) / actionCost(action)
            }.filter { action ->
                action == "NO_ACTION" || diagnosis.any { it.predictions[action] == true }
            }.ifEmpty { listOf("NO_ACTION") }
        }
    }

    private fun runAction(action: String): MRXRTrial {
        return when (action) {
            "KNOWLEDGE" -> runFrozen("KNOWLEDGE", allRefs, allLibrary, baseView.maxCandidates)
            "BUDGET" -> runFrozen("BUDGET", baseView.refs, baseLibrary, 200_000)
            "DNF" -> runSpace("DNF", MRXOperator.DNF)
            "IF" -> runSpace("IF", MRXOperator.IF)
            "CNF" -> runSpace("CNF", MRXOperator.CNF)
            else -> MRXRTrial(action, "NO_ACTION", null, "UNVERIFIED", 0, 0, 0, 0, 0, "explicit_no_action")
        }
    }

    private fun runFrozen(action: String, refs: List<KnowledgeRef>, library: List<CapabilityDefinition>, maxCandidates: Int): MRXRTrial {
        val view = StudyView(
            baseView.worldId, baseView.stage, baseView.training, baseView.queryPool, baseView.budget,
            refs, baseView.inputNames, baseView.maxDepth, maxCandidates, baseView.maxCallsPerCapability,
            baseView.strategy, baseView.seed,
        )
        val result = ActiveStudySession(view, querySource.newController(), dbPath).run()
        val selected = result.selected
        val validation = selected?.let {
            ledger.validate(it.canonical, { input -> predict(it, input, library) }, action, 16)
        }
        return MRXRTrial(
            action, validation?.status?.name ?: "UNVERIFIED", selected,
            validation?.status?.name ?: "UNVERIFIED", validation?.checks ?: 0,
            validation?.counterexamples ?: 0, result.generated, result.rounds.size,
            selected?.estimatedSteps ?: 0, result.failure ?: validation?.note ?: "no_candidate",
        )
    }

    private fun runSpace(action: String, operator: MRXOperator): MRXRTrial {
        val space = MRXSpace(baseView.inputNames, baseLibrary, baseView.training)
            .withQuery(baseView.queryPool, querySource.newController())
        val attempt = space.generate(MRXSpaceConfig(operator, maxCandidates = 30_000, literalLimit = 10))
        if (attempt.survivors.isEmpty()) {
            return MRXRTrial(
                action, "UNVERIFIED", null, "UNVERIFIED", 0, 0,
                attempt.generated, attempt.queries.size, 0, attempt.failure ?: "no_survivor",
            )
        }
        val (filtered, validation) = ledger.filter(
            attempt.survivors,
            { candidate, input -> predict(candidate, input, baseLibrary) },
            action, 16,
        )
        val selected = filtered.minWithOrNull(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
        return MRXRTrial(
            action, validation.status.name, selected, validation.status.name,
            validation.checks, validation.counterexamples, attempt.generated,
            attempt.queries.size + validation.usedEntries.size, selected?.estimatedSteps ?: 0,
            attempt.failure ?: validation.note,
        )
    }

    private fun predict(candidate: StudyCandidate, input: Map<String, String>, library: List<CapabilityDefinition>): Boolean? =
        try {
            Pack02Knowledge.predict(candidate.body, input, library)
        } catch (_: Exception) {
            null
        }

    private fun diagnose(base: StudyStageResult, counter: Int, conflict: Boolean): List<MRXRHypothesis> {
        val extra = allRefs.map { it.id }.toSet() - baseView.refs.map { it.id }.toSet()
        val out = ArrayList<MRXRHypothesis>()
        if (conflict) out += hypothesis("H6", "观察冲突/噪声", 1.0, mapOf("NO_ACTION" to false),
            "训练集同输入相反标签", "结构无法修复标签冲突", "需要独立观察/标注", "NO_ACTION")
        if (extra.isNotEmpty()) out += hypothesis("H3", "知识缺失/依赖不可用", 0.8,
            mapOf("KNOWLEDGE" to true, "DNF" to true, "IF" to false),
            "注册表有未使用 active 能力 $extra", "深度也可能补偿", "比较知识/深度成本", "KNOWLEDGE")
        if (base.composeReason == "candidate_limit" || base.status == "SEARCH_LIMITED") {
            out += hypothesis("H2b", "候选预算不足", 0.9,
                mapOf("BUDGET" to true, "DNF" to false),
                "candidate_limit 停止", "目标可能在空间内", "扩大预算对照", "BUDGET")
        }
        if (base.status == "NO_CANDIDATE" || counter > 0) {
            out += hypothesis("H1", "表达空间不足/需要新结构", 0.6,
                mapOf("DNF" to true, "IF" to true, "CNF" to true, "KNOWLEDGE" to false),
                "status=${base.status}, consistent=${base.consistent}", "扩展后仍无候选则反对", "结构实验", "DNF")
            out += hypothesis("H2", "搜索深度不足", 0.6,
                mapOf("DNF" to true, "IF" to false, "CNF" to true),
                "depth=${baseView.maxDepth} 结束", "DNF 仍失败则反对", "depth+1 对照", "DNF")
        }
        if (counter > 0) {
            out += hypothesis("H4", "训练证据不足/错误概括", 0.5,
                mapOf("DNF" to false, "IF" to false, "BUDGET" to false),
                "CHALLENGE 反例", "反例不说明需要新操作", "更多反例", "NO_ACTION")
            out += hypothesis("H5", "查询覆盖不足", 0.5,
                mapOf("DNF" to true, "IF" to true, "KNOWLEDGE" to false),
                "QUERY 唯一但 CHALLENGE 反例", "若挑战也无法区分则不能修", "改变查询分布", "DNF")
        }
        return out.sortedByDescending { it.score }
    }

    private fun hypothesis(
        id: String, claim: String, score: Double, predictions: Map<String, Boolean>,
        support: String, against: String, missing: String, test: String,
    ) = MRXRHypothesis(id, claim, score, predictions, support, against, missing, test)

    private fun analyzeIdentifiability(diagnosis: List<MRXRHypothesis>): MRXRIdentifiability {
        if (diagnosis.isEmpty()) return MRXRIdentifiability(emptyList(), true, emptyList(), "no_hypotheses")
        val max = diagnosis.maxOf { it.score }
        val top = diagnosis.filter { it.score >= max - 0.1 }
        val actions = listOf("KNOWLEDGE", "BUDGET", "DNF", "IF", "CNF")
        if (top.size == 1) {
            return MRXRIdentifiability(
                top.map { it.id }, false,
                actions.filter { top[0].predictions[it] == true },
                "single leading hypothesis; no competing top hypothesis",
            )
        }
        val distinguishing = actions.filter { action ->
            top.map { it.predictions[action] }.toSet().size > 1
        }
        return MRXRIdentifiability(
            top.map { it.id }, distinguishing.isEmpty(), distinguishing,
            if (distinguishing.isEmpty()) "top hypotheses predict same outcomes on all available actions"
            else "distinguishing actions=$distinguishing",
        )
    }

    private fun updateDiagnosis(diagnosis: List<MRXRHypothesis>, action: String, success: Boolean): List<MRXRHypothesis> =
        diagnosis.map { hypothesis ->
            val predicted = hypothesis.predictions[action]
            if (predicted == null) hypothesis
            else {
                val delta = if (predicted == success) 0.15 else -0.15
                hypothesis.copy(score = (hypothesis.score + delta).coerceIn(0.0, 1.0))
            }
        }.sortedByDescending { it.score }

    private fun actionCost(action: String): Double = when (action) {
        "KNOWLEDGE" -> 1.0
        "BUDGET" -> 2.0
        "DNF" -> 3.0
        "IF" -> 2.0
        "CNF" -> 3.0
        "NO_ACTION" -> 0.5
        else -> 4.0
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
        started: Long, base: StudyStageResult, baseValidation: String, baseChecks: Int,
        baseCounter: Int, diagnosis: List<MRXRHypothesis>, ident: MRXRIdentifiability,
        trials: List<MRXRTrial>, chosen: String, status: String, selected: StudyCandidate?, calls: Int,
    ) = MRXRResult(
        strategy = strategy.wire,
        baseStatus = base.status,
        baseValidation = baseValidation,
        baseChecks = baseChecks,
        baseCounterexamples = baseCounter,
        diagnosis = diagnosis,
        identifiability = ident,
        trials = trials,
        chosenAction = chosen,
        status = status,
        selected = selected,
        evidenceEntries = ledger.allEntries().size,
        evidenceReused = ledger.allEntries().sumOf { it.usedBy.size },
        queries = base.rounds.size + ledger.queriesSpent() + trials.sumOf { it.queries },
        generated = base.generated + trials.sumOf { it.generated },
        calls = calls,
        wallMillis = (System.nanoTime() - started) / 1_000_000,
    )
}
