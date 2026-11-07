package logic.e3.pack02

/**
 * SD-03 R3: sequential, examiner-blind attribution on real TRAIN-only search snapshots.
 * This is a deterministic decision kernel, not an executor or a scientific result.
 *
 * Dependency: SD03BlindAttributionR2.kt, already present in the project.
 * The examiner MUST freeze+persist an immutable decision record BEFORE running
 * the next student search, then hand the resulting TRAIN-only snapshot back.
 */
enum class R3Action {
    PROBE_BASELINE_10, PROBE_DIVERSITY_10, PROBE_PAIRWISE_12,
    AUDIT_GENERATION_CAP, AUDIT_COMPOSITION_COVERAGE,
    STOP_FOR_VERIFICATION, ABSTAIN,
}

enum class R3Stage { BASELINE, POST_INTERVENTION }
enum class R3Finding {
    SIGNATURE_OBSTRUCTION_PERSISTS,
    SIGNATURE_OBSTRUCTION_REMOVED,
    TRAIN_SURVIVOR_RECOVERED,
    GENERATION_CAP_PRESSURE,
    GRAMMAR_OR_ENUMERATION_UNRESOLVED,
    INTERVENTION_NOT_EFFECTIVE,
    OBSERVATIONS_UNCHANGED,
}
enum class R3Epistemic { SUPPORTED, REFUTED, UNKNOWN }

data class R3Claim(
    val finding: R3Finding,
    val status: R3Epistemic,
    val evidenceIds: List<String>,
    val explanation: String,
)

data class R3Decision(
    val stage: R3Stage,
    val snapshotId: String,
    val snapshotDigest: String,
    val previousSnapshotDigest: String?,
    val previousAction: R3Action?,
    val action: R3Action,
    val claims: List<R3Claim>,
    val evidenceIds: List<String>,
    val reasonCode: String,
    /** Diagnostics are not student repair success; these actions request evidence only. */
    val diagnosticOnly: Boolean,
)

object SD03SequentialAttributionR3 {
    private val repairActions = setOf(
        R3Action.PROBE_BASELINE_10,
        R3Action.PROBE_DIVERSITY_10,
        R3Action.PROBE_PAIRWISE_12,
    )

    private fun mapped(action: R2Action): R3Action = when (action) {
        R2Action.BASELINE_10 -> R3Action.PROBE_BASELINE_10
        R2Action.DIVERSITY_10 -> R3Action.PROBE_DIVERSITY_10
        R2Action.PAIRWISE_12 -> R3Action.PROBE_PAIRWISE_12
        else -> R3Action.ABSTAIN
    }

    /** No target expression, world id, external truth, challenge or heldout is accepted. */
    fun first(snapshot: R2Snapshot): R3Decision {
        val base = SD03BlindAttributionR2.decide(snapshot)
        val chosen = mapped(base.action)
        return R3Decision(
            stage = R3Stage.BASELINE,
            snapshotId = snapshot.id,
            snapshotDigest = base.snapshotDigest,
            previousSnapshotDigest = null,
            previousAction = null,
            action = chosen,
            claims = emptyList(),
            evidenceIds = (base.actionEvidenceIds + snapshot.lifecycle.evidenceId).distinct(),
            reasonCode = if (chosen in repairActions) "R2_TRAIN_ONLY_DISCRIMINATION_PROBE" else "R2_ABSTAIN",
            diagnosticOnly = false,
        )
    }

    /**
     * This method MUST be called only AFTER a real new search produced `after`.
     * It checks identity, TRAIN observations, first action authorization and
     * tamper-evident snapshot linkage. Pure code cannot prove a search ran;
     * the repository integration adapter must match trace.atoms and lifecycle.
     *
     * Without additional evidence, removal of one necessary obstruction does
     * NOT establish that the target grammar is expressive or that repair succeeded.
     */
    fun afterFirstIntervention(
        before: R2Snapshot,
        issued: R3Decision,
        after: R2Snapshot,
    ): R3Decision {
        require(issued.stage == R3Stage.BASELINE) { "not_an_initial_decision" }
        require(issued.action in repairActions) { "no_repair_was_issued" }
        require(issued.previousAction == null && issued.previousSnapshotDigest == null)
        require(issued.snapshotId == before.id &&
            issued.snapshotDigest == SD03BlindAttributionR2.snapshotDigest(before)) { "first_decision_snapshot_mismatch" }
        require(after.id != before.id) { "stale_snapshot_id" }
        require(SD03BlindAttributionR2.snapshotDigest(after) != issued.snapshotDigest) { "unchanged_snapshot" }
        val observationFingerprint: (R2Snapshot) -> Map<String, Pair<Map<String, String>, Boolean>> = { s ->
            s.observations.associate { it.id to (it.input.toMap() to it.label) }
        }
        require(observationFingerprint(before) == observationFingerprint(after)) { "TRAIN_was_modified_or_label_leaked" }
        require(before.atoms.map { it.id }.toSet() == after.atoms.map { it.id }.toSet()) { "atom_universe_changed" }
        val previousR2 = SD03BlindAttributionR2.decide(before)
        require(mapped(previousR2.action) == issued.action) { "first_action_not_supported_by_before_evidence" }
        val allowedBefore = previousR2.action in before.allowedActions
        require(allowedBefore) { "action_not_pre_registered" }
        val afterR2 = SD03BlindAttributionR2.decide(after)
        val oldBlocked = previousR2.blockedWitnesses.map { it.leftId to it.rightId }.toSet()
        val newBlocked = afterR2.blockedWitnesses.map { it.leftId to it.rightId }.toSet()
        // Empty blocked list is NOT proof when selected atoms are absent or predictions Unknown.
        val signatureResolved = afterR2.hypotheses.single {
            it.cause == R2Cause.SELECTED_SIGNATURE_INSUFFICIENT
        }.status == R2Status.REFUTED
        val repairedPairs = oldBlocked - newBlocked
        val extraEvidence = listOf(before.lifecycle.evidenceId, after.lifecycle.evidenceId) +
            repairedPairs.flatMap { (l, r) -> listOf(l, r) } + afterR2.actionEvidenceIds
        val findings = listOf(
            R3Claim(R3Finding.OBSERVATIONS_UNCHANGED, R3Epistemic.SUPPORTED,
                before.observations.map { it.id }, "TRAIN dataset and labels match the initial snapshot"),
            R3Claim(R3Finding.SIGNATURE_OBSTRUCTION_PERSISTS,
                if (newBlocked.isNotEmpty()) R3Epistemic.SUPPORTED else R3Epistemic.REFUTED,
                afterR2.blockedWitnesses.flatMap { it.evidenceIds }.distinct(),
                "At least one opposite-label TRAIN pair still has identical selected-atom signature"),
            R3Claim(R3Finding.SIGNATURE_OBSTRUCTION_REMOVED,
                when {
                    oldBlocked.isEmpty() -> R3Epistemic.UNKNOWN
                    newBlocked.isEmpty() && signatureResolved -> R3Epistemic.SUPPORTED
                    else -> R3Epistemic.REFUTED
                }, extraEvidence.distinct(),
                "Previously blocked pairs are no longer blocked; necessary but not sufficient for learning"),
            R3Claim(R3Finding.TRAIN_SURVIVOR_RECOVERED,
                if (after.lifecycle.trainConsistent > 0) R3Epistemic.SUPPORTED else R3Epistemic.REFUTED,
                listOf(after.lifecycle.evidenceId), "Post-intervention TRAIN-consistent candidates exist"),
            R3Claim(R3Finding.GENERATION_CAP_PRESSURE,
                if (after.lifecycle.generationCapHit) R3Epistemic.SUPPORTED else R3Epistemic.REFUTED,
                listOf(after.lifecycle.evidenceId), "Generation cap was reached; this alone is NOT causal attribution"),
            R3Claim(R3Finding.GRAMMAR_OR_ENUMERATION_UNRESOLVED,
                if (signatureResolved && after.lifecycle.trainConsistent == 0)
                    R3Epistemic.SUPPORTED else R3Epistemic.UNKNOWN,
                listOf(after.lifecycle.evidenceId),
                "Zero TRAIN survivors despite distinguishable retained atoms; cause could be grammar, enumeration ordering, cap, or interpreter"),
            R3Claim(R3Finding.INTERVENTION_NOT_EFFECTIVE,
                if (oldBlocked.isNotEmpty() && newBlocked.containsAll(oldBlocked) && after.lifecycle.trainConsistent == 0)
                    R3Epistemic.SUPPORTED else R3Epistemic.REFUTED,
                extraEvidence.distinct(), "The earlier blocked-pair obstruction persists without TRAIN survivors"),
        )
        val selected: R3Action
        val reason: String
        when {
            after.lifecycle.trainConsistent > 0 -> {
                selected = R3Action.STOP_FOR_VERIFICATION
                reason = "TRAIN_SURVIVORS_FOUND_NO_CLAIM_OF_CHALLENGE_PASS"
            }
            signatureResolved && after.lifecycle.generationCapHit -> {
                selected = R3Action.AUDIT_GENERATION_CAP
                reason = "SIGNATURE_RESTORED_BUT_NO_TRAIN_SURVIVOR_CAP_PRESSURE"
            }
            signatureResolved -> {
                selected = R3Action.AUDIT_COMPOSITION_COVERAGE
                reason = "SIGNATURE_RESTORED_BUT_NO_TRAIN_SURVIVOR_CAUSE_UNRESOLVED"
            }
            else -> {
                val possible = mapped(afterR2.action)
                if (possible in repairActions && possible != issued.action) {
                    selected = possible
                    reason = "NEW_TRAIN_EVIDENCE_SUPPORTS_DISTINCT_SECOND_PROBE"
                } else {
                    selected = R3Action.ABSTAIN
                    reason = "NO_DISTINCT_SAFE_PROBE_OR_UNKNOWN_PREDICTIONS"
                }
            }
        }
        return R3Decision(
            stage = R3Stage.POST_INTERVENTION,
            snapshotId = after.id,
            snapshotDigest = afterR2.snapshotDigest,
            previousSnapshotDigest = issued.snapshotDigest,
            previousAction = issued.action,
            action = selected,
            claims = findings,
            evidenceIds = extraEvidence.distinct(),
            reasonCode = reason,
            diagnosticOnly = selected == R3Action.AUDIT_GENERATION_CAP ||
                selected == R3Action.AUDIT_COMPOSITION_COVERAGE,
        )
    }
}
