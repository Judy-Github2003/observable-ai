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

/**
 * DEVELOPMENT-only matched-query experiment. No task truth/gold/CHALLENGE/HELDOUT
 * enters E1 or E2 kernels. This test orchestrates an examiner-owned Oracle.
 * The ACTIVE_LOCAL arm replays ACTIVE_FULL's already frozen query IDs through
 * its OWN independent ledger; no query labels are copied between ledgers.
 */
class SD03E2DevelopmentIntegrationTest {
    private data class Task(val worldId:String,val seed:Int)
    private data class Outcome(
        val arm:String,val program:E1Program?,val public:List<E1Example>,
        val source:SD03E2RepoAdapter,val queryIds:List<String>,
        val queryNew:Int,val queryReuse:Int,val repairs:List<String>,
        val fullRefits:Int,val localSuccess:Int,val queryStopReason:String,
        val elapsedMillis:Long,
    )
    private fun sha(x:String) = MessageDigest.getInstance("SHA-256")
        .digest(x.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun q(x:String)=CanonicalJson.string(x)
    private fun record(file:Path,event:String) {
        Files.createDirectories(file.parent)
        FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,
            StandardOpenOption.APPEND).use { ch ->
            val buf=ByteBuffer.wrap((event+"\n").toByteArray(Charsets.UTF_8))
            while(buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }
    private val config=E1Config(maxDepth=4,beamWidthPerNode=5,maxTreeNodes=63,
        maxPartitionAttempts=30_000,minExamplesPerChild=1)
    private fun initial(train:List<E1Example>,adapter:SD03E2RepoAdapter):E1Program? =
        adapter.propose(train,config).program

    private fun queryOnce(
        id:String, token:String, arm:String, step:Int,
        program:E1Program, public:List<E1Example>,
        query:List<E2Unlabelled>, ledger:MRX2Ledger,
        log:Path, source:SD03E2RepoAdapter,
        policy:String,
    ):E1Example? {
        val u=query.single { it.id==id }
        val pred=SD03EvidenceCompositionE1.predict(program,u.atomPredictions)
        if(pred==null) {
            record(log,"""{"phase":"ABSTAIN","arm":${q(arm)},"step":$step,"reason":"UNKNOWN_QUERY_PREDICTION","id":${q(id)}}""")
            return null
        }
        val programHash=sha(CanonicalJson.expr(source.toExpr(program)))
        val frozen=sha("$token|$arm|$step|$id|$programHash|${public.joinToString { it.id+":"+it.label }}")
        // Durable freeze BEFORE the (possibly counterexample) label is revealed.
        record(log,"""{"phase":"QUERY_DECISION_FROZEN","arm":${q(arm)},"step":$step,"id":${q(id)},"policy":${q(policy)},"snapshotHash":${q(frozen)},"programHash":${q(programHash)},"publicLabelCount":${public.size}}""")
        val (label,entry)=ledger.query(u.input,mapOf("CURRENT_PROGRAM" to pred),
            listOf("CURRENT_PROGRAM"),"E2_${arm}_QUERY")
        if(label==null || entry==null) {
            record(log,"""{"phase":"QUERY_FAILED","arm":${q(arm)},"step":$step,"id":${q(id)},"reason":"NO_ORACLE_LABEL_OR_BUDGET"}""")
            return null
        }
        record(log,"""{"phase":"QUERY_REVEALED","arm":${q(arm)},"step":$step,"id":${q(id)},"label":$label,"snapshotHash":${q(frozen)},"ledgerIndex":${entry.queryIndex}}""")
        return E1Example(u.id,u.input,label)
    }

    private fun active(
        token:String,initialTrain:List<E1Example>,query:List<E2Unlabelled>,
        library:List<CapabilityDefinition>, ledger:MRX2Ledger, log:Path,
    ):Outcome {
        val started=System.nanoTime()
        val source=SD03E2RepoAdapter(library)
        var current=initial(initialTrain,source)
        val evidence=initialTrain.toMutableList()
        val chosen=mutableListOf<String>(); var refits=0
        var stop="MAX_QUERIES"
        for(step in 0 until 4) {
            val program=current
            if(program==null) { stop="NO_INITIAL_PROGRAM"; break }
            val contenders=source.competingModels(evidence,program,config)
            val known=source.atomCatalog(evidence)
            val pending=query.filterNot { q -> q.id in chosen }
            val decision=SD03ActiveQueryLocalRepairE2.choose(pending,contenders,program,evidence,known)
            if(decision.queryId==null) {stop=decision.reason;break}
            val id=decision.queryId
            record(log,"""{"phase":"QUERY_RATIONALE","arm":"ACTIVE_FULL","step":$step,"id":${q(id)},"reason":${q(decision.reason)},"trueVotes":${decision.agreeingTrue},"falseVotes":${decision.agreeingFalse},"modelIds":[${decision.supportingModelIds.joinToString(",") { q(it) }}],"leafSupport":${decision.leafSupportInTraining ?: "null"}}""")
            val revealed=queryOnce(id,token,"ACTIVE_FULL",step,program,evidence,query,ledger,log,source,decision.reason)
            if(revealed==null) {stop="ORACLE_UNAVAILABLE";break}
            chosen+=id; evidence+=revealed
            val newFit=source.propose(evidence,config)
            current=newFit.program
            refits++
            record(log,"""{"phase":"REFIT","arm":"ACTIVE_FULL","step":$step,"status":${q(newFit.status.name)},"publicLabelCount":${evidence.size},"splitAttempts":${newFit.splitAttempts}}""")
        }
        return Outcome("ACTIVE_FULL",current,evidence,source,chosen,ledger.newQueries(),0,
            emptyList(),refits,0,stop,(System.nanoTime()-started)/1_000_000)
    }

    private fun replayLocal(
        token:String,initialTrain:List<E1Example>,query:List<E2Unlabelled>,
        ids:List<String>,library:List<CapabilityDefinition>, ledger:MRX2Ledger,log:Path,
    ):Outcome {
        val started=System.nanoTime()
        val source=SD03E2RepoAdapter(library)
        var current=initial(initialTrain,source)
        val evidence=initialTrain.toMutableList()
        val statuses=mutableListOf<String>();var successes=0;var stop="REPLAY_DONE"
        for((step,id) in ids.withIndex()) {
            val program=current
            if(program==null) { stop="NO_INITIAL_PROGRAM"; break }
            val revealed=queryOnce(id,token,"ACTIVE_LOCAL",step,program,evidence,query,ledger,log,source,"MATCHED_ACTIVE_FULL_REPLAY")
            if(revealed==null) {stop="ORACLE_UNAVAILABLE";break}
            evidence+=revealed
            val result=SD03ActiveQueryLocalRepairE2.repair(program,evidence,source.atomCatalog(evidence),revealed.id,config)
            statuses+=result.status.name
            if(result.status==E2RepairStatus.LOCAL_REPAIRED) {current=result.program;successes++}
            record(log,"""{"phase":"LOCAL_REPAIR","arm":"ACTIVE_LOCAL","step":$step,"status":${q(result.status.name)},"changedPath":${result.changedPath?.let(::q) ?: "null"},"ancestorAttempts":${result.ancestorAttempts},"oldEvidenceCorrect":${result.remainingOldEvidenceCorrect},"allPublicCorrect":${result.allKnownEvidenceCorrect},"fullRefitUsed":${result.fullRefitUsed},"oldSubtreeNodes":${result.changedNodesBefore},"newSubtreeNodes":${result.changedNodesAfter},"localPartitionAttempts":${result.localPartitionAttempts},"localPredictionReads":${result.localPredictionReads}}""")
        }
        return Outcome("ACTIVE_LOCAL",current,evidence,source,ids.take(evidence.size-initialTrain.size),
            ledger.newQueries(),0,statuses,0,successes,stop,(System.nanoTime()-started)/1_000_000)
    }
    private fun randomControl(
        token:String,initialTrain:List<E1Example>,query:List<E2Unlabelled>,
        count:Int,seed:Int,library:List<CapabilityDefinition>,ledger:MRX2Ledger,log:Path,
    ):Outcome {
        val started=System.nanoTime();val source=SD03E2RepoAdapter(library)
        val evidence=initialTrain.toMutableList();var current=initial(initialTrain,source)
        val ids=query.shuffled(Random(seed xor 0x5A17)).take(count).map { it.id }
        var refits=0;var stop="RANDOM_MATCHED_COMPLETED";val done=mutableListOf<String>()
        for((step,id) in ids.withIndex()) {
            val program=current
            if(program==null) { stop="NO_CURRENT_PROGRAM"; break }
            val revealed=queryOnce(id,token,"RANDOM",step,program,evidence,query,ledger,log,source,"SEEDED_RANDOM_MATCHED_BUDGET")
            if(revealed==null) {stop="ORACLE_UNAVAILABLE";break}
            done+=id;evidence+=revealed
            val fit=source.propose(evidence,config);current=fit.program;refits++
            record(log,"""{"phase":"REFIT","arm":"RANDOM","step":$step,"status":${q(fit.status.name)},"publicLabelCount":${evidence.size},"splitAttempts":${fit.splitAttempts}}""")
        }
        return Outcome("RANDOM",current,evidence,source,done,ledger.newQueries(),0,emptyList(),refits,0,
            stop,(System.nanoTime()-started)/1_000_000)
    }
    private fun grade(
        item:Outcome,challenge:List<Map<String,String>>,heldout:List<Map<String,String>>,
        truth:(Map<String,String>)->Boolean,library:List<CapabilityDefinition>,
    ):String {
        val body=item.program?.let(item.source::toExpr)
        val eligible=item.program!=null && item.source.certified(item.program,item.public)
        val cost=MRX2Cost()
        var status="NOT_ELIGIBLE";var selected:String?=null;var held:Int?=null
        if(eligible && body!=null) {
            val ledger=MRX2Ledger(challenge,StudyQueryController(challenge,challenge.size,truth),challenge.size,cost)
            val v=ledger.validate(CanonicalJson.expr(body),
                { input-> Pack02Knowledge.predict(body,input,library) },"E2_FINAL_CHALLENGE",challenge.size,cost)
            status=v.status.name
            if(v.status==MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                selected=sha(CanonicalJson.expr(body))
                held=heldout.count { input-> Pack02Knowledge.predict(body,input,library)==truth(input) }
            }
        }
        return """{"arm":${q(item.arm)},"eligible":$eligible,"challengeStatus":${q(status)},"chosenProgramHash":${selected?.let(::q) ?: "null"},"heldoutCorrect":${held ?: "null"},"heldoutTotal":${heldout.size},"challengeOracleNew":${cost.oracleNewQueries},"challengeOracleReuse":${cost.oracleReuseUses},"challengeInterpreterCalls":${cost.interpreterCalls},"queryNew":${item.queryNew},"queryIds":[${item.queryIds.joinToString(",") { q(it) }}],"refits":${item.fullRefits},"localRepairs":${item.localSuccess},"repairStatuses":[${item.repairs.joinToString(",") { q(it) }}],"atomPreviewCalls":${item.source.atomInterpreterCalls},"independentTrainValidationCalls":${item.source.candidateInterpreterCalls},"e1Proposals":${item.source.e1Proposals},"e1PartitionAttempts":${item.source.e1PartitionAttempts},"e1PredictionReads":${item.source.e1PredictionReads},"e1MemoHits":${item.source.e1MemoHits},"wallMillis":${item.elapsedMillis},"queryStopReason":${q(item.queryStopReason)},"completeExecutionSteps":"NOT_INSTRUMENTED"}"""
    }

    @Test fun `E2 active query and matched local repair development matrix`() {
        val tasks=listOf(
            Task("mrx-base-or-r-d1",20261111),
            Task("mrx-depth-w4",20261110),
            Task("mrx-depth-r-xor-b2",20261112),
            Task("mrx-if-nested-3",20261109),
        )
        val library=C8TestBases.bases.library("FULL").definitions
        val dir=Path.of("docs/research/sd03-e2-development-2026-11-07")
        Files.createDirectories(dir)
        val marker=dir.resolve("E2-RUN-STARTED.txt")
        require(!Files.exists(marker)) { "E2 evidence already exists: archive prior run, never overwrite" }
        record(marker,"E2 development experiment started; no Challenge/HELDOUT evaluated")
        var arms=0
        for(task in tasks) {
            val world=MRXWorlds.baseline.single { it.id==task.worldId }
            val shuffled=C8Domain.all().shuffled(Random(task.seed))
            val train=shuffled.take(32).mapIndexed { i,input -> E1Example("t-$i",input,world.truth(input)) }
            val pool=shuffled.drop(32).take(12).mapIndexed { i,input -> "q-$i" to input }
            val challenge=shuffled.drop(44).take(16)
            val heldout=shuffled.drop(60)
            assertEquals(21,heldout.size)
            val token=sha("${task.worldId}|${task.seed}").take(16)
            val eventFile=dir.resolve("EVENTS-$token.jsonl")
            val previewAdapter=SD03E2RepoAdapter(library)
            val query=pool.map { (id,input) -> E2Unlabelled(id,input,previewAdapter.atomPreview(input)) }
            fun newLedger()=MRX2Ledger(pool.map { it.second },
                StudyQueryController(pool.map { it.second },12,world.truth),12,MRX2Cost())
            val start=System.nanoTime();val staticSource=SD03E2RepoAdapter(library)
            val staticProgram=initial(train,staticSource)
            val static=Outcome("STATIC",staticProgram,train,staticSource,emptyList(),0,0,
                emptyList(),0,0,"NO_QUERY",(System.nanoTime()-start)/1_000_000)
            val active=active(token,train,query,library,newLedger(),eventFile)
            val local=replayLocal(token,train,query,active.queryIds,library,newLedger(),eventFile)
            val random=randomControl(token,train,query,active.queryIds.size,task.seed,library,newLedger(),eventFile)
            val runs=listOf(static,random,active,local)
            // Freeze all four candidate ASTs/decisions BEFORE examiner scoring.
            for(run in runs) {
                val body=run.program?.let(run.source::toExpr)
                val canonical=body?.let(CanonicalJson::expr)
                val evidence=run.public.joinToString("|") { "${it.id}:${it.label}" }
                val freezeHash=sha("${run.arm}|$evidence|$canonical")
                record(eventFile,"""{"phase":"FINAL_STUDENT_FROZEN","arm":${q(run.arm)},"token":${q(token)},"programHash":${canonical?.let { q(sha(it)) } ?: "null"},"snapshotHash":${q(freezeHash)},"publicLabels":${run.public.size},"queryCount":${run.queryNew}}""")
                arms++
            }
            // Examiner is the only place where CHALLENGE/HELDOUT truth is read.
            for(run in runs) record(dir.resolve("EXAMINER-AFTER-FREEZE.jsonl"),
                """{"taskToken":${q(token)},"seed":${task.seed},"result":${grade(run,challenge,heldout,world.truth,library)}}""")
            // Matched-query invariant; no same-session label sharing.
            assertEquals(active.queryIds,local.queryIds)
            assertEquals(active.queryNew,local.queryNew)
            assertEquals(active.queryNew,random.queryNew)
        }
        assertEquals(16,arms)
    }
}
