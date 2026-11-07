package logic.e3.pack02

/**
 * SD-03-E2.1 STUDENT-SAFE query decision.
 * No truth callback, world, QUERY labels, CHALLENGE or HELDOUT are accepted.
 * Only already disclosed labels can certify contender consistency.
 */
enum class E21QueryPolicy { E2_UNFILTERED_DISAGREEMENT, TRAIN_CONSISTENT_VERSIONSPACE }

data class E21QueryDecision(
    val queryId: String?,
    val policy: E21QueryPolicy,
    val reason: String,
    val eligibleDistinctModels: Int,
    val rejectedInconsistentModels: Int,
    val rejectedUnknownModels: Int,
    val agreeingTrue: Int,
    val agreeingFalse: Int,
    val modelEvidenceIds: List<String>,
    val predictionOfCurrent: Boolean?,
)

object SD03E21EvidenceQuery {
    fun select(
        policy: E21QueryPolicy,
        pending: List<E2Unlabelled>,
        models: List<E2Model>,
        current: E1Program,
        public: List<E1Example>,
        atoms: List<E1Atom>,
    ): E21QueryDecision {
        require(pending.map { it.id }.distinct().size == pending.size)
        require(public.map { it.id }.distinct().size == public.size)
        require(atoms.map { it.id }.distinct().size == atoms.size)
        val trainIds = public.map { it.id }.toSet()
        val atomIds = atoms.map { it.id }.toSet()
        require(atoms.all { it.predictions.keys == trainIds }) { "Unknown=null; full public coverage mandatory" }
        require(pending.all { it.id !in trainIds && it.atomPredictions.keys == atomIds })
        require(models.map { it.evidenceId }.distinct().size == models.size)

        val knownById = public.associate { ex ->
            ex.id to atoms.associate { atom -> atom.id to atom.predictions[ex.id] }
        }
        var inconsistent = 0
        var unknown = 0
        // A model that cannot decide ALL disclosed labels is not in the version space.
        // We intentionally report the rejected model count for falsifiability.
        val candidates = if (policy == E21QueryPolicy.TRAIN_CONSISTENT_VERSIONSPACE) {
            (models + E2Model("CURRENT_CERTIFIED", current)).filter { m ->
                val predictions = public.map { ex ->
                    SD03EvidenceCompositionE1.predict(m.program, knownById.getValue(ex.id))
                }
                when {
                    predictions.any { it == null } -> { unknown++; false }
                    predictions.zip(public).any { (p,e) -> p != e.label } -> { inconsistent++; false }
                    else -> true
                }
            }
        } else models
        val unique = candidates.sortedBy { it.evidenceId }.distinctBy { it.program }
        fun abstain(reason: String) = E21QueryDecision(null, policy, reason,
            unique.size, inconsistent, unknown, 0, 0, unique.map { it.evidenceId }, null)
        if (unique.size < 2) return abstain("NO_TWO_DISTINCT_ELIGIBLE_PROGRAMS")

        data class Rank(val u: E2Unlabelled, val yes: Int, val no: Int,
            val ids: List<String>, val current: Boolean?)
        val ranked = pending.sortedBy { it.id }.mapNotNull { u ->
            val votes = unique.mapNotNull { m ->
                SD03EvidenceCompositionE1.predict(m.program, u.atomPredictions)?.let { m.evidenceId to it }
            }
            val yes = votes.count { it.second }; val no = votes.size - yes
            if (yes == 0 || no == 0) null else Rank(u, yes, no,
                votes.map { it.first }, SD03EvidenceCompositionE1.predict(current, u.atomPredictions))
        }.sortedWith(compareByDescending<Rank> { minOf(it.yes,it.no) }
            .thenByDescending { it.yes + it.no }.thenBy { it.u.id })
        val best = ranked.firstOrNull() ?: return abstain("NO_KNOWN_DISAGREEMENT")
        return E21QueryDecision(best.u.id, policy, "PUBLIC_ONLY_DISAGREEMENT",
            unique.size, inconsistent, unknown, best.yes, best.no,
            best.ids, best.current)
    }
}

/** The locality proof checks the original and updated tree, never the examiner truth. */
object SD03E21LocalIntegrity {
    private fun subtree(p: E1Program, path: String): E1Program {
        var cursor = p
        for (ch in path) {
            val b = cursor as? E1Program.Branch ?: throw IllegalArgumentException("Invalid path")
            cursor = when (ch) { 'T' -> b.whenTrue; 'F' -> b.whenFalse
                else -> throw IllegalArgumentException("Bad path character") }
        }
        return cursor
    }
    fun exactlyOneSubtreeReplaced(old: E1Program, next: E1Program, path: String?): Boolean {
        if (path.isNullOrEmpty() || path.any { it != 'T' && it != 'F' }) return false
        if (runCatching { subtree(old,path) }.isFailure || runCatching { subtree(next,path) }.isFailure) return false
        if (subtree(old,path) == subtree(next,path)) return false
        var a: E1Program = old
        var b: E1Program = next
        for (ch in path) {
            val oldBranch = a as? E1Program.Branch ?: return false
            val newBranch = b as? E1Program.Branch ?: return false
            if (oldBranch.atomId != newBranch.atomId) return false
            if (ch == 'T') {
                if (oldBranch.whenFalse != newBranch.whenFalse) return false
                a = oldBranch.whenTrue; b = newBranch.whenTrue
            } else {
                if (oldBranch.whenTrue != newBranch.whenTrue) return false
                a = oldBranch.whenFalse; b = newBranch.whenFalse
            }
        }
        return true
    }
}
