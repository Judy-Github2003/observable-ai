// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02 第二关：真正动态重规划。每执行一个实验后重新生成假说/可辨识性/行动价值，
// 不再预先固定一个行动队列。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path

enum class MRX2Strategy(val wire: String) {
    FIXED("FIXED"),
    STATIC("STATIC"),
    DYNAMIC("DYNAMIC"),
}

data class MRX2Step(
    val index: Int,
    val action: String,
    val feedback: String,
    val checks: Int,
    val counterexamples: Int,
    val oracleNew: Int,
    val oracleReused: Int,
    val interpreterCalls: Int,
    val selectedNodes: Int,
    val nextAction: String,
    val note: String,
    val wallMillis: Long,
)

data class MRX2Result(
    val strategy: String,
    val firstAction: String,
    val feedback: String,
    val secondAction: String,
    val status: String,
    val steps: List<MRX2Step>,
    val selected: StudyCandidate?,
    val diagnosisBefore: List<String>,
    val diagnosisAfter: List<String>,
    val identifiability: String,
    val cost: MRX2Cost,
)

/**
 * 动态学习循环：
 * VALIDATE_BASE → 重新诊断 → 选择/执行一个行动 → 记录真实反馈 → 重新诊断 …
 * 每轮行动都是重新计算的，不复用启动时的队列。
 */
class MRX2Student(
    private val baseView: StudyView,
    private val querySource: MRXQuerySource,
    private val ledger: MRX2Ledger,
    private val allRefs: List<KnowledgeRef>,
    private val dbPath: Path,
    private val strategy: MRX2Strategy = MRX2Strategy.DYNAMIC,
    private val meter: MRX2Cost = MRX2Cost(),
) {
    private val baseLibrary = Pack02Knowledge.resolveActive(dbPath, baseView.refs) ?: error("base inactive")
    private val allLibrary = Pack02Knowledge.resolveActive(dbPath, allRefs) ?: error("all inactive")
    private var staticQueue: MutableList<String>? = null

    fun run(): MRX2Result {
        val started = System.nanoTime()
        val base = ActiveStudySession(baseView, querySource.newController(), dbPath).run()
        meter.generatedCandidates += base.generated
        val selectedBase = base.selected
        val baseValidation = selectedBase?.let { candidate ->
            ledger.validate(
                candidate.canonical,
                { input -> predict(candidate, input, baseLibrary) },
                "VALIDATE_BASE", 16, meter,
            )
        }
        var diagnosis = diagnose(base, baseValidation, baseView.training)
        var ident = analyze(diagnosis)
        val steps = ArrayList<MRX2Step>()
        var firstAction = "VALIDATE_BASE"
        var firstFeedback = baseValidation?.status?.name ?: "UNVERIFIED"
        var secondAction = "STOP"
        var currentSelected = selectedBase
        var status = if (baseValidation?.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) "IDENTIFIED" else "INSUFFICIENT_EVIDENCE"
        steps += MRX2Step(
            0, firstAction, firstFeedback, baseValidation?.checks ?: 0,
            baseValidation?.counterexamples ?: 0, 0, ledger.entries().sumOf { it.usedBy.size },
            meter.interpreterCalls, selectedBase?.nodeCount ?: 0, "", baseValidation?.note ?: "no_candidate",
            (System.nanoTime() - started) / 1_000_000,
        )

        var next = if (baseValidation?.status == MRX2ValidationStatus.PASS_ON_CHALLENGE && strategy != MRX2Strategy.FIXED) {
            "STOP"
        } else {
            chooseNext(diagnosis, ident, step = 1)
        }
        secondAction = next
        var index = 1
        while (next != "STOP" && index <= 3) {
            val actionStarted = System.nanoTime()
            val outcome = execute(next)
            val wall = (System.nanoTime() - actionStarted) / 1_000_000
            meter.actionWallMillis += wall
            steps += MRX2Step(
                index, next, outcome.status, outcome.checks, outcome.counterexamples,
                outcome.oracleNew, outcome.oracleReused, outcome.interpreterCalls,
                outcome.selected?.nodeCount ?: 0, "", outcome.note, wall,
            )
            if (outcome.selected != null) currentSelected = outcome.selected
            diagnosis = update(diagnosis, next, outcome.status == MRX2ValidationStatus.PASS_ON_CHALLENGE.name)
            ident = analyze(diagnosis)
            if (outcome.status == MRX2ValidationStatus.PASS_ON_CHALLENGE.name && outcome.selected != null) {
                status = "REPAIRED"
                next = "STOP"
            } else {
                next = if (strategy == MRX2Strategy.DYNAMIC && index < 3) {
                    planDynamicNext(steps, index, currentSelected)
                } else {
                    chooseNext(diagnosis, ident, step = index + 1)
                }
            }
            steps[index] = steps[index].copy(nextAction = next)
            index++
        }
        meter.totalWallMillis = (System.nanoTime() - started) / 1_000_000
        return MRX2Result(
            strategy.wire, firstAction, firstFeedback, secondAction, status, steps, currentSelected,
            diagnosis.map { it.id }, diagnosis.map { it.id }, ident.toString(), meter,
        )
    }

    private data class Outcome(
        val status: String, val checks: Int, val counterexamples: Int,
        val oracleNew: Int, val oracleReused: Int, val interpreterCalls: Int,
        val selected: StudyCandidate?, val note: String,
    )

    private fun planDynamicNext(
        steps: List<MRX2Step>,
        currentIndex: Int,
        selected: StudyCandidate?,
    ): String {
        val catalog = listOf("KNOWLEDGE", "BUDGET", "DNF", "IF", "CNF")
        val history = steps.filter { it.index in 1..currentIndex }.map { step ->
            MRX2EActionFeedback(
                action = step.action,
                status = step.feedback,
                evidenceVersionBefore = step.index - 1,
                evidenceVersionAfter = step.index,
                candidateCountBefore = null,
                candidateCountAfter = null,
                oracleNew = step.oracleNew,
                oracleReused = step.oracleReused,
                source = "REAL_MRX2STUDENT",
            )
        }
        val input = MRX2EPlannerInput(
            strategy = strategy.wire,
            actionHistory = history,
            allowedNextActions = catalog,
            frozenInitialQueue = catalog,
            remainingOracleBudget = ledger.remaining(),
            oracleRequiredActions = catalog.toSet(),
            evidenceVersion = currentIndex,
            evidenceDigest = "ledger-${ledger.entries().size}-v${ledger.newQueries()}",
            candidateDigest = selected?.canonical ?: "none",
            trainingConflict = trainingConflict(baseView.training),
            hasLegalExperiment = catalog.isNotEmpty(),
        )
        return MRX2EPlanner().choose(input).action
    }

    private fun chooseNext(diagnosis: List<MRXRHypothesis>, ident: MRXRIdentifiability, step: Int): String {
        if (diagnosis.isEmpty() || (ident.indistinguishable && ident.distinguishingActions.isEmpty())) return "STOP"
        val catalog = listOf("KNOWLEDGE", "BUDGET", "DNF", "IF", "CNF")
        if (strategy == MRX2Strategy.FIXED) return catalog.getOrNull(step - 1) ?: "STOP"
        if (strategy == MRX2Strategy.STATIC) {
            val queue = staticQueue ?: catalog.filter { action ->
                diagnosis.any { it.predictions[action] == true }
            }.sortedByDescending { action -> diagnosis.filter { it.predictions[action] == true }.sumOf { it.score } }.toMutableList()
            staticQueue = queue
            return queue.removeFirstOrNull() ?: "STOP"
        }
        // DYNAMIC：每次都从当前诊断重新计算，不复用旧队列。
        val supported = catalog.map { action ->
            action to diagnosis.filter { it.predictions[action] == true }.sumOf { it.score }
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        return supported.firstOrNull()?.first ?: "STOP"
    }

    private fun execute(action: String): Outcome {
        val beforeNew = ledger.newQueries()
        val beforeReuse = ledger.entries().sumOf { it.usedBy.size }
        val beforeCalls = meter.interpreterCalls
        return when (action) {
            "KNOWLEDGE" -> executeFrozen(allRefs, allLibrary, onCreated = 20_000)
            "BUDGET" -> executeFrozen(baseView.refs, baseLibrary, onCreated = 200_000)
            "DNF" -> executeSpace(MRXROperator.DNF)
            "IF" -> executeSpace(MRXROperator.IF)
            "CNF" -> executeSpace(MRXROperator.CNF)
            else -> Outcome("NO_ACTION", 0, 0, 0, 0, 0, null, "unknown_action")
        }.copy(
            oracleNew = ledger.newQueries() - beforeNew,
            oracleReused = ledger.entries().sumOf { it.usedBy.size } - beforeReuse,
            interpreterCalls = meter.interpreterCalls - beforeCalls,
        )
    }

    private fun executeFrozen(refs: List<KnowledgeRef>, library: List<CapabilityDefinition>, onCreated: Int): Outcome {
        val view = StudyView(
            baseView.worldId, baseView.stage, baseView.training, baseView.queryPool, baseView.budget,
            refs, baseView.inputNames, baseView.maxDepth, onCreated, baseView.maxCallsPerCapability,
            baseView.strategy, baseView.seed,
        )
        val result = ActiveStudySession(view, querySource.newController(), dbPath).run()
        meter.generatedCandidates += result.generated
        val selected = result.selected
        val validation = selected?.let {
            ledger.validate(it.canonical, { input -> predict(it, input, library) }, "ACTION", 16, meter)
        }
        return Outcome(
            validation?.status?.name ?: "NO_CANDIDATE", validation?.checks ?: 0,
            validation?.counterexamples ?: 0, 0, 0, 0, selected,
            result.failure ?: validation?.note ?: "no_candidate",
        )
    }

    private fun executeSpace(operator: MRXROperator): Outcome {
        val space = MRXRSpace(baseView.inputNames, baseLibrary, baseView.training)
            .withQuery(baseView.queryPool, querySource.newController())
        val attempt = space.generate(MRXRSpaceConfig(operator, maxCandidates = 30_000, literalLimit = 10))
        meter.generatedCandidates += attempt.generated
        if (attempt.survivors.isEmpty()) {
            return Outcome("NO_CANDIDATE", 0, 0, 0, 0, 0, null, attempt.failure ?: "no_survivor")
        }
        val (filtered, validation) = ledger.filter(
            attempt.survivors, { candidate, input -> predict(candidate, input, baseLibrary) }, "ACTION", 16, meter,
        )
        val selected = filtered.minWithOrNull(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
        return Outcome(
            validation.status.name, validation.checks, validation.counterexamples,
            0, 0, 0, selected, validation.note,
        )
    }

    private fun predict(candidate: StudyCandidate, input: Map<String, String>, library: List<CapabilityDefinition>): Boolean? =
        try {
            Pack02Knowledge.predict(candidate.body, input, library)
        } catch (_: Exception) {
            null
        }

    private fun diagnose(base: StudyStageResult, validation: MRX2Validation?, training: List<StudyCase>): List<MRXRHypothesis> {
        val out = ArrayList<MRXRHypothesis>()
        val conflict = trainingConflict(training)
        val extra = allRefs.map { it.id }.toSet() - baseView.refs.map { it.id }.toSet()
        if (conflict) out += MRXRHypothesis("H6", "观察冲突", 1.0, mapOf("STOP" to true), "conflict", "", "", "STOP")
        if (extra.isNotEmpty()) out += MRXRHypothesis("H3", "知识缺失", 0.8, mapOf("KNOWLEDGE" to true, "DNF" to true), "extra=$extra", "", "", "KNOWLEDGE")
        if (base.composeReason == "candidate_limit" || base.status == "SEARCH_LIMITED") {
            out += MRXRHypothesis("H2b", "预算不足", 0.9, mapOf("BUDGET" to true), "candidate_limit", "", "", "BUDGET")
        }
        if (base.status == "NO_CANDIDATE" || (validation?.counterexamples ?: 0) > 0) {
            out += MRXRHypothesis("H1", "表达空间不足", 0.6, mapOf("DNF" to true, "IF" to true, "CNF" to true), "status=${base.status}", "", "", "DNF")
            out += MRXRHypothesis("H2", "深度不足", 0.6, mapOf("DNF" to true), "depth=${baseView.maxDepth}", "", "", "DNF")
        }
        if ((validation?.counterexamples ?: 0) > 0) {
            out += MRXRHypothesis("H5", "查询覆盖不足", 0.5, mapOf("DNF" to true, "IF" to true), "challenge counterexample", "", "", "DNF")
            out += MRXRHypothesis("H4", "证据不足", 0.5, mapOf("STOP" to true), "challenge counterexample", "", "", "STOP")
        }
        return out.sortedByDescending { it.score }
    }

    private fun analyze(diagnosis: List<MRXRHypothesis>): MRXRIdentifiability {
        if (diagnosis.isEmpty()) return MRXRIdentifiability(emptyList(), true, emptyList(), "no_hypotheses")
        val max = diagnosis.maxOf { it.score }
        val top = diagnosis.filter { it.score >= max - 0.1 }
        val actions = listOf("KNOWLEDGE", "BUDGET", "DNF", "IF", "CNF")
        if (top.size == 1) return MRXRIdentifiability(top.map { it.id }, false, actions.filter { top[0].predictions[it] == true }, "single")
        val distinguishing = actions.filter { a -> top.map { it.predictions[a] }.toSet().size > 1 }
        return MRXRIdentifiability(top.map { it.id }, distinguishing.isEmpty(), distinguishing, "top=${top.map { it.id }}")
    }

    private fun update(diagnosis: List<MRXRHypothesis>, action: String, success: Boolean): List<MRXRHypothesis> =
        diagnosis.map { h ->
            val predicted = h.predictions[action] ?: return@map h
            h.copy(score = (h.score + if (predicted == success) 0.15 else -0.15).coerceIn(0.0, 1.0))
        }.sortedByDescending { it.score }

    private fun trainingConflict(training: List<StudyCase>): Boolean {
        val labels = HashMap<String, Boolean>()
        for (case in training) {
            val key = C8Domain.key(case.values)
            val prev = labels.put(key, case.label)
            if (prev != null && prev != case.label) return true
        }
        return false
    }
}
