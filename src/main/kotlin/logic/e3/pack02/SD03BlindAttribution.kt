// SD-03 blind attribution: pure, examiner-blind hypothesis/decision kernel.
// Research scaffold; it does not execute MRX2BSpace or claim an experiment result.
package logic.e3.pack02

/** Only public TRAIN observations. No target formula, CHALLENGE labels or HELDOUT labels. */
data class BAObservation(
    val evidenceId: String,
    val inputs: Map<String, String>,
    val label: Boolean,
)

/** Dependency set is a conservative over-approximation resolved from Expr and active library.
 * COMPLETE_OVERAPPROX: if an input is absent, the atom provably cannot depend on it.
 * UNKNOWN: unsupported Call/Ref or incomplete resolver; never infer absence from it.
 */
enum class BADependencyQuality { COMPLETE_OVERAPPROX, UNKNOWN }

data class BARankedAtom(
    val canonical: String,
    val rank: Int,
    val selected: Boolean,
    val possibleInputDependencies: Set<String>,
    val dependencyQuality: BADependencyQuality,
    val traceEvidenceId: String,
)

data class BACandidateLifecycle(
    val generated: Int,
    val trainConsistent: Int,
    val retained: Int,
    val generationCapHit: Boolean,
    val traceEvidenceId: String,
)

enum class BAAction { KEEP, DIVERSITY_10, BASELINE_10, PAIRWISE_12, ABSTAIN }
enum class BAEpistemic { SUPPORTED, REFUTED, UNKNOWN }
enum class BACause { REQUIRED_INPUT_DROPPED, ALL_TRAIN_CANDIDATES_REFUTED, POST_TRAIN_SELECTION_FAILURE, UNKNOWN }

data class BAClaim(
    val cause: BACause,
    val status: BAEpistemic,
    val evidenceIds: List<String>,
    val statement: String,
)

data class BABlindSnapshot(
    val snapshotId: String,
    val observations: List<BAObservation>,
    val rankedAtoms: List<BARankedAtom>,
    val lifecycle: BACandidateLifecycle,
    val allowedActions: Set<BAAction>,
)

data class BADecision(
    val snapshotId: String,
    val claims: List<BAClaim>,
    val action: BAAction,
    val rationaleEvidenceIds: List<String>,
    val reasonCode: String,
)

/** All reasoning is restricted to public TRAIN and the frozen search trace.
 * No world ID, gold Expr or evaluator outcome enters this function.
 * 'SUPPORTED' means observationally supported, not proven causal; causal verification
 * requires a separately executed matched intervention.
 */
object SD03BlindAttributor {
    fun decide(snapshot: BABlindSnapshot): BADecision {
        require(snapshot.snapshotId.isNotBlank())
        require(snapshot.observations.map { it.evidenceId }.distinct().size == snapshot.observations.size)
        require(snapshot.rankedAtoms.map { it.canonical }.distinct().size == snapshot.rankedAtoms.size)
        require(snapshot.rankedAtoms.map { it.rank }.distinct().size == snapshot.rankedAtoms.size)
        require(snapshot.lifecycle.generated >= snapshot.lifecycle.trainConsistent)
        require(snapshot.lifecycle.trainConsistent >= snapshot.lifecycle.retained)

        // Find directly observed label reversals with exactly one input attribute changed.
        val witnesses = linkedMapOf<String, MutableSet<String>>()
        for (i in snapshot.observations.indices) for (j in i + 1 until snapshot.observations.size) {
            val a = snapshot.observations[i]
            val b = snapshot.observations[j]
            if (a.label == b.label || a.inputs.keys != b.inputs.keys) continue
            val changed = a.inputs.keys.filter { a.inputs[it] != b.inputs[it] }
            if (changed.size == 1) {
                witnesses.getOrPut(changed.single()) { linkedSetOf() }.addAll(listOf(a.evidenceId, b.evidenceId))
            }
        }
        val selected = snapshot.rankedAtoms.filter { it.selected }
        val complete = selected.all { it.dependencyQuality == BADependencyQuality.COMPLETE_OVERAPPROX }
        // If any retained atom has unresolved semantics, missing-feature attribution is UNKNOWN.
        val implicatedMissing = if (complete) witnesses.keys.filter { field ->
            selected.none { field in it.possibleInputDependencies }
        }.sorted() else emptyList()

        val claims = mutableListOf<BAClaim>()
        if (witnesses.isEmpty()) {
            claims += BAClaim(BACause.REQUIRED_INPUT_DROPPED, BAEpistemic.UNKNOWN, emptyList(),
                "No single-input label-reversal witness in public TRAIN")
        } else if (!complete) {
            claims += BAClaim(BACause.REQUIRED_INPUT_DROPPED, BAEpistemic.UNKNOWN,
                witnesses.values.flatten().distinct(), "Some selected atom dependencies are unresolved")
        } else if (implicatedMissing.isNotEmpty()) {
            val refs = implicatedMissing.flatMap { witnesses.getValue(it) } +
                selected.map { it.traceEvidenceId } + snapshot.lifecycle.traceEvidenceId
            claims += BAClaim(BACause.REQUIRED_INPUT_DROPPED, BAEpistemic.SUPPORTED,
                refs.distinct(), "Discriminating TRAIN attribute(s) absent from all retained atom dependencies")
        } else {
            claims += BAClaim(BACause.REQUIRED_INPUT_DROPPED, BAEpistemic.REFUTED,
                witnesses.values.flatten().distinct() + selected.map { it.traceEvidenceId },
                "Every observed distinguishing input is represented by at least one selected atom")
        }
        claims += when {
            snapshot.lifecycle.trainConsistent == 0 -> BAClaim(
                BACause.ALL_TRAIN_CANDIDATES_REFUTED, BAEpistemic.SUPPORTED,
                listOf(snapshot.lifecycle.traceEvidenceId), "Zero generated candidates survive TRAIN")
            else -> BAClaim(BACause.ALL_TRAIN_CANDIDATES_REFUTED, BAEpistemic.REFUTED,
                listOf(snapshot.lifecycle.traceEvidenceId), "Some generated candidates survive TRAIN")
        }
        claims += when {
            snapshot.lifecycle.trainConsistent > 0 && snapshot.lifecycle.retained == 0 -> BAClaim(
                BACause.POST_TRAIN_SELECTION_FAILURE, BAEpistemic.SUPPORTED,
                listOf(snapshot.lifecycle.traceEvidenceId), "TRAIN survivors existed but none retained")
            snapshot.lifecycle.trainConsistent == 0 -> BAClaim(
                BACause.POST_TRAIN_SELECTION_FAILURE, BAEpistemic.REFUTED,
                listOf(snapshot.lifecycle.traceEvidenceId), "Failure precedes post-TRAIN retention")
            else -> BAClaim(BACause.POST_TRAIN_SELECTION_FAILURE, BAEpistemic.UNKNOWN,
                listOf(snapshot.lifecycle.traceEvidenceId), "Need additional validation evidence")
        }
        val coverage = claims.first()
        val suggested = when {
            coverage.status == BAEpistemic.SUPPORTED &&
                snapshot.lifecycle.trainConsistent == 0 &&
                !snapshot.lifecycle.generationCapHit -> BAAction.DIVERSITY_10
            else -> BAAction.ABSTAIN
        }
        // Actions are frozen by examiner before analysis; do not override if unavailable.
        val action = if (suggested in snapshot.allowedActions) suggested else BAAction.ABSTAIN
        val rationale = if (action != BAAction.ABSTAIN) {
            (coverage.evidenceIds + snapshot.lifecycle.traceEvidenceId).distinct()
        } else claims.flatMap { it.evidenceIds }.distinct()
        return BADecision(snapshot.snapshotId, claims, action, rationale,
            if (action != BAAction.ABSTAIN) "EVIDENCE_SUPPORTED_BOUNDED_PROBE" else "INSUFFICIENT_FOR_SAFE_ACTION")
    }
}
