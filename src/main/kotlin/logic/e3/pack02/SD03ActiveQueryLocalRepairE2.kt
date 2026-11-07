package logic.e3.pack02

/**
 * SD-03-E2: pure TRAIN+revealed-QUERY decision kernel; no truth callback, world ID,
 * CHALLENGE labels or HELDOUT labels. Query candidates contain only their INPUTS
 * and their computed atom predictions. Returned query IDs are proposals, NOT labels.
 * Reuse E1Program/E1Example/E1Atom from the committed E1 learner.
 */
data class E2Unlabelled(
    val id: String,
    val input: Map<String, String>,
    val atomPredictions: Map<String, Boolean?>,
)

data class E2Model(
    val evidenceId: String,
    val program: E1Program,
)

data class E2QueryChoice(
    val queryId: String?,
    val reason: String,
    val agreeingTrue: Int,
    val agreeingFalse: Int,
    val supportingModelIds: List<String>,
    val leafSupportInTraining: Int?,
)

enum class E2RepairStatus {
    NO_COUNTEREXAMPLE, LOCAL_REPAIRED, LOCAL_UNAVAILABLE, LOCAL_FAILED,
    UNKNOWN_PATH, INSUFFICIENT_PUBLIC_EVIDENCE,
}

data class E2RepairResult(
    val status: E2RepairStatus,
    val program: E1Program,
    val changedPath: String?,
    val ancestorAttempts: Int,
    val remainingOldEvidenceCorrect: Boolean,
    val allKnownEvidenceCorrect: Boolean,
    val changedNodesBefore: Int,
    val changedNodesAfter: Int,
    val fullRefitUsed: Boolean,
    val reason: String,
    val localPartitionAttempts: Int = 0,
    val localPredictionReads: Int = 0,
)

object SD03ActiveQueryLocalRepairE2 {
    /** Only disagreement is a positive query signal. If every known rival agrees,
     * refuse to pretend sparse support alone identifies an informative label.
     * Unknown predictions do not vote. Equal scores have stable ID tie-breaking.
     */
    fun choose(
        unlabelled: List<E2Unlabelled>,
        models: List<E2Model>,
        current: E1Program,
        training: List<E1Example>,
        knownAtoms: List<E1Atom>,
    ): E2QueryChoice {
        require(models.map { it.evidenceId }.distinct().size == models.size)
        require(unlabelled.map { it.id }.distinct().size == unlabelled.size)
        val atomIds = knownAtoms.map { it.id }.toSet()
        require(unlabelled.all { it.atomPredictions.keys == atomIds }) {
            "Unlabelled atom preview must cover complete catalog; Unknown=null"
        }
        require(unlabelled.none { u -> training.any { it.id == u.id } })
        val contenders = models.distinctBy { it.program }.sortedBy { it.evidenceId }
        if (contenders.size < 2) return E2QueryChoice(null,"NO_COMPETING_PROGRAMS",0,0,emptyList(),null)
        val trainingPred = knownAtoms.associate { it.id to it.predictions }
        fun support(u: E2Unlabelled): Int? {
            var tree = current
            val same = training.toMutableList()
            while (tree is E1Program.Branch) {
                val v = u.atomPredictions[tree.atomId] ?: return null
                val p = trainingPred[tree.atomId] ?: return null
                same.removeAll { p[it.id] != v }
                tree = if (v) tree.whenTrue else tree.whenFalse
            }
            return same.size
        }
        data class Candidate(val item: E2Unlabelled, val t: Int, val f: Int, val support: Int?, val ids: List<String>)
        val candidates = unlabelled.sortedBy { it.id }.mapNotNull { u ->
            val predictions = contenders.mapNotNull { m ->
                SD03EvidenceCompositionE1.predict(m.program,u.atomPredictions)?.let { m.evidenceId to it }
            }
            val t = predictions.count { it.second }
            val f = predictions.size-t
            if (t==0 || f==0) null else Candidate(u,t,f,support(u),predictions.map { it.first })
        }
        val best = candidates.sortedWith(compareByDescending<Candidate> { minOf(it.t,it.f) }
            .thenBy { it.support ?: Int.MAX_VALUE }
            .thenBy { it.item.id }).firstOrNull()
            ?: return E2QueryChoice(null,"NO_OBSERVED_PREDICTION_DISAGREEMENT",0,0,emptyList(),null)
        return E2QueryChoice(best.item.id,"PREDICTIVE_DISAGREEMENT",best.t,best.f,best.ids,best.support)
    }

    private fun predictionsFor(examples: List<E1Example>, atoms: List<E1Atom>): Map<String,Map<String,Boolean?>> {
        val ids = examples.map { it.id }.toSet()
        require(examples.map { it.id }.distinct().size == examples.size)
        require(atoms.map { it.id }.distinct().size == atoms.size)
        require(atoms.all { it.predictions.keys == ids }) { "Public atom predictions must exactly cover disclosed labels" }
        return examples.associate { e -> e.id to atoms.associate { it.id to it.predictions[e.id] } }
    }

    fun correct(program: E1Program, examples: List<E1Example>, atoms: List<E1Atom>): Boolean {
        val byInput = predictionsFor(examples,atoms)
        return examples.all { e -> SD03EvidenceCompositionE1.predict(program,byInput.getValue(e.id)) == e.label }
    }

    private fun nodes(p: E1Program): Int = when(p) {
        is E1Program.Constant -> 1
        is E1Program.Branch -> 1 + nodes(p.whenTrue) + nodes(p.whenFalse)
    }
    private fun depth(p: E1Program): Int = when(p) {
        is E1Program.Constant -> 0
        is E1Program.Branch -> 1 + maxOf(depth(p.whenTrue),depth(p.whenFalse))
    }
    private fun at(p: E1Program,path: String): E1Program = if (path.isEmpty()) p else {
        val b = p as? E1Program.Branch ?: error("path_not_in_tree")
        at(if(path[0]=='T') b.whenTrue else b.whenFalse,path.substring(1))
    }
    private fun replace(p:E1Program,path:String, replacement:E1Program):E1Program {
        if(path.isEmpty()) return replacement
        val b=p as? E1Program.Branch ?: error("path_not_in_tree")
        return if(path[0]=='T') b.copy(whenTrue=replace(b.whenTrue,path.substring(1),replacement))
        else b.copy(whenFalse=replace(b.whenFalse,path.substring(1),replacement))
    }
    private fun tracePath(p:E1Program,values:Map<String,Boolean?>):String? {
        val sb=StringBuilder()
        var node=p
        while(node is E1Program.Branch) {
            val pred=values[node.atomId] ?: return null
            sb.append(if(pred)'T' else 'F')
            node=if(pred) node.whenTrue else node.whenFalse
        }
        return sb.toString()
    }
    private fun passesPrefix(p:E1Program,path:String,values:Map<String,Boolean?>):Boolean {
        var node=p
        for(c in path) {
            val branch=node as? E1Program.Branch ?: return false
            val pred=values[branch.atomId] ?: return false
            if((if(pred)'T' else 'F') != c) return false
            node=if(pred)branch.whenTrue else branch.whenFalse
        }
        return true
    }

    /**
     * Local repair only: replace a NON-ROOT subtree on the observed counterexample
     * path. Test deepest subtree first, then ascend. Every sibling outside the
     * replacement path stays structurally identical by construction. No sneaky
     * root refit is counted as local. Refitting from root belongs to ACTIVE_FULL.
     * The caller must supply only original TRAIN and QUERY labels already revealed.
     */
    fun repair(
        old: E1Program,
        publicEvidence: List<E1Example>,
        atoms: List<E1Atom>,
        newEvidenceId: String,
        config: E1Config = E1Config(),
    ): E2RepairResult {
        if(publicEvidence.isEmpty() || publicEvidence.none{it.id==newEvidenceId})
            return E2RepairResult(E2RepairStatus.INSUFFICIENT_PUBLIC_EVIDENCE,old,null,0,false,false,0,0,false,"No disclosed new evidence")
        val atomByInput = predictionsFor(publicEvidence,atoms)
        val example=publicEvidence.single { it.id==newEvidenceId }
        val beforeCorrect=publicEvidence.filter{it.id!=newEvidenceId}.all {
            SD03EvidenceCompositionE1.predict(old,atomByInput.getValue(it.id))==it.label
        }
        if (!beforeCorrect) return E2RepairResult(E2RepairStatus.LOCAL_UNAVAILABLE,old,null,0,false,false,0,0,false,
            "Original proposal already contradicted earlier public evidence; local repair not certified")
        val predict=SD03EvidenceCompositionE1.predict(old,atomByInput.getValue(example.id))
        if(predict==null) return E2RepairResult(E2RepairStatus.UNKNOWN_PATH,old,null,0,beforeCorrect,false,0,0,false,"Unknown must not be converted to False")
        if(predict==example.label) return E2RepairResult(E2RepairStatus.NO_COUNTEREXAMPLE,old,null,0,beforeCorrect,
            correct(old,publicEvidence,atoms),0,0,false,"New evidence agrees; no repair authorized")
        val path=tracePath(old,atomByInput.getValue(example.id))
            ?: return E2RepairResult(E2RepairStatus.UNKNOWN_PATH,old,null,0,beforeCorrect,false,0,0,false,"Unknown split path")
        // Root is deliberately EXCLUDED, preventing full refit in the LOCAL arm.
        if(path.isEmpty()) return E2RepairResult(E2RepairStatus.LOCAL_UNAVAILABLE,old,null,0,beforeCorrect,false,0,0,false,"No non-root subtree")
        var tried=0
        var totalPartitionAttempts=0
        var totalPredictionReads=0
        for(prefixLen in path.length downTo 1) {
            val prefix=path.take(prefixLen)
            val scope=publicEvidence.filter { e -> passesPrefix(old,prefix,atomByInput.getValue(e.id)) }
            if (scope.isEmpty()) continue
            val scopedAtoms=atoms.map { atom -> atom.copy(predictions=scope.associate { e -> e.id to atom.predictions[e.id] }) }
            val remainingDepth=config.maxDepth-prefixLen
            if (remainingDepth<0) continue
            tried++
            val result=SD03EvidenceCompositionE1.propose(scope,scopedAtoms,config.copy(maxDepth=remainingDepth))
            totalPartitionAttempts+=result.splitAttempts
            totalPredictionReads+=result.predictionReads
            if (result.status!=E1Status.TRAIN_CONSISTENT_PROPOSAL || result.program==null) continue
            val replacement=result.program
            val previous=at(old,prefix)
            if(previous==replacement) continue
            val new=replace(old,prefix,replacement)
            if(nodes(new)>config.maxTreeNodes || depth(new)>config.maxDepth) continue
            if(!correct(new,publicEvidence,atoms)) continue
            return E2RepairResult(E2RepairStatus.LOCAL_REPAIRED,new,prefix,tried,beforeCorrect,true,
                nodes(previous),nodes(replacement),false,"Replaced only the minimal sufficient non-root subtree",
                totalPartitionAttempts,totalPredictionReads)
        }
        return E2RepairResult(E2RepairStatus.LOCAL_FAILED,old,null,tried,beforeCorrect,false,0,0,false,
            "No bounded non-root repair satisfies all disclosed labels; full refit is separate",
            totalPartitionAttempts,totalPredictionReads)
    }
}
