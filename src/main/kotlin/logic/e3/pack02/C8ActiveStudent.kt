// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-C8-03 四属性三值主动学习学生端。学生只持有公开训练、无标签 QUERY 池、
// 已激活能力引用和冻结预算；目标函数、HELDOUT 与考官最优答案不进入学生对象。
// 边界：候选来自 B0 CapabilityComposer 的有界枚举；学生自己完成一致性筛选、行为等价类、
// 查询选择、预测冻结、反例淘汰和候选选择。查询标签只能经 StudyQueryController 揭示。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path
import kotlin.math.exp

/** 四属性三值输入域；标签和划分都不在这里。 */
object C8Domain {
    val attributes = listOf("a", "b", "c", "d")
    val values = listOf("0", "1", "2")

    fun all(): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>(81)
        for (a in values) for (b in values) for (c in values) for (d in values) {
            out += mapOf("a" to a, "b" to b, "c" to c, "d" to d)
        }
        return out
    }

    fun key(input: Map<String, String>): String =
        attributes.joinToString(",") { input[it] ?: "" }
}

/** 公开训练样本：输入 + 标签。 */
data class StudyCase(val id: String, val values: Map<String, String>, val label: Boolean)

/** 学生可见面：没有 family/target/heldout/truth 字段。 */
class StudyView internal constructor(
    val worldId: String,
    val stage: String,
    val training: List<StudyCase>,
    val queryPool: List<Map<String, String>>,
    val budget: Int,
    val refs: List<KnowledgeRef>,
    val inputNames: List<String>,
    val maxDepth: Int,
    val maxCandidates: Int,
    val maxCallsPerCapability: Int,
    val strategy: String,
    val seed: Int,
)

/** 冻结查询：不可变；Controller 用引用校验，学生不能拿别的查询换标签。 */
class FrozenStudyQuery internal constructor(
    val sequence: Int,
    val input: Map<String, String>,
    val predictions: Map<String, Boolean>,
    val candidateIds: List<String>,
    val evidenceVersion: Int,
)

data class StudyObservation(
    val query: FrozenStudyQuery,
    val label: Boolean,
    val source: String,
    val usedAfter: Int,
)

/**
 * 考试端查询控制器：只允许冻结池内、未重复、预算内且预测覆盖全部当前候选的查询。
 * reveal 是唯一读取标签的入口；控制器不向学生暴露目标函数。
 */
class StudyQueryController internal constructor(
    private val pool: List<Map<String, String>>,
    private val budget: Int,
    private val target: (Map<String, String>) -> Boolean,
) {
    private val allowed: Set<String> = pool.map { C8Domain.key(it) }.toSet()
    private val used = LinkedHashSet<String>()
    private var pending: FrozenStudyQuery? = null
    private var oracleCalls = 0

    fun freeze(
        sequence: Int,
        input: Map<String, String>,
        predictions: Map<String, Boolean>,
        candidateIds: List<String>,
    ): FrozenStudyQuery {
        require(C8Domain.key(input) in allowed) { "input_not_in_query_pool" }
        require(C8Domain.key(input) !in used) { "duplicate_query" }
        require(used.size < budget) { "query_budget_exhausted" }
        require(candidateIds.isNotEmpty()) { "empty_candidate_set" }
        require(candidateIds.toSet() == predictions.keys) { "prediction_coverage_mismatch" }
        used += C8Domain.key(input)
        val query = FrozenStudyQuery(
            sequence = sequence,
            input = input.toMap(),
            predictions = predictions.toMap(),
            candidateIds = candidateIds.toList(),
            evidenceVersion = used.size - 1,
        )
        pending = query
        return query
    }

    fun reveal(query: FrozenStudyQuery): StudyObservation {
        require(pending === query) { "query_not_frozen_by_controller" }
        pending = null
        oracleCalls++
        return StudyObservation(query, target(query.input), "trusted_oracle", used.size)
    }

    fun remaining(): Int = budget - used.size

    internal fun oracleCallCount(): Int = oracleCalls
}

/** 学生候选：保留 AST、依赖锁定、结构复杂度和估算执行成本。 */
data class StudyCandidate(
    val body: Expr,
    val canonical: String,
    val nodeCount: Int,
    val depth: Int,
    val dependencies: List<DependencyPin>,
    val estimatedSteps: Int,
    val structuralCost: Int,
) {
    val dependencyIds: List<String> get() = dependencies.map { it.id }.distinct()
}

data class StudyRound(
    val sequence: Int,
    val input: Map<String, String>,
    val label: Boolean,
    val strategy: String,
    val reason: String,
    val classesBefore: Int,
    val classesAfter: Int,
    val candidatesBefore: Int,
    val candidatesAfter: Int,
    val eliminated: Int,
    val predictedTrue: Int,
    val predictedFalse: Int,
)

data class StudyCost(
    val trainingEvaluations: Int,
    val signatureEvaluations: Int,
    val frozenPredictions: Int,
    val interpreterCalls: Int,
    val searchGenerated: Int,
    val composedCandidates: Int,
    val wallMillis: Long,
)

data class StudyStageResult(
    val stage: String,
    val status: String,
    val worldId: String,
    val strategy: String,
    val generated: Int,
    val searchGenerated: Int,
    val composeReason: String?,
    val consistent: Int,
    val initialCandidates: Int,
    val initialClasses: Int,
    val rounds: List<StudyRound>,
    val remainingCandidates: Int,
    val remainingClasses: Int,
    val selected: StudyCandidate?,
    val selectionReason: String?,
    val failure: String?,
    val budget: Int,
    val consistentPreview: List<StudyCandidate>,
    val stats: StudyCost,
)

/**
 * 学生主动学习会话：
 * 1) 从已激活能力 + IR 原语做有界组合；
 * 2) 用公开训练标签筛选；
 * 3) 在无标签 QUERY 池上计算行为等价类；
 * 4) 由策略选择区分性查询，冻结全部候选预测后向受控 Oracle 揭示；
 * 5) 用反例淘汰候选，直到唯一行为类、预算耗尽或没有区分查询。
 *
 * 选择候选时只使用结构/依赖/估算执行成本；不使用 preferredId，也不读取隐藏目标。
 */
class ActiveStudySession(
    private val view: StudyView,
    private val controller: StudyQueryController,
    private val dbPath: Path,
) {
    private val library: List<CapabilityDefinition>? =
        Pack02Knowledge.resolveActive(dbPath, view.refs)
    private val inputTypes: Map<String, CapType> = view.inputNames.associateWith { CapType.Str }
    private val definitionsById: Map<Pair<String, Int>, CapabilityDefinition> =
        library.orEmpty().associateBy { it.id to it.version }
    private val interpreter: CapabilityInterpreter? = library?.let { CapabilityInterpreter(InMemoryResolver(it)) }

    private data class Behavior(
        val candidates: List<StudyCandidate>,
        val predictions: Map<String, List<Boolean>>,
        val signatures: Map<String, String>,
        val classes: LinkedHashMap<String, List<StudyCandidate>>,
    )

    private data class Choice(
        val index: Int,
        val input: Map<String, String>,
        val reason: String,
        val minSide: Int,
        val maxSide: Int,
        val utility: Double,
    )

    fun run(): StudyStageResult {
        val started = System.nanoTime()
        val lib = library ?: return failed("INACTIVE", 0, 0, null, 0, 0, 0, "inactive_or_hash_mismatch", started)
        val compose = CapabilityComposer().compose(
            ComposeRequest(
                inputs = inputTypes,
                output = CapType.Bool3,
                constants = C8Domain.values.map { CapValue.Str(it) },
                library = lib,
                budget = ComposeBudget(
                    maxDepth = view.maxDepth,
                    maxNodes = 40,
                    maxCandidates = view.maxCandidates,
                    maxCallsPerCapability = view.maxCallsPerCapability,
                ),
            ),
        )
        if (compose.reason == "candidate_limit") {
            return failed("SEARCH_LIMITED", compose.candidates.size, compose.generated, compose.reason, 0, 0, 0, "candidate_limit", started)
        }

        // 训练筛选：一旦某条公开样本不匹配就淘汰，避免对明显错误候选做完整查询评估。
        val consistent = ArrayList<StudyCandidate>()
        var trainingEvaluations = 0
        for (candidate in compose.candidates) {
            if (!usesInput(candidate.body)) continue
            var ok = true
            for (case in view.training) {
                val predicted = evaluate(candidate.body, case.values)
                trainingEvaluations++
                if (predicted == null || predicted != case.label) { ok = false; break }
            }
            if (ok) consistent += makeCandidate(candidate)
        }
        if (consistent.isEmpty()) {
            return failed(
                "NO_CANDIDATE", compose.candidates.size, compose.generated, compose.reason,
                0, 0, 0, "no_consistent_candidate", started, trainingEvaluations,
            )
        }

        if (view.queryPool.isEmpty()) {
            if (consistent.size == 1) {
                return identified(
                    consistent[0], compose, consistent, consistent.size, 1, emptyList(),
                    consistent.size, 1, trainingEvaluations, 0, 0, started, "single_hypothesis_no_query_pool",
                )
            }
            return failed(
                "NO_DISTINGUISHING_QUERY", compose.candidates.size, compose.generated, compose.reason,
                consistent.size, consistent.size, consistent.size, "empty_query_pool", started, trainingEvaluations,
            )
        }

        val initial = behavior(consistent)
        val signatureEvaluations = initial.candidates.size * view.queryPool.size
        var current = initial
        if (current.classes.size == 1) {
            return identified(
                representative(current.candidates), compose, consistent, consistent.size, initial.classes.size,
                emptyList(), current.candidates.size, current.classes.size,
                trainingEvaluations, signatureEvaluations, 0, started, null,
            )
        }

        val rounds = ArrayList<StudyRound>()
        val used = HashSet<String>()
        var frozenPredictions = 0
        while (true) {
            if (current.candidates.isEmpty()) {
                return failed(
                    "ALL_REFUTED", compose.candidates.size, compose.generated, compose.reason,
                    consistent.size, initial.classes.size, 0, "all_candidates_refuted", started,
                    trainingEvaluations, signatureEvaluations, frozenPredictions, rounds = rounds,
                )
            }
            if (current.classes.size == 1) {
                return identified(
                    representative(current.candidates), compose, consistent, consistent.size, initial.classes.size,
                    rounds, current.candidates.size, current.classes.size,
                    trainingEvaluations, signatureEvaluations, frozenPredictions, started,
                    "identified_after_${rounds.size}_queries",
                )
            }
            if (rounds.size >= view.budget) {
                return failed(
                    "BUDGET_EXHAUSTED", compose.candidates.size, compose.generated, compose.reason,
                    consistent.size, initial.classes.size, current.candidates.size, "query_budget", started,
                    trainingEvaluations, signatureEvaluations, frozenPredictions, rounds = rounds,
                    remainingClasses = current.classes.size,
                )
            }
            val choice = choose(current, used)
                ?: return failed(
                    "NO_DISTINGUISHING_QUERY", compose.candidates.size, compose.generated, compose.reason,
                    consistent.size, initial.classes.size, current.candidates.size, "no_distinguishing_query", started,
                    trainingEvaluations, signatureEvaluations, frozenPredictions, rounds = rounds,
                    remainingClasses = current.classes.size,
                )
            used += C8Domain.key(choice.input)
            val predictions = current.candidates.associate { candidate ->
                candidate.canonical to current.predictions.getValue(candidate.canonical)[choice.index]
            }
            frozenPredictions += predictions.size
            val frozen = controller.freeze(
                rounds.size, choice.input, predictions, current.candidates.map { it.canonical },
            )
            val observation = controller.reveal(frozen)
            val before = current.candidates.size
            val beforeClasses = current.classes.size
            val remaining = current.candidates.filter {
                current.predictions.getValue(it.canonical)[choice.index] == observation.label
            }
            val after = filterBehavior(current, remaining)
            rounds += StudyRound(
                sequence = observation.query.sequence,
                input = observation.query.input,
                label = observation.label,
                strategy = view.strategy,
                reason = choice.reason,
                classesBefore = beforeClasses,
                classesAfter = after.classes.size,
                candidatesBefore = before,
                candidatesAfter = remaining.size,
                eliminated = before - remaining.size,
                predictedTrue = predictions.count { it.value },
                predictedFalse = predictions.count { !it.value },
            )
            current = after
        }
    }

    /** 计算全部候选在 QUERY 池上的行为；这个阶段不读取任何标签。 */
    private fun behavior(candidates: List<StudyCandidate>): Behavior {
        val predictions = LinkedHashMap<String, List<Boolean>>()
        val signatures = LinkedHashMap<String, String>()
        val valid = ArrayList<StudyCandidate>(candidates.size)
        for (candidate in candidates) {
            val row = ArrayList<Boolean>(view.queryPool.size)
            var ok = true
            for (input in view.queryPool) {
                val value = evaluate(candidate.body, input)
                if (value == null) { ok = false; break }
                row += value
            }
            if (!ok) continue
            valid += candidate
            predictions[candidate.canonical] = row
            signatures[candidate.canonical] = buildString { row.forEach { append(if (it) '1' else '0') } }
        }
        val grouped = LinkedHashMap<String, MutableList<StudyCandidate>>()
        for (candidate in valid) {
            grouped.getOrPut(signatures.getValue(candidate.canonical)) { mutableListOf() }.add(candidate)
        }
        val classes = LinkedHashMap<String, List<StudyCandidate>>()
        grouped.forEach { (signature, group) -> classes[signature] = group.toList() }
        return Behavior(valid, predictions, signatures, classes)
    }

    /** 淘汰候选后复用已经算好的行为矩阵，不重新执行能力。 */
    private fun filterBehavior(previous: Behavior, remaining: List<StudyCandidate>): Behavior {
        val predictions = LinkedHashMap<String, List<Boolean>>()
        val signatures = LinkedHashMap<String, String>()
        for (candidate in remaining) {
            predictions[candidate.canonical] = previous.predictions.getValue(candidate.canonical)
            signatures[candidate.canonical] = previous.signatures.getValue(candidate.canonical)
        }
        val grouped = LinkedHashMap<String, MutableList<StudyCandidate>>()
        for (candidate in remaining) {
            grouped.getOrPut(signatures.getValue(candidate.canonical)) { mutableListOf() }.add(candidate)
        }
        val classes = LinkedHashMap<String, List<StudyCandidate>>()
        grouped.forEach { (signature, group) -> classes[signature] = group.toList() }
        return Behavior(remaining, predictions, signatures, classes)
    }

    /** 两种通用策略：行为分歧（minimax）与成本/复用加权的期望分歧。 */
    private fun choose(current: Behavior, used: Set<String>): Choice? {
        if (view.strategy == "fixed" || view.strategy == "random") {
            val order = if (view.strategy == "random") {
                view.queryPool.sortedBy { C8Domain.key(it) }.shuffled(kotlin.random.Random(view.seed.toLong()))
            } else {
                view.queryPool.sortedBy { C8Domain.key(it) }
            }
            for (input in order) {
                if (C8Domain.key(input) in used) continue
                val index = view.queryPool.indexOfFirst { C8Domain.key(it) == C8Domain.key(input) }
                val trueCount = current.candidates.count { current.predictions.getValue(it.canonical)[index] }
                val falseCount = current.candidates.size - trueCount
                if (trueCount == 0 || falseCount == 0) continue
                return Choice(
                    index, input, "${view.strategy}_order",
                    minOf(trueCount, falseCount), maxOf(trueCount, falseCount), 0.0,
                )
            }
            return null
        }

        val weights = current.candidates.associate { it.canonical to exp(-it.structuralCost / 2.0) }
        val classWeights = current.classes.values.map { group -> group.sumOf { weights.getValue(it.canonical) } }
        val giniBefore = gini(classWeights)
        val totalWeight = classWeights.sum()

        var best: Choice? = null
        for (index in view.queryPool.indices) {
            if (C8Domain.key(view.queryPool[index]) in used) continue
            val trueSet = ArrayList<StudyCandidate>()
            val falseSet = ArrayList<StudyCandidate>()
            for (candidate in current.candidates) {
                if (current.predictions.getValue(candidate.canonical)[index]) trueSet += candidate else falseSet += candidate
            }
            if (trueSet.isEmpty() || falseSet.isEmpty()) continue
            val minSide = minOf(trueSet.size, falseSet.size)
            val maxSide = maxOf(trueSet.size, falseSet.size)
            val utility = if (view.strategy == "utility") {
                val trueWeight = trueSet.sumOf { weights.getValue(it.canonical) }
                val falseWeight = falseSet.sumOf { weights.getValue(it.canonical) }
                // 分支内的不确定性必须按“行为等价类”聚合，不能按候选个数聚合；
                // 否则一个包含大量同义候选的纯分支会被误算成高熵。
                val trueClasses = trueSet.groupBy { current.signatures.getValue(it.canonical) }.values
                    .map { group -> group.sumOf { weights.getValue(it.canonical) } }
                val falseClasses = falseSet.groupBy { current.signatures.getValue(it.canonical) }.values
                    .map { group -> group.sumOf { weights.getValue(it.canonical) } }
                val after = (trueWeight / totalWeight) * gini(trueClasses) +
                    (falseWeight / totalWeight) * gini(falseClasses)
                (giniBefore - after).coerceAtLeast(0.0)
            } else 0.0
            val candidate = Choice(
                index, view.queryPool[index],
                if (view.strategy == "utility") {
                    "utility gain=%.4f minSide=%d maxSide=%d classes=%d".format(utility, minSide, maxSide, current.classes.size)
                } else {
                    "divergence minSide=%d maxSide=%d classes=%d".format(minSide, maxSide, current.classes.size)
                },
                minSide, maxSide, utility,
            )
            if (best == null || better(candidate, best)) best = candidate
        }
        return best
    }

    private fun better(candidate: Choice, best: Choice): Boolean {
        if (view.strategy == "utility") {
            if (candidate.utility != best.utility) return candidate.utility > best.utility
        }
        if (candidate.minSide != best.minSide) return candidate.minSide > best.minSide
        if (candidate.maxSide != best.maxSide) return candidate.maxSide < best.maxSide
        return C8Domain.key(candidate.input) < C8Domain.key(best.input)
    }

    private fun gini(weights: List<Double>): Double {
        val total = weights.sum()
        if (total <= 0.0) return 0.0
        var sum = 0.0
        for (weight in weights) {
            val share = weight / total
            sum += share * share
        }
        return 1.0 - sum
    }

    private fun representative(candidates: List<StudyCandidate>): StudyCandidate =
        if (view.strategy == "utility") {
            candidates.minWith(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
        } else {
            candidates.minWith(compareBy({ it.nodeCount }, { it.estimatedSteps }, { it.canonical }))
        }

    private fun makeCandidate(candidate: ComposedCandidate): StudyCandidate {
        val dependencies = candidate.dependencies
        return StudyCandidate(
            body = candidate.body,
            canonical = candidate.canonical,
            nodeCount = candidate.nodeCount,
            depth = candidate.depth,
            dependencies = dependencies,
            estimatedSteps = estimatedSteps(candidate.body),
            structuralCost = candidate.nodeCount - dependencies.map { it.id }.distinct().size,
        )
    }

    /** 估算执行成本：表达式节点 + 每个调用的被调能力正文节点（近似内联执行量）。 */
    private fun estimatedSteps(body: Expr): Int {
        var cost = countNodes(body)
        fun walk(expr: Expr) {
            when (expr) {
                is Expr.Call -> {
                    cost += definitionsById[expr.id to expr.version]?.let { countNodes(it.body) } ?: 0
                    expr.args.values.forEach(::walk)
                }
                is Expr.Field -> walk(expr.record)
                is Expr.Eq -> { walk(expr.left); walk(expr.right) }
                is Expr.And -> { walk(expr.left); walk(expr.right) }
                is Expr.Or -> { walk(expr.left); walk(expr.right) }
                is Expr.Not -> walk(expr.value)
                is Expr.If -> { walk(expr.condition); walk(expr.whenTrue); walk(expr.whenFalse) }
                is Expr.Let -> { walk(expr.value); walk(expr.body) }
                is Expr.Arg, is Expr.Const, is Expr.Ref -> Unit
            }
        }
        walk(body)
        return cost
    }

    private fun usesInput(body: Expr): Boolean {
        var found = false
        fun walk(expr: Expr) {
            when (expr) {
                is Expr.Arg -> found = true
                is Expr.Field -> walk(expr.record)
                is Expr.Eq -> { walk(expr.left); walk(expr.right) }
                is Expr.And -> { walk(expr.left); walk(expr.right) }
                is Expr.Or -> { walk(expr.left); walk(expr.right) }
                is Expr.Not -> walk(expr.value)
                is Expr.If -> { walk(expr.condition); walk(expr.whenTrue); walk(expr.whenFalse) }
                is Expr.Let -> { walk(expr.value); walk(expr.body) }
                is Expr.Call -> expr.args.values.forEach(::walk)
                is Expr.Const, is Expr.Ref -> Unit
            }
        }
        walk(body)
        return found
    }

    /** 只用于候选自评：Unknown/Error 返回 null，不晋升为 false。 */
    private fun evaluate(body: Expr, values: Map<String, String>): Boolean? {
        val interpreter = this.interpreter ?: return null
        if (values.keys != inputTypes.keys) return null
        val definition = CapabilityDefinition("__c8_study_candidate", 1, inputTypes, CapType.Bool3, body)
        val result = try {
            interpreter.execute(
                definition,
                values.mapValues { (_, value) -> EvalResult.Known(CapValue.Str(value)) },
                EvalBudget(maxSteps = 10000),
            )
        } catch (_: Exception) {
            return null
        }
        return (result as? EvalResult.Known)?.value?.let { (it as? CapValue.Bool)?.value }
    }

    private fun identified(
        selected: StudyCandidate,
        compose: ComposeResult,
        consistent: List<StudyCandidate>,
        initialCandidates: Int,
        initialClasses: Int,
        rounds: List<StudyRound>,
        remainingCandidates: Int,
        remainingClasses: Int,
        trainingEvaluations: Int,
        signatureEvaluations: Int,
        frozenPredictions: Int,
        started: Long,
        extra: String?,
    ): StudyStageResult {
        val reason = buildString {
            append("strategy=").append(view.strategy)
            append("; initial_classes=").append(initialClasses)
            append("; remaining_classes=").append(remainingClasses)
            append("; candidates=").append(consistent.size)
            append("; selected_estimated_steps=").append(selected.estimatedSteps)
            append("; selected_nodes=").append(selected.nodeCount)
            append("; dependencies=").append(selected.dependencyIds.joinToString("|").ifEmpty { "none" })
            if (extra != null) append("; ").append(extra)
        }
        return StudyStageResult(
            stage = view.stage,
            status = "IDENTIFIED",
            worldId = view.worldId,
            strategy = view.strategy,
            generated = compose.candidates.size,
            searchGenerated = compose.generated,
            composeReason = compose.reason,
            consistent = consistent.size,
            initialCandidates = initialCandidates,
            initialClasses = initialClasses,
            rounds = rounds,
            remainingCandidates = remainingCandidates,
            remainingClasses = remainingClasses,
            selected = selected,
            selectionReason = reason,
            failure = null,
            budget = view.budget,
            consistentPreview = consistent.sortedWith(compareBy({ it.estimatedSteps }, { it.canonical })).take(20),
            stats = StudyCost(
                trainingEvaluations, signatureEvaluations, frozenPredictions,
                trainingEvaluations + signatureEvaluations, compose.generated, compose.candidates.size,
                (System.nanoTime() - started) / 1_000_000,
            ),
        )
    }

    private fun failed(
        status: String,
        generated: Int,
        searchGenerated: Int,
        composeReason: String?,
        consistent: Int,
        initialClasses: Int,
        remainingCandidates: Int,
        failure: String,
        started: Long,
        trainingEvaluations: Int = 0,
        signatureEvaluations: Int = 0,
        frozenPredictions: Int = 0,
        rounds: List<StudyRound> = emptyList(),
        remainingClasses: Int = 0,
    ): StudyStageResult = StudyStageResult(
        stage = view.stage,
        status = status,
        worldId = view.worldId,
        strategy = view.strategy,
        generated = generated,
        searchGenerated = searchGenerated,
        composeReason = composeReason,
        consistent = consistent,
        initialCandidates = consistent,
        initialClasses = initialClasses,
        rounds = rounds,
        remainingCandidates = remainingCandidates,
        remainingClasses = remainingClasses,
        selected = null,
        selectionReason = null,
        failure = failure,
        budget = view.budget,
        consistentPreview = emptyList(),
        stats = StudyCost(
            trainingEvaluations, signatureEvaluations, frozenPredictions,
            trainingEvaluations + signatureEvaluations, searchGenerated, generated,
            (System.nanoTime() - started) / 1_000_000,
        ),
    )
}
