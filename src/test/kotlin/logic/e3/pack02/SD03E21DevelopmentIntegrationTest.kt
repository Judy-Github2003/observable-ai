package logic.e3.pack02

import logic.capability.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Public DEVELOPMENT tasks only. This is a two-part DIAGNOSTIC, not a formal exam.
 * A: the student chooses queries without labels, then the examiner separately
 *    audits whether the original program had ANY counterexample in the QUERY pool.
 * B: the examiner selects the FIRST counterexample by frozen ID order; the student
 *    receives ONLY that one disclosed label to test isolated local-vs-full repair.
 * Do NOT treat B as autonomous counterexample discovery. Do NOT route A audit
 * truth/labels back to any A student arm.
 */
class SD03E21DevelopmentIntegrationTest {
    private data class Task(val id: String, val seed: Int)
    private data class Trial(
        val arm: String, val program: E1Program?, val public: List<E1Example>,
        val source: SD03E2RepoAdapter, val queried: List<String>,
        val stop: String, val startHash: String?, val localStatus: String? = null,
        val localPath: String? = null, val localIntegrity: Boolean? = null,
    )
    private data class QueryPool(val u: E2Unlabelled, val input: Map<String,String>)
    private val tasks = listOf(
        Task("mrx-base-or-r-d1", 20261111), Task("mrx-depth-w4", 20261110),
        Task("mrx-depth-r-xor-b2", 20261112), Task("mrx-if-nested-3", 20261109),
    )
    private val cfg = E1Config(maxDepth=4,beamWidthPerNode=5,maxTreeNodes=63,
        maxPartitionAttempts=30_000,minExamplesPerChild=1)
    private fun h(x:String)=MessageDigest.getInstance("SHA-256")
        .digest(x.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun q(x:String)=CanonicalJson.string(x)
    private fun bool(x:Boolean?)=x?.toString()?:"null"
    private fun append(path:Path,line:String) {
        Files.createDirectories(path.parent)
        FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,
            StandardOpenOption.APPEND).use { channel ->
            val bytes=ByteBuffer.wrap((line+"\n").toByteArray(Charsets.UTF_8))
            while(bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }
    private fun hashProgram(p:E1Program?,src:SD03E2RepoAdapter):String? =
        p?.let { h(CanonicalJson.expr(src.toExpr(it))) }
    private fun programText(p:E1Program?,src:SD03E2RepoAdapter):String? =
        p?.let { CanonicalJson.expr(src.toExpr(it)) }
    private fun freeze(path:Path, token:String, trial:Trial) {
        val canonical=programText(trial.program,trial.source)
        val publicDigest=h(trial.public.joinToString("|") { e ->
            e.id+":"+e.label+":"+e.fields.toSortedMap().toString() })
        append(path,"""{"task":${q(token)},"arm":${q(trial.arm)},"program":${canonical?.let(::q)?:"null"},"programHash":${hashProgram(trial.program,trial.source)?.let(::q)?:"null"},"initialHash":${trial.startHash?.let(::q)?:"null"},"publicDigest":${q(publicDigest)},"publicCount":${trial.public.size},"queryIds":[${trial.queried.joinToString(",") { q(it) }}],"stop":${q(trial.stop)},"localStatus":${trial.localStatus?.let(::q)?:"null"},"localPath":${trial.localPath?.let(::q)?:"null"},"localStructureInvariant":${trial.localIntegrity?.toString()?:"null"},"atomCalls":${trial.source.atomInterpreterCalls},"candidateCalls":${trial.source.candidateInterpreterCalls},"e1Proposals":${trial.source.e1Proposals},"e1PartitionAttempts":${trial.source.e1PartitionAttempts},"e1PredictionReads":${trial.source.e1PredictionReads}}""")
    }
    private fun ledger(pool:List<QueryPool>,truth:(Map<String,String>)->Boolean):MRX2Ledger =
        MRX2Ledger(pool.map { it.input },StudyQueryController(pool.map { it.input },pool.size,truth),
            pool.size,MRX2Cost())

    /** Query pool inputs only are public; oracle labels are not included. */
    private fun runA(
        token:String,arm:String,train:List<E1Example>,pool:List<QueryPool>,
        count:Int,seed:Int,library:List<CapabilityDefinition>,
        oracle:MRX2Ledger,events:Path,
    ):Trial {
        val src=SD03E2RepoAdapter(library)
        var current=src.propose(train,cfg).program
        val initial=hashProgram(current,src)
        val disclosed=train.toMutableList()
        val chosen=mutableListOf<String>()
        val randomOrder=pool.map { it.u.id }.shuffled(Random(seed xor 0x52A1))
        var stop="BUDGET_COMPLETE"
        if (arm=="A_STATIC") return Trial(arm,current,disclosed,src,chosen,"NO_QUERY",initial)
        for (step in 0 until count) {
            val p=current
            if(p==null){stop="NO_INITIAL_PROGRAM";break}
            val remaining=pool.filter { it.u.id !in chosen }.map { it.u }
            val actionId:String?
            var rationale="RANDOM_PRECOMMITTED"
            var rejected=0
            var rejectedUnknown=0
            var versionSpace=0
            var votesT=0
            var votesF=0
            if (arm=="A_RANDOM_MATCHED") actionId=randomOrder.firstOrNull { it !in chosen }
            else {
                val candidates=src.competingModels(disclosed,p,cfg)
                if(arm=="A_E2_DISAGREEMENT") {
                    val decision=SD03ActiveQueryLocalRepairE2.choose(remaining,candidates,p,
                        disclosed,src.atomCatalog(disclosed))
                    actionId=decision.queryId; rationale=decision.reason
                    votesT=decision.agreeingTrue;votesF=decision.agreeingFalse
                } else {
                    val decision=SD03E21EvidenceQuery.select(
                        E21QueryPolicy.TRAIN_CONSISTENT_VERSIONSPACE,remaining,candidates,p,
                        disclosed,src.atomCatalog(disclosed))
                    actionId=decision.queryId;rationale=decision.reason
                    votesT=decision.agreeingTrue;votesF=decision.agreeingFalse
                    rejected=decision.rejectedInconsistentModels
                    rejectedUnknown=decision.rejectedUnknownModels
                    versionSpace=decision.eligibleDistinctModels
                }
            }
            if(actionId==null) {
                stop=rationale
                append(events,"""{"phase":"A_ABSTAIN","task":${q(token)},"arm":${q(arm)},"step":$step,"reason":${q(stop)},"versionSpace":$versionSpace,"inconsistentContenders":$rejected,"unknownContenders":$rejectedUnknown}""")
                break
            }
            val selected=pool.single { it.u.id==actionId }
            val currentPrediction=SD03EvidenceCompositionE1.predict(p,selected.u.atomPredictions)
            // Never query when current prediction is Unknown; report, don't coerce.
            if(currentPrediction==null) {stop="CURRENT_UNKNOWN";break}
            val publicHash=h(disclosed.joinToString("|") { it.id+":"+it.label })
            val freezeHash=h("$token|$arm|$step|$actionId|$publicHash|${hashProgram(p,src)}")
            append(events,"""{"phase":"A_DECISION_FROZEN","task":${q(token)},"arm":${q(arm)},"step":$step,"queryId":${q(actionId)},"snapshotHash":${q(freezeHash)},"currentPrediction":$currentPrediction,"reason":${q(rationale)},"trueVotes":$votesT,"falseVotes":$votesF,"versionSpace":$versionSpace,"inconsistentContenders":$rejected,"unknownContenders":$rejectedUnknown}""")
            val (label,entry)=oracle.query(selected.input,mapOf("CURRENT_PROGRAM" to currentPrediction),
                listOf("CURRENT_PROGRAM"),"E21_${arm}_QUERY")
            if(label==null||entry==null){stop="ORACLE_UNAVAILABLE";break}
            append(events,"""{"phase":"A_LABEL_REVEALED","task":${q(token)},"arm":${q(arm)},"step":$step,"queryId":${q(actionId)},"label":$label,"predictionBeforeReveal":$currentPrediction,"wasCurrentCounterexample":${label!=currentPrediction},"snapshotHash":${q(freezeHash)},"ledgerIndex":${entry.queryIndex}}""")
            chosen+=actionId
            disclosed+=E1Example(actionId,selected.input,label)
            val fit=src.propose(disclosed,cfg)
            current=fit.program
            append(events,"""{"phase":"A_FULL_REFIT","task":${q(token)},"arm":${q(arm)},"step":$step,"fitStatus":${q(fit.status.name)},"fitTrainCorrect":${fit.trainCorrect},"fitTrainTotal":${fit.trainTotal},"newProgramHash":${hashProgram(current,src)?.let(::q)?:"null"}}""")
        }
        return Trial(arm,current,disclosed,src,chosen,stop,initial)
    }

    private fun grade(
        token:String,trial:Trial, challenge:List<Map<String,String>>,
        heldout:List<Map<String,String>>,truth:(Map<String,String>)->Boolean,
        library:List<CapabilityDefinition>,file:Path,
    ) {
        val candidate=trial.program?.let(trial.source::toExpr)
        val certified=trial.program?.let { trial.source.certified(it,trial.public) }?:false
        val meter=MRX2Cost()
        // NO_UPDATE is deliberately contradicted by the supplied public label.
        // Grade it as a negative EXAMINER control, but NEVER certify or promote it.
        val scoreNegativeControl = trial.arm == "B_NO_UPDATE" && candidate != null
        var status="NOT_ELIGIBLE";var held:Int?=null
        if((certified || scoreNegativeControl) && candidate!=null) {
            val ledger=MRX2Ledger(challenge,StudyQueryController(challenge,challenge.size,truth),
                challenge.size,meter)
            val result=ledger.validate(CanonicalJson.expr(candidate),
                { input -> Pack02Knowledge.predict(candidate,input,library) },
                "E21_FINAL_EXAMINER",challenge.size,meter)
            status=result.status.name
            if(result.status==MRX2ValidationStatus.PASS_ON_CHALLENGE)
                held=heldout.count { input -> Pack02Knowledge.predict(candidate,input,library)==truth(input) }
        }
        append(file,"""{"task":${q(token)},"arm":${q(trial.arm)},"certifiedOnPublic":$certified,"scoredAsIneligibleNegativeControl":${scoreNegativeControl && !certified},"challengeStatus":${q(status)},"heldoutCorrect":${held?.toString()?:"null"},"heldoutTotal":${heldout.size},"challengeOracleNew":${meter.oracleNewQueries},"challengeInterpreterCalls":${meter.interpreterCalls},"programHash":${hashProgram(trial.program,trial.source)?.let(::q)?:"null"}}""")
    }

    @Test fun `E21 query opportunity and matched counterexample repair are separated`() {
        val library=C8TestBases.bases.library("FULL").definitions
        val dir=Path.of("docs/research/sd03-e21-development-2026-11-07")
        Files.createDirectories(dir)
        val marker=dir.resolve("E21-RUN-STARTED.txt")
        require(!Files.exists(marker)){"Evidence exists: archive previous attempt; never overwrite"}
        append(marker,"started; formal_runs=0; independent_closed_book=NOT_RUN")
        for(t in tasks) {
            val world=MRXWorlds.baseline.single { it.id==t.id }
            val shuffled=C8Domain.all().shuffled(Random(t.seed))
            val train=shuffled.take(32).mapIndexed { i,m -> E1Example("t-$i",m,world.truth(m)) }
            val queryInput=shuffled.drop(32).take(12)
            val challenge=shuffled.drop(44).take(16)
            val heldout=shuffled.drop(60)
            assertEquals(21,heldout.size)
            val token=h("${t.id}|${t.seed}").take(16)
            val publicFile=dir.resolve("A-PUBLIC-INPUTS.jsonl")
            val eventFile=dir.resolve("A-DECISION-TIMELINE.jsonl")
            val preview=SD03E2RepoAdapter(library)
            val pool=queryInput.mapIndexed { i,m -> QueryPool(
                E2Unlabelled("q-$i",m,preview.atomPreview(m)),m) }
            // Inputs are public, labels are not. Initial TRAIN labels are disclosed.
            val trainRecords=train.joinToString(",") { e ->
                """{"id":${q(e.id)},"fields":${q(e.fields.toSortedMap().toString())},"label":${e.label}}""" }
            val poolRecords=pool.joinToString(",") { u ->
                """{"id":${q(u.u.id)},"fields":${q(u.input.toSortedMap().toString())}}""" }
            append(publicFile,"""{"task":${q(token)},"seed":${t.seed},"train":[$trainRecords],"queryInputsWithoutLabels":[$poolRecords]}""")
            val e2=runA(token,"A_E2_DISAGREEMENT",train,pool,4,t.seed,library,
                ledger(pool,world.truth),eventFile)
            val strict=runA(token,"A_STRICT_VERSIONSPACE",train,pool,4,t.seed,library,
                ledger(pool,world.truth),eventFile)
            val random=runA(token,"A_RANDOM_MATCHED",train,pool,e2.queried.size,t.seed,library,
                ledger(pool,world.truth),eventFile)
            val static=runA(token,"A_STATIC",train,pool,0,t.seed,library,
                ledger(pool,world.truth),eventFile)
            val aRuns=listOf(static,random,e2,strict)
            // A choices and final programs MUST be durable BEFORE the opportunity audit.
            for(trial in aRuns) freeze(dir.resolve("A-STUDENT-FROZEN-BEFORE-AUDIT.jsonl"),token,trial)
            assertEquals(e2.queried.size,random.queried.size)
            assertEquals(0,static.queried.size)
            val initial=static.program
            // EXAMINER-ONLY AUDIT: all 12 QUERY truth values, never fed to A arms.
            // Reuse atom previews for the ORIGINAL program so the audit scores true
            // correction opportunities, not diversity among other hypotheses.
            val counterexamples=if(initial==null) emptyList() else pool.filter { u ->
                val p=SD03EvidenceCompositionE1.predict(initial,u.u.atomPredictions)
                p!=null && p!=world.truth(u.input)
            }
            val unknown=if(initial==null) pool.size else pool.count { u ->
                SD03EvidenceCompositionE1.predict(initial,u.u.atomPredictions)==null }
            val available=counterexamples.map {it.u.id}.toSet()
            val initialHash=hashProgram(initial,static.source)
            val armAudit=aRuns.joinToString(",") { a ->
                """{"arm":${q(a.arm)},"queries":${a.queried.size},"initialCounterexampleHits":${a.queried.count { it in available }},"queryIds":[${a.queried.joinToString(","){q(it)}}]}""" }
            append(dir.resolve("A-EXAMINER-OPPORTUNITY-AFTER-FREEZE.jsonl"),
                """{"task":${q(token)},"initialHash":${initialHash?.let(::q)?:"null"},"poolSize":${pool.size},"initialCounterexamples":${counterexamples.size},"unknownInitialPredictions":$unknown,"counterexampleIds":[${available.sorted().joinToString(",") { q(it) }}],"arms":[$armAudit]}""")
            // B: selection is explicitly examiner-controlled. The first TRUE
            // counterexample in q-ID order is selected before local/full execution.
            val selected=counterexamples.minByOrNull { it.u.id }
            val bInitialSource=SD03E2RepoAdapter(library)
            val original=bInitialSource.propose(train,cfg).program
            assertEquals(initial,original,"B must use same initial TRAIN-only proposal")
            val initialCertified=original?.let { bInitialSource.certified(it,train) }?:false
            append(dir.resolve("B-EXAMINER-CHALLENGE-SELECTION.jsonl"),
                """{"task":${q(token)},"initialHash":${initialHash?.let(::q)?:"null"},"selectionRule":"FIRST_SORTED_INITIAL_COUNTEREXAMPLE","queryCounterexamplesAvailable":${counterexamples.size},"selectedQueryId":${selected?.u?.id?.let(::q)?:"null"},"selectedLabel":${selected?.let { world.truth(it.input).toString() }?:"null"},"initialTrainCertified":$initialCertified,"diagnosticOnly":true}""")
            val bTrials=ArrayList<Trial>()
            if(original!=null && initialCertified && selected!=null) {
                val one=E1Example(selected.u.id,selected.input,world.truth(selected.input))
                val public=train+one
                val refAdapter=SD03E2RepoAdapter(library)
                val full=refAdapter.propose(public,cfg).program
                val locAdapter=SD03E2RepoAdapter(library)
                val local=SD03ActiveQueryLocalRepairE2.repair(original,public,
                    locAdapter.atomCatalog(public),one.id,cfg)
                val integrity=if(local.status==E2RepairStatus.LOCAL_REPAIRED)
                    SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(original,local.program,local.changedPath)
                    else null
                require(integrity!=false) {"LOCAL_REPAIRED violates sibling-immutability"}
                bTrials+=Trial("B_NO_UPDATE",original,public,bInitialSource,listOf(one.id),"FROZEN_CONTROL",initialHash)
                bTrials+=Trial("B_FULL_REFIT",full,public,refAdapter,listOf(one.id),"ONE_COUNTEREXAMPLE",initialHash)
                bTrials+=Trial("B_LOCAL_ONLY",local.program,public,locAdapter,listOf(one.id),
                    local.reason,initialHash,local.status.name,local.changedPath,integrity)
                append(dir.resolve("B-REPAIR-DETAILS.jsonl"),
                    """{"task":${q(token)},"queryId":${q(one.id)},"status":${q(local.status.name)},"changedPath":${local.changedPath?.let(::q)?:"null"},"nonRootSiblingInvariant":${integrity?.toString()?:"null"},"ancestorAttempts":${local.ancestorAttempts},"oldEvidenceCorrect":${local.remainingOldEvidenceCorrect},"allPublicCorrect":${local.allKnownEvidenceCorrect},"fullRefitUsed":${local.fullRefitUsed},"localPartitionAttempts":${local.localPartitionAttempts},"localPredictionReads":${local.localPredictionReads}}""")
            } else {
                append(dir.resolve("B-REPAIR-DETAILS.jsonl"),
                    """{"task":${q(token)},"status":"NOT_ELIGIBLE_OR_NO_POOL_COUNTEREXAMPLE","initialCertified":$initialCertified,"available":${counterexamples.size}}""")
            }
            // Examiner grades only after all A and B final student programs frozen.
            for(b in bTrials) freeze(dir.resolve("B-STUDENT-FROZEN-BEFORE-GRADING.jsonl"),token,b)
            for(a in aRuns+bTrials) grade(token,a,challenge,heldout,world.truth,library,
                dir.resolve("EXAMINER-CHALLENGE-AFTER-FREEZE.jsonl"))
        }
    }
}
