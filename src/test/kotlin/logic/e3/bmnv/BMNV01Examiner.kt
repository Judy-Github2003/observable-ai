package logic.e3.bmnv

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

/** Single-package post-freeze examiner. VISIBLE to implementer: NOT independent closed-book.
 * Do not execute official mode twice or tune after scores are revealed.
 */
object BMNV01Examiner {
    private val operators = listOf(BmnvOp.XOR, BmnvOp.XNOR, BmnvOp.AND, BmnvOp.OR)
    private val wrappers = listOf(BmnvWrapper.DIRECT, BmnvWrapper.XOR_CONTEXT,
        BmnvWrapper.AND_CONTEXT, BmnvWrapper.OR_CONTEXT)
    private val arms = listOf("FULL", "NO_MEMORY", "NO_OBSERVATION", "NO_COMPOSE")
    private val pairs = listOf(BmnvSensor.ALPHA to BmnvSensor.BETA,
        BmnvSensor.ALPHA to BmnvSensor.GAMMA,
        BmnvSensor.BETA to BmnvSensor.GAMMA)
    private val utf8 = StandardCharsets.UTF_8
    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(utf8)).joinToString("") { "%02x".format(it) }
    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun bool(v: Boolean?) = v?.toString() ?: "null"
    private fun boolArray(xs: List<Boolean?>): String = xs.joinToString(prefix = "[", postfix = "]", separator = ",") { bool(it) }
    private data class Row(val id: String, val context: Boolean, val channels: Map<BmnvSensor, Boolean>)
    private data class World(val trainA: List<Row>, val trainB: List<Row>, val heldoutB: List<Row>,
        val expression: BmnvExpr)
    private class WorldPort(private val rows: List<Row>): BmnvReadPort {
        private val data = rows.associateBy { it.id }
        var reads = 0; private set
        override fun read(objectId: String, sensor: BmnvSensor): Boolean {
            val row = data[objectId] ?: error("No permitted object id: $objectId")
            reads++
            return row.channels[sensor] ?: error("Unavailable sensor")
        }
    }
    private fun world(pair: Pair<BmnvSensor,BmnvSensor>, op: BmnvOp, seed: Int, salt: Long): World {
        val expr = BmnvExpr(op, pair.first, pair.second)
        fun make(prefix: String, offset: Long): List<Row> {
            val shuffle = Random(salt xor (seed * 47111L) xor offset)
            val cases = (0 until 8).flatMap { k -> (0..1).map { ctx ->
                val id = sha256("$prefix/$salt/$seed/$k/$ctx").take(18)
                Row(id, ctx == 1, BmnvSensor.entries.associateWith { ((k shr it.ordinal) and 1) != 0 })
            } }
            return cases.shuffled(shuffle)
        }
        return World(make("trainA",1),make("trainB",2),make("heldoutB",3),expr)
    }
    private fun trainingA(w:World): List<BmnvExample> = w.trainA.map {
        BmnvExample(it.id, it.context, w.expression.eval(it.channels)!!)
    }
    private fun trainingB(w:World, wrapper:BmnvWrapper): List<BmnvExample> = w.trainB.map {
        BmnvExample(it.id, it.context,
            BmnvGrammar.applyWrapper(wrapper, w.expression.eval(it.channels)!!, it.context))
    }
    private fun actual(w:World, wrapper:BmnvWrapper):List<Boolean> = w.heldoutB.map {
        BmnvGrammar.applyWrapper(wrapper, w.expression.eval(it.channels)!!, it.context)
    }
    private fun publicPrediction(row:Row, expr:BmnvExpr, wrapper:BmnvWrapper, port:BmnvReadPort):Boolean? {
        val concept = expr.eval(expr.sensors().associateWith { port.read(row.id, it) }) ?: return null
        return BmnvGrammar.applyWrapper(wrapper, concept, row.context)
    }

    private data class StudentRun(val row:String, val predictions: List<List<Boolean?>>,
        val aExpr: BmnvExpr?, val totalReads:Int)

    private fun studentRun(taskId:String, w:World, arm:String, memoryDir:Path):StudentRun {
        val learner = BMNV01ReferenceStudent()
        val port = WorldPort(w.trainA + w.trainB + w.heldoutB)
        val aRows = trainingA(w)
        var aStatus = "NOT_RUN"; var expression: BmnvExpr? = null
        var memory: BmnvConcept? = null; var aReads = 0
        var aCandidates = 0; var aClasses = 0; var aChannels = emptyList<BmnvSensor>()
        if (arm == "FULL" || arm == "NO_COMPOSE") {
            if (arm == "FULL") {
                val a = learner.learnA(aRows, port)
                aStatus = a.status.name; aReads = a.measurements
                aCandidates = a.candidatesEvaluated; aClasses = a.behaviorClasses; aChannels = a.queriedChannels
                // Real filesystem persistence boundary; Task B re-loads concept from disk.
                if (a.concept != null) {
                    Files.createDirectories(memoryDir)
                    val memoryPath = memoryDir.resolve("$taskId.concept")
                    require(!Files.exists(memoryPath)) { "Memory file exists: refusing overwrite" }
                    Files.writeString(memoryPath, a.concept.encode(), utf8)
                    memory = BmnvConcept.decode(Files.readString(memoryPath,utf8))
                }
                expression = memory?.expression
            } else {
                val channels = BmnvSensor.entries.associateWith { s -> aRows.associate { r -> r.id to port.read(r.id,s) } }
                aReads = aRows.size * 3
                val unary = BmnvGrammar.candidates(BmnvSensor.entries).filter { it.second == null }
                val match = unary.filter { e -> aRows.all { row ->
                    e.eval(channels.mapValues { (_, values) -> values.getValue(row.id) }) == row.label
                } }
                aStatus = if (match.isEmpty()) "NOT_FOUND" else "UNARY_FIT_ONLY"
            }
        } else if (arm == "NO_OBSERVATION") {
            aStatus = if (aRows.groupBy { it.context }.values.any { xs -> xs.map { it.label }.distinct().size > 1 })
                "OBSERVATIONAL_COLLISION" else "NO_VISIBLE_COLLISION"
        }
        val aReadsActual = port.reads
        require(aReadsActual == aReads)
        val predictions = mutableListOf<List<Boolean?>>()
        val bTrainReads = mutableListOf<Int>(); val bTestReads = mutableListOf<Int>()
        val bStatus = mutableListOf<String>()
        val bModelCodes = mutableListOf<String?>()
        val bCandidateCounts = mutableListOf<Int>()
        for (wrapper in wrappers) {
            val prior = port.reads
            val b = trainingB(w, wrapper)
            val model = if (arm == "FULL") learner.learnB(b, port, memory) else null
            val direct = if (arm == "NO_MEMORY") learner.learnBFromScratch(b, port) else null
            bTrainReads += port.reads - prior
            bModelCodes += when (arm) {
                "FULL" -> model?.rule?.let { "${it.concept.expression.encode()}@${it.wrapper.name}" }
                "NO_MEMORY" -> if (direct?.expr != null && direct.wrapper != null)
                    "${direct.expr.encode()}@${direct.wrapper.name}" else null
                else -> null
            }
            bCandidateCounts += when (arm) {
                "FULL" -> model?.candidatesEvaluated ?: 0
                "NO_MEMORY" -> direct?.candidatesEvaluated ?: 0
                else -> 0
            }
            val beforeHeldout = port.reads
            val preds = w.heldoutB.map { row -> when(arm) {
                "FULL" -> {
                    val rule = model?.rule
                    if (rule == null) null else rule.infer(row.context,
                        rule.concept.expression.sensors().associateWith { port.read(row.id,it) })
                }
                "NO_MEMORY" -> {
                    val expr = direct?.expr; val w2 = direct?.wrapper
                    if (expr == null || w2 == null) null else publicPrediction(row,expr,w2,port)
                }
                else -> null
            } }
            bTestReads += port.reads - beforeHeldout
            predictions += preds
            bStatus += when(arm) {
                "FULL" -> model?.status?.name ?: "NOT_RUN"
                "NO_MEMORY" -> if (direct?.expr != null) "LEARNED_FROM_SCRATCH" else "UNKNOWN"
                else -> "ABSTAIN"
            }
        }
        val predsJson = predictions.joinToString(prefix="[",postfix="]",separator=",") { boolArray(it) }
        val row = "{" +
            "\"taskId\":${quote(taskId)},\"arm\":${quote(arm)}," +
            "\"aStatus\":${quote(aStatus)},\"aExpr\":${if (expression==null) "null" else quote(expression.encode())}," +
            "\"memory\":${if (memory==null) "null" else quote(memory.encode())}," +
            "\"aReads\":$aReads,\"aCandidates\":$aCandidates,\"aClasses\":$aClasses," +
            "\"aChannels\":[${aChannels.joinToString(",") { quote(it.name) }}]," +
            "\"bTrainReads\":${bTrainReads.joinToString(prefix="[",postfix="]")}," +
            "\"bTestReads\":${bTestReads.joinToString(prefix="[",postfix="]")}," +
            "\"bStatus\":[${bStatus.joinToString(",") { quote(it) }}]," +
            "\"bModelCodes\":[${bModelCodes.joinToString(",") { if (it==null) "null" else quote(it) }}]," +
            "\"bCandidateCounts\":${bCandidateCounts.joinToString(prefix="[",postfix="]")}," +
            "\"totalReads\":${port.reads},\"teacherCalls\":0,\"predictions\":$predsJson}"
        return StudentRun(row,predictions,expression,port.reads)
    }

    @JvmStatic fun main(args:Array<String>) {
        require(args.size == 2) { "usage: BMNV01Examiner <fresh-output-directory> <one-time-salt>" }
        val dir = Path.of(args[0]); require(!Files.exists(dir)) { "Output exists: one-shot guard" }
        Files.createDirectories(dir)
        val salt = args[1].toLong()
        val students = mutableListOf<String>()
        val grades = mutableListOf<String>()
        val scoreByArm = arms.associateWith { mutableListOf<Int>() }
        val costByArm = arms.associateWith { mutableListOf<Int>() }
        for ((pairIndex,pair) in pairs.withIndex()) for ((opIndex,op) in operators.withIndex()) for (seed in 0..3) {
            val w = world(pair,op,seed,salt)
            val taskId = sha256("task|$salt|$pairIndex|$opIndex|$seed").take(16)
            for (arm in arms) {
                val sr = studentRun(taskId,w,arm,dir.resolve("PERSISTED-CONCEPTS"))
                students += sr.row
                val truths = wrappers.map { actual(w,it) }
                var correct = 0; var covered = 0
                for ((i,preds) in sr.predictions.withIndex()) for ((j,pred) in preds.withIndex()) {
                    if (pred != null) { covered++; if (pred == truths[i][j]) correct++ }
                }
                val aCorrect = sr.aExpr?.let { expr -> (0 until 8).all { bits ->
                    val reading = BmnvSensor.entries.associateWith { (bits shr it.ordinal) and 1 != 0 }
                    expr.eval(reading) == w.expression.eval(reading)
                } } ?: false
                val gtJson = truths.joinToString(prefix="[",postfix="]",separator=",") { boolArray(it) }
                val inputsJson = w.heldoutB.joinToString(prefix="[",postfix="]",separator=",") { row ->
                    "{\"context\":${row.context},\"alpha\":${row.channels.getValue(BmnvSensor.ALPHA)}," +
                    "\"beta\":${row.channels.getValue(BmnvSensor.BETA)},\"gamma\":${row.channels.getValue(BmnvSensor.GAMMA)}}"
                }
                val grade = "{" +
                    "\"taskId\":${quote(taskId)},\"arm\":${quote(arm)}," +
                    "\"groundTruthExpr\":${quote(w.expression.encode())}," +
                    "\"aCorrect\":$aCorrect,\"heldoutInputs\":$inputsJson,\"truths\":$gtJson," +
                    "\"correct\":$correct,\"covered\":$covered,\"total\":${truths.sumOf { it.size }}," +
                    "\"totalReads\":${sr.totalReads}}"
                grades += grade
                scoreByArm.getValue(arm) += correct
                costByArm.getValue(arm) += sr.totalReads
            }
        }
        val studentPath = dir.resolve("STUDENTS-FROZEN.jsonl")
        Files.writeString(studentPath, students.joinToString("\n",postfix="\n"),utf8)
        val frozenSha = sha256(Files.readString(studentPath,utf8))
        Files.writeString(dir.resolve("STUDENTS-FROZEN.sha256"), "$frozenSha  STUDENTS-FROZEN.jsonl\n",utf8)
        // CRITICAL BARRIER: grade file must only be written after ALL student records are frozen.
        Files.writeString(dir.resolve("EXAMINER-AFTER-FREEZE.jsonl"),grades.joinToString("\n",postfix="\n"),utf8)
        val summary = buildString {
            append("BMNV-01 visible-examiner development experiment; NOT independent closed-book\n")
            append("Worlds: ${pairs.size * operators.size * 4}; arms: ${arms.size}; student rows: ${students.size}\n")
            append("Student SHA256: $frozenSha\n")
            for (arm in arms) append("$arm: B correct=${scoreByArm.getValue(arm).sum()}/${pairs.size * operators.size * 4 * wrappers.size * 16}, measurements=${costByArm.getValue(arm).sum()}\n")
            append("No significance claims from repeated seeds; human-authored student grammar.\n")
        }
        Files.writeString(dir.resolve("SUMMARY.txt"),summary,utf8)
        print(summary)
    }
}
