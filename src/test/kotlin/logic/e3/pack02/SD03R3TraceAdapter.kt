package logic.e3.pack02

import java.security.MessageDigest

/** Integration-only adapter. Generates next R2 snapshot from a REAL MRX2BSpace search.
 * The adapter takes no examiner truth/callback and compares selected canonical
 * atoms with the actual trace. Never use it to manufacture synthetic success.
 */
object SD03R3TraceAdapter {
    private fun atomId(canonical: String): String = "atom:" +
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun afterRealSearch(
        before: R2Snapshot,
        action: R3Action,
        search: MRX2BSpaceResult,
        runToken: String,
    ): R2Snapshot {
        require(runToken.isNotBlank())
        val mapped = when (action) {
            R3Action.PROBE_BASELINE_10 -> R2Action.BASELINE_10
            R3Action.PROBE_DIVERSITY_10 -> R2Action.DIVERSITY_10
            R3Action.PROBE_PAIRWISE_12 -> R2Action.PAIRWISE_12
            else -> error("afterRealSearch requires an executed search intervention")
        }
        val original = before.alternatives.single { it.action == mapped }
        val traceIds = search.trace.atoms.map(::atomId)
        require(traceIds == original.selectedAtomIds) {
            "predeclared preview selection != real student trace for $mapped; stop rather than fabricate"
        }
        val selectedSet = traceIds.toSet()
        val catalog = before.atoms.associateBy { it.id }
        require(selectedSet.all { it in catalog })
        val ordering = (traceIds + before.atoms.map { it.id }).distinct()
        val ranks = ordering.withIndex().associate { it.value to it.index + 1 }
        return before.copy(
            id = "${before.id}:after:$runToken:${mapped.name}",
            atoms = before.atoms.map { atom ->
                atom.copy(rank = ranks.getValue(atom.id), retained = atom.id in selectedSet,
                    evidenceId = "trace:$runToken:atom:${atom.id}")
            },
            lifecycle = R2Lifecycle(search.generated, search.consistent, search.retained,
                search.generated >= 30000, "trace:$runToken:lifecycle:post"),
        )
    }
}
