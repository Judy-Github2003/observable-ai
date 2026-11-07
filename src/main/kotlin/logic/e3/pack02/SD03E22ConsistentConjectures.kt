package logic.e3.pack02

/**
 * SD-03 E2.2 / examiner-blind learner kernel.
 * Inferred alternatives are hypotheses, NOT discovered facts. They preserve every
 * disclosed label, but can differ on unlabelled inputs. No world/target/truth,
 * QUERY labels, CHALLENGE or HELDOUT can enter this API.
 *
 * Mechanism: refine a TRAIN-pure leaf with an atom that is constant on TRAIN
 * examples reaching that leaf but has an unobserved opposite branch in QUERY.
 * The new branch predicts the opposite Boolean as a falsifiable counterfactual.
 * This is a bounded, explicit inductive bias; do not call it general version space.
 */
data class E22Config(
    val maxConjectures: Int = 64,
    val maxDepth: Int = 5,
    val maxNodes: Int = 65,
    val minLeafSupport: Int = 1,
) {
    init {
        require(maxConjectures in 1..1024 && maxDepth in 1..12 && maxNodes >= 3)
        require(minLeafSupport >= 1)
    }
}

enum class E22BuildStatus { READY, BASE_NOT_TRAIN_CERTIFIED, NO_COUNTERFACTUAL_DIVERSITY }

data class E22Conjecture(
    val id: String,
    val program: E1Program,
    val leafPath: String,
    val splitAtomId: String,
    val supportingPublicIds: List<String>,
    val oppositeQueryIds: List<String>,
)

data class E22Build(
    val status: E22BuildStatus,
    val alternatives: List<E22Conjecture>,
    val testedAtomLeafPairs: Int,
    val rejectedUnknown: Int,
    val rejectedNonconstant: Int,
    val rejectedNoOppositeQuery: Int,
    val rejectedStructuralBudget: Int,
    val rejectedNotTrainCertified: Int,
    val possibleBeforeTruncation: Int,
    val note: String,
)

enum class E22RowReason {
    ELIGIBLE_DISAGREEMENT, NO_DISAGREEMENT, UNKNOWN_CURRENT,
    INSUFFICIENT_KNOWN_VOTES, ALREADY_QUERIED, RANDOM_PRECOMMITTED,
}

data class E22Vote(val modelId: String, val predicted: Boolean?)

data class E22QueryRow(
    val queryId: String,
    val currentPrediction: Boolean?,
    val votes: List<E22Vote>,
    val trueVotes: Int,
    val falseVotes: Int,
    val unknownVotes: Int,
    val disagreementScore: Int,
    val leafTrainingSupport: Int?,
    val rank: Int?,
    val reason: E22RowReason,
    val selected: Boolean,
)

data class E22Matrix(
    val selectedQueryId: String?,
    val decisionReason: String,
    val rows: List<E22QueryRow>,
    val modelCount: Int,
)

object SD03E22ConsistentConjectures {
    private fun nodes(tree: E1Program): Int = when (tree) {
        is E1Program.Constant -> 1
        is E1Program.Branch -> 1 + nodes(tree.whenTrue) + nodes(tree.whenFalse)
    }
    private fun depth(tree: E1Program): Int = when (tree) {
        is E1Program.Constant -> 0
        is E1Program.Branch -> 1 + maxOf(depth(tree.whenTrue), depth(tree.whenFalse))
    }
    private fun replace(tree: E1Program, path: String, subtree: E1Program): E1Program {
        if (path.isEmpty()) return subtree
        val branch = tree as? E1Program.Branch ?: error("invalid replacement path")
        return when (path.first()) {
            'T' -> branch.copy(whenTrue = replace(branch.whenTrue, path.drop(1), subtree))
            'F' -> branch.copy(whenFalse = replace(branch.whenFalse, path.drop(1), subtree))
            else -> error("invalid branch character")
        }
    }
    private fun route(tree: E1Program, predictions: Map<String, Boolean?>): String? {
        var cursor = tree
        val path = StringBuilder()
        while (cursor is E1Program.Branch) {
            val prediction = predictions[cursor.atomId] ?: return null
            path.append(if (prediction) 'T' else 'F')
            cursor = if (prediction) cursor.whenTrue else cursor.whenFalse
        }
        return path.toString()
    }
    private fun leaves(tree: E1Program, path: String = ""): List<Pair<String, Boolean>> = when (tree) {
        is E1Program.Constant -> listOf(path to tree.value)
        is E1Program.Branch -> leaves(tree.whenTrue, path + "T") + leaves(tree.whenFalse, path + "F")
    }
    private fun atomValues(examples: List<E1Example>, atoms: List<E1Atom>): Map<String, Map<String, Boolean?>> =
        examples.associate { e -> e.id to atoms.associate { a -> a.id to a.predictions[e.id] } }

    fun generate(
        current: E1Program,
        public: List<E1Example>,
        atoms: List<E1Atom>,
        pending: List<E2Unlabelled>,
        config: E22Config = E22Config(),
    ): E22Build {
        require(public.isNotEmpty() && public.map { it.id }.distinct().size == public.size)
        require(atoms.map { it.id }.distinct().size == atoms.size)
        require(pending.map { it.id }.distinct().size == pending.size)
        val publicIds = public.map { it.id }.toSet()
        val catalogIds = atoms.map { it.id }.toSet()
        require(atoms.all { it.id.isNotBlank() && it.predictions.keys == publicIds })
        require(pending.all { it.id !in publicIds && it.atomPredictions.keys == catalogIds })
        val pvals = atomValues(public, atoms)
        if (public.any { e -> SD03EvidenceCompositionE1.predict(current, pvals.getValue(e.id)) != e.label }) {
            return E22Build(E22BuildStatus.BASE_NOT_TRAIN_CERTIFIED, emptyList(),
                0, 0, 0, 0, 0, 0, 0,
                "Original proposal is not consistent with all already disclosed labels")
        }
        var tested = 0; var unknown = 0; var nonconstant = 0
        var noQuery = 0; var structural = 0; var failed = 0
        val raw = ArrayList<E22Conjecture>()
        val ppaths = public.associate { e -> e.id to route(current, pvals.getValue(e.id)) }
        val qpaths = pending.associate { u -> u.id to route(current, u.atomPredictions) }
        val baseLeaves = leaves(current)
        for ((path, leafValue) in baseLeaves.sortedBy { it.first }) {
            val scoped = public.filter { ppaths[it.id] == path }
            if (scoped.size < config.minLeafSupport) continue
            val matchingQueries = pending.filter { qpaths[it.id] == path }
            if (matchingQueries.isEmpty()) continue
            for (atom in atoms.sortedBy { it.id }) {
                tested++
                val values = scoped.map { atom.predictions[it.id] }
                if (values.any { it == null }) { unknown++; continue }
                if (values.distinct().size != 1) { nonconstant++; continue }
                val observed = requireNotNull(values.first())
                val opposite = matchingQueries.filter { it.atomPredictions[atom.id] == !observed }
                    .map { it.id }.sorted()
                if (opposite.isEmpty()) { noQuery++; continue }
                val updatedLeaf = if (observed == true)
                    E1Program.Branch(atom.id, E1Program.Constant(leafValue), E1Program.Constant(!leafValue))
                else E1Program.Branch(atom.id, E1Program.Constant(!leafValue), E1Program.Constant(leafValue))
                val updated = replace(current, path, updatedLeaf)
                if (nodes(updated) > config.maxNodes || depth(updated) > config.maxDepth) {
                    structural++; continue
                }
                if (public.any { e -> SD03EvidenceCompositionE1.predict(updated, pvals.getValue(e.id)) != e.label }) {
                    failed++; continue
                }
                raw += E22Conjecture("CONJECTURE:${path.ifEmpty { "ROOT" }}:${atom.id}",
                    updated, path, atom.id, scoped.map { it.id }, opposite)
            }
        }
        // Multiple structural conjectures with identical QUERY behavior count as
        // ONE hypothesis for query scoring. Preserve the simplest stable exemplar.
        val uniqueBySignature = linkedMapOf<String, E22Conjecture>()
        for (candidate in raw.sortedWith(compareBy<E22Conjecture> { it.program.toString().length }
            .thenBy { it.id })) {
            val signature = pending.sortedBy { it.id }.joinToString("|") { q ->
                q.id + ":" + SD03EvidenceCompositionE1.predict(candidate.program, q.atomPredictions)
            }
            uniqueBySignature.putIfAbsent(signature, candidate)
        }
        val alternatives = uniqueBySignature.values.take(config.maxConjectures)
        return E22Build(if (alternatives.isEmpty()) E22BuildStatus.NO_COUNTERFACTUAL_DIVERSITY else E22BuildStatus.READY,
            alternatives, tested, unknown, nonconstant, noQuery, structural, failed, uniqueBySignature.size,
            "TRAIN-certified bounded leaf counterfactuals, not exhaustive version-space or validated truths")
    }

    /** Audit every pending candidate and already-disclosed ID. Query labels never enter. */
    fun matrix(
        current: E1Program,
        alternatives: List<E22Conjecture>,
        allPool: List<E2Unlabelled>,
        alreadyRevealedIds: Set<String>,
        public: List<E1Example>,
        atoms: List<E1Atom>,
    ): E22Matrix {
        require(allPool.map { it.id }.distinct().size == allPool.size)
        val ids = atoms.map { it.id }.toSet()
        require(allPool.all { it.atomPredictions.keys == ids })
        val pvals = atomValues(public, atoms)
        val paths = public.associate { it.id to route(current,pvals.getValue(it.id)) }
        val models = listOf("CURRENT" to current) + alternatives.map { it.id to it.program }
        require(models.map { it.first }.distinct().size == models.size)
        // Verify all proposed alternatives preserve *all* public labels. Reject
        // unsupported external model injection before it can cast a vote.
        require(models.all { (_,program) -> public.all { e ->
            SD03EvidenceCompositionE1.predict(program,pvals.getValue(e.id)) == e.label
        } }) { "A model contradicted public TRAIN; cannot vote in E2.2" }
        data class Draft(val u:E2Unlabelled,val cur:Boolean?,val votes:List<E22Vote>,
            val t:Int,val f:Int,val unk:Int,val support:Int?,val reason:E22RowReason)
        val drafts = allPool.sortedBy { it.id }.map { u ->
            val values = u.atomPredictions
            val cur = SD03EvidenceCompositionE1.predict(current,values)
            val votes = models.map { (id, program) -> E22Vote(id,SD03EvidenceCompositionE1.predict(program,values)) }
            val t=votes.count {it.predicted==true}; val f=votes.count {it.predicted==false}
            val unk=votes.size-t-f
            val path=route(current,values)
            val support=if(path==null)null else paths.values.count {it==path}
            val reason=when {
                u.id in alreadyRevealedIds -> E22RowReason.ALREADY_QUERIED
                cur==null -> E22RowReason.UNKNOWN_CURRENT
                t==0 || f==0 -> E22RowReason.NO_DISAGREEMENT
                t+f<2 -> E22RowReason.INSUFFICIENT_KNOWN_VOTES
                else -> E22RowReason.ELIGIBLE_DISAGREEMENT
            }
            Draft(u,cur,votes,t,f,unk,support,reason)
        }
        val eligible = drafts.filter {it.reason==E22RowReason.ELIGIBLE_DISAGREEMENT}
            .sortedWith(compareByDescending<Draft> {minOf(it.t,it.f)}
                .thenBy {it.support ?: Int.MAX_VALUE}
                .thenBy {it.u.id})
        val ranks=eligible.mapIndexed {i,d->d.u.id to (i+1)}.toMap()
        val selected=eligible.firstOrNull()?.u?.id
        return E22Matrix(selected,when {
            alternatives.isEmpty() -> "NO_TRAIN_CONSISTENT_COMPETING_CONJECTURE"
            selected==null -> "NO_ELIGIBLE_PREDICTIVE_DISAGREEMENT"
            else -> "TRAIN_CONSISTENT_COUNTERFACTUAL_DISAGREEMENT"
        },drafts.map {d->E22QueryRow(d.u.id,d.cur,d.votes,d.t,d.f,d.unk,
            minOf(d.t,d.f),d.support,ranks[d.u.id],d.reason,d.u.id==selected)},models.size)
    }
}
