package logic.e3.gnjv

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.random.Random

/** SEALED EXAMINER. Do not reveal before the learner's Phase-A freeze is committed.
 * Development-domain delayed reveal ONLY: this is not a secure external closed-book exam.
 * Compile alongside GNJV01Kernel.kt. The only learner-facing API is choose/update/predict.
 * Freeze all student predictions before any held-out scoring.
 */
object GNJV01SealedExaminer {
    enum class Arm { OBS_ONLY, RANDOM, FIXED, GNJV_ACTIVE }
    private data class Task(val token:String, val hidden:GNJVCausalHypothesis, val u:Boolean, val index:Int)
    private data class FrozenRun(val task:Task,val arm:Arm,val actions:List<GNJVTrial>,
        val hypotheses:List<GNJVCausalHypothesis>,val predicted:List<GNJVObservation?>,
        val conflicting:Boolean)

    private fun sha(s:String):String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun quote(s:String):String = "\"" + buildString {
        for (ch in s) when(ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
            '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if(ch.code<32) append("\\u%04x".format(ch.code)) else append(ch)
        }
    } + "\""
    private fun obs(o:GNJVObservation?):String = o?.let { "[${it.x},${it.y}]" } ?: "null"
    private fun actionsForState():List<GNJVIntervention> = listOf(
        GNJVIntervention(CausalVariable.X,false),GNJVIntervention(CausalVariable.X,true),
        GNJVIntervention(CausalVariable.Y,false),GNJVIntervention(CausalVariable.Y,true))
    private fun gradeTruth(hidden:GNJVCausalHypothesis, b:GNJVObservation, a:GNJVIntervention): GNJVObservation =
        hidden.predict(b,a)

    /** Task identity is an opaque public token. Structure/seed are never part of learner's input. */
    private fun tasks():List<Task> = buildList {
        val hiddenFamily=GNJV01HypothesisGrammar.candidates()
        for (world in hiddenFamily.indices) for (rep in 0 until 4) {
            val index=world*4+rep
            val seed=19021+index*1789
            val u=Random(seed).nextBoolean()
            add(Task(sha("GNJV01|$index|$seed").take(16),hiddenFamily[world],u,index))
        }
    }

    private fun runArm(task:Task,arm:Arm):FrozenRun {
        val publicBaseline=GNJVObservation(task.u,task.u) // only observed X,Y, never U or hidden model
        val learner=GNJV01Student()
        val observations=mutableListOf<GNJVTrial>()
        for (step in 0 until 2) {
            if (arm==Arm.OBS_ONLY || learner.identified()!=null || learner.hasConflict()) break
            val next=when(arm) {
                Arm.OBS_ONLY -> null
                Arm.GNJV_ACTIVE -> learner.choose(publicBaseline).intervention
                Arm.FIXED -> learner.legalActions(publicBaseline).getOrNull(step)
                Arm.RANDOM -> {
                    val r=Random(23459+task.index*1987+step*37)
                    learner.legalActions(publicBaseline)[r.nextInt(2)]
                }
            } ?: break
            // Oracle is invoked here ONLY for a requested intervention; never for candidate selection.
            val response=gradeTruth(task.hidden,publicBaseline,next)
            val trial=GNJVTrial(publicBaseline,next,response)
            observations += trial
            learner.update(trial)
        }
        val unseenBaseline=GNJVObservation(!task.u,!task.u)
        val holdoutPredictions=actionsForState().map { learner.predict(unseenBaseline,it) }
        return FrozenRun(task,arm,observations.toList(),learner.hypotheses(),holdoutPredictions,learner.hasConflict())
    }

    private fun studentLine(run:FrozenRun):String {
        val records=run.actions.joinToString(",") { t ->
            "{\"baseline\":${obs(t.initialObservation)},\"action\":${quote(t.intervention.key)},\"result\":${obs(t.observedAfter)}}"
        }
        val preds=run.predicted.joinToString(",") { obs(it) }
        val hyps=run.hypotheses.joinToString(",") { quote(it.structuralKey()) }
        return "{\"task\":${quote(run.task.token)},\"arm\":${quote(run.arm.name)}," +
            "\"queryCount\":${run.actions.size},\"teacherCalls\":0,\"conflict\":${run.conflicting}," +
            "\"hypotheses\":[$hyps],\"actions\":[$records],\"heldoutPredictions\":[$preds]}"
    }

    private fun gradeLine(run:FrozenRun):String {
        val unseen=GNJVObservation(!run.task.u,!run.task.u)
        val truths=actionsForState().map { gradeTruth(run.task.hidden,unseen,it) }
        val correct=run.predicted.zip(truths).count { (p,t) -> p!=null && p==t }
        val covered=run.predicted.count { it!=null }
        val errors=run.predicted.zip(truths).count { (p,t) -> p!=null && p!=t }
        val identified=run.hypotheses.size==1 && run.hypotheses.single()==run.task.hidden && !run.conflicting
        val falseCertainty=run.hypotheses.size==1 && !identified
        return "{\"task\":${quote(run.task.token)},\"arm\":${quote(run.arm.name)}," +
            "\"identifiedCorrect\":$identified,\"falseCertainty\":$falseCertainty," +
            "\"correct\":$correct,\"covered\":$covered,\"errors\":$errors," +
            "\"total\":${truths.size},\"queryCount\":${run.actions.size}}"
    }

    @JvmStatic fun main(args:Array<String>) {
        require(args.size==1) { "Usage: GNJV01SealedExaminer <new-empty-output-directory>" }
        val out=Path.of(args[0]).toAbsolutePath()
        Files.createDirectories(out.parent)
        Files.createDirectory(out) // CREATE_NEW behavior; never overwrite an earlier sealed exam
        val runs=tasks().flatMap { t -> Arm.entries.map { runArm(t,it) } }
        check(runs.size==48)
        val frozenText=runs.joinToString("\n") { studentLine(it) }+"\n"
        val students=out.resolve("STUDENTS-FROZEN.jsonl")
        Files.writeString(students,frozenText,StandardOpenOption.CREATE_NEW)
        val digest=sha(frozenText)
        Files.writeString(out.resolve("STUDENTS-FROZEN.sha256"),"$digest  STUDENTS-FROZEN.jsonl\n",StandardOpenOption.CREATE_NEW)
        // EXPLICIT BARRIER: all 48 learners + heldout PREDICTIONS are frozen before any grading.
        val gradeText=runs.joinToString("\n") { gradeLine(it) }+"\n"
        Files.writeString(out.resolve("EXAMINER-AFTER-FREEZE.jsonl"),gradeText,StandardOpenOption.CREATE_NEW)
        println("GNJV01_SEALED_COMPLETE tasks=12 arms=48 frozenSha256=$digest dir=$out")
    }
}
