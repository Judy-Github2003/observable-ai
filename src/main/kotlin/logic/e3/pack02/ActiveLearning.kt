// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL-A 隔离；学生只持有 StudentView + QueryController，不持有 ActiveWorld/heldout。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path
import java.util.Random

data class ActiveSample(val id: String, val values: Map<String, String>, val label: Boolean)

class ActiveWorld internal constructor(
    val id: String,
    val family: String,
    private val viewId: String,
    private val seed: Int,
    private val target: (Map<String, String>) -> Boolean,
) {
    private val values = listOf("0", "1", "2")
    private val all: List<Map<String, String>> = buildList {
        for (a in values) for (b in values) for (c in values) add(mapOf("a" to a, "b" to b, "c" to c))
    }
    private val shuffled = all.shuffled(Random(seed.toLong()))
    val training: List<ActiveSample> = shuffled.take(6).mapIndexed { i, m -> ActiveSample("tr-$i", m, target(m)) }
    val heldout: List<ActiveSample> = shuffled.drop(6).take(6).mapIndexed { i, m -> ActiveSample("ho-$i", m, target(m)) }
    val queryPool: List<Map<String, String>> = shuffled.drop(12)

    fun studentView(pRef: KnowledgeRef, qRef: KnowledgeRef, budget: Int): StudentView =
        StudentView(
            worldId = viewId,
            training = training,
            queryPool = queryPool,
            budget = budget,
            pRef = pRef,
            qRef = qRef,
        )

    fun controller(budget: Int): QueryController = QueryController(queryPool, budget, target)

    fun evaluator(): ExamEvaluator = ExamEvaluator(this)

    internal fun oracleForTestOnly(input: Map<String, String>): Boolean = target(input)
    internal fun heldoutForTestOnly(): List<ActiveSample> = heldout
}

object ActiveWorlds {
    fun generate(seed: Int, family: String = "and_ac"): ActiveWorld {
        val target: (Map<String, String>) -> Boolean = when (family) {
            "and_ac" -> { m -> m["a"] == "1" && m["c"] == "1" }
            "or_ac" -> { m -> m["a"] == "1" || m["c"] == "1" }
            "outside_b2" -> { m -> m["b"] == "2" }
            else -> error("unknown world family")
        }
        return ActiveWorld("world-$seed-$family", family, "session-$seed", seed, target)
    }
}

/** 学生可见面：无 family/target/heldout；只有公开训练、无标签 QUERY、基础能力引用和预算。 */
class StudentView internal constructor(
    val worldId: String,
    val training: List<ActiveSample>,
    val queryPool: List<Map<String, String>>,
    val budget: Int,
    val pRef: KnowledgeRef,
    val qRef: KnowledgeRef,
)

/** 考试端查询控制：只有 freeze 后才 reveal；限制池/预算/重复；不向学生暴露 truth。 */
class QueryController internal constructor(
    private val allowedPool: List<Map<String, String>>,
    private val budget: Int,
    private val target: (Map<String, String>) -> Boolean,
) {
    private val allowed = allowedPool.map { key(it) }.toSet()
    private val used = HashSet<String>()
    private var frozen: FrozenQuery? = null

    private fun key(input: Map<String, String>): String = listOf("a", "b", "c").joinToString(",") { input[it] ?: "" }

    fun freeze(sequence: Int, input: Map<String, String>, predictions: Map<String, Boolean?>, candidateIds: List<String>): FrozenQuery {
        require(key(input) in allowed) { "input not in authorized query pool" }
        require(key(input) !in used) { "duplicate query" }
        require(used.size < budget) { "query budget exhausted" }
        require(predictions.isNotEmpty() && candidateIds.isNotEmpty()) { "empty frozen candidate set" }
        used += key(input)
        val q = FrozenQuery(sequence, input, predictions, candidateIds, used.size - 1)
        frozen = q
        return q
    }

    fun reveal(query: FrozenQuery): QueryObservation {
        require(frozen === query) { "query was not frozen by this controller" }
        frozen = null
        return QueryObservation(query, target(query.input), "trusted_oracle")
    }

    fun remaining(): Int = budget - used.size
}

/** 冻结查询是不可变对象；没有公开构造器和 copy，Controller 通过引用校验。 */
class FrozenQuery internal constructor(
    val sequence: Int,
    val input: Map<String, String>,
    val predictions: Map<String, Boolean?>,
    val candidateIds: List<String>,
    val evidenceVersion: Int,
)
data class QueryObservation(val query: FrozenQuery, val label: Boolean, val source: String)
data class ActiveCandidate(val body: Expr, val canonical: String, val ids: List<String>)
data class ActiveActivation(val active: Boolean, val contentHash: String?, val verificationRunId: String?, val definition: CapabilityDefinition?, val failure: String?)

data class ActiveResult(
    val status: String,
    val worldId: String,
    val generated: Int,
    val searchReason: String?,
    val initialCandidates: Int,
    val initialClasses: Int,
    val rounds: List<QueryObservation>,
    val remainingCandidates: List<ActiveCandidate>,
    val remainingClasses: Int,
    val selected: ActiveCandidate?,
    val failure: String?,
)

/** 学生 ACTIVE 会话：只持有 StudentView 与 QueryController，不持有目标/heldout。 */
class ActiveSession(
    private val view: StudentView,
    private val controller: QueryController,
    private val dbPath: Path,
    private val maxDepth: Int = 3,
    private val maxCandidates: Int = 20000,
    private val strategy: String = "active",
    private val seed: Int = 0,
) {
    private val library = Pack02Knowledge.resolveActive(dbPath, listOf(view.pRef, view.qRef))
        ?: error("active base capabilities are not active")

    fun run(): ActiveResult {
        val inputs = listOf("a", "b", "c")
        val compose = CapabilityComposer().compose(
            ComposeRequest(
                inputs = inputs.associateWith { CapType.Str },
                output = CapType.Bool3,
                constants = listOf("0", "1", "2").map { CapValue.Str(it) },
                library = library,
                budget = ComposeBudget(maxDepth = maxDepth, maxNodes = 40, maxCandidates = maxCandidates),
            ),
        )
        val exhausted = compose.reason == "candidate_limit"
        val examples = view.training.map {
            PublicExample(it.values.mapValues { (_, v) -> CapValue.Str(v) }, CapValue.Bool(it.label))
        }
        val queryDomain = inputs.associateWith { listOf("0", "1", "2").map { CapValue.Str(it) } }
        val evidence = CandidateEvidenceFilter(InMemoryResolver(library)).filter(
            inputs.associateWith { CapType.Str }, compose.candidates, examples, queryDomain,
            EvalBudget(maxSteps = 10000), exhausted,
        )
        if (evidence.status == EvidenceStatus.REFUSED) {
            return finish(if (exhausted) "SEARCH_LIMITED" else "FILTER_REFUSED", compose.generated, compose.reason, 0, 0, emptyList(), emptyList(), null, "filter_refused")
        }
        val initial = evidence.evaluations.filter { it.consistent }.map { it.candidate }
            .distinctBy { it.canonical }
            .filter { idsIn(it.body).isNotEmpty() && !containsEq(it.body) }
            .map { ActiveCandidate(it.body, it.canonical, Pack02Knowledge.idsIn(it.body)) }
        if (initial.isEmpty()) {
            return finish("NO_CANDIDATE", compose.generated, compose.reason, 0, 0, emptyList(), initial, null, "no_candidate")
        }
        val initialClasses = classes(initial).size
        var current = initial
        val observations = ArrayList<QueryObservation>()
        val used = HashSet<String>()
        var sequence = 0
        while (true) {
            if (classes(current).size == 1) {
                return finish("IDENTIFIED", compose.generated, compose.reason, initial.size, initialClasses, observations, current, current.minByOrNull { it.canonical }, null)
            }
            if (observations.size >= view.budget) {
                return finish("BUDGET_EXHAUSTED", compose.generated, compose.reason, initial.size, initialClasses, observations, current, null, "query_budget")
            }
            val choice = choose(current, used) ?: return finish("NO_DISTINGUISHING_QUERY", compose.generated, compose.reason, initial.size, initialClasses, observations, current, null, "no_query")
            used += key(choice)
            val predictions = current.associate { it.canonical to Pack02Knowledge.predict(it.body, choice, library) }
            val frozen = controller.freeze(sequence++, choice, predictions, current.map { it.canonical })
            val observation = controller.reveal(frozen)
            observations += observation
            current = current.filter { candidate -> predictions[candidate.canonical] == observation.label }
            if (current.isEmpty()) {
                return finish("ALL_REFUTED", compose.generated, compose.reason, initial.size, initialClasses, observations, current, null, "all_refuted")
            }
        }
    }

    private fun classes(candidates: List<ActiveCandidate>): Map<String, List<ActiveCandidate>> =
        candidates.groupBy { c -> view.queryPool.joinToString("|") { key(it) + "=" + Pack02Knowledge.predict(c.body, it, library) } }

    private fun choose(candidates: List<ActiveCandidate>, used: Set<String>): Map<String, String>? {
        if (strategy == "fixed") return view.queryPool.sortedBy { key(it) }.firstOrNull { key(it) !in used }
        if (strategy == "random") return view.queryPool.sortedBy { key(it) }.shuffled(Random(seed.toLong())).firstOrNull { key(it) !in used }
        return view.queryPool.filter { key(it) !in used }.map { input ->
            val groups = candidates.groupBy { Pack02Knowledge.predict(it.body, input, library) }
            val size = if (groups.size < 2) -1 else groups.values.minOf { it.size }
            Triple(input, groups.size, size)
        }.filter { it.second >= 2 }.maxWithOrNull(compareBy<Triple<Map<String, String>, Int, Int>> { it.third }.thenBy { it.second }.thenByDescending { key(it.first) })?.first
    }

    private fun finish(status: String, generated: Int, reason: String?, initialCount: Int, initialClasses: Int, observations: List<QueryObservation>, current: List<ActiveCandidate>, selected: ActiveCandidate?, failure: String?) =
        ActiveResult(status, view.worldId, generated, reason, initialCount, initialClasses, observations, current, classes(current).size, selected, failure)

    private fun key(input: Map<String, String>): String = listOf("a", "b", "c").joinToString(",") { input[it] ?: "" }
    private fun idsIn(expr: Expr): List<String> = Pack02Knowledge.idsIn(expr)

    private fun containsEq(expr: Expr): Boolean {
        fun walk(e: Expr): Boolean = when (e) {
            is Expr.Eq -> true
            is Expr.And -> walk(e.left) || walk(e.right)
            is Expr.Or -> walk(e.left) || walk(e.right)
            is Expr.Not -> walk(e.value)
            is Expr.Call -> e.args.values.any(::walk)
            is Expr.If -> walk(e.condition) || walk(e.whenTrue) || walk(e.whenFalse)
            is Expr.Let -> walk(e.value) || walk(e.body)
            is Expr.Field -> walk(e.record)
            is Expr.Arg, is Expr.Const, is Expr.Ref -> false
        }
        return walk(expr)
    }
}

/** 考试端：持有 heldout/target，完成候选验证与 B0 激活；学生不可调用。 */
class ExamEvaluator internal constructor(private val world: ActiveWorld) {
    fun activate(candidate: ActiveCandidate, dbPath: Path): ActiveActivation {
        val definition = CapabilityDefinition(
            "pack02r.active.r", 1, mapOf("a" to CapType.Str, "b" to CapType.Str, "c" to CapType.Str),
            CapType.Bool3, candidate.body,
        )
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val proposed = registry.propose(definition, """{"source":"eval_exam","attribution":"student_composed"}""")
            if (!proposed.ok) return ActiveActivation(false, null, null, null, proposed.code ?: "proposal_failed")
            val cases = world.heldout.map {
                VerificationCase(it.id, it.values.mapValues { (_, v) -> CapValue.Str(v) }, EvalResult.Known(CapValue.Bool(it.label)))
            }
            if (cases.size < 4) return ActiveActivation(false, proposed.contentHash, null, null, "heldout_too_small")
            val suite = TrustedVerificationSuite.create("pack02r.eval.heldout", 1, cases)
            val suites = TrustedSuiteRegistry()
            suites.register(suite)
            val verification = CapabilityVerificationService(registry, suites)
                .verify(definition.id, definition.version, suite.suiteId, suite.suiteVersion)
            if (!verification.recorded || verification.outcome.verdict.name != "PASS") {
                return ActiveActivation(false, proposed.contentHash, verification.runId, null, verification.code ?: "verification_failed")
            }
            val activation = CapabilityActivationService(registry, suites)
                .activate(definition.id, definition.version, verification.runId!!)
            return ActiveActivation(activation.ok, proposed.contentHash, verification.runId, if (activation.ok) definition else null, if (activation.ok) null else activation.code ?: "activation_failed")
        } finally {
            registry.close()
        }
    }

    fun heldoutForEvaluator(): List<ActiveSample> = world.heldout
}
