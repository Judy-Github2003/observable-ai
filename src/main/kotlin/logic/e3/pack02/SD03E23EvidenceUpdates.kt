package logic.e3.pack02

/**
 * SD-03 E2.3. Update POLICY, not query selection; receives only already disclosed
 * public evidence and public atom predictions. No truth oracle or world identity.
 * A new agreeing observation may prune rival hypotheses but is not a mandate to
 * modify the accepted program. Results remain proposals until repository adapter
 * independently checks the actual Capability interpreter.
 */
enum class E23UpdatePolicy { FULL_REFIT, KEEP_IF_AGREE, LOCAL_FIRST }
enum class E23UpdateStatus {
    CONFIRM_KEEP, FULL_REFIT_ACCEPTED, LOCAL_REPAIRED,
    LOCAL_FAILED_FULL_FALLBACK, LOCAL_UNAVAILABLE_FULL_FALLBACK,
    FULL_REFIT_NOT_CONSISTENT, OLD_PUBLIC_INCONSISTENT,
    UNKNOWN_CURRENT_PREDICTION, INVALID_NEW_EVIDENCE
}

data class E23UpdateResult(
    val policy: E23UpdatePolicy,
    val status: E23UpdateStatus,
    val program: E1Program,
    val newEvidenceId: String,
    val predictionBeforeReveal: Boolean?,
    val confirmed: Boolean?,
    val programChanged: Boolean,
    val fullRefitCalls: Int,
    val fullRefitSplitAttempts: Int,
    val fullRefitPredictionReads: Int,
    val localAttempts: Int,
    val localSplitAttempts: Int,
    val localPredictionReads: Int,
    val localChangedPath: String?,
    val localStatus: E2RepairStatus?,
    val allPublicConsistent: Boolean,
    val note: String,
)

object SD03E23EvidenceUpdates {
    fun update(
        policy: E23UpdatePolicy,
        old: E1Program,
        public: List<E1Example>,
        atoms: List<E1Atom>,
        newEvidenceId: String,
        config: E1Config = E1Config(),
    ): E23UpdateResult {
        require(public.map { it.id }.distinct().size == public.size)
        require(atoms.map { it.id }.distinct().size == atoms.size)
        require(atoms.all { it.predictions.keys == public.map { e -> e.id }.toSet() })
        val next = public.singleOrNull { it.id == newEvidenceId }
        fun result(status: E23UpdateStatus, program: E1Program=old,
                   prediction: Boolean?=null, confirmed: Boolean?=null,
                   full: E1Result?=null, local: E2RepairResult?=null,
                   note: String): E23UpdateResult = E23UpdateResult(
            policy, status, program, newEvidenceId, prediction, confirmed,
            old != program, if (full==null) 0 else 1,
            full?.splitAttempts ?: 0, full?.predictionReads ?: 0,
            local?.ancestorAttempts ?: 0, local?.localPartitionAttempts ?: 0,
            local?.localPredictionReads ?: 0, local?.changedPath, local?.status,
            SD03ActiveQueryLocalRepairE2.correct(program, public, atoms), note)
        if (next == null) return result(E23UpdateStatus.INVALID_NEW_EVIDENCE,
            note="New public evidence ID absent or duplicated")
        val older=public.filter { it.id != newEvidenceId }
        val restricted=atoms.map { a -> a.copy(predictions=older.associate { it.id to a.predictions[it.id] }) }
        if (!SD03ActiveQueryLocalRepairE2.correct(old, older, restricted))
            return result(E23UpdateStatus.OLD_PUBLIC_INCONSISTENT,
                note="Old program already contradicted earlier disclosed evidence; cannot certify update")
        val preview=atoms.associate { it.id to it.predictions[newEvidenceId] }
        val pred=SD03EvidenceCompositionE1.predict(old, preview)
        if (pred==null) return result(E23UpdateStatus.UNKNOWN_CURRENT_PREDICTION,
            prediction=pred,note="Unknown preserved; no false-label coercion")
        val agrees=(pred==next.label)
        if (agrees && policy!=E23UpdatePolicy.FULL_REFIT) return result(
            E23UpdateStatus.CONFIRM_KEEP,prediction=pred,confirmed=true,
            note="Confirming label updates public evidence; current program unchanged")

        fun fullRefit(local:E2RepairResult?=null):E23UpdateResult {
            val fit=SD03EvidenceCompositionE1.propose(public,atoms,config)
            val candidate=fit.program
            val valid=fit.status==E1Status.TRAIN_CONSISTENT_PROPOSAL &&
                candidate!=null && SD03ActiveQueryLocalRepairE2.correct(candidate,public,atoms)
            if (!valid) return result(E23UpdateStatus.FULL_REFIT_NOT_CONSISTENT,
                prediction=pred,confirmed=agrees,full=fit,local=local,
                note="No validated E1 full refit; retain old but explicitly mark unresolved")
            val status=when {
                local?.status==E2RepairStatus.LOCAL_FAILED -> E23UpdateStatus.LOCAL_FAILED_FULL_FALLBACK
                local!=null -> E23UpdateStatus.LOCAL_UNAVAILABLE_FULL_FALLBACK
                else -> E23UpdateStatus.FULL_REFIT_ACCEPTED
            }
            return result(status,candidate!!,pred,agrees,fit,local,
                note="Full refit selected; student proposal still needs independent interpreter certification")
        }
        if (policy!=E23UpdatePolicy.LOCAL_FIRST) return fullRefit()
        val repaired=SD03ActiveQueryLocalRepairE2.repair(old,public,atoms,newEvidenceId,config)
        if (repaired.status==E2RepairStatus.LOCAL_REPAIRED &&
            repaired.changedPath!=null && SD03ActiveQueryLocalRepairE2.correct(repaired.program,public,atoms)) {
            return result(E23UpdateStatus.LOCAL_REPAIRED,repaired.program,pred,false,
                local=repaired,note="Non-root subtree replaced; verify sibling integrity and execute AST independently")
        }
        return fullRefit(repaired)
    }
}
