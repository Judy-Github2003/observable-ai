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
 * E2.3 PUBLIC DEVELOPMENT REPLAY, NOT independent closed-book generalization.
 * All three E23 policies receive the same precommitted 11 E22 input IDs, in the
 * same order, with fresh arm-specific QUERY ledgers. Previous E22 label logs,
 * challenge scores and gold target are never read by the student updater.
 * Existing challenge/heldout truth appears only in examiner code after ALL arms
 * have been durably frozen for each task.
 */
class SD03E23DevelopmentIntegrationTest {
    private data class Task(val world:String,val seed:Int,val token:String,
        val replay:List<String>,val historicalE22FullHash:String)
    private data class Outcome(val arm:String,val tree:E1Program?,val public:List<E1Example>,
        val adapter:SD03E2RepoAdapter,val ids:List<String>,val outcomes:List<E23UpdateResult>,
        val initialHash:String?,val finalHash:String?,val queryNew:Int)
    // A single immutable-source-derived manifest replaces the previous duplicated
    // hard-coded task/token/query/hash table. It is validated BEFORE any Oracle call.
    private fun loadFrozenTasks(): List<Task> {
        val planPath = Path.of("config/e23-frozen-replay-plan.json")
        val root = Json.parseToJsonElement(Files.readString(planPath)).jsonObject
        require(root["schemaVersion"]!!.jsonPrimitive.int == 2) { "E23-R plan schema mismatch" }
        require(root["replayQueryCount"]!!.jsonPrimitive.int == 11)
        require(root["independentClosedBook"]!!.jsonPrimitive.content == "NOT_RUN")
        val rows = root["tasks"]!!.jsonArray
        require(rows.size == 8) { "Eight previously committed task-seeds required" }
        val loaded = rows.map { entry ->
            val o = entry.jsonObject
            val world = o["world"]!!.jsonPrimitive.content
            val seed = o["seed"]!!.jsonPrimitive.int
            val token = o["taskToken"]!!.jsonPrimitive.content
            val ids = o["queryIds"]!!.jsonArray.map { it.jsonPrimitive.content }
            val finalHash = o["expectedE22FullRefitHash"]!!.jsonPrimitive.content
            val sourceHash = o["sourceEventSha256"]!!.jsonPrimitive.content
            require(token == hash("$world|$seed").take(16)) { "Token mismatched world+seed" }
            require(ids.size <= 4 && ids.distinct() == ids)
            require(ids.all { it in (0..11).map { i -> "q-$i" } })
            require(Regex("[0-9a-f]{64}").matches(finalHash))
            require(Regex("[0-9a-f]{64}").matches(sourceHash))
            Task(world, seed, token, ids, finalHash)
        }
        val expected = listOf(
            "mrx-base-or-r-d1" to 20261111,
            "mrx-depth-w4" to 20261110,
            "mrx-depth-r-xor-b2" to 20261112,
            "mrx-if-nested-3" to 20261109,
            "mrx-base-or-r-d1" to 20261121,
            "mrx-depth-w4" to 20261122,
            "mrx-depth-r-xor-b2" to 20261123,
            "mrx-if-nested-3" to 20261124,
        )
        require(loaded.map { it.world to it.seed } == expected) {
            "Frozen task list changed; no after-the-fact task substitution permitted"
        }
        require(loaded.map { it.token }.distinct().size == 8)
        require(loaded.sumOf { it.replay.size } == 11)
        return loaded
    }
    private val tasks by lazy { loadFrozenTasks() }
    private val cfg=E1Config(maxDepth=4,beamWidthPerNode=5,maxTreeNodes=63,
        maxPartitionAttempts=30_000,minExamplesPerChild=1)
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun q(s:String)=CanonicalJson.string(s)
    private fun qs(s:String?)=s?.let(::q) ?: "null"
    private fun h(t:E1Program?,adapter:SD03E2RepoAdapter)=
        t?.let { hash(CanonicalJson.expr(adapter.toExpr(it))) }
    private fun record(path:Path,line:String) {
        Files.createDirectories(path.parent)
        FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,
            StandardOpenOption.APPEND).use { channel ->
            val bb=ByteBuffer.wrap((line+"\n").toByteArray(Charsets.UTF_8))
            while(bb.hasRemaining()) channel.write(bb)
            channel.force(true)
        }
    }
    private fun replay(
        spec:Task,arm:String,train:List<E1Example>,query:List<Map<String,String>>,
        library:List<CapabilityDefinition>,truth:(Map<String,String>)->Boolean,events:Path,
    ):Outcome {
        val adapter=SD03E2RepoAdapter(library)
        val evidence=train.toMutableList()
        var current=adapter.propose(evidence,cfg).program
        val initialHash=h(current,adapter)
        val updates=ArrayList<E23UpdateResult>()
        val issued=ArrayList<String>()
        val ledger=MRX2Ledger(query,StudyQueryController(query,query.size,truth),query.size,MRX2Cost())
        val policy=when(arm) {
            "FULL_REFIT" -> E23UpdatePolicy.FULL_REFIT
            "KEEP_IF_AGREE" -> E23UpdatePolicy.KEEP_IF_AGREE
            "LOCAL_FIRST" -> E23UpdatePolicy.LOCAL_FIRST
            else -> null
        }
        if(policy!=null) for((step,id) in spec.replay.withIndex()) {
            val p=current ?: break
            val index=id.removePrefix("q-").toIntOrNull()
                ?: error("Invalid frozen E22 replay ID: $id")
            require(id=="q-$index" && index in query.indices)
            val input=query[index]
            val preview=adapter.atomPreview(input)
            val oldPrediction=SD03EvidenceCompositionE1.predict(p,preview)
            if(oldPrediction==null) {
                record(events,"""{"phase":"ABSTAIN","task":${q(spec.token)},"arm":${q(arm)},"step":$step,"reason":"UNKNOWN_CURRENT"}""")
                break
            }
            val beforeHash=h(p,adapter)
            val digest=hash("${spec.token}|$arm|$step|$id|$beforeHash|${evidence.joinToString { it.id+":"+it.label }}")
            record(events,"""{"phase":"QUERY_DECISION_FROZEN","task":${q(spec.token)},"arm":${q(arm)},"step":$step,"queryId":${q(id)},"previousHash":${qs(beforeHash)},"freezeHash":${q(digest)},"publicCount":${evidence.size},"queryOrigin":"E22_PRECOMMITTED_INPUT_ONLY"}""")
            val (label,entry)=ledger.query(input,mapOf("CURRENT" to oldPrediction),
                listOf("CURRENT"),"E23_MATCHED_REPLAY")
            if(label==null || entry==null) {
                record(events,"""{"phase":"ABSTAIN","task":${q(spec.token)},"arm":${q(arm)},"step":$step,"reason":"NO_ORACLE"}""")
                break
            }
            record(events,"""{"phase":"LABEL_REVEALED","task":${q(spec.token)},"arm":${q(arm)},"step":$step,"queryId":${q(id)},"label":$label,"priorPrediction":$oldPrediction,"currentCounterexample":${label!=oldPrediction},"freezeHash":${q(digest)},"ledgerIndex":${entry.queryIndex}}""")
            evidence+=E1Example(id,input,label)
            issued+=id
            val publicAtoms=adapter.atomCatalog(evidence)
            val update=SD03E23EvidenceUpdates.update(policy,p,evidence,publicAtoms,id,cfg)
            var proposed=update.program
            val certified=adapter.certified(proposed,evidence)
            // NO promotion of a program that fails the real Capability evaluator.
            // Preserve proposal for final grade as ineligible; never report success.
            if(update.status==E23UpdateStatus.LOCAL_REPAIRED) {
                assertTrue(SD03E21LocalIntegrity.exactlyOneSubtreeReplaced(
                    p,proposed,update.localChangedPath),"claimed local repair changed sibling or root")
            }
            if(update.status==E23UpdateStatus.CONFIRM_KEEP) assertEquals(p,proposed)
            current=proposed
            updates+=update
            record(events,"""{"phase":"UPDATE_FROZEN","task":${q(spec.token)},"arm":${q(arm)},"step":$step,"policy":${q(policy.name)},"status":${q(update.status.name)},"programHash":${qs(h(proposed,adapter))},"changed":${update.programChanged},"confirmed":${update.confirmed ?: "null"},"localPath":${qs(update.localChangedPath)},"localStatus":${qs(update.localStatus?.name)},"fullRefitCalls":${update.fullRefitCalls},"localAttempts":${update.localAttempts},"localPartitionAttempts":${update.localSplitAttempts},"fullPartitionAttempts":${update.fullRefitSplitAttempts},"realInterpreterCertified":$certified}""")
        }
        val finalHash=h(current,adapter)
        record(events,"""{"phase":"FINAL_STUDENT_FROZEN","task":${q(spec.token)},"arm":${q(arm)},"queryIds":[${issued.joinToString(","){q(it)}}],"publicCount":${evidence.size},"initialHash":${qs(initialHash)},"finalHash":${qs(finalHash)},"programCanonical":${current?.let{q(CanonicalJson.expr(adapter.toExpr(it)))}?:"null"},"atomInterpreterCalls":${adapter.atomInterpreterCalls},"candidateValidationCalls":${adapter.candidateInterpreterCalls},"e1Proposals":${adapter.e1Proposals},"e1PartitionAttempts":${adapter.e1PartitionAttempts},"e1PredictionReads":${adapter.e1PredictionReads},"replaySource":"E22_FROZEN"}""")
        return Outcome(arm,current,evidence,adapter,issued,updates,initialHash,finalHash,ledger.newQueries())
    }
    private fun grade(run:Outcome,world:(Map<String,String>)->Boolean,
        challenge:List<Map<String,String>>,heldout:List<Map<String,String>>,
        library:List<CapabilityDefinition>,task:String,out:Path) {
        val candidate=run.tree?.let(run.adapter::toExpr)
        val eligible=run.tree!=null && run.adapter.certified(run.tree,run.public)
        val cost=MRX2Cost()
        var status="NOT_ELIGIBLE"
        var correct:Int?=null
        if(eligible && candidate!=null) {
            val ledger=MRX2Ledger(challenge,StudyQueryController(challenge,challenge.size,world),
                challenge.size,cost)
            val verdict=ledger.validate(CanonicalJson.expr(candidate),
                { input -> Pack02Knowledge.predict(candidate,input,library) },
                "E23_AFTER_FREEZE",challenge.size,cost)
            status=verdict.status.name
            if(verdict.status==MRX2ValidationStatus.PASS_ON_CHALLENGE)
                correct=heldout.count { Pack02Knowledge.predict(candidate,it,library)==world(it) }
        }
        record(out,"""{"task":${q(task)},"arm":${q(run.arm)},"eligible":$eligible,"challengeStatus":${q(status)},"heldoutCorrect":${correct ?: "null"},"heldoutTotal":${heldout.size},"queryNew":${run.queryNew},"queryCount":${run.ids.size},"confirmedKeeps":${run.outcomes.count{it.status==E23UpdateStatus.CONFIRM_KEEP}},"programChanges":${run.outcomes.count{it.programChanged}},"fullRefitCalls":${run.outcomes.sumOf{it.fullRefitCalls}},"localRepairSuccesses":${run.outcomes.count{it.status==E23UpdateStatus.LOCAL_REPAIRED}},"localFallbackCalls":${run.outcomes.count{it.status==E23UpdateStatus.LOCAL_FAILED_FULL_FALLBACK||it.status==E23UpdateStatus.LOCAL_UNAVAILABLE_FULL_FALLBACK}},"localSplitAttempts":${run.outcomes.sumOf{it.localSplitAttempts}},"fullSplitAttempts":${run.outcomes.sumOf{it.fullRefitSplitAttempts}},"atomCalls":${run.adapter.atomInterpreterCalls},"challengeOracleCalls":${cost.oracleNewQueries},"challengeInterpreterCalls":${cost.interpreterCalls},"initialHash":${qs(run.initialHash)},"finalHash":${qs(run.finalHash)}}""")
    }
    @Test fun `E23 matched update policies replay frozen E22 queries`() {
        val library=C8TestBases.bases.library("FULL").definitions
        val output=Files.createTempDirectory(Path.of("build").also{Files.createDirectories(it)},"sd03-e23-")
        println("E23_OUTPUT_DIR="+output.toAbsolutePath())
        data class PendingGrade(
            val runs: List<Outcome>,
            val truth: (Map<String,String>) -> Boolean,
            val challenge: List<Map<String,String>>,
            val heldout: List<Map<String,String>>,
            val library: List<CapabilityDefinition>,
            val token: String,
        )
        val pendingGrades = ArrayList<PendingGrade>()
        for (spec in tasks) {
            val world=MRXWorlds.baseline.single{it.id==spec.world}
            val all=C8Domain.all().shuffled(Random(spec.seed))
            val train=all.take(32).mapIndexed{i,v->E1Example("t-$i",v,world.truth(v))}
            val query=all.drop(32).take(12)
            val challenge=all.drop(44).take(16)
            val heldout=all.drop(60)
            assertEquals(21,heldout.size)
            val token=hash(spec.world+"|"+spec.seed).take(16)
            assertEquals(spec.token,token,"E22 preregistered replay token drift")
            require(spec.replay.size<=4 && spec.replay.distinct().size==spec.replay.size)
            val events=output.resolve("STUDENT-$token.jsonl")
            val runs=listOf("STATIC","FULL_REFIT","KEEP_IF_AGREE","LOCAL_FIRST")
                .map{arm->replay(spec,arm,train,query,library,world.truth,events)}
            assertTrue(runs.map{it.initialHash}.distinct().size==1)
            val full=runs.single{it.arm=="FULL_REFIT"}
            // Replay fidelity: same frozen E22 labels + same E1 model => same AST.
            assertEquals(spec.historicalE22FullHash,full.finalHash,
                "E22 baseline replay mismatch; DO NOT compare strategies until diagnosed")
            for (r in runs.filter{it.arm!="STATIC"}) {
                assertEquals(spec.replay,r.ids)
                assertEquals(spec.replay.size,r.queryNew)
            }
            pendingGrades += PendingGrade(runs, world.truth, challenge, heldout, library, token)
        }
        // NO hidden-label grading until EVERY task's historical FULL_REFIT hash
        // has been reproduced exactly. A failure earlier in the loop leaves no grades.
        assertEquals(8,pendingGrades.size)
        val grades=output.resolve("EXAMINER-AFTER-ALL-FREEZE.jsonl")
        for (job in pendingGrades) {
            for (r in job.runs) grade(r,job.truth,job.challenge,job.heldout,
                job.library,job.token,grades)
        }
        assertEquals(8,tasks.size)
    }
}
