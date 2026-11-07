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
 * SD03 E2.2 PUBLIC DEVELOPMENT ONLY. Every STUDENT control uses input-only pool
 * and public labels. Examiner truth for QUERY opportunity and CHALLENGE is
 * accessed exclusively after every student's FINAL_FROZEN on a given task-seed.
 * E2.2 conjectures are independently checked on all public labels BEFORE voting;
 * they are not promoted to registered capabilities.
 */
class SD03E22DevelopmentIntegrationTest {
    private data class Task(val name: String, val seed: Int)
    private data class Query(val u: E2Unlabelled, val fields: Map<String,String>)
    private data class ModelInfo(
        val id:String, val tree:E1Program, val certified:Boolean, val origin:String,
        val supportingPublicIds:List<String> = emptyList(),
        val oppositeQueryIds:List<String> = emptyList(),
        val leafPath:String? = null, val splitAtomId:String? = null,
    )
    private data class QuerySnapshot(
        val arm:String, val step:Int, val selected:String?, val reason:String,
        val programHash:String, val snapshotHash:String, val rows:List<E22QueryRow>,
        val eligibleModels:Int, val excludedModels:Int, val conjectureCount:Int,
    )
    private data class ArmRun(
        val arm:String, val program:E1Program?, val initial:E1Program?,
        val public:List<E1Example>, val source:SD03E2RepoAdapter,
        val queried:List<String>, val beforeReveals:List<Boolean?>,
        val snapshots:List<QuerySnapshot>, val stop:String,
        val queryNew:Int, val queryReuse:Int,
    )
    private val e1cfg=E1Config(maxDepth=4,beamWidthPerNode=5,maxTreeNodes=63,
        maxPartitionAttempts=30_000,minExamplesPerChild=1)
    private val e22cfg=E22Config(maxConjectures=64,maxDepth=5,maxNodes=65,minLeafSupport=1)
    private val tasks = listOf(
        Task("mrx-base-or-r-d1",20261111), Task("mrx-depth-w4",20261110),
        Task("mrx-depth-r-xor-b2",20261112), Task("mrx-if-nested-3",20261109),
        Task("mrx-base-or-r-d1",20261121), Task("mrx-depth-w4",20261122),
        Task("mrx-depth-r-xor-b2",20261123), Task("mrx-if-nested-3",20261124),
    )
    private fun sha(s:String)=MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it) }
    private fun q(s:String)=CanonicalJson.string(s)
    private fun j(s:String?)=s?.let(::q)?:"null"
    private fun bool(v:Boolean?)=v?.toString()?:"null"
    private fun append(path:Path,record:String,createNew:Boolean=false) {
        Files.createDirectories(path.parent)
        FileChannel.open(path,*if(createNew) arrayOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
            else arrayOf(StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)).use { ch ->
            val buffer=ByteBuffer.wrap((record+"\n").toByteArray(Charsets.UTF_8))
            while(buffer.hasRemaining()) ch.write(buffer)
            ch.force(true)
        }
    }
    private fun treeHash(t:E1Program?,src:SD03E2RepoAdapter)=
        t?.let { sha(CanonicalJson.expr(src.toExpr(it))) }
    private fun publicHash(train:List<E1Example>)=sha(train.joinToString("|") {e->
        e.id+":"+e.label+":"+e.fields.toSortedMap().entries.joinToString(","){it.key+"="+it.value}
    })
    private fun toJsonRows(rows:List<E22QueryRow>):String=rows.joinToString(",", "[", "]") {r->
        val votes=r.votes.joinToString(",","[","]"){v->
            """{"model":${q(v.modelId)},"prediction":${bool(v.predicted)}}"""
        }
        """{"id":${q(r.queryId)},"currentPrediction":${bool(r.currentPrediction)},"true":${r.trueVotes},"false":${r.falseVotes},"unknown":${r.unknownVotes},"score":${r.disagreementScore},"leafSupport":${r.leafTrainingSupport?:"null"},"rank":${r.rank?:"null"},"reason":${q(r.reason.name)},"selected":${r.selected},"votes":$votes}"""
    }
    private fun legacyRows(
        current:E1Program, models:List<ModelInfo>, pool:List<Query>,
        queried:Set<String>, choice:String?, public:List<E1Example>, atoms:List<E1Atom>,
        strict:Boolean=false, random:Boolean=false,
    ):List<E22QueryRow> {
        data class Draft(val query:Query,val cur:Boolean?,val votes:List<E22Vote>,val t:Int,val f:Int,val unk:Int,val support:Int?)
        val publicPredictions=atoms.associate {it.id to it.predictions}
        fun support(input:E2Unlabelled):Int? {
            var cursor=current
            val same=public.toMutableList()
            while(cursor is E1Program.Branch){
                val atom=input.atomPredictions[cursor.atomId]?:return null
                val p=publicPredictions[cursor.atomId]?:return null
                same.removeAll{p[it.id]!=atom}
                cursor=if(atom)cursor.whenTrue else cursor.whenFalse
            }
            return same.size
        }
        val draft=pool.sortedBy{it.u.id}.map{u->
            val votes=models.map{m->E22Vote(m.id,SD03EvidenceCompositionE1.predict(m.tree,u.u.atomPredictions))}
            val currentPrediction=SD03EvidenceCompositionE1.predict(current,u.u.atomPredictions)
            val t=votes.count{it.predicted==true};val f=votes.count{it.predicted==false}
            Draft(u,currentPrediction,votes,t,f,votes.size-t-f,support(u.u))
        }
        val ranked=draft.filter{it.t>0&&it.f>0&&it.query.u.id !in queried&&it.cur!=null}
            .sortedWith(if(strict) compareByDescending<Draft>{minOf(it.t,it.f)}
                .thenByDescending{it.t+it.f}.thenBy{it.query.u.id}
                else compareByDescending<Draft>{minOf(it.t,it.f)}
                    .thenBy{it.support?:Int.MAX_VALUE}.thenBy{it.query.u.id})
            .mapIndexed{i,d->d.query.u.id to (i+1)}.toMap()
        return draft.map { d->
            val reason=when {
                d.query.u.id in queried->E22RowReason.ALREADY_QUERIED
                random && d.query.u.id==choice->E22RowReason.RANDOM_PRECOMMITTED
                d.cur==null->E22RowReason.UNKNOWN_CURRENT
                d.t==0||d.f==0->E22RowReason.NO_DISAGREEMENT
                else->E22RowReason.ELIGIBLE_DISAGREEMENT
            }
            E22QueryRow(d.query.u.id,d.cur,d.votes,d.t,d.f,d.unk,minOf(d.t,d.f),
                d.support,ranked[d.query.u.id],reason,d.query.u.id==choice)
        }
    }
    private fun ledger(pool:List<Query>,truth:(Map<String,String>)->Boolean):MRX2Ledger {
        val inputs=pool.map{it.fields}
        return MRX2Ledger(inputs, StudyQueryController(inputs,inputs.size,truth),inputs.size,MRX2Cost())
    }
    private fun runArm(
        token:String,arm:String,train:List<E1Example>,pool:List<Query>,
        library:List<CapabilityDefinition>,oracle:MRX2Ledger,
        studentFile:Path,randomSeed:Int,
    ):ArmRun {
        val src=SD03E2RepoAdapter(library)
        val disclosed=train.toMutableList()
        var current=src.propose(train,e1cfg).program
        val initial=current
        val chosen=mutableListOf<String>()
        val before=mutableListOf<Boolean?>()
        val snapshots=mutableListOf<QuerySnapshot>()
        val randomOrder=pool.map{it.u.id}.shuffled(Random(randomSeed xor 0x52E22))
        var stop="QUERY_BUDGET_4_EXHAUSTED"
        if(arm=="STATIC") stop="NO_QUERY"
        if(arm!="STATIC") for(step in 0 until 4) {
            val p=current
            if(p==null){stop="NO_CURRENT_PROGRAM";break}
            val publicAtoms=src.atomCatalog(disclosed)
            val remaining=pool.filterNot{it.u.id in chosen}.map{it.u}
            val modelInfo=mutableListOf<ModelInfo>()
            var strategyChoice:String?=null
            var reason=""
            var excluded=0
            var conjectures=0
            var diagnostic=""
            val rows:List<E22QueryRow>
            if(arm=="E22_CONJECTURE") {
                val built=SD03E22ConsistentConjectures.generate(p,disclosed,publicAtoms,remaining,e22cfg)
                // Independent Capability interpreter + typechecker validates every
                // hypothesis. Fail closed; do NOT pass an unverified model to votes.
                val certified=built.alternatives.filter{src.certified(it.program,disclosed)}
                excluded=built.alternatives.size-certified.size
                conjectures=certified.size
                val baseCertified=src.certified(p,disclosed)
                modelInfo+=ModelInfo("CURRENT",p,baseCertified,"CURRENT")
                modelInfo+=certified.map{ModelInfo(it.id,it.program,true,"LEAF_COUNTERFACTUAL",
                    it.supportingPublicIds,it.oppositeQueryIds,it.leafPath,it.splitAtomId)}
                if (built.status==E22BuildStatus.BASE_NOT_TRAIN_CERTIFIED || !baseCertified) {
                    strategyChoice=null
                    reason="BASE_NOT_PUBLIC_CERTIFIED_ABSTAIN"
                    rows=legacyRows(p,listOf(ModelInfo("CURRENT",p,false,"CURRENT")),pool,
                        chosen.toSet(),null,disclosed,publicAtoms)
                } else {
                    val matrix=SD03E22ConsistentConjectures.matrix(p,certified,pool.map{it.u},
                        chosen.toSet(),disclosed,publicAtoms)
                    strategyChoice=matrix.selectedQueryId
                    reason=matrix.decisionReason
                    rows=matrix.rows
                }
                diagnostic="""{"buildStatus":${q(built.status.name)},"attemptedAtomLeafPairs":${built.testedAtomLeafPairs},"rawDistinctConjectures":${built.possibleBeforeTruncation},"selectedConjectures":${certified.size},"rejectedInterpreterCheck":$excluded,"unknown":${built.rejectedUnknown},"nonconstant":${built.rejectedNonconstant},"noOppositeQuery":${built.rejectedNoOppositeQuery},"structuralBudget":${built.rejectedStructuralBudget},"trainCertificateFailed":${built.rejectedNotTrainCertified}}"""
            } else if(arm=="RANDOM_FIXED_4") {
                modelInfo+=ModelInfo("CURRENT",p,src.certified(p,disclosed),"CURRENT")
                strategyChoice=randomOrder.firstOrNull{it !in chosen}
                reason="PRECOMMITTED_SEEDED_RANDOM"
                rows=legacyRows(p,modelInfo,pool,chosen.toSet(),strategyChoice,disclosed,publicAtoms,
                    random=true)
            } else {
                val rivals=src.competingModels(disclosed,p,e1cfg)
                val publicMap=disclosed.associate { e->e.id to publicAtoms.associate { a->a.id to a.predictions[e.id] } }
                val rankedRivals=rivals.distinctBy{it.program}.sortedBy{it.evidenceId}
                modelInfo+=rankedRivals.map{m->ModelInfo(m.evidenceId,m.program,
                    disclosed.all{e->SD03EvidenceCompositionE1.predict(m.program,publicMap.getValue(e.id))==e.label},
                    "E2_SUBSET_OR_SHALLOW")}
                if(arm=="E2_LEGACY"){
                    val d=SD03ActiveQueryLocalRepairE2.choose(remaining,rivals,p,disclosed,publicAtoms)
                    strategyChoice=d.queryId;reason=d.reason
                }else {
                    val d=SD03E21EvidenceQuery.select(E21QueryPolicy.TRAIN_CONSISTENT_VERSIONSPACE,
                        remaining,rivals,p,disclosed,publicAtoms)
                    strategyChoice=d.queryId;reason=d.reason
                }
                excluded=modelInfo.count{!it.certified}
                rows=legacyRows(p,if(arm=="E21_STRICT"){
                    (modelInfo.filter{it.certified}+ModelInfo("CURRENT_CERTIFIED",p,
                        src.certified(p,disclosed),"CURRENT")).distinctBy{it.tree}
                }else modelInfo,pool,chosen.toSet(),strategyChoice,disclosed,publicAtoms,
                    strict=arm=="E21_STRICT")
            }
            // The matrix contains ALL query inputs, including excluded and already
            // disclosed rows. Every model vote is saved BEFORE label revelation.
            val proposals=modelInfo.joinToString(",","[","]") {m->
                """{"id":${q(m.id)},"programCanonical":${q(CanonicalJson.expr(src.toExpr(m.tree)))},"programHash":${j(treeHash(m.tree,src))},"publicCertified":${m.certified},"origin":${q(m.origin)},"supportingPublicIds":[${m.supportingPublicIds.joinToString(","){q(it)}}],"oppositeUnlabelledQueryIds":[${m.oppositeQueryIds.joinToString(","){q(it)}}],"leafPath":${j(m.leafPath)},"splitAtomId":${j(m.splitAtomId)}}"""
            }
            val programHash=treeHash(p,src)?:"MISSING"
            val payload="""{"task":${q(token)},"arm":${q(arm)},"step":$step,"programHash":${q(programHash)},"publicDigest":${q(publicHash(disclosed))},"knownLabels":${disclosed.size},"selectedQuery":${j(strategyChoice)},"decisionReason":${q(reason)},"allQueryRows":${toJsonRows(rows)},"models":$proposals,"excludedUncertified":$excluded,"conjectureCount":$conjectures,"diagnostics":${diagnostic.ifBlank{"null"}}}"""
            val snapHash=sha(payload)
            append(studentFile,"""{"phase":"FULL_MATRIX_FROZEN","sha256":${q(snapHash)},"payload":$payload}""")
            append(studentFile,"""{"phase":"QUERY_DECISION_FROZEN","arm":${q(arm)},"task":${q(token)},"step":$step,"queryId":${j(strategyChoice)},"matrixSha256":${q(snapHash)},"programHash":${q(programHash)},"reason":${q(reason)}}""")
            snapshots+=QuerySnapshot(arm,step,strategyChoice,reason,programHash,snapHash,rows,
                modelInfo.count{it.certified},excluded,conjectures)
            if(strategyChoice==null){stop=reason;break}
            val item=pool.single{it.u.id==strategyChoice}
            val prediction=SD03EvidenceCompositionE1.predict(p,item.u.atomPredictions)
            if(prediction==null){stop="UNKNOWN_CURRENT_PREDICTION";break}
            val (label,entry)=oracle.query(item.fields,mapOf("CURRENT_PROGRAM" to prediction),
                listOf("CURRENT_PROGRAM"),"E22_${arm}_QUERY")
            if(label==null||entry==null){stop="ORACLE_UNAVAILABLE";break}
            append(studentFile,"""{"phase":"QUERY_LABEL_REVEALED","arm":${q(arm)},"task":${q(token)},"step":$step,"queryId":${q(strategyChoice)},"label":$label,"oldPrediction":$prediction,"counterexampleOfCurrent":${label!=prediction},"matrixSha256":${q(snapHash)},"ledgerIndex":${entry.queryIndex}}""")
            chosen+=strategyChoice;before+=prediction
            disclosed+=E1Example(strategyChoice,item.fields,label)
            val fit=src.propose(disclosed,e1cfg)
            current=fit.program
            append(studentFile,"""{"phase":"REFIT","arm":${q(arm)},"task":${q(token)},"step":$step,"status":${q(fit.status.name)},"trainCorrect":${fit.trainCorrect},"knownLabels":${disclosed.size},"nextProgramHash":${j(treeHash(current,src))}}""")
        }
        val finalCanonical=current?.let{CanonicalJson.expr(src.toExpr(it))}
        append(studentFile,"""{"phase":"FINAL_STUDENT_FROZEN","arm":${q(arm)},"task":${q(token)},"initialProgramHash":${j(treeHash(initial,src))},"finalProgram":${j(finalCanonical)},"finalProgramHash":${finalCanonical?.let{q(sha(it))}?:"null"},"publicDigest":${q(publicHash(disclosed))},"publicLabels":${disclosed.size},"queryIds":[${chosen.joinToString(","){q(it)}}],"stopReason":${q(stop)},"atomInterpreterCalls":${src.atomInterpreterCalls},"realCandidateValidationCalls":${src.candidateInterpreterCalls},"e1Proposals":${src.e1Proposals},"e1PartitionAttempts":${src.e1PartitionAttempts},"e1PredictionReads":${src.e1PredictionReads}}""")
        return ArmRun(arm,current,initial,disclosed,src,chosen,before,snapshots,stop,
            oracle.newQueries(),0)
    }
    private fun grade(
        token:String,arm:ArmRun,challenge:List<Map<String,String>>,
        heldout:List<Map<String,String>>,truth:(Map<String,String>)->Boolean,
        library:List<CapabilityDefinition>,dest:Path,
    ) {
        val eligible=arm.program!=null && arm.source.certified(arm.program,arm.public)
        val cost=MRX2Cost()
        var status="NOT_ELIGIBLE";var correct:Int?=null
        if(eligible){
            val body=arm.source.toExpr(requireNotNull(arm.program))
            val ledger=MRX2Ledger(challenge,StudyQueryController(challenge,challenge.size,truth),
                challenge.size,cost)
            val validation=ledger.validate(CanonicalJson.expr(body),
                {input->Pack02Knowledge.predict(body,input,library)},
                "E22_EXAMINER",challenge.size,cost)
            status=validation.status.name
            if(validation.status==MRX2ValidationStatus.PASS_ON_CHALLENGE)
                correct=heldout.count{Pack02Knowledge.predict(body,it,library)==truth(it)}
        }
        append(dest,"""{"task":${q(token)},"arm":${q(arm.arm)},"eligible":$eligible,"challengeStatus":${q(status)},"heldoutCorrect":${correct?:"null"},"heldoutTotal":${heldout.size},"queryNew":${arm.queryNew},"queryCount":${arm.queried.size},"queryIds":[${arm.queried.joinToString(","){q(it)}}],"e1Proposals":${arm.source.e1Proposals},"e1SplitAttempts":${arm.source.e1PartitionAttempts},"atomCalls":${arm.source.atomInterpreterCalls},"independentValidationCalls":${arm.source.candidateInterpreterCalls},"challengeNewOracleCalls":${cost.oracleNewQueries},"challengeInterpreterCalls":${cost.interpreterCalls},"completeCPUCost":"NOT_INSTRUMENTED"}""")
    }
    @Test fun `E22 full query matrices and TRAIN consistent conjectures have matched examiner opportunity audit`() {
        val library=C8TestBases.bases.library("FULL").definitions
        val dir=Path.of("docs/research/sd03-e22-development-2026-11-07")
        Files.createDirectories(dir)
        val marker=dir.resolve("E22-RUN-STARTED.txt")
        append(marker,"PUBLIC DEVELOPMENT; no examination labels revealed before student freeze",true)
        var completed=0
        for(task in tasks) {
            val world=MRXWorlds.baseline.single{it.id==task.name}
            val all=C8Domain.all().shuffled(Random(task.seed))
            val train=all.take(32).mapIndexed{i,m->E1Example("t-$i",m,world.truth(m))}
            val queryInputs=all.drop(32).take(12)
            val challenge=all.drop(44).take(16)
            val heldout=all.drop(60)
            assertEquals(21,heldout.size)
            val token=sha(task.name+"|"+task.seed).take(16)
            val adapter=SD03E2RepoAdapter(library)
            val pool=queryInputs.mapIndexed{i,m->Query(E2Unlabelled("q-$i",m,adapter.atomPreview(m)),m)}
            val studentFile=dir.resolve("STUDENT-EVENTS-$token.jsonl")
            val arms=listOf("STATIC","RANDOM_FIXED_4","E2_LEGACY","E21_STRICT","E22_CONJECTURE")
            val completedArms=arms.map{arm->runArm(token,arm,train,pool,library,
                ledger(pool,world.truth),studentFile,task.seed)}
            assertEquals(5,completedArms.size)
            assertTrue(completedArms.all{it.queryNew==it.queried.size})
            completed+=completedArms.size
            // EXAMINER-ONLY: disclose all opportunity labels AFTER ALL five
            // student programs and all query matrices have been fsync-frozen.
            val initial=completedArms.single{it.arm=="STATIC"}.initial
            val initialWrong=pool.mapIndexedNotNull {i,p->
                if(initial==null) null else {
                    val pred=SD03EvidenceCompositionE1.predict(initial,p.u.atomPredictions)
                    if(pred!=null && pred!=world.truth(pool[i].fields)) pool[i].u.id else null
                }
            }
            val examinerRows=completedArms.joinToString(",","[","]") {run->
                val hits=run.queried.count{it in initialWrong}
                val retrospective=run.snapshots.filter{it.step==0}.flatMap{snap->
                    initialWrong.mapNotNull {id->snap.rows.find{it.queryId==id}?.let{row->
                        """{"id":${q(id)},"rank":${row.rank?:"null"},"true":${row.trueVotes},"false":${row.falseVotes},"unknown":${row.unknownVotes},"reason":${q(row.reason.name)},"selected":${row.selected}}"""
                    }}
                }
                """{"arm":${q(run.arm)},"queries":${run.queried.size},"initialCounterexampleHits":$hits,"missedCounterexampleRows":${retrospective.joinToString(",","[","]")}}"""
            }
            append(dir.resolve("EXAMINER-OPPORTUNITY-AFTER-FREEZE.jsonl"),
                """{"task":${q(token)},"seed":${task.seed},"initialCounterexampleIds":[${initialWrong.joinToString(","){q(it)}}],"initialCounterexampleCount":${initialWrong.size},"poolSize":${pool.size},"sharedUnlabelledAtomPreviewCalls":${adapter.atomInterpreterCalls},"arms":$examinerRows}""")
            for(run in completedArms)grade(token,run,challenge,heldout,world.truth,library,
                dir.resolve("EXAMINER-SCORES-AFTER-FREEZE.jsonl"))
        }
        assertEquals(40,completed)
    }
}
