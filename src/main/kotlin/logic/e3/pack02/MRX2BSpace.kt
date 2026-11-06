// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02B 候选生命周期审计 + A/B/C 原子搜索策略（BASELINE/DIVERSITY/PAIRWISE）。
// 只使用 TRAIN 标签排序；完整域真值只出现在考官端 auditAfterExam。
package logic.e3.pack02

import logic.capability.*

enum class MRX2BStrategy(val wire: String) {
    BASELINE("BASELINE"),
    DIVERSITY("DIVERSITY"),
    PAIRWISE("PAIRWISE"),
}

enum class MRX2BStage(val wire: String) {
    GENERATED("GENERATED"),
    TRAIN_CONSISTENT("TRAIN_CONSISTENT"),
    RETAINED("RETAINED"),
    SELECTED("SELECTED"),
}

data class MRX2BPrune(val canonical: String, val stage: String, val reason: String)

data class MRX2BPairGain(
    val left: String,
    val right: String,
    val operator: String,
    val accuracy: Double,
    val gainOverSingle: Double,
)

class MRX2BTrace {
    private val stages = LinkedHashMap<MRX2BStage, LinkedHashMap<String, StudyCandidate>>()
    val prunes = ArrayList<MRX2BPrune>()
    val atoms = ArrayList<String>()
    val pairGains = ArrayList<MRX2BPairGain>()

    fun record(stage: MRX2BStage, candidate: StudyCandidate) {
        stages.getOrPut(stage) { LinkedHashMap() }[candidate.canonical] = candidate
    }

    fun prune(candidate: StudyCandidate, stage: String, reason: String) {
        prunes += MRX2BPrune(candidate.canonical, stage, reason)
    }

    fun candidates(stage: MRX2BStage): List<StudyCandidate> = stages[stage]?.values?.toList() ?: emptyList()
    fun size(stage: MRX2BStage): Int = stages[stage]?.size ?: 0
}

data class MRX2BSpaceResult(
    val strategy: String,
    val generated: Int,
    val consistent: Int,
    val retained: Int,
    val selected: StudyCandidate?,
    val trace: MRX2BTrace,
    val wallMillis: Long,
)

/**
 * 三种策略共用同一类型检查、解释器、候选上限和训练集；差别只在“原子如何排序/截断”。
 * - BASELINE：单原子训练准确率 top10。
 * - DIVERSITY：按 family 分组轮转，避免相似特征占满 top10。
 * - PAIRWISE：训练集上两两 AND/OR/XOR/XNOR 预评估，保留能产生组合增益的原子。
 */
class MRX2BSpace(
    private val inputNames: List<String>,
    private val library: List<CapabilityDefinition>,
    private val training: List<StudyCase>,
    private val strategy: MRX2BStrategy,
    private val atomLimit: Int = 10,
    private val maxCandidates: Int = 30_000,
    private val consistentLimit: Int = 2_000,
) {
    private val inputTypes = inputNames.associateWith { CapType.Str }
    private val resolver = InMemoryResolver(library)
    private val checker = CapabilityTypeChecker(resolver)
    private val interpreter = CapabilityInterpreter(resolver)
    private val byId = library.associateBy { it.id to it.version }
    private val labels = training.map { it.label }

    private data class Atom(val expr: Expr, val family: String, val canonical: String, val predictions: List<Boolean?>)

    fun generate(): MRX2BSpaceResult {
        val started = System.nanoTime()
        val trace = MRX2BTrace()
        val atoms = buildAtoms()
        val selectedAtoms = selectAtoms(atoms, trace)
        trace.atoms += selectedAtoms.map { it.canonical }
        val literals = selectedAtoms.flatMap { atom -> listOf(atom.expr, Expr.Not(atom.expr)) }
        val terms = ArrayList<Expr>()
        terms += literals
        for (i in literals.indices) for (j in i until literals.size) terms += Expr.And(literals[i], literals[j])
        val candidates = ArrayList<StudyCandidate>()
        val seen = HashSet<String>()

        fun admit(body: Expr) {
            if (candidates.size >= maxCandidates) return
            if (countNodes(body) > 40) return
            val canonical = try { CanonicalJson.expr(body) } catch (_: Exception) { return }
            if (!seen.add(canonical)) return
            val definition = CapabilityDefinition("__mrx2b", 1, inputTypes, CapType.Bool3, body)
            if (!checker.check(definition).ok) return
            val candidate = StudyCandidate(
                body, canonical, countNodes(body), exprDepth(body), collectDependencies(body),
                estimatedSteps(body), countNodes(body) - collectDependencies(body).map { it.id }.distinct().size,
            )
            candidates += candidate
            trace.record(MRX2BStage.GENERATED, candidate)
        }

        terms.forEach { admit(it) }
        for (i in terms.indices) for (j in i until terms.size) admit(Expr.Or(terms[i], terms[j]))
        // 一层 If：条件/分支来自原子字面量，保留条件结构。
        val simple = literals.take(12)
        for (c in simple) for (a in simple) for (b in simple) admit(Expr.If(c, a, b))

        val consistent = ArrayList<StudyCandidate>()
        for (candidate in candidates) {
            var ok = true
            for (case in training) {
                if (evaluate(candidate.body, case.values) != case.label) { ok = false; break }
            }
            if (ok) {
                consistent += candidate
                trace.record(MRX2BStage.TRAIN_CONSISTENT, candidate)
            } else {
                trace.prune(candidate, "TRAIN_CONSISTENT", "train_mismatch")
            }
        }
        val retained = consistent.sortedWith(compareBy({ it.nodeCount }, { it.canonical })).take(consistentLimit)
        val retainedSet = retained.map { it.canonical }.toSet()
        for (candidate in consistent) {
            if (candidate.canonical in retainedSet) trace.record(MRX2BStage.RETAINED, candidate)
            else trace.prune(candidate, "RETAINED", "consistent_cap_$consistentLimit")
        }
        val selected = retained.minWithOrNull(compareBy({ it.estimatedSteps }, { it.nodeCount }, { it.canonical }))
        selected?.let { trace.record(MRX2BStage.SELECTED, it) }
        return MRX2BSpaceResult(
            strategy.wire, candidates.size, consistent.size, retained.size, selected, trace,
            (System.nanoTime() - started) / 1_000_000,
        )
    }

    private fun buildAtoms(): List<Atom> {
        val out = ArrayList<Atom>()
        for (name in inputNames) for (value in C8Domain.values) {
            val expr = Expr.Eq(Expr.Arg(name, CapType.Str), Expr.Const(CapValue.Str(value)))
            out += Atom(expr, "eq:$name", CanonicalJson.expr(expr), predictions(expr))
        }
        for (definition in library) {
            if (definition.inputs.keys.all { it in inputNames }) {
                val expr = Expr.Call(
                    definition.id, definition.version, definition.contentHash(),
                    definition.inputs.keys.associateWith { Expr.Arg(it, CapType.Str) },
                )
                out += Atom(expr, "call:${definition.id}", CanonicalJson.expr(expr), predictions(expr))
            }
        }
        return out
    }

    private fun selectAtoms(atoms: List<Atom>, trace: MRX2BTrace): List<Atom> {
        fun accuracy(predictions: List<Boolean?>): Double {
            val known = predictions.mapIndexedNotNull { i, p -> p?.let { i to it } }
            if (known.isEmpty()) return 0.0
            val correct = known.count { it.second == labels[it.first] }
            return maxOf(correct, known.size - correct).toDouble() / known.size
        }
        return when (strategy) {
            MRX2BStrategy.BASELINE -> atoms.sortedWith(compareByDescending<Atom> { accuracy(it.predictions) }.thenBy { it.canonical }).take(atomLimit)
            MRX2BStrategy.DIVERSITY -> {
                val groups = atoms.groupBy { it.family }.mapValues { (_, group) ->
                    group.sortedWith(compareByDescending<Atom> { accuracy(it.predictions) }.thenBy { it.canonical })
                }
                val out = ArrayList<Atom>()
                var round = 0
                while (out.size < atomLimit && groups.values.any { round < it.size }) {
                    for ((_, group) in groups.toSortedMap()) {
                        if (round < group.size && out.size < atomLimit) out += group[round]
                    }
                    round++
                }
                out
            }
            MRX2BStrategy.PAIRWISE -> {
                val ranked = ArrayList<Atom>()
                val gain = HashMap<String, Double>()
                for (i in atoms.indices) for (j in i + 1 until atoms.size) {
                    val a = atoms[i].predictions
                    val b = atoms[j].predictions
                    if (a.any { it == null } || b.any { it == null }) continue
                    val single = maxOf(accuracy(a), accuracy(b))
                    val ops = mapOf<String, (Boolean, Boolean) -> Boolean>(
                        "AND" to { x, y -> x && y }, "OR" to { x, y -> x || y },
                        "XOR" to { x, y -> x != y }, "XNOR" to { x, y -> x == y },
                    )
                    for ((name, op) in ops) {
                        val combined = a.indices.map { k -> op(a[k]!!, b[k]!!) }
                        val score = accuracy(combined)
                        val pairGain = score - single
                        trace.pairGains += MRX2BPairGain(atoms[i].canonical, atoms[j].canonical, name, score, pairGain)
                        gain[atoms[i].canonical] = maxOf(gain[atoms[i].canonical] ?: 0.0, pairGain)
                        gain[atoms[j].canonical] = maxOf(gain[atoms[j].canonical] ?: 0.0, pairGain)
                    }
                }
                ranked += atoms.sortedWith(compareByDescending<Atom> { (gain[it.canonical] ?: 0.0) + accuracy(it.predictions) }.thenBy { it.canonical })
                ranked.distinctBy { it.canonical }.take(atomLimit)
            }
        }
    }

    private fun predictions(expr: Expr): List<Boolean?> = training.map { evaluate(expr, it.values) }

    private fun evaluate(body: Expr, values: Map<String, String>): Boolean? {
        val definition = CapabilityDefinition("__mrx2b_eval", 1, inputTypes, CapType.Bool3, body)
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
