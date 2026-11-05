// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-ACTIVE 最小主动辨识实验；复用 B0 Composer/Filter/Interpreter，不注入目标 AST。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path
import java.util.Random

data class ActiveSample(val id: String, val values: Map<String, String>, val label: Boolean)
data class ActiveWorld(
    val id: String,
    val family: String,
    val training: List<ActiveSample>,
    val queryPool: List<Map<String, String>>,
    val heldout: List<ActiveSample>,
    private val target: (Map<String, String>) -> Boolean,
) {
    fun oracle(input: Map<String, String>): Boolean = target(input)
}

object ActiveWorlds {
    private val values = listOf("0", "1", "2")

    fun generate(seed: Int, family: String = "and_ac", trainCount: Int = 6): ActiveWorld {
        val all = ArrayList<Map<String, String>>()
        for (a in values) for (b in values) for (c in values) {
            all += mapOf("a" to a, "b" to b, "c" to c)
        }
        val shuffled = all.shuffled(Random(seed.toLong()))
        val train = shuffled.take(trainCount)
        val held = shuffled.drop(trainCount).take(6)
        val query = shuffled.drop(trainCount + 6).take(27 - trainCount - 6)
        val target: (Map<String, String>) -> Boolean = when (family) {
            "and_ac" -> { m -> m["a"] == "1" && m["c"] == "1" }
            "or_ac" -> { m -> m["a"] == "1" || m["c"] == "1" }
            "outside_b2" -> { m -> m["b"] == "2" }
            else -> error("unknown world family")
        }
        val trainSamples = train.mapIndexed { i, m -> ActiveSample("tr-$i", m, target(m)) }
        val heldSamples = held.mapIndexed { i, m -> ActiveSample("ho-$i", m, target(m)) }
        return ActiveWorld("world-$seed-$family", family, trainSamples, query, heldSamples, target)
    }
}

data class FrozenQuery(
    val sequence: Int,
    val input: Map<String, String>,
    val predictions: Map<String, Boolean?>,
    val candidateIds: List<String>,
    val evidenceVersion: Int,
)
data class QueryObservation(val query: FrozenQuery, val label: Boolean, val source: String)
data class ActiveCandidate(val body: Expr, val canonical: String, val ids: List<String>)
data class ActiveActivation(
    val active: Boolean,
    val contentHash: String?,
    val verificationRunId: String?,
    val definition: CapabilityDefinition?,
    val failure: String?,
)

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
    val activation: ActiveActivation?,
    val failure: String?,
)

/**
 * 最小 ACTIVE 会话：
 * 1) Composer 生成候选；Filter 只保留训练一致者；
 * 2) 在 QUERY 池上按预测分组；选择最大分裂/最均衡输入；
 * 3) 冻结候选+输入+预测，再由 Oracle 返回标签；
 * 4) 淘汰矛盾候选，继续直到一类、预算耗尽或无区分输入。
 * 学生对象不持有 hidden target / heldout 标签。
 */
class ActiveSession(
    private val world: ActiveWorld,
    private val dbPath: Path,
    private val pRef: KnowledgeRef,
    private val qRef: KnowledgeRef,
    private val budget: Int = 3,
    private val maxDepth: Int = 3,
    private val maxCandidates: Int = 20000,
    private val strategy: String = "active",
    private val seed: Int = 0,
) {
    private val library = Pack02Knowledge.resolveActive(dbPath, listOf(pRef, qRef))
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
        val examples = world.training.map {
            PublicExample(it.values.mapValues { (_, v) -> CapValue.Str(v) }, CapValue.Bool(it.label))
        }
        val queryDomain = inputs.associateWith { listOf("0", "1", "2").map { CapValue.Str(it) } }
        val evidence = CandidateEvidenceFilter(InMemoryResolver(library)).filter(
            inputs.associateWith { CapType.Str }, compose.candidates, examples, queryDomain,
            EvalBudget(maxSteps = 10000), exhausted,
        )
        if (evidence.status == EvidenceStatus.REFUSED) {
            return finish(
                if (exhausted) "SEARCH_LIMITED" else "FILTER_REFUSED",
                compose.generated, compose.reason, 0, 0, emptyList(), emptyList(), emptyList(), null, null,
                "filter_refused",
            )
        }
        val initial = evidence.evaluations.filter { it.consistent }.map { it.candidate }
            .distinctBy { it.canonical }
            .filter { idsIn(it.body).isNotEmpty() && !containsEq(it.body) }
            .map { ActiveCandidate(it.body, it.canonical, Pack02Knowledge.idsIn(it.body)) }
        if (initial.isEmpty()) {
            return finish("NO_CANDIDATE", compose.generated, compose.reason, 0, 0, emptyList(), initial, emptyList(), null, null, "no_candidate")
        }
        val initialClasses = classes(initial).size
        var current = initial
        val observations = ArrayList<QueryObservation>()
        val used = HashSet<String>()
        var sequence = 0
        while (true) {
            val classCount = classes(current).size
            if (classCount == 1) {
                val selected = current.minByOrNull { it.canonical }
                val activation = selected?.let { activate(it) }
                return finish("IDENTIFIED", compose.generated, compose.reason, initial.size, initialClasses, observations, current, current, selected, activation, null)
            }
            if (observations.size >= budget) {
                return finish("BUDGET_EXHAUSTED", compose.generated, compose.reason, initial.size, initialClasses, observations, current, current, null, null, "query_budget")
            }
            val choice = choose(current, world.queryPool, used)
                ?: return finish("NO_DISTINGUISHING_QUERY", compose.generated, compose.reason, initial.size, initialClasses, observations, current, current, null, null, "no_query")
            used += key(choice)
            val predictions = current.associate { it.canonical to Pack02Knowledge.predict(it.body, choice, library) }
            val frozen = FrozenQuery(sequence++, choice, predictions, current.map { it.canonical }, observations.size)
            val label = world.oracle(choice)
            observations += QueryObservation(frozen, label, "trusted_oracle:${world.id}")
            current = current.filter { candidate -> predictions[candidate.canonical] == label }
            if (current.isEmpty()) {
                return finish("ALL_REFUTED", compose.generated, compose.reason, initial.size, initialClasses, observations, current, current, null, null, "all_refuted")
            }
        }
    }

    private fun activate(candidate: ActiveCandidate): ActiveActivation {
        val definition = CapabilityDefinition(
            "pack02r.active.r", 1, mapOf("a" to CapType.Str, "b" to CapType.Str, "c" to CapType.Str),
            CapType.Bool3, candidate.body,
        )
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val proposed = registry.propose(definition, """{"source":"active_session","attribution":"student_composed"}""")
            if (!proposed.ok) return ActiveActivation(false, null, null, null, proposed.code ?: "proposal_failed")
            val cases = world.heldout.map {
                VerificationCase(it.id, it.values.mapValues { (_, v) -> CapValue.Str(v) }, EvalResult.Known(CapValue.Bool(it.label)))
            }
            if (cases.size < 4) return ActiveActivation(false, proposed.contentHash, null, null, "heldout_too_small")
            val suite = TrustedVerificationSuite.create("pack02r.active.heldout", 1, cases)
            val suites = TrustedSuiteRegistry()
            suites.register(suite)
            val verification = CapabilityVerificationService(registry, suites)
                .verify(definition.id, definition.version, suite.suiteId, suite.suiteVersion)
            if (!verification.recorded || verification.outcome.verdict.name != "PASS") {
                return ActiveActivation(false, proposed.contentHash, verification.runId, null, verification.code ?: "verification_failed")
            }
            val activation = CapabilityActivationService(registry, suites)
                .activate(definition.id, definition.version, verification.runId!!)
            return ActiveActivation(
                activation.ok, proposed.contentHash, verification.runId,
                if (activation.ok) definition else null, if (activation.ok) null else activation.code ?: "activation_failed",
            )
        } finally {
            registry.close()
        }
    }

    private fun classes(candidates: List<ActiveCandidate>): Map<String, List<ActiveCandidate>> =
        candidates.groupBy { c -> world.queryPool.joinToString("|") { key(it) + "=" + Pack02Knowledge.predict(c.body, it, library) } }

    private fun choose(candidates: List<ActiveCandidate>, pool: List<Map<String, String>>, used: Set<String>): Map<String, String>? {
        if (strategy == "fixed") {
            return pool.sortedBy { key(it) }.firstOrNull { key(it) !in used }
        }
        if (strategy == "random") {
            return pool.sortedBy { key(it) }.shuffled(Random(seed.toLong())).firstOrNull { key(it) !in used }
        }
        return pool.filter { key(it) !in used }.map { input ->
            val groups = candidates.groupBy { Pack02Knowledge.predict(it.body, input, library) }
            val size = if (groups.size < 2) -1 else groups.values.minOf { it.size }
            Triple(input, groups.size, size)
        }.filter { it.second >= 2 }.maxWithOrNull(compareBy<Triple<Map<String, String>, Int, Int>> { it.third }.thenBy { it.second }.thenByDescending { key(it.first) })?.first
    }

    private fun finish(
        status: String, generated: Int, reason: String?, initialCount: Int, initialClasses: Int,
        observations: List<QueryObservation>, current: List<ActiveCandidate>, remaining: List<ActiveCandidate>,
        selected: ActiveCandidate?, activation: ActiveActivation?, failure: String?,
    ) = ActiveResult(status, world.id, generated, reason, initialCount, initialClasses, observations, current, classes(current).size, selected, activation, failure)

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
