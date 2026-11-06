// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 学生的结构升级候选空间；在冻结 C8-03 学生之外，显式提供
// DNF/CNF/If 形态的有界枚举，用于诊断实验。基础学习仍由 C8-03 学生完成。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path

enum class MRXROperator(val wire: String) {
    DNF("dnf"),
    CNF("cnf"),
    IF("if"),
    DNF_IF("dnf_if"),
}

data class MRXRSpaceConfig(
    val operator: MRXROperator,
    val maxCandidates: Int = 20_000,
    val literalLimit: Int = 10,
)

data class MRXRQuery(
    val input: Map<String, String>,
    val label: Boolean,
    val purpose: String,
    val eliminated: Int,
)

data class MRXRCost(
    val generated: Int,
    val evaluated: Int,
    val selectedNodes: Int,
    val selectedSteps: Int,
    val queries: Int,
    val wallMillis: Long,
)

data class MRXRAttempt(
    val operator: String,
    val status: String,
    val generated: Int,
    val consistent: Int,
    val initialClasses: Int,
    val remainingClasses: Int,
    val selected: StudyCandidate?,
    val queries: List<MRXRQuery>,
    val challengeQueries: Int,
    val failure: String?,
    val cost: MRXRCost,
    val survivors: List<StudyCandidate> = emptyList(),
)

/**
 * 结构升级候选空间：
 * - DNF：两到三个合取项的析取；
 * - CNF：两个析取子句的合取；
 * - IF：条件表达式（可含一层嵌套）。
 *
 * 只从训练标签选择“有区分度”的字面量，降低组合爆炸；隐藏目标/HELDOUT 不参与生成。
 */
class MRXRSpace(
    private val inputNames: List<String>,
    private val library: List<CapabilityDefinition>,
    private val training: List<StudyCase>,
) {
    private val inputTypes = inputNames.associateWith { CapType.Str }
    private val resolver = InMemoryResolver(library)
    private val checker = CapabilityTypeChecker(resolver)
    private val interpreter = CapabilityInterpreter(resolver)
    private val byId = library.associateBy { it.id to it.version }

    private val allAtoms: List<Expr> = buildAtoms()
    private val rankedAtoms: List<Expr> = rankAtoms(allAtoms)
    private val literals: List<Expr> = buildLiterals(rankedAtoms)

    fun generate(config: MRXRSpaceConfig): MRXRAttempt {
        val started = System.nanoTime()
        var evaluated = 0
        val seen = HashSet<String>()
        val candidates = ArrayList<StudyCandidate>()
        var generated = 0

        fun admit(body: Expr) {
            if (candidates.size >= config.maxCandidates) return
            if (countNodes(body) > 40) return
            val canonical = try { CanonicalJson.expr(body) } catch (_: Exception) { return }
            if (!seen.add(canonical)) return
            val definition = CapabilityDefinition("__mrx_candidate", 1, inputTypes, CapType.Bool3, body)
            if (!checker.check(definition).ok) return
            generated++
            val deps = collectDependencies(body)
            candidates += StudyCandidate(
                body = body,
                canonical = canonical,
                nodeCount = countNodes(body),
                depth = exprDepth(body),
                dependencies = deps,
                estimatedSteps = estimatedSteps(body),
                structuralCost = countNodes(body) - deps.map { it.id }.distinct().size,
            )
        }

        val terms = buildTerms()
        when (config.operator) {
            MRXROperator.DNF -> {
                terms.forEach { admit(it) }
                for (i in terms.indices) for (j in i until terms.size) admit(Expr.Or(terms[i], terms[j]))
                val cappedTerms = terms.take(24)
                for (i in cappedTerms.indices) for (j in i until cappedTerms.size) for (k in j until cappedTerms.size) {
                    admit(Expr.Or(Expr.Or(cappedTerms[i], cappedTerms[j]), cappedTerms[k]))
                }
            }
            MRXROperator.CNF -> {
                val clauses = buildClauses()
                clauses.forEach { admit(it) }
                for (i in clauses.indices) for (j in i until clauses.size) admit(Expr.And(clauses[i], clauses[j]))
            }
            MRXROperator.IF -> buildIfCandidates { admit(it) }
            MRXROperator.DNF_IF -> {
                terms.forEach { admit(it) }
                for (i in terms.indices) for (j in i until terms.size) admit(Expr.Or(terms[i], terms[j]))
                buildIfCandidates { admit(it) }
            }
        }

        if (candidates.isEmpty()) {
            return MRXRAttempt(
                operator = config.operator.wire, status = "NO_CANDIDATE", generated = 0, consistent = 0,
                initialClasses = 0, remainingClasses = 0, selected = null, queries = emptyList(),
                challengeQueries = 0, failure = "empty_space",
                cost = MRXRCost(0, 0, 0, 0, 0, (System.nanoTime() - started) / 1_000_000),
            )
        }

        val consistent = ArrayList<StudyCandidate>()
        for (candidate in candidates.sortedWith(compareBy({ it.nodeCount }, { it.canonical }))) {
            var ok = true
            for (case in training) {
                val predicted = evaluate(candidate.body, case.values)
                evaluated++
                if (predicted == null || predicted != case.label) { ok = false; break }
            }
            if (ok) consistent += candidate
            if (consistent.size >= 2_000) break
        }
        if (consistent.isEmpty()) {
            return MRXRAttempt(
                operator = config.operator.wire, status = "NO_CANDIDATE", generated = generated, consistent = 0,
                initialClasses = 0, remainingClasses = 0, selected = null, queries = emptyList(),
                challengeQueries = 0, failure = "no_consistent_candidate",
                cost = MRXRCost(generated, evaluated, 0, 0, 0, (System.nanoTime() - started) / 1_000_000),
            )
        }

        var current: List<StudyCandidate> = consistent
        val predictions = HashMap<String, List<Boolean>>()
        val signatures = HashMap<String, String>()
        for (candidate in current) {
            val row = ArrayList<Boolean>(querySize())
            for (index in 0 until querySize()) {
                val value = evaluate(candidate.body, queryInput(index))
                if (value == null) { row.clear(); break }
                row += value
            }
            if (row.size != querySize()) continue
            predictions[candidate.canonical] = row
            signatures[candidate.canonical] = buildString { row.forEach { append(if (it) '1' else '0') } }
        }
        current = current.filter { predictions.containsKey(it.canonical) }
        val initialClasses = current.map { signatures.getValue(it.canonical) }.toSet().size
        val rounds = ArrayList<MRXRQuery>()
        var used = HashSet<String>()

        while (true) {
            if (current.isEmpty()) {
                return finish(config, generated, evaluated, consistent.size, initialClasses, 0, null, rounds, "all_refuted", started)
            }
            val classes = current.groupBy { signatures.getValue(it.canonical) }
            if (classes.size == 1) {
                val (selected, challengeUsed) = resolveChallenge(current)
                return MRXRAttempt(
                    operator = config.operator.wire, status = "IDENTIFIED", generated = generated,
                    consistent = consistent.size, initialClasses = initialClasses, remainingClasses = 1,
                    selected = selected, queries = rounds, challengeQueries = challengeUsed, failure = null,
                    cost = MRXRCost(
                        generated, evaluated + current.size * querySize() + challengeEvals,
                        selected.nodeCount, selected.estimatedSteps, rounds.size + challengeUsed,
                        (System.nanoTime() - started) / 1_000_000,
                    ),
                    survivors = current,
                )
            }
            if (rounds.size >= 12) {
                return finish(config, generated, evaluated, consistent.size, initialClasses, classes.size, null, rounds, "query_budget", started)
            }
            val choice = choose(current, predictions, used) ?: return finish(
                config, generated, evaluated, consistent.size, initialClasses, classes.size, null, rounds, "no_distinguishing_query", started,
            )
            used += C8Domain.key(choice)
            val controller = queryController ?: return finish(
                config, generated, evaluated, consistent.size, initialClasses, classes.size, null, rounds, "no_query_controller", started,
            )
            val frozenPredictions = current.associate { it.canonical to predictions.getValue(it.canonical)[indexOf(choice)] }
            val observation = try {
                val frozen = controller.freeze(
                    rounds.size, choice, frozenPredictions, current.map { it.canonical },
                )
                controller.reveal(frozen)
            } catch (_: IllegalArgumentException) {
                return finish(
                    config, generated, evaluated, consistent.size, initialClasses, classes.size, null, rounds, "query_controller_refused", started,
                )
            }
            val label = observation.label
            val before = current.size
            current = current.filter { frozenPredictions.getValue(it.canonical) == label }
            rounds += MRXRQuery(choice, label, "resolve_class", before - current.size)
        }
    }

    private fun finish(
        config: MRXRSpaceConfig, generated: Int, evaluated: Int, consistent: Int,
        initialClasses: Int, remainingClasses: Int, selected: StudyCandidate?, rounds: List<MRXRQuery>,
        failure: String, started: Long,
    ): MRXRAttempt = MRXRAttempt(
        operator = config.operator.wire, status = "BUDGET_EXHAUSTED", generated = generated,
        consistent = consistent, initialClasses = initialClasses, remainingClasses = remainingClasses,
        selected = selected, queries = rounds, challengeQueries = 0, failure = failure,
        cost = MRXRCost(generated, evaluated + challengeEvals, 0, 0, rounds.size, (System.nanoTime() - started) / 1_000_000),
    )

    // 升级实验的行为类用 QUERY 池；每次查询都必须经过冻结/揭示控制器，不能直接读标签。
    private var queryInputs: List<Map<String, String>> = emptyList()
    private var queryController: StudyQueryController? = null

    fun withQuery(pool: List<Map<String, String>>, controller: StudyQueryController): MRXRSpace {
        queryInputs = pool
        queryController = controller
        return this
    }

    private var challengeInputs: List<Map<String, String>> = emptyList()
    private var challengeController: StudyQueryController? = null
    private var challengeEvals = 0

    /** CHALLENGE 是独立反馈池：学生必须付费查询；其标签永远不作为最终 HELDOUT 成绩。 */
    fun withChallenge(pool: List<Map<String, String>>, controller: StudyQueryController): MRXRSpace {
        challengeInputs = pool
        challengeController = controller
        return this
    }

    private fun resolveChallenge(candidates: List<StudyCandidate>): Pair<StudyCandidate, Int> {
        val controller = challengeController ?: return candidates.minWith(
            compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }),
        ) to 0
        if (challengeInputs.isEmpty()) return candidates.minWith(
            compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }),
        ) to 0
        var current = candidates
        var used = 0
        while (true) {
            val predictions = HashMap<String, List<Boolean>>()
            for (candidate in current) {
                val row = ArrayList<Boolean>(challengeInputs.size)
                for (input in challengeInputs) {
                    val value = evaluate(candidate.body, input)
                    challengeEvals++
                    if (value == null) { row.clear(); break }
                    row += value
                }
                if (row.size == challengeInputs.size) predictions[candidate.canonical] = row
            }
            current = current.filter { predictions.containsKey(it.canonical) }
            if (current.size <= 1) break
            val groups = current.groupBy { candidate ->
                predictions.getValue(candidate.canonical).joinToString("") { if (it) "1" else "0" }
            }
            if (groups.size == 1) break
            if (controller.remaining() <= 0) break
            var bestIndex = -1
            var bestScore = -1
            for (index in challengeInputs.indices) {
                val trueCount = current.count { predictions.getValue(it.canonical)[index] }
                val falseCount = current.size - trueCount
                if (trueCount == 0 || falseCount == 0) continue
                val score = minOf(trueCount, falseCount)
                if (score > bestScore) { bestScore = score; bestIndex = index }
            }
            if (bestIndex < 0) break
            val input = challengeInputs[bestIndex]
            val frozenPredictions = current.associate { it.canonical to predictions.getValue(it.canonical)[bestIndex] }
            val observation = try {
                val frozen = controller.freeze(used, input, frozenPredictions, current.map { it.canonical })
                controller.reveal(frozen)
            } catch (_: IllegalArgumentException) {
                break
            }
            used++
            current = current.filter { frozenPredictions.getValue(it.canonical) == observation.label }
            if (current.isEmpty()) break
        }
        val selected = current.minWithOrNull(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
            ?: candidates.minWith(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
        return selected to used
    }

    private fun querySize(): Int = queryInputs.size
    private fun queryInput(index: Int): Map<String, String> = queryInputs[index]
    private fun indexOf(input: Map<String, String>): Int = queryInputs.indexOfFirst { C8Domain.key(it) == C8Domain.key(input) }

    private fun choose(current: List<StudyCandidate>, predictions: Map<String, List<Boolean>>, used: Set<String>): Map<String, String>? {
        var best: Map<String, String>? = null
        var bestScore = -1
        var bestMax = Int.MAX_VALUE
        for (index in queryInputs.indices) {
            if (C8Domain.key(queryInputs[index]) in used) continue
            val trueCount = current.count { predictions.getValue(it.canonical)[index] }
            val falseCount = current.size - trueCount
            if (trueCount == 0 || falseCount == 0) continue
            val score = minOf(trueCount, falseCount)
            val maxSide = maxOf(trueCount, falseCount)
            if (score > bestScore || (score == bestScore && maxSide < bestMax)) {
                bestScore = score; bestMax = maxSide; best = queryInputs[index]
            }
        }
        return best
    }

    private fun buildAtoms(): List<Expr> {
        val atoms = ArrayList<Expr>()
        for (name in inputNames) for (value in C8Domain.values) {
            atoms += Expr.Eq(Expr.Arg(name, CapType.Str), Expr.Const(CapValue.Str(value)))
        }
        for (definition in library) {
            if (definition.inputs.keys.all { it in inputNames }) {
                atoms += Expr.Call(
                    definition.id, definition.version, definition.contentHash(),
                    definition.inputs.keys.associateWith { Expr.Arg(it, CapType.Str) },
                )
            }
        }
        return atoms.distinctBy { CanonicalJson.expr(it) }
    }

    private fun rankAtoms(atoms: List<Expr>): List<Expr> =
        atoms.map { atom ->
            var correct = 0
            for (case in training) if (evaluate(atom, case.values) == case.label) correct++
            val accuracy = maxOf(correct, training.size - correct).toDouble() / training.size.coerceAtLeast(1)
            atom to accuracy
        }.filter { it.second >= 0.55 }
            .sortedWith(compareByDescending<Pair<Expr, Double>> { it.second }.thenBy { CanonicalJson.expr(it.first) })
            .map { it.first }

    private fun buildLiterals(atoms: List<Expr>): List<Expr> {
        val selected = atoms.take(10)
        val out = ArrayList<Expr>()
        for (atom in selected) {
            out += atom
            out += Expr.Not(atom)
        }
        return out
    }

    private fun buildTerms(): List<Expr> {
        val out = ArrayList<Expr>()
        out += literals
        for (i in literals.indices) for (j in i until literals.size) out += Expr.And(literals[i], literals[j])
        return out
    }

    private fun buildClauses(): List<Expr> {
        val out = ArrayList<Expr>()
        out += literals
        for (i in literals.indices) for (j in i until literals.size) out += Expr.Or(literals[i], literals[j])
        return out
    }

    private fun buildIfCandidates(admit: (Expr) -> Unit) {
        val simple = literals
        for (condition in simple) for (whenTrue in simple) for (whenFalse in simple) {
            admit(Expr.If(condition, whenTrue, whenFalse))
        }
        // 一层嵌套：if c then (if c2 then a else b) else d
        for ((index, condition) in simple.withIndex()) {
            if (index >= 8) break
            for (innerCondition in simple.take(8)) for (a in simple.take(8)) for (b in simple.take(8)) {
                admit(Expr.If(condition, Expr.If(innerCondition, a, b), b))
            }
        }
    }

    private fun evaluate(body: Expr, values: Map<String, String>): Boolean? {
        val definition = CapabilityDefinition("__mrx_eval", 1, inputTypes, CapType.Bool3, body)
        val result = try {
            interpreter.execute(definition, values.mapValues { (_, v) -> EvalResult.Known(CapValue.Str(v)) }, EvalBudget(10000))
        } catch (_: Exception) { return null }
        return (result as? EvalResult.Known)?.value?.let { (it as? CapValue.Bool)?.value }
    }

    private fun estimatedSteps(body: Expr): Int {
        var cost = countNodes(body)
        fun walk(expr: Expr) {
            when (expr) {
                is Expr.Call -> {
                    cost += byId[expr.id to expr.version]?.let { countNodes(it.body) } ?: 0
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
}
