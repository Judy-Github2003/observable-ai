package logic.e3.pack02

import java.security.MessageDigest

/**
 * SD-03-R2: pure, examiner-blind attribution kernel. Only public TRAIN labels,
 * frozen TRAIN predictions of atoms, and search metadata are accepted here.
 * No world/target/truth/CHALLENGE/HELDOUT fields; no callbacks to an examiner.
 * Diagnostic hypotheses are observational. Intervention outcomes must be measured
 * separately and cannot be fed back as if they were pre-decision evidence.
 */
data class R2Observation(val id: String, val input: Map<String, String>, val label: Boolean)

data class R2Atom(
    val id: String,
    val rank: Int,
    val retained: Boolean,
    val predictions: Map<String, Boolean?>,
    val evidenceId: String,
)

enum class R2Action { KEEP, DIVERSITY_10, BASELINE_10, PAIRWISE_12, ABSTAIN }
enum class R2Status { SUPPORTED, REFUTED, UNKNOWN }
enum class R2Cause {
    SELECTED_SIGNATURE_INSUFFICIENT,
    DISCARDED_ATOM_CAN_SEPARATE,
    NO_ATOM_CAN_SEPARATE,
    ALL_TRAIN_CANDIDATES_REFUTED,
    POST_TRAIN_RETENTION_FAILURE,
    GENERATION_CAP_PRESSURE,
    UNKNOWN_ATOM_PREDICTION,
}

data class R2Lifecycle(
    val generated: Int,
    val trainConsistent: Int,
    val retained: Int,
    val generationCapHit: Boolean,
    val evidenceId: String,
)

/** Preview is produced only from TRAIN input/labels and source atom catalog.
 * If obtaining it requires full candidate generation, charge ALL preview cost.
 */
data class R2Alternative(
    val action: R2Action,
    val selectedAtomIds: List<String>,
    val evidenceId: String,
)

data class R2Snapshot(
    val id: String,
    val observations: List<R2Observation>,
    val atoms: List<R2Atom>,
    val lifecycle: R2Lifecycle,
    val alternatives: List<R2Alternative>,
    val allowedActions: Set<R2Action>,
)

data class R2Witness(
    val leftId: String,
    val rightId: String,
    val differentAttributes: List<String>,
    val selectedSignatureEqual: Boolean,
    val discardedSeparators: List<String>,
    val evidenceIds: List<String>,
)

data class R2Hypothesis(
    val cause: R2Cause,
    val status: R2Status,
    val evidenceIds: List<String>,
    val explanation: String,
)

data class R2Decision(
    val snapshotId: String,
    val snapshotDigest: String,
    val hypotheses: List<R2Hypothesis>,
    val blockedWitnesses: List<R2Witness>,
    val action: R2Action,
    val actionEvidenceIds: List<String>,
    val predictedRecoveredWitnesses: Int,
    val reason: String,
)

object SD03BlindAttributionR2 {
    private fun digest(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Stable forensic hash; not a signature and not a substitute for source provenance. */
    fun snapshotDigest(s: R2Snapshot): String {
        val canonical = buildString {
            append(s.id).append('|')
            for (o in s.observations.sortedBy { it.id }) {
                append(o.id).append(':').append(o.label).append(':')
                o.input.toSortedMap().forEach { (k, v) -> append(k.length).append('#').append(k).append(v.length).append('#').append(v) }
                append('|')
            }
            for (a in s.atoms.sortedBy { it.id }) {
                append(a.id).append(':').append(a.rank).append(':').append(a.retained).append(':').append(a.evidenceId).append(':')
                a.predictions.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v).append(',') }
                append('|')
            }
            append(s.lifecycle).append('|')
            for (alt in s.alternatives.sortedBy { it.action.name }) {
                append(alt.action).append(':').append(alt.selectedAtomIds.joinToString(",")).append(':').append(alt.evidenceId).append('|')
            }
            append(s.allowedActions.map { it.name }.sorted().joinToString(","))
        }
        return digest(canonical)
    }

    fun decide(s: R2Snapshot): R2Decision {
        require(s.id.isNotBlank())
        require(s.observations.isNotEmpty())
        require(s.observations.map { it.id }.distinct().size == s.observations.size)
        require(s.observations.all { it.id.isNotBlank() && it.input.isNotEmpty() })
        val inputKeys = s.observations.first().input.keys
        require(s.observations.all { it.input.keys == inputKeys })
        require(s.atoms.map { it.id }.distinct().size == s.atoms.size)
        require(s.atoms.map { it.rank }.distinct().size == s.atoms.size)
        require(s.atoms.all { it.id.isNotBlank() && it.evidenceId.isNotBlank() })
        val observationIds = s.observations.map { it.id }.toSet()
        require(s.atoms.all { it.predictions.keys == observationIds }) {
            "Atom predictions must cover exactly the public TRAIN; use null for Unknown, never false"
        }
        require(s.lifecycle.generated >= s.lifecycle.trainConsistent &&
            s.lifecycle.trainConsistent >= s.lifecycle.retained && s.lifecycle.retained >= 0)
        require(s.lifecycle.evidenceId.isNotBlank())
        require(s.alternatives.map { it.action }.distinct().size == s.alternatives.size)
        require(s.alternatives.all { it.action != R2Action.KEEP && it.action != R2Action.ABSTAIN })
        require(s.alternatives.all { it.evidenceId.isNotBlank() && it.selectedAtomIds.distinct().size == it.selectedAtomIds.size })
        val atomsById = s.atoms.associateBy { it.id }
        require(s.alternatives.all { alt -> alt.selectedAtomIds.all { it in atomsById } })

        val selected = s.atoms.filter { it.retained }
        val discarded = s.atoms.filterNot { it.retained }
        val anyUnknown = s.atoms.any { a -> a.predictions.values.any { it == null } }
        val blocked = mutableListOf<R2Witness>()
        var comparableOppositePairs = 0
        var separatedOppositePairs = 0
        var unresolvedOppositePairs = 0
        val obs = s.observations
        for (i in obs.indices) for (j in i + 1 until obs.size) {
            val left = obs[i]; val right = obs[j]
            if (left.label == right.label) continue
            val changed = inputKeys.filter { left.input[it] != right.input[it] }.sorted()
            if (selected.isEmpty() || selected.any { a -> a.predictions[left.id] == null || a.predictions[right.id] == null }) {
                unresolvedOppositePairs++
                continue
            }
            comparableOppositePairs++
            if (selected.any { a -> a.predictions[left.id] != a.predictions[right.id] }) {
                separatedOppositePairs++
                continue
            }
            val discardedSeparators = discarded.filter { a ->
                val l = a.predictions[left.id]; val r = a.predictions[right.id]
                l != null && r != null && l != r
            }.map { it.id }
            val refs = (listOf(left.id, right.id) + selected.map { it.evidenceId } +
                discarded.filter { it.id in discardedSeparators }.map { it.evidenceId } +
                s.lifecycle.evidenceId).distinct()
            blocked += R2Witness(left.id, right.id, changed, true, discardedSeparators, refs)
        }
        val evidenceBlocked = blocked.flatMap { it.evidenceIds }.distinct()
        val weakOrMissing = anyUnknown || unresolvedOppositePairs > 0
        val hypotheses = listOf(
            R2Hypothesis(R2Cause.SELECTED_SIGNATURE_INSUFFICIENT,
                if (blocked.isNotEmpty()) R2Status.SUPPORTED
                else if (comparableOppositePairs > 0 && !weakOrMissing) R2Status.REFUTED else R2Status.UNKNOWN,
                evidenceBlocked, "Opposite-label TRAIN cases have identical selected-atom prediction signatures"),
            R2Hypothesis(R2Cause.DISCARDED_ATOM_CAN_SEPARATE,
                if (blocked.any { it.discardedSeparators.isNotEmpty() }) R2Status.SUPPORTED
                else if (blocked.isNotEmpty() && !weakOrMissing) R2Status.REFUTED else R2Status.UNKNOWN,
                evidenceBlocked, "Discarded atom can distinguish at least one blocked TRAIN pair; not proof of complete repair"),
            R2Hypothesis(R2Cause.NO_ATOM_CAN_SEPARATE,
                if (blocked.isNotEmpty() && blocked.all { it.discardedSeparators.isEmpty() } && !weakOrMissing) R2Status.SUPPORTED
                else if (blocked.any { it.discardedSeparators.isNotEmpty() }) R2Status.REFUTED else R2Status.UNKNOWN,
                evidenceBlocked, "No known source atom can distinguish the blocked TRAIN pairs"),
            R2Hypothesis(R2Cause.ALL_TRAIN_CANDIDATES_REFUTED,
                if (s.lifecycle.trainConsistent == 0) R2Status.SUPPORTED else R2Status.REFUTED,
                listOf(s.lifecycle.evidenceId), "Search stage outcome; does not itself identify cause"),
            R2Hypothesis(R2Cause.POST_TRAIN_RETENTION_FAILURE,
                if (s.lifecycle.trainConsistent > 0 && s.lifecycle.retained == 0) R2Status.SUPPORTED
                else if (s.lifecycle.trainConsistent == 0 || s.lifecycle.retained > 0) R2Status.REFUTED else R2Status.UNKNOWN,
                listOf(s.lifecycle.evidenceId), "TRAIN survivors existed but no candidate survived retention"),
            R2Hypothesis(R2Cause.GENERATION_CAP_PRESSURE,
                if (s.lifecycle.generationCapHit) R2Status.SUPPORTED else R2Status.REFUTED,
                listOf(s.lifecycle.evidenceId), "Candidate cap was reached; causality not established"),
            R2Hypothesis(R2Cause.UNKNOWN_ATOM_PREDICTION,
                if (anyUnknown) R2Status.SUPPORTED else R2Status.REFUTED,
                s.atoms.filter { null in it.predictions.values }.map { it.evidenceId },
                "Incomplete atom predictions prevent exhaustive signature claims"),
        )
        val keyClaims = hypotheses.associateBy { it.cause }
        // Unknown blocks unsafe promotion. No need to know the target formula or hidden labels.
        val canProbe = s.lifecycle.trainConsistent == 0 && !s.lifecycle.generationCapHit &&
            !weakOrMissing && blocked.isNotEmpty() &&
            keyClaims.getValue(R2Cause.DISCARDED_ATOM_CAN_SEPARATE).status == R2Status.SUPPORTED
        val candidates = if (canProbe) s.alternatives.filter { alt ->
            alt.action in s.allowedActions &&
                blocked.all { witness ->
                    alt.selectedAtomIds.any { id ->
                        val predictions = atomsById.getValue(id).predictions
                        val l = predictions[witness.leftId]; val r = predictions[witness.rightId]
                        l != null && r != null && l != r
                    }
                }
        } else emptyList()
        // Predeclared risk tie-break; do not use CHALLENGE/HELDOUT to pick an arm.
        val priority = listOf(R2Action.BASELINE_10, R2Action.DIVERSITY_10, R2Action.PAIRWISE_12)
        val chosen = candidates.minWithOrNull(compareBy<R2Alternative>({ it.selectedAtomIds.size }, { priority.indexOf(it.action) }))
        val action = chosen?.action ?: R2Action.ABSTAIN
        val decisionEvidence = if (chosen != null)
            (evidenceBlocked + chosen.evidenceId).distinct()
        else (evidenceBlocked + s.lifecycle.evidenceId).distinct()
        return R2Decision(
            snapshotId = s.id,
            snapshotDigest = snapshotDigest(s),
            hypotheses = hypotheses,
            blockedWitnesses = blocked,
            action = action,
            actionEvidenceIds = decisionEvidence,
            predictedRecoveredWitnesses = if (chosen != null) blocked.size else 0,
            reason = when {
                chosen != null -> "BOUNDED_TRAIN_ONLY_DISCRIMINATION_PROBE_NOT_CAUSAL_PROOF"
                s.lifecycle.generationCapHit -> "CAP_PRESSURE_UNKNOWN_CAUSE_ABSTAIN"
                weakOrMissing -> "INCOMPLETE_ATOM_BEHAVIOR_ABSTAIN"
                blocked.isEmpty() -> "NO_OBSERVED_SIGNATURE_OBSTRUCTION_ABSTAIN"
                else -> "NO_PREDECLARED_ALTERNATIVE_COVERS_BLOCKED_PAIRS_ABSTAIN"
            },
        )
    }
}
