package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
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
 * E2.3-V: new fixed examiner targets revealed AFTER the learner-code freeze.
 * The source code is an implementer-visible evaluator at grading time, hence
 * a delayed-reveal / code-frozen research evaluation, NOT a fully secret
 * independently hosted closed-book examination.
 *
 * Four matched policies; active policies receive identical 4 precommitted
 * input-only query IDs. No adaptive query advantage is being tested here.
 * Student update API sees only public examples and atom evaluations.
 * Examiner truth for CHALLENGE/HELDOUT is read only after all 128 arm outputs
 * have been durably frozen. Do not tune student code on this exam and rerun.
 */
class SD03E23VFreshExaminerTest {
    private data class World(val id: String, val family: String,
        val truth: (Map<String, String>) -> Boolean)
    private fun eq(m:Map<String,String>, k:String, v:String) = m[k]==v
    private val worlds = listOf(
        World("e23v-or-cross","COMPOSITION") { m ->
            (eq(m,"a","2") && eq(m,"b","1")) || eq(m,"d","0") },
        World("e23v-branch-and","BRANCH") { m ->
            if(eq(m,"a","0")) eq(m,"b","2") else (eq(m,"c","1") && eq(m,"d","2")) },
        World("e23v-nested-choice","NESTED") { m ->
            if(eq(m,"c","2")) { if(eq(m,"b","1")) eq(m,"a","0") else eq(m,"d","2") }
            else { if(eq(m,"a","2")) eq(m,"b","0") else eq(m,"c","0") } },
        World("e23v-xor-branches","XOR") { m ->
            (eq(m,"a","0") && eq(m,"b","2")) != (eq(m,"c","2") && eq(m,"d","1")) },
        World("e23v-local-exception","EXCEPTION") { m ->
            (eq(m,"a","2") || eq(m,"d","0")) &&
                !(eq(m,"a","2") && eq(m,"b","1") && eq(m,"c","0")) },
        World("e23v-parity-three","PARITY") { m ->
            eq(m,"a","1") != (eq(m,"b","0") != eq(m,"d","2")) },
        World("e23v-asymmetric-tree","NESTED") { m ->
            if(eq(m,"b","2")) eq(m,"c","0")
            else if(eq(m,"a","1")) eq(m,"d","2") else eq(m,"c","2") },
        World("e23v-composition-not","COMPOSITION") { m ->
            (eq(m,"b","1") || eq(m,"c","2")) && !(eq(m,"a","0") && eq(m,"d","0")) },
    )
    private val cfg=E1Config(maxDepth=4,beamWidthPerNode=5,maxTreeNodes=63,
        maxPartitionAttempts=30_000,minExamplesPerChild=1)
    private val arms=listOf("STATIC","FULL_REFIT","KEEP_IF_AGREE","LOCAL_FIRST")
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(s:String)=sha(s.toByteArray(Charsets.UTF_8))
    private fun q(s:String)=CanonicalJson.string(s)
    private fun opt(s:String?)=s?.let(::q)?:"null"
    private fun bool(b:Boolean?)=b?.toString()?:"null"
    private fun hash(p:E1Program?,adapter:SD03E2RepoAdapter):String?=
        p?.let{sha(CanonicalJson.expr(adapter.toExpr(it)))}
    private fun persist(path:Path,line:String,first:Boolean=false){
        Files.createDirectories(path.parent)
        val ops=if(first) arrayOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
            else arrayOf(StandardOpenOption.CREATE,StandardOpenOption.APPEND,StandardOpenOption.WRITE)
        FileChannel.open(path,*ops).use { ch ->
            val b=ByteBuffer.wrap((line+"\n").toByteArray(Charsets.UTF_8))
            while(b.hasRemaining())ch.write(b)
            ch.force(true)
        }
    }
    private data class Run(val arm:String,val world:String,val family:String,val token:String,
        val seed:Int,val public:List<E1Example>,val initial:E1Program?,val final:E1Program?,
        val adapter:SD03E2RepoAdapter,val received:List<String>,val proposedIds:List<String>,
        val updates:List<E23UpdateResult>,val initialCertified:Boolean,val elapsedMs:Long,
        val queryNew:Int,val failure:String?)
    private fun verifyFreeze() {
        val manifest=Path.of("docs/research/sd03-e23v-2026-11-08/FREEZE.json")
        require(Files.exists(manifest)) { "PHASE A freeze not committed; refusing exam" }
        val root=Json.parseToJsonElement(Files.readString(manifest)).jsonObject
        require(root["schema"]!!.jsonPrimitive.content=="E23V-FREEZE-v1")
        require(root["sourceSha256"]!!.jsonObject.size>=4)
        for((rel,expected) in root["sourceSha256"]!!.jsonObject){
            val path=Path.of(rel)
            require(Files.isRegularFile(path) && sha(Files.readAllBytes(path))==expected.jsonPrimitive.content){
                "FROZEN_LEARNER_SOURCE_DRIFT: $rel; REFUSE scoring" }
        }
        require(root["independentClosedBook"]!!.jsonPrimitive.content=="NOT_CLAIMED")
        require(root["examinerFamilies"]!!.jsonPrimitive.int==8)
        require(root["taskInstances"]!!.jsonPrimitive.int==32)
        println("E23V_FROZEN_LEARNER_VERIFIED "+root["sourceSha256"]!!.jsonObject.size)
    }
    private fun runArm(world:World,seed:Int,token:String,arm:String,
        train:List<E1Example>,query:List<Map<String,String>>,schedule:List<Int>,
        library:List<CapabilityDefinition>,dest:Path):Run{
        val started=System.nanoTime()
        val adapter=SD03E2RepoAdapter(library)
        val currentPublic=train.toMutableList()
        val initialFit=adapter.propose(currentPublic,cfg)
        val first=initialFit.program
        val startCertified=first!=null &&
            initialFit.status==E1Status.TRAIN_CONSISTENT_PROPOSAL && adapter.certified(first,currentPublic)
        var p:E1Program?=if(startCertified) first else null
        val initial=p
        val revealed=mutableListOf<String>()
        val chosen=mutableListOf<String>()
        val updates=mutableListOf<E23UpdateResult>()
        val ledger=MRX2Ledger(query,StudyQueryController(query,query.size,world.truth),
            query.size,MRX2Cost())
        var failure:String?=if(p==null)"NO_CERTIFIED_START" else null
        val plan=schedule.map{"q-$it"}
        persist(dest,"""{"phase":"ARM_PREPARED","task":${q(token)},"arm":${q(arm)},"initialHash":${opt(hash(initial,adapter))},"startCertified":$startCertified,"plannedIds":[${plan.joinToString(","){q(it)}}]}""")
        val policy=when(arm){
            "FULL_REFIT"->E23UpdatePolicy.FULL_REFIT
            "KEEP_IF_AGREE"->E23UpdatePolicy.KEEP_IF_AGREE
            "LOCAL_FIRST"->E23UpdatePolicy.LOCAL_FIRST
            else->null
        }
        if(policy!=null && p!=null) for((step,index) in schedule.withIndex()){
            val old=requireNotNull(p)
            val id="q-$index"
            val pred=SD03EvidenceCompositionE1.predict(old,adapter.atomPreview(query[index]))
            val previous=hash(old,adapter)
            persist(dest,"""{"phase":"QUERY_DECISION_FROZEN","task":${q(token)},"arm":${q(arm)},"step":$step,"queryId":${q(id)},"oldHash":${opt(previous)},"priorPrediction":${bool(pred)},"selector":"SEEDED_INPUT_ONLY"}""")
            // MRX2Ledger requires a nonempty, frozen candidate prediction. If
            // CURRENT is Unknown, use a separately identified constant-zero
            // QUERY probe, NOT a coercion of the student's Unknown to false.
            // The student receives the label; CURRENT remains Unknown in logs.
            val votes=if(pred==null) mapOf("FIXED_PROBE_ZERO" to false) else mapOf("CURRENT" to pred)
            val source=if(pred==null) listOf("FIXED_PROBE_ZERO") else listOf("CURRENT")
            val (label,entry)=ledger.query(query[index],votes,source,"E23V_MATCHED_FIXED_QUERY")
            if(label==null || entry==null){failure="ORACLE_UNAVAILABLE";break}
            persist(dest,"""{"phase":"LABEL_REVEALED","task":${q(token)},"arm":${q(arm)},"step":$step,"queryId":${q(id)},"label":$label,"oldPrediction":${bool(pred)},"currentCounterexample":${if(pred==null)"null" else (pred!=label).toString()}}""")
            currentPublic+=E1Example(id,query[index],label)
            revealed+=id
            val atomCatalog=adapter.atomCatalog(currentPublic)
            val updated=SD03E23EvidenceUpdates.update(policy,old,currentPublic,atomCatalog,id,cfg)
            val next=updated.program
            if(updated.status==E23UpdateStatus.LOCAL_REPAIRED)
                assertTrue(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(old,next,updated.localChangedPath))
            if(updated.status==E23UpdateStatus.CONFIRM_KEEP)assertEquals(old,next)
            val certified=adapter.certified(next,currentPublic)
            p=next
            chosen+=id
            updates+=updated
            persist(dest,"""{"phase":"UPDATE_FROZEN","task":${q(token)},"arm":${q(arm)},"step":$step,"status":${q(updated.status.name)},"programHash":${opt(hash(p,adapter))},"candidateCertified":$certified,"changed":${updated.programChanged},"fullRefitCalls":${updated.fullRefitCalls},"localAttempts":${updated.localAttempts},"localSplitAttempts":${updated.localSplitAttempts}}""")
        }
        val elapsed=(System.nanoTime()-started)/1_000_000
        persist(dest,"""{"phase":"FINAL_STUDENT_FROZEN","task":${q(token)},"arm":${q(arm)},"seed":$seed,"family":${q(world.family)},"plannedIds":[${plan.joinToString(","){q(it)}}],"revealedIds":[${revealed.joinToString(","){q(it)}}],"initialHash":${opt(hash(initial,adapter))},"finalHash":${opt(hash(p,adapter))},"failure":${opt(failure)},"startCertified":$startCertified,"wallMillis":$elapsed,"atomInterpreterCalls":${adapter.atomInterpreterCalls},"realCandidateValidationCalls":${adapter.candidateInterpreterCalls},"e1Proposals":${adapter.e1Proposals},"e1SplitAttempts":${adapter.e1PartitionAttempts},"e1PredictionReads":${adapter.e1PredictionReads}}""")
        return Run(arm,world.id,world.family,token,seed,currentPublic.toList(),initial,p,adapter,
            revealed.toList(),plan,updates,startCertified,elapsed,ledger.newQueries(),failure)
    }
    private data class Pending(val task:String,val world:World,val challenge:List<Map<String,String>>,
        val heldout:List<Map<String,String>>,val arms:List<Run>)
    private fun grade(pending:Pending,library:List<CapabilityDefinition>,gradeFile:Path){
        val world=pending.world
        val challenge=pending.challenge
        val heldout=pending.heldout
        for(run in pending.arms){
            val candidate=run.final?.let {run.adapter.toExpr(it)}
            val initialBody=run.initial?.let{run.adapter.toExpr(it)}
            val eligible=run.final!=null && run.adapter.certified(run.final,run.public)
            val meter=MRX2Cost()
            var status="NOT_ELIGIBLE"
            var correct:Int?=null
            if(eligible && candidate!=null){
                val ledger=MRX2Ledger(challenge,StudyQueryController(challenge,challenge.size,world.truth),
                    challenge.size,meter)
                val result=ledger.validate(CanonicalJson.expr(candidate),
                    {input->Pack02Knowledge.predict(candidate,input,library)},
                    "E23V_EXAMINER_AFTER_ALL_FROZEN",challenge.size,meter)
                status=result.status.name
                if(result.status==MRX2ValidationStatus.PASS_ON_CHALLENGE)
                    correct=heldout.count { Pack02Knowledge.predict(candidate,it,library)==world.truth(it) }
            }
            // Diagnostic counterfactual retained/gained correct Challenge examples;
            // grader-only AFTER all four arms across all 32 tasks have frozen.
            val lost=if(initialBody!=null && candidate!=null)challenge.count{
                Pack02Knowledge.predict(initialBody,it,library)==world.truth(it) &&
                    Pack02Knowledge.predict(candidate,it,library)!=world.truth(it)
            }else null
            val gained=if(initialBody!=null && candidate!=null)challenge.count{
                Pack02Knowledge.predict(initialBody,it,library)!=world.truth(it) &&
                    Pack02Knowledge.predict(candidate,it,library)==world.truth(it)
            }else null
            val full=run.updates.sumOf{it.fullRefitCalls}
            val local=run.updates.count{it.status==E23UpdateStatus.LOCAL_REPAIRED}
            val confirming=run.updates.count{it.status==E23UpdateStatus.CONFIRM_KEEP}
            val localAttempts=run.updates.sumOf{it.localAttempts}
            val localSplits=run.updates.sumOf{it.localSplitAttempts}
            val fullSplits=run.updates.sumOf{it.fullRefitSplitAttempts}
            persist(gradeFile,"""{"task":${q(run.token)},"world":${q(run.world)},"family":${q(run.family)},"seed":${run.seed},"arm":${q(run.arm)},"eligible":$eligible,"challengeStatus":${q(status)},"heldoutCorrect":${correct?:"null"},"heldoutTotal":${heldout.size},"queryCount":${run.received.size},"queryNew":${run.queryNew},"plannedIds":[${run.proposedIds.joinToString(","){q(it)}}],"revealedIds":[${run.received.joinToString(","){q(it)}}],"fullRefitCalls":$full,"localRepairs":$local,"confirmingKeeps":$confirming,"localAttempts":$localAttempts,"localSplitAttempts":$localSplits,"fullSplitAttempts":$fullSplits,"programChanges":${run.updates.count{it.programChanged}},"lostPreviouslyCorrectChallenge":${lost?:"null"},"gainedCorrectChallenge":${gained?:"null"},"atomInterpreterCalls":${run.adapter.atomInterpreterCalls},"candidateValidationCalls":${run.adapter.candidateInterpreterCalls},"e1Proposals":${run.adapter.e1Proposals},"e1SplitAttempts":${run.adapter.e1PartitionAttempts},"e1PredictionReads":${run.adapter.e1PredictionReads},"wallMillis":${run.elapsedMs},"challengeOracleCalls":${meter.oracleNewQueries}}""")
        }
    }
    @Test fun `E23V thirty two new targets four matched frozen policies`() {
        verifyFreeze() // BEFORE computing any TRAIN label; never bypass
        require(worlds.size==8 && worlds.map{it.id}.distinct().size==8)
        require(arms==listOf("STATIC","FULL_REFIT","KEEP_IF_AGREE","LOCAL_FIRST"))
        val lib=C8TestBases.bases.library("FULL").definitions
        val build=Path.of("build");Files.createDirectories(build)
        val output=Files.createTempDirectory(build,"sd03-e23v-")
        println("E23V_OUTPUT_DIR="+output.toAbsolutePath())
        val pending=mutableListOf<Pending>()
        val tasks=mutableSetOf<String>()
        for((ix,world) in worlds.withIndex())for(repeatIndex in 0 until 4){
            val seed=320_000+ix*1009+repeatIndex*29
            val task=sha("E23V|${world.id}|$seed").take(16)
            require(tasks.add(task))
            val ordered=C8Domain.all().shuffled(Random(seed))
            require(ordered.size==81)
            val train=ordered.take(32).mapIndexed { i,m -> E1Example("t-$i",m,world.truth(m)) }
            val query=ordered.drop(32).take(12)
            val challenge=ordered.drop(44).take(16)
            val heldout=ordered.drop(60)
            assertEquals(21,heldout.size)
            val schedule=(0 until 12).shuffled(Random(seed xor 0x0E24024)).take(4)
            val student=output.resolve("STUDENT-$task.jsonl")
            persist(student,"""{"phase":"TASK_FROZEN","task":${q(task)},"seed":$seed,"family":${q(world.family)},"plannedQueryIds":[${schedule.joinToString(","){q("q-$it")}}],"split":"32/12/16/21","queryBudget":4}""",true)
            val runs=arms.map{arm->runArm(world,seed,task,arm,train,query,schedule,lib,student)}
            assertEquals(4,runs.size)
            assertTrue(runs.map{it.initialCertified}.distinct().size==1)
            assertTrue(runs.map{it.proposedIds}.distinct().size==1)
            for(run in runs.filter{it.arm!="STATIC" && it.initialCertified})
                assertEquals(run.proposedIds,run.received,"Equal matched queries required")
            pending+=Pending(task,world,challenge,heldout,runs)
        }
        assertEquals(32,pending.size)
        assertEquals(128,pending.sumOf{it.arms.size})
        // Important barrier: no examiner Challenge/HELDOUT call precedes this line.
        persist(output.resolve("ALL_STUDENTS_FROZEN.txt"),
            "32 task instances / 128 arms frozen before examiner truth",true)
        val gradeFile=output.resolve("EXAMINER-AFTER-ALL-FREEZE.jsonl")
        for(p in pending)grade(p,lib,gradeFile)
        assertEquals(128,Files.readAllLines(gradeFile).size)
        println("E23V_EXAMINER_DONE tasks=32 armGrades=128 output="+output.toAbsolutePath())
    }
}
