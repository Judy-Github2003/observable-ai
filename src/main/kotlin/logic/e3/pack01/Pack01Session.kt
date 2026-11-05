// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-01 D2/N02/A-R1 最小联合实验；复用 B0 Composer/Filter/Interpreter，不修改 B0。
package logic.e3.pack01

import logic.capability.*

data class PackObservation(
    val id: String,
    val a: String,
    val b: String,
    val label: Boolean,
    val sourceId: String = "unknown",
)
data class PackInput(val sampleId: String, val a: String, val b: String)
data class PackLabel(val sampleId: String, val a: String, val b: String, val label: Boolean, val sourceId: String)
data class PackCandidate(
    val id: Int,
    val body: Expr,
    val canonical: String,
    val evidenceIds: List<String>,
    val source: String = "composer_bounded_search",
)
data class PackSnapshot(
    val label: String,
    val status: String,
    val composed: Int,
    val composeReason: String?,
    val candidates: List<PackCandidate>,
    val observations: List<PackObservation>,
)
data class PackFrozenPrediction(
    val sampleId: String,
    val input: PackInput,
    val candidateCanonicals: List<String>,
    val predictions: Map<Int, Boolean?>,
    val sequence: Long,
)
data class PackQuery(
    val input: PackInput,
    val predictions: Map<Int, Boolean?>,
    val distinctSignatures: Int,
)
data class PackResult(
    val before: PackSnapshot,
    val query: PackQuery?,
    val frozen: PackFrozenPrediction?,
    val label: PackLabel?,
    val after: PackSnapshot?,
    val eliminated: List<PackCandidate>,
    val state: String,
    val failureCode: String?,
)

interface PackLabelSource {
    fun label(sampleId: String): PackLabel?
}

/**
 * D2/N02/A-R1 单会话：
 * 初始候选集由 Composer/Filter 生成；选择区分性输入时只使用候选预测；
 * 冻结后才调用标签源；新证据后重跑 Composer/Filter 并保留前后快照。
 */
class Pack01Session(
    private val initialObservations: List<PackObservation>,
    private val queryPool: List<PackInput>,
    private val labels: PackLabelSource,
    private val maxDepth: Int = 3,
    private val maxCandidates: Int = 2000,
) {
    private var initialSnapshot: PackSnapshot? = null
    private var currentSnapshot: PackSnapshot? = null
    private val historySnapshots = ArrayList<PackSnapshot>()
    private var frozen: PackFrozenPrediction? = null
    private var selectedQuery: PackQuery? = null
    private val revealedSampleIds = HashSet<String>()
    private var sequence = 0L

    fun initial(): PackSnapshot {
        initialSnapshot?.let { return it }
        val snapshot = build("initial", initialObservations)
        initialSnapshot = snapshot
        currentSnapshot = snapshot
        historySnapshots += snapshot
        return snapshot
    }

    fun current(): PackSnapshot = currentSnapshot ?: initial()

    fun history(): List<PackSnapshot> = historySnapshots.toList()

    fun chooseDistinguishing(): PackQuery? {
        val snapshot = current()
        if (frozen != null) return null
        val existing = snapshot.observations.map { it.a to it.b }.toSet()
        for (input in queryPool) {
            if (input.sampleId in revealedSampleIds) continue
            if ((input.a to input.b) in existing) continue
            val predictions = snapshot.candidates.associate { candidate ->
                candidate.id to predict(candidate.body, input.a, input.b)
            }
            val distinct = predictions.values.distinct()
            if (distinct.size > 1) {
                val query = PackQuery(input, predictions, distinct.size)
                selectedQuery = query
                return query
            }
        }
        return null
    }

    fun freeze(query: PackQuery): PackFrozenPrediction {
        val selected = selectedQuery ?: throw IllegalStateException("query_not_selected")
        if (selected.input.sampleId != query.input.sampleId ||
            selected.input.a != query.input.a || selected.input.b != query.input.b) {
            throw IllegalStateException("query_input_mismatch")
        }
        if (frozen != null) throw IllegalStateException("prediction_already_frozen")
        val snapshot = current()
        val actual = snapshot.candidates.associate { candidate ->
            candidate.id to predict(candidate.body, query.input.a, query.input.b)
        }
        if (actual != query.predictions) throw IllegalStateException("query_predictions_mismatch")
        sequence += 1
        val value = PackFrozenPrediction(
            query.input.sampleId, query.input, snapshot.candidates.map { it.canonical }, actual, sequence,
        )
        frozen = value
        return value
    }

    fun reveal(): PackResult {
        val snapshot = current()
        val query = selectedQuery ?: throw IllegalStateException("query_not_selected")
        if (query.input.sampleId in revealedSampleIds) throw IllegalStateException("challenge_already_revealed")
        val prediction = frozen ?: throw IllegalStateException("prediction_not_frozen")
        val label = labels.label(query.input.sampleId)
            ?: throw IllegalStateException("label_unavailable")
        if (label.sampleId != query.input.sampleId || label.a != query.input.a || label.b != query.input.b) {
            throw IllegalStateException("label_input_mismatch")
        }
        sequence += 1
        val observation = PackObservation(
            "pack-obs-${label.sampleId}", label.a, label.b, label.label, label.sourceId,
        )
        val after = build("after-${label.sampleId}", snapshot.observations + observation)
        val eliminated = snapshot.candidates.filter { old -> after.candidates.none { it.canonical == old.canonical } }
        val state = when {
            after.candidates.isEmpty() -> "UNDETERMINED"
            after.status == "IDENTIFIED" -> "IDENTIFIED"
            else -> "AMBIGUOUS"
        }
        currentSnapshot = after
        historySnapshots += after
        revealedSampleIds += query.input.sampleId
        frozen = null
        selectedQuery = null
        return PackResult(
            snapshot, query, prediction, label, after, eliminated, state,
            if (after.candidates.isEmpty()) "no_candidate_after_new_evidence" else null,
        )
    }

    /** A-R1 边界：外部新证据直接进入时，仍重跑 Composer/Filter 并保留前后快照。 */
    fun applyObservation(observation: PackObservation): PackResult {
        val snapshot = current()
        if (snapshot.observations.any { it.id == observation.id }) {
            throw IllegalStateException("duplicate_observation")
        }
        val after = build("after-${observation.id}", snapshot.observations + observation)
        val eliminated = snapshot.candidates.filter { old -> after.candidates.none { it.canonical == old.canonical } }
        val state = when {
            after.candidates.isEmpty() -> "UNDETERMINED"
            after.status == "IDENTIFIED" -> "IDENTIFIED"
            else -> "AMBIGUOUS"
        }
        currentSnapshot = after
        historySnapshots += after
        return PackResult(
            snapshot, null, null, null, after, eliminated, state,
            if (after.candidates.isEmpty()) "no_candidate_after_new_evidence" else null,
        )
    }
    fun predict(body: Expr, a: String, b: String): Boolean? {
        val definition = CapabilityDefinition(
            "pack.eval", 1, mapOf("a" to CapType.Str, "b" to CapType.Str), CapType.Bool3, body,
        )
        val result = CapabilityInterpreter(InMemoryResolver(emptyList())).execute(
            definition,
            mapOf("a" to EvalResult.Known(CapValue.Str(a)), "b" to EvalResult.Known(CapValue.Str(b))),
            EvalBudget(maxSteps = 10000),
        )
        return (result as? EvalResult.Known)?.value?.let { (it as? CapValue.Bool)?.value }
    }

    private fun build(label: String, observations: List<PackObservation>): PackSnapshot {
        val compose = CapabilityComposer().compose(
            ComposeRequest(
                inputs = mapOf("a" to CapType.Str, "b" to CapType.Str),
                output = CapType.Bool3,
                constants = listOf(CapValue.Str("0"), CapValue.Str("1")),
                budget = ComposeBudget(maxDepth = maxDepth, maxNodes = 16, maxCandidates = maxCandidates),
            ),
        )
        val examples = observations.map {
            PublicExample(
                mapOf("a" to CapValue.Str(it.a), "b" to CapValue.Str(it.b)),
                CapValue.Bool(it.label),
            )
        }
        val domain = mapOf(
            "a" to listOf(CapValue.Str("0"), CapValue.Str("1")),
            "b" to listOf(CapValue.Str("0"), CapValue.Str("1")),
        )
        val evidence = CandidateEvidenceFilter(InMemoryResolver(emptyList())).filter(
            mapOf("a" to CapType.Str, "b" to CapType.Str),
            compose.candidates,
            examples,
            domain,
            EvalBudget(maxSteps = 10000),
            compose.reason == "candidate_limit",
        )
        val candidates = evidence.evaluations.filter { it.consistent }.mapIndexed { index, evaluation ->
            PackCandidate(index + 1, evaluation.candidate.body, evaluation.candidate.canonical, observations.map { it.id })
        }
        return PackSnapshot(label, evidence.status.name, compose.generated, compose.reason, candidates, observations)
    }
}
