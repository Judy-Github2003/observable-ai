package logic.e3.dmrv

/** DMRV-01 bounded reference student, written by ChatGPT, not self-invented by the AI student.
 * No world family, target, oracle, heldout labels, or examiner types are reachable from here.
 * Every policy input is derived from public examples or prior-task scalar rewards.
 */
enum class DmrvAction { BASE_ONLY, ADD_BINARY, ADD_PARITY3, ADD_MUX }
enum class DmrvBinary { AND, OR, XOR, XNOR, NAND, NOR }
enum class DmrvArm { META_CONTEXT, GLOBAL_FEEDBACK, STATIC_BINARY, RANDOM, SHUFFLED_CONTEXT, COLD_START }

data class DmrvExample(val input: Int, val label: Boolean) {
    init { require(input in 0..15) }
}

data class DmrvPublicTask(val taskId: String, val observations: List<DmrvExample>) {
    init {
        require(taskId.isNotBlank()); require(observations.isNotEmpty())
        require(observations.map { it.input }.distinct().size == observations.size)
    }
}

/** Explicit, inspectable program. The program constructors are all designer-supplied. */
sealed class DmrvExpr {
    abstract fun eval(input: Int): Boolean
    abstract fun encode(): String
    abstract val depth: Int

    data class Literal(val index: Int, val invert: Boolean = false) : DmrvExpr() {
        init { require(index in 0..3) }
        override fun eval(input: Int) = (((input shr index) and 1) == 1) != invert
        override fun encode() = "L:$index:${if (invert) 1 else 0}"
        override val depth = 1
    }
    data class Pair(val left: Int, val right: Int, val op: DmrvBinary) : DmrvExpr() {
        init { require(left in 0..3 && right in 0..3 && left < right) }
        override fun eval(input: Int): Boolean {
            val a = ((input shr left) and 1) == 1
            val b = ((input shr right) and 1) == 1
            return when (op) {
                DmrvBinary.AND -> a && b
                DmrvBinary.OR -> a || b
                DmrvBinary.XOR -> a != b
                DmrvBinary.XNOR -> a == b
                DmrvBinary.NAND -> !(a && b)
                DmrvBinary.NOR -> !(a || b)
            }
        }
        override fun encode() = "B:$left:$right:${op.name}"
        override val depth = 2
    }
    data class Parity3(val a: Int, val b: Int, val c: Int, val invert: Boolean = false) : DmrvExpr() {
        init { require(a in 0..3 && a < b && b < c && c in 0..3) }
        override fun eval(input: Int) = ((((input shr a) xor (input shr b) xor (input shr c)) and 1) == 1) != invert
        override fun encode() = "P:$a:$b:$c:${if (invert) 1 else 0}"
        override val depth = 3
    }
    data class Mux(val selector: Int, val ifTrue: Int, val ifFalse: Int) : DmrvExpr() {
        init { require(setOf(selector, ifTrue, ifFalse).size == 3); require(listOf(selector, ifTrue, ifFalse).all { it in 0..3 }) }
        override fun eval(input: Int): Boolean {
            val x = ((input shr selector) and 1) == 1
            return ((input shr (if (x) ifTrue else ifFalse)) and 1) == 1
        }
        override fun encode() = "M:$selector:$ifTrue:$ifFalse"
        override val depth = 3
    }

    companion object {
        fun decode(raw: String): DmrvExpr {
            val s = raw.split(':')
            return when (s.firstOrNull()) {
                "L" -> { require(s.size == 3); Literal(s[1].toInt(), s[2] == "1") }
                "B" -> { require(s.size == 4); Pair(s[1].toInt(), s[2].toInt(), DmrvBinary.valueOf(s[3])) }
                "P" -> { require(s.size == 5); Parity3(s[1].toInt(), s[2].toInt(), s[3].toInt(), s[4] == "1") }
                "M" -> { require(s.size == 4); Mux(s[1].toInt(), s[2].toInt(), s[3].toInt()) }
                else -> error("Invalid expression")
            }
        }
    }
}

object DmrvGrammar {
    fun options(action: DmrvAction): List<DmrvExpr> {
        val base: List<DmrvExpr> = (0..3).flatMap { k -> listOf(DmrvExpr.Literal(k), DmrvExpr.Literal(k, true)) }
        if (action == DmrvAction.BASE_ONLY) return base
        val extra: List<DmrvExpr> = when (action) {
            DmrvAction.ADD_BINARY -> (0..3).flatMap { i -> (i + 1..3).flatMap { j -> DmrvBinary.entries.map { DmrvExpr.Pair(i, j, it) } } }
            DmrvAction.ADD_PARITY3 -> (0..3).flatMap { i -> (i + 1..3).flatMap { j -> (j + 1..3).flatMap { k -> listOf(DmrvExpr.Parity3(i, j, k), DmrvExpr.Parity3(i, j, k, true)) } } }
            DmrvAction.ADD_MUX -> (0..3).flatMap { c -> (0..3).filter { it != c }.flatMap { t -> (0..3).filter { it != c && it != t }.map { f -> DmrvExpr.Mux(c, t, f) } } }
            else -> emptyList()
        }
        return base + extra
    }
}

data class DmrvDiagnostics(val unaryMinErrors: Int, val positives: Int, val trainSize: Int,
    val mixedAdjacentPairs: Int, val consistentUnary: Int) {
    /** Buckets are evidence-derived and contain no family or answer IDs. */
    fun context(): String {
        val fit = when { unaryMinErrors == 0 -> "FIT"; unaryMinErrors == 1 -> "NEAR"; else -> "GAP" }
        val pos = when { positives * 3 < trainSize -> "LOW"; positives * 3 > trainSize * 2 -> "HIGH"; else -> "MID" }
        val edge = if (mixedAdjacentPairs >= 3) "CHANGING" else "CALM"
        return "$fit/$pos/$edge"
    }
}

data class DmrvSynthesis(val action: DmrvAction, val expr: DmrvExpr?,
    val candidatesEvaluated: Int, val consistentCandidates: Int,
    val behaviorClasses: Int, val trainError: Int) {
    fun predict(input: Int): Boolean? = expr?.eval(input)
}

class DmrvReferenceStudent {
    fun diagnose(task: DmrvPublicTask): DmrvDiagnostics {
        val literals = DmrvGrammar.options(DmrvAction.BASE_ONLY)
        val errors = literals.map { e -> task.observations.count { it.label != e.eval(it.input) } }
        val byInput = task.observations.associateBy { it.input }
        val adjacent = task.observations.sumOf { e ->
            (0..3).count { k ->
                val other = byInput[e.input xor (1 shl k)]
                other != null && other.input > e.input && other.label != e.label
            }
        }
        return DmrvDiagnostics(errors.min(), task.observations.count { it.label }, task.observations.size,
            adjacent, errors.count { it == 0 })
    }

    fun synthesize(task: DmrvPublicTask, action: DmrvAction): DmrvSynthesis {
        val candidates = DmrvGrammar.options(action)
        val fitting = candidates.filter { e -> task.observations.all { e.eval(it.input) == it.label } }
        val behaviorClasses = fitting.map { e -> (0..15).joinToString("") { if (e.eval(it)) "1" else "0" } }.distinct().size
        // Never turn ambiguous, underidentified evidence into a unique claim.
        val chosen = if (behaviorClasses == 1) fitting.minWithOrNull(compareBy<DmrvExpr> { it.depth }.thenBy { it.encode() }) else null
        return DmrvSynthesis(action, chosen, candidates.size, fitting.size, behaviorClasses,
            candidates.minOf { e -> task.observations.count { e.eval(it.input) != it.label } })
    }
}

/** Scalar feedback from PRIOR meta-training tasks. No current-task oracle/heldout access. */
data class DmrvFeedback(val context: String, val action: DmrvAction, val reward: Double)

data class DmrvDecision(val action: DmrvAction, val score: Double, val rationale: String)

/** Smoothed contextual empirical policy, not hand-written family -> action mapping. */
class DmrvMetaPolicy(private val priorStrength: Double = 4.0) {
    private val rewardSum = mutableMapOf<String, Double>()
    private val rewardCount = mutableMapOf<String, Int>()
    private val globalSum = mutableMapOf<DmrvAction, Double>()
    private val globalCount = mutableMapOf<DmrvAction, Int>()

    fun observe(feedback: DmrvFeedback) {
        require(feedback.reward.isFinite())
        val key = "${feedback.context}|${feedback.action.name}"
        rewardSum[key] = rewardSum.getOrDefault(key, 0.0) + feedback.reward
        rewardCount[key] = rewardCount.getOrDefault(key, 0) + 1
        globalSum[feedback.action] = globalSum.getOrDefault(feedback.action, 0.0) + feedback.reward
        globalCount[feedback.action] = globalCount.getOrDefault(feedback.action, 0) + 1
    }
    fun globalAverage(action: DmrvAction) =
        if ((globalCount[action] ?: 0) == 0) 0.0 else globalSum.getValue(action) / globalCount.getValue(action)
    fun contextualScore(context: String, action: DmrvAction): Double {
        val key = "$context|${action.name}"
        val n = rewardCount.getOrDefault(key, 0)
        return (rewardSum.getOrDefault(key, 0.0) + priorStrength * globalAverage(action)) / (n + priorStrength)
    }
    fun choose(context: String, contextual: Boolean): DmrvDecision {
        val vals = DmrvAction.entries.map { a -> a to if (contextual) contextualScore(context, a) else globalAverage(a) }
        val chosen = vals.sortedWith(compareByDescending<Pair<DmrvAction, Double>> { it.second }
            .thenBy { DmrvGrammar.options(it.first).size }.thenBy { it.first.name }).first()
        return DmrvDecision(chosen.first, chosen.second, if (contextual) "LEARNED_CONTEXT:$context" else "LEARNED_GLOBAL")
    }
    fun snapshot(): List<String> {
        val globals = DmrvAction.entries.map { a -> "G|${a.name}|${globalCount.getOrDefault(a,0)}|${globalSum.getOrDefault(a,0.0)}" }
        val locals = rewardCount.keys.sorted().map { k -> "C|$k|${rewardCount.getValue(k)}|${rewardSum.getValue(k)}" }
        return globals + locals
    }
    companion object {
        fun restore(lines: List<String>): DmrvMetaPolicy {
            val p = DmrvMetaPolicy()
            // Rebuild exact aggregate counters without expanding all feedback samples.
            for (line in lines) {
                val s = line.split('|')
                when (s.first()) {
                    "G" -> { require(s.size == 4); val a = DmrvAction.valueOf(s[1]); p.globalCount[a] = s[2].toInt(); p.globalSum[a] = s[3].toDouble() }
                    "C" -> { require(s.size == 5); val key = "${s[1]}|${s[2]}"; p.rewardCount[key] = s[3].toInt(); p.rewardSum[key] = s[4].toDouble() }
                    else -> error("Invalid policy snapshot")
                }
            }
            return p
        }
    }
}
