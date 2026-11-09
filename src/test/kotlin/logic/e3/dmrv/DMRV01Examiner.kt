package logic.e3.dmrv

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

/** Visible examiner: developer-run, not independent closed-book. One-shot guard applies to output dir.
 * Meta-training scalar feedback is allowed; meta-test target labels are grader-only until frozen.
 */
object DMRV01Examiner {
    private val utf8 = StandardCharsets.UTF_8
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(utf8)).joinToString("") { "%02x".format(it) }
    private fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun b(v: Boolean?) = v?.toString() ?: "null"
    private fun inputLabels(rows: List<DmrvExample>) = rows.joinToString(prefix="[",postfix="]",separator=",") { "{\"x\":${it.input},\"y\":${it.label}}" }
    private data class World(val family: Int, val variant: Int, val seed: Int,
        val target: DmrvExpr, val public: DmrvPublicTask,
        val heldout: List<Int>)

    private fun hiddenTarget(family: Int, variant: Int, seed: Int): DmrvExpr = when (family) {
        0 -> DmrvExpr.Literal(variant % 4, variant >= 4)
        1 -> {
            val left = (variant * 3 + seed) % 4
            val right = (left + 1 + seed % 3) % 4
            DmrvExpr.Pair(minOf(left, right), maxOf(left, right), DmrvBinary.entries[(variant + seed) % DmrvBinary.entries.size])
        }
        2 -> { val a = (0..3).filter { it != (variant + seed) % 4 }; DmrvExpr.Parity3(a[0], a[1], a[2], variant >= 4) }
        3 -> {
            val selector = (variant + seed) % 4
            val others = (0..3).filter { it != selector }
            DmrvExpr.Mux(selector, others[(variant + 1) % 3], others[(variant + 2) % 3])
        }
        else -> error("Unknown hidden family")
    }
    private fun world(phase: String, family: Int, variant: Int, seed: Int, salt: Long): World {
        val target = hiddenTarget(family, variant, seed)
        val random = Random(salt xor (family * 179L + variant * 239L + seed * 1013L + phase.hashCode()).toLong())
        val shuffled = (0..15).shuffled(random)
        val train = shuffled.take(8).sorted().map { DmrvExample(it, target.eval(it)) }
        val heldout = shuffled.drop(8).sorted()
        val token = sha("DMRV|$phase|$salt|$family|$variant|$seed").take(20)
        return World(family, variant, seed, target, DmrvPublicTask(token, train), heldout)
    }
    private fun reward(correct: Int, total: Int, evaluations: Int): Double =
        correct.toDouble() / total.toDouble() - 0.08 * evaluations / 44.0

    private fun randomAction(taskId: String): DmrvAction {
        val index = (taskId.take(8).toLong(16) % DmrvAction.entries.size).toInt()
        return DmrvAction.entries[index]
    }
    private data class StudentSubmission(val taskId: String, val arm: DmrvArm, val action: DmrvAction,
        val context: String, val expr: DmrvExpr?, val train: List<DmrvExample>,
        val pred: List<Boolean?>, val evaluated: Int, val consistent: Int, val behaviorClasses: Int,
        val metaScore: Double, val rationale: String) {
        fun json(): String = "{" +
            "\"taskId\":${quoted(taskId)},\"arm\":${quoted(arm.name)},\"action\":${quoted(action.name)}," +
            "\"context\":${quoted(context)},\"train\":${inputLabels(train)}," +
            "\"expr\":${expr?.let { quoted(it.encode()) } ?: "null"}," +
            "\"predictions\":${pred.joinToString(prefix="[",postfix="]",separator=",") { b(it) }}," +
            "\"candidateEvaluations\":$evaluated,\"consistentCandidates\":$consistent," +
            "\"behaviorClasses\":$behaviorClasses,\"metaScore\":$metaScore," +
            "\"rationale\":${quoted(rationale)},\"teacherCalls\":0}"
    }
    private fun study(learner: DmrvReferenceStudent, task: DmrvPublicTask, heldoutInputs: List<Int>,
        policy: DmrvMetaPolicy, shuffled: DmrvMetaPolicy, arm: DmrvArm): StudentSubmission {
        // heldoutInputs contain NO labels. Student only receives them after a policy action has been fixed.
        val context = learner.diagnose(task).context()
        val decision = when (arm) {
            DmrvArm.META_CONTEXT -> policy.choose(context, true)
            DmrvArm.GLOBAL_FEEDBACK -> policy.choose(context, false)
            DmrvArm.SHUFFLED_CONTEXT -> shuffled.choose(context, true)
            DmrvArm.STATIC_BINARY -> DmrvDecision(DmrvAction.ADD_BINARY, 0.0, "STATIC")
            DmrvArm.RANDOM -> DmrvDecision(randomAction(task.taskId), 0.0, "RANDOM")
            DmrvArm.COLD_START -> DmrvDecision(DmrvAction.BASE_ONLY, 0.0, "COLD_NO_META_FEEDBACK")
        }
        val result = learner.synthesize(task, decision.action)
        val pred = heldoutInputs.map { result.predict(it) }
        return StudentSubmission(task.taskId, arm, decision.action, context, result.expr,
            task.observations, pred, result.candidatesEvaluated, result.consistentCandidates,
            result.behaviorClasses, decision.score, decision.rationale)
    }
    private fun train(learner: DmrvReferenceStudent, salt: Long, dev: Boolean): Triple<DmrvMetaPolicy,DmrvMetaPolicy,List<String>> {
        val feedbackRows = mutableListOf<String>()
        val feedback = mutableListOf<DmrvFeedback>()
        val sourceSeeds = if (dev) 500..501 else 20..25
        val phase = if (dev) "DEV_META_TRAIN" else "META_TRAIN"
        for (family in 0..3) for (variant in 0..7) for (seed in sourceSeeds) {
            val w = world(phase, family, variant, seed, salt)
            val context = learner.diagnose(w.public).context()
            for (action in DmrvAction.entries) {
                val r = learner.synthesize(w.public, action)
                val correct = w.heldout.count { r.predict(it) == w.target.eval(it) && r.predict(it) != null }
                val value = reward(correct, w.heldout.size, r.candidatesEvaluated)
                feedback += DmrvFeedback(context, action, value)
                feedbackRows += "{\"taskId\":${quoted(w.public.taskId)},\"context\":${quoted(context)}," +
                    "\"action\":${quoted(action.name)},\"correct\":$correct,\"total\":${w.heldout.size}," +
                    "\"candidateEvaluations\":${r.candidatesEvaluated},\"reward\":$value}"
            }
        }
        val p = DmrvMetaPolicy(); feedback.forEach(p::observe)
        val scrambled = DmrvMetaPolicy()
        // Shuffle observed context assignments, while preserving action-specific reward distribution and budget.
        val keys = feedback.map { it.context }.shuffled(Random(salt xor 0x7135L))
        feedback.forEachIndexed { i, f -> scrambled.observe(f.copy(context = keys[i])) }
        return Triple(p, scrambled, feedbackRows)
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 3) { "Usage: DMRV01Examiner <fresh-output-directory> <salt-long> <development|official>" }
        val dir = Path.of(args[0]); require(!Files.exists(dir)) { "Output directory exists; no repeated examination" }
        val salt = args[1].toLong()
        val dev = when (args[2]) { "development" -> true; "official" -> false; else -> error("Invalid run mode") }
        Files.createDirectories(dir)
        val learner = DmrvReferenceStudent()
        val (policy, shuffled, metaRows) = train(learner, salt, dev)
        Files.writeString(dir.resolve("META-TRAIN-FEEDBACK.jsonl"), metaRows.joinToString("\n",postfix="\n"), utf8)
        val policyText = (listOf("DMRV01|policy-v1|prior=4.0") + policy.snapshot()).joinToString("\n",postfix="\n")
        val shuffleText = (listOf("DMRV01|shuffled-v1|prior=4.0") + shuffled.snapshot()).joinToString("\n",postfix="\n")
        Files.writeString(dir.resolve("POLICY-FROZEN.txt"), policyText, utf8)
        Files.writeString(dir.resolve("POLICY-SHUFFLED-FROZEN.txt"), shuffleText, utf8)
        Files.writeString(dir.resolve("POLICY-FROZEN.sha256"), "${sha(policyText)}  POLICY-FROZEN.txt\n", utf8)
        val check = DmrvMetaPolicy.restore(policy.snapshot())
        require(check.snapshot() == policy.snapshot()) { "Policy persistence failed" }
        // Official student outputs use only frozen prior-task policy and public evidence.
        val submissions = mutableListOf<StudentSubmission>()
        val worlds = linkedMapOf<String, World>()
        val testSeeds = if (dev) 700..701 else 800..803
        val phase = if (dev) "DEV_META_TEST" else "META_TEST"
        for (family in 0..3) for (variant in 0..7) for (seed in testSeeds) {
            val w = world(phase, family, variant, seed, salt)
            check(w.public, w.heldout)
            worlds[w.public.taskId] = w
            for (arm in DmrvArm.entries) submissions += study(learner, w.public, w.heldout, check, shuffled, arm)
        }
        val students = submissions.joinToString("\n",postfix="\n") { it.json() }
        Files.writeString(dir.resolve("STUDENTS-FROZEN.jsonl"), students, utf8)
        val studentSha = sha(students)
        Files.writeString(dir.resolve("STUDENTS-FROZEN.sha256"), "$studentSha  STUDENTS-FROZEN.jsonl\n", utf8)
        // SCORE BARRIER: NO TEST TRUTH or ground-truth labels are serialized before this point.
        val grades = mutableListOf<String>()
        val byArm = linkedMapOf<String, MutableList<Pair<Int,Int>>>()
        for (s in submissions) {
            val w = worlds.getValue(s.taskId)
            val truth = w.heldout.map { w.target.eval(it) }
            val correct = s.pred.indices.count { s.pred[it] != null && s.pred[it] == truth[it] }
            val covered = s.pred.count { it != null }
            val rew = reward(correct, truth.size, s.evaluated)
            byArm.getOrPut(s.arm.name) { mutableListOf() }.add(correct to covered)
            grades += "{\"taskId\":${quoted(s.taskId)},\"arm\":${quoted(s.arm.name)}," +
                "\"hiddenFamily\":${quoted(arrayOf("UNARY","BINARY","PARITY3","MUX")[w.family])}," +
                "\"groundTruthExpr\":${quoted(w.target.encode())}," +
                "\"heldoutInputs\":${w.heldout.joinToString(prefix="[",postfix="]")}," +
                "\"truths\":${truth.joinToString(prefix="[",postfix="]") { b(it) }}," +
                "\"correct\":$correct,\"covered\":$covered,\"total\":${truth.size}," +
                "\"reward\":$rew,\"candidateEvaluations\":${s.evaluated}}"
        }
        Files.writeString(dir.resolve("EXAMINER-AFTER-FREEZE.jsonl"), grades.joinToString("\n",postfix="\n"), utf8)
        val report = buildString {
            append("DMRV-01 / ${if (dev) "DEVELOPMENT" else "OFFICIAL"} / VISIBLE EXAMINER (not independent closed-book)\n")
            append("metaTrainTasks=${metaRows.size / DmrvAction.entries.size}; metaTestTasks=${worlds.size}; studentRows=${submissions.size}\n")
            append("studentSHA256=$studentSha\n")
            byArm.forEach { (arm, rows) -> append("$arm correct=${rows.sumOf { it.first }}/${rows.size * 8} covered=${rows.sumOf { it.second }}/${rows.size * 8}\n") }
            append("Do not infer advantage without paired calculation, reward/cost parity, and leakage audit.\n")
        }
        Files.writeString(dir.resolve("SUMMARY.txt"), report, utf8)
        print(report)
    }
    private fun check(task: DmrvPublicTask, heldout: List<Int>) {
        require(task.observations.size == 8 && heldout.size == 8)
        require(task.observations.map { it.input }.toSet().intersect(heldout.toSet()).isEmpty())
        require(task.observations.map { it.input }.toSet().union(heldout.toSet()) == (0..15).toSet())
    }
}
