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
 * Public DEVELOPMENT task, not a blind/closed-book test. The target truth is used
 * only to create labelled TRAIN StudyCase and later in the examiner grader.
 * The generator only receives actual public training cases and active library.
 */
class SD03R32IntegrationTest {
    private data class Arm(val id: String, val policy: MRX2BR32IfPolicy)
    private data class Frozen(val arm: Arm, val result: MRX2BSpaceResult, val digest: String)
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun q(s: String) = CanonicalJson.string(s)

    private fun appendFsync(path: Path, value: String) {
        Files.createDirectories(path.parent)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap((value + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    /** Nested If is a syntactic audit, NOT a proof of semantic impossibility. */
    private fun containsNested(e: Expr, withinIf: Boolean = false): Boolean = when (e) {
        is Expr.If -> withinIf || containsNested(e.condition, true) ||
            containsNested(e.whenTrue, true) || containsNested(e.whenFalse, true)
        is Expr.And -> containsNested(e.left, withinIf) || containsNested(e.right, withinIf)
        is Expr.Or -> containsNested(e.left, withinIf) || containsNested(e.right, withinIf)
        is Expr.Eq -> containsNested(e.left, withinIf) || containsNested(e.right, withinIf)
        is Expr.Not -> containsNested(e.value, withinIf)
        is Expr.Field -> containsNested(e.record, withinIf)
        is Expr.Let -> containsNested(e.value, withinIf) || containsNested(e.body, withinIf)
        is Expr.Call -> e.args.values.any { containsNested(it, withinIf) }
        is Expr.Arg, is Expr.Const, is Expr.Ref -> false
    }

    private fun directRoleStats(space: MRX2BSpaceResult): String {
        val direct = space.trace.candidates(MRX2BStage.GENERATED).map { it.body }
            .filterIsInstance<Expr.If>().filterNot { containsNested(it) }
        return """{"directIfAccepted":${direct.size},"whenTrueKinds":${direct.map { CanonicalJson.expr(it.whenTrue) }.toSet().size},"whenFalseKinds":${direct.map { CanonicalJson.expr(it.whenFalse) }.toSet().size},"conditionKinds":${direct.map { CanonicalJson.expr(it.condition) }.toSet().size}}"""
    }

    private fun familyMetrics(space: MRX2BSpaceResult): String = MRX2BR31Family.entries.joinToString(",") { fam ->
        val c = space.trace.r31.forFamily(fam)
        """${q(fam.name)}:{"attempted":${c.attempted},"admitted":${c.admitted},"globalCapRejected":${c.globalCapRejected},"familyQuotaRejected":${c.familyQuotaRejected},"duplicateRejected":${c.duplicateRejected},"nodeRejected":${c.nodeRejected},"canonicalRejected":${c.canonicalRejected},"typeRejected":${c.typeRejected}}"""
    }

    private fun grade(
        f: Frozen, challenge: List<Map<String,String>>, heldout: List<Map<String,String>>,
        truth: (Map<String,String>) -> Boolean, library: List<CapabilityDefinition>,
    ): String {
        val cost = MRX2Cost()
        val ledger = MRX2Ledger(challenge,
            StudyQueryController(challenge, challenge.size, truth), challenge.size, cost)
        var pass = 0
        var chosen: StudyCandidate? = null
        for (candidate in f.result.trace.candidates(MRX2BStage.RETAINED)) {
            val check = ledger.validate(candidate.canonical,
                { input -> Pack02Knowledge.predict(candidate.body, input, library) },
                "R32_EXAMINER", challenge.size, cost)
            if (check.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                pass++
                if (chosen == null) chosen = candidate
                if (pass >= 4) break
            }
        }
        val heldCorrect = chosen?.let { c -> heldout.count { input ->
            Pack02Knowledge.predict(c.body, input, library) == truth(input)
        } }
        return """{"arm":${q(f.arm.id)},"freezeHash":${q(f.digest)},"challengePass":$pass,"selected":${chosen?.let { q(sha(it.canonical)) } ?: "null"},"heldoutCorrect":${heldCorrect ?: "null"},"heldoutTotal":${heldout.size},"oracleNew":${cost.oracleNewQueries},"oracleReuse":${cost.oracleReuseUses},"gradeInterpreterCalls":${cost.interpreterCalls}}"""
    }

    @Test fun `same rank same quotas compare IF order and nested budget replacement`() {
        val world = MRXWorlds.baseline.single { it.id == "mrx-if-nested-3" }
        val all = C8Domain.all().shuffled(Random(20261109))
        val train = all.take(40).mapIndexed { i, input ->
            StudyCase("r32-train-$i", input, world.truth(input))
        }
        val challenge = all.drop(40).take(20)
        val heldout = all.drop(60)
        val library = C8TestBases.bases.library("FULL").definitions
        val arms = listOf(
            Arm("LEX_DIRECT24", MRX2BR32IfPolicy.R31_LEXICOGRAPHIC),
            Arm("ROTATING_DIRECT24", MRX2BR32IfPolicy.ROTATING_DIRECT),
            Arm("ROTATING_NESTED24", MRX2BR32IfPolicy.ROTATING_WITH_NESTED),
        )
        val dir = Path.of("docs/research/sd03-r32-development-2026-11-07")
        Files.createDirectories(dir)
        val before = dir.resolve("FROZEN-BEFORE-GRADING.jsonl")
        val after = dir.resolve("EXAMINER-AFTER-FREEZE.jsonl")
        require(!Files.exists(before) && !Files.exists(after)) {
            "R3.2 evidence already exists: archive earlier run, do not overwrite"
        }
        val frozen = ArrayList<Frozen>()
        for (arm in arms) {
            val space = MRX2BSpace(C8Domain.attributes, library, train,
                MRX2BStrategy.PAIRWISE, atomLimit = 12, maxCandidates = 30_000,
                consistentLimit = 2_000, r31Schedule = MRX2BR31Schedule.QUOTA_IF_FIRST,
                r31Quotas = MRX2BR31Quotas(), r31IfLiteralLimit = 24,
                r32IfPolicy = arm.policy).generate()
            val digest = sha(space.trace.atoms.joinToString("\n") + "\n" + arm.id + "\n" +
                space.trace.candidates(MRX2BStage.GENERATED).joinToString("\n") { it.canonical })
            val nestedCount = space.trace.candidates(MRX2BStage.GENERATED)
                .count { containsNested(it.body) }
            val line = """{"arm":${q(arm.id)},"freezeHash":${q(digest)},"atomsHash":${q(sha(space.trace.atoms.joinToString("\n")))},"generated":${space.generated},"trainConsistent":${space.consistent},"retained":${space.retained},"nestedIfAccepted":$nestedCount,"directRoleStats":${directRoleStats(space)},"searchInterpreterCalls":${space.trace.r31.interpreterCalls},"wallMillis":${space.wallMillis},"family":{${familyMetrics(space)}}}"""
            appendFsync(before, line)
            frozen += Frozen(arm, space, digest)
        }
        assertTrue(frozen.all { it.result.trace.atoms == frozen.first().result.trace.atoms },
            "PAIRED CONTROL INVALID: atom rankings changed")
        assertTrue(frozen.all { it.result.generated <= 30_000 }, "global candidate cap violated")
        for (f in frozen) {
            val m = f.result.trace.r31.forFamily(MRX2BR31Family.IF)
            assertEquals(f.result.generated.toLong(), f.result.trace.r31.admitted,
                "family counts must account for every generated candidate")
            assertEquals(m.attempted, m.admitted + m.globalCapRejected + m.familyQuotaRejected +
                m.duplicateRejected + m.nodeRejected + m.canonicalRejected + m.typeRejected,
                "IF attempts must reconcile")
        }
        assertEquals(0, frozen[0].result.trace.candidates(MRX2BStage.GENERATED)
            .count { containsNested(it.body) })
        assertEquals(0, frozen[1].result.trace.candidates(MRX2BStage.GENERATED)
            .count { containsNested(it.body) })
        // Unlike a manufactured-success test, the nested arm may fail to admit
        // any useful nested candidate; publish what actually happened.
        for (f in frozen) appendFsync(after, grade(f, challenge, heldout, world.truth, library))
    }
}
