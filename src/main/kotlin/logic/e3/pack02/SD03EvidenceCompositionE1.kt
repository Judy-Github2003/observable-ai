package logic.e3.pack02

/**
 * SD-03-E1: a pure, examiner-blind evidence-driven program constructor.
 * Inputs are immutable PUBLIC TRAIN examples and actual atom predictions on TRAIN.
 * There is no truth callback, world ID/category, CHALLENGE or HELDOUT in this API.
 *
 * A tree is a *proposal*, never a registered capability or a proof of generalization.
 * The repository adapter must independently typecheck and run the resulting AST.
 */
data class E1Example(val id: String, val fields: Map<String, String>, val label: Boolean)
data class E1Atom(val id: String, val predictions: Map<String, Boolean?>, val evidenceId: String)

data class E1Config(
    val maxDepth: Int = 4,
    val beamWidthPerNode: Int = 5,
    val maxTreeNodes: Int = 63,
    val maxPartitionAttempts: Int = 30_000,
    val minExamplesPerChild: Int = 1,
) {
    init {
        require(maxDepth in 0..8 && beamWidthPerNode in 1..100 && maxTreeNodes >= 1)
        require(maxPartitionAttempts >= 1 && minExamplesPerChild >= 1)
    }
}

sealed interface E1Program {
    data class Constant(val value: Boolean) : E1Program
    data class Branch(val atomId: String, val whenTrue: E1Program, val whenFalse: E1Program) : E1Program
}

enum class E1Status {
    TRAIN_CONSISTENT_PROPOSAL, TRAIN_INCONSISTENT_PROPOSAL,
    CONFLICTING_TRAIN_LABELS, NO_EXAMPLES, SEARCH_BUDGET_EXHAUSTED,
}

data class E1DecisionEvidence(
    val path: String,
    val atomId: String,
    val supportingTrainingIds: List<String>,
    val trueTrainingIds: List<String>,
    val falseTrainingIds: List<String>,
    val positiveLabels: Int,
    val negativeLabels: Int,
    val informationGain: Double,
    val atomEvidenceId: String,
)

data class E1Result(
    val status: E1Status,
    val program: E1Program?,
    val trainCorrect: Int,
    val trainTotal: Int,
    val proposedNodes: Int,
    val maxDepthReached: Int,
    val splitAttempts: Int,
    val predictionReads: Int,
    val unknownPredictionsSkipped: Int,
    val memoHits: Int,
    val budgetHit: Boolean,
    val decisionEvidence: List<E1DecisionEvidence>,
    val note: String,
)

object SD03EvidenceCompositionE1 {
    private data class Fit(val tree: E1Program, val errors: Int, val nodes: Int, val depth: Int)
    private data class Split(val atom: E1Atom, val yes: List<Int>, val no: List<Int>, val gain: Double)
    private data class MemoKey(val indices: List<Int>, val depth: Int, val excluded: List<String>)

    fun propose(examples: List<E1Example>, sourceAtoms: List<E1Atom>, config: E1Config = E1Config()): E1Result {
        if (examples.isEmpty()) return E1Result(
            E1Status.NO_EXAMPLES, null, 0, 0, 0, 0, 0, 0, 0, 0, false, emptyList(), "no TRAIN evidence")
        require(examples.map { it.id }.distinct().size == examples.size && examples.all { it.id.isNotBlank() })
        require(sourceAtoms.map { it.id }.distinct().size == sourceAtoms.size)
        val ids = examples.map { it.id }.toSet()
        require(sourceAtoms.all { it.id.isNotBlank() && it.evidenceId.isNotBlank() && it.predictions.keys == ids }) {
            "Every atom must predict exactly the public TRAIN IDs; represent Unknown as null, not false"
        }
        val fingerprints = examples.groupBy { example ->
            example.fields.toSortedMap().entries.joinToString("|") { (k, v) -> "${k.length}:$k=${v.length}:$v" }
        }
        if (fingerprints.values.any { group -> group.map { it.label }.distinct().size > 1 }) return E1Result(
            E1Status.CONFLICTING_TRAIN_LABELS, null, 0, examples.size,
            0, 0, 0, 0, 0, 0, false, emptyList(), "identical TRAIN inputs have conflicting labels")

        val atoms = sourceAtoms.sortedBy { it.id }
        val byId = atoms.associateBy { it.id }
        val labels = examples.map { it.label }
        val memo = HashMap<MemoKey, Fit>()
        var attempts = 0
        var reads = 0
        var unknown = 0
        var hits = 0
        var exhausted = false

        fun ambiguity(indices: List<Int>): Double {
            val n = indices.size
            if (n == 0) return 0.0
            val yes = indices.count { labels[it] }
            val no = n - yes
            return 2.0 * yes * no / n
        }

        fun bestLeaf(indices: List<Int>): Fit {
            val yes = indices.count { labels[it] }
            val no = indices.size - yes
            val value = yes > no // deterministic conservative tie: false
            return Fit(E1Program.Constant(value), minOf(yes, no), 1, 0)
        }

        fun better(a: Fit, b: Fit): Boolean = when {
            a.errors != b.errors -> a.errors < b.errors
            a.nodes != b.nodes -> a.nodes < b.nodes
            else -> a.depth < b.depth
        }

        fun recurse(indices: List<Int>, depthRemaining: Int, excluded: Set<String>): Fit {
            val key = MemoKey(indices, depthRemaining, excluded.sorted())
            memo[key]?.let { hits++; return it }
            var winner = bestLeaf(indices)
            if (winner.errors == 0 || depthRemaining <= 0 || exhausted) {
                memo[key] = winner
                return winner
            }
            val parentAmbiguity = ambiguity(indices)
            val splits = ArrayList<Split>()
            for (atom in atoms) {
                if (atom.id in excluded) continue
                if (attempts >= config.maxPartitionAttempts) { exhausted = true; break }
                attempts++
                val yes = ArrayList<Int>()
                val no = ArrayList<Int>()
                var unknownSeen = false
                for (index in indices) {
                    reads++
                    when (atom.predictions[examples[index].id]) {
                        true -> yes += index
                        false -> no += index
                        null -> { unknownSeen = true; unknown++; break }
                    }
                }
                if (unknownSeen || yes.size < config.minExamplesPerChild || no.size < config.minExamplesPerChild) continue
                val gain = parentAmbiguity - ambiguity(yes) - ambiguity(no)
                splits += Split(atom, yes, no, gain)
            }
            val ranked = splits.sortedWith(
                compareByDescending<Split> { it.gain }
                    .thenByDescending { minOf(it.yes.size, it.no.size) }
                    .thenBy { it.atom.id },
            ).take(config.beamWidthPerNode)
            for (split in ranked) {
                // Zero information gain is deliberately allowed: XOR requires a root split
                // whose single-step impurity improvement can be exactly zero.
                val blocked = excluded + split.atom.id
                val onTrue = recurse(split.yes, depthRemaining - 1, blocked)
                val onFalse = recurse(split.no, depthRemaining - 1, blocked)
                val candidate = Fit(
                    E1Program.Branch(split.atom.id, onTrue.tree, onFalse.tree),
                    onTrue.errors + onFalse.errors,
                    1 + onTrue.nodes + onFalse.nodes,
                    1 + maxOf(onTrue.depth, onFalse.depth),
                )
                if (candidate.nodes <= config.maxTreeNodes && better(candidate, winner)) winner = candidate
                if (exhausted) break
            }
            memo[key] = winner
            return winner
        }

        val fit = recurse(examples.indices.toList(), config.maxDepth, emptySet())
        val evidence = ArrayList<E1DecisionEvidence>()
        fun record(tree: E1Program, indices: List<Int>, path: String) {
            if (tree !is E1Program.Branch) return
            val atom = byId.getValue(tree.atomId)
            val yes = indices.filter { atom.predictions[examples[it].id] == true }
            val no = indices.filter { atom.predictions[examples[it].id] == false }
            val parent = ambiguity(indices)
            val gain = parent - ambiguity(yes) - ambiguity(no)
            evidence += E1DecisionEvidence(
                path, atom.id, indices.map { examples[it].id }, yes.map { examples[it].id },
                no.map { examples[it].id }, indices.count { labels[it] }, indices.count { !labels[it] },
                gain, atom.evidenceId,
            )
            record(tree.whenTrue, yes, "$path/T")
            record(tree.whenFalse, no, "$path/F")
        }
        record(fit.tree, examples.indices.toList(), "root")
        val status = when {
            exhausted -> E1Status.SEARCH_BUDGET_EXHAUSTED
            fit.errors == 0 -> E1Status.TRAIN_CONSISTENT_PROPOSAL
            else -> E1Status.TRAIN_INCONSISTENT_PROPOSAL
        }
        return E1Result(
            status, fit.tree, examples.size - fit.errors, examples.size, fit.nodes, fit.depth,
            attempts, reads, unknown, hits, exhausted, evidence,
            if (fit.errors == 0) "TRAIN-only fit; Challenge/HELDOUT unknown" else "unresolved TRAIN counterexamples remain",
        )
    }

    /** Prediction of a *proposal* using provided public atom values. Unknown stays Unknown. */
    fun predict(tree: E1Program, predictions: Map<String, Boolean?>): Boolean? = when (tree) {
        is E1Program.Constant -> tree.value
        is E1Program.Branch -> when (predictions[tree.atomId]) {
            true -> predict(tree.whenTrue, predictions)
            false -> predict(tree.whenFalse, predictions)
            null -> null
        }
    }
}
