package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Development-only causal controls. Never passes world.truth/gold to the searcher.
 * The common TRAIN and atom ranking are fixed. Grading happens after the snapshot
 * and all search-arm structures have been frozen to disk.
 */
class SD03R31EnumerationIntegrationTest {
    private data class Arm(val id: String, val schedule: MRX2BR31Schedule, val ifLimit: Int)
    private data class Frozen(val arm: Arm, val space: MRX2BSpaceResult, val digest: String)
    private fun q(s: String) = CanonicalJson.string(s)
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun appendFsync(file: Path, line: String) {
        Files.createDirectories(file.parent)
        val content = (line + "\n").toByteArray(Charsets.UTF_8)
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.APPEND).use { ch ->
            val buf = ByteBuffer.wrap(content)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }
    private fun familyJson(space: MRX2BSpaceResult): String = MRX2BR31Family.entries.joinToString(",") { f ->
        val c = space.trace.r31.forFamily(f)
        """${q(f.name)}:{"attempted":${c.attempted},"admitted":${c.admitted},"globalCapRejected":${c.globalCapRejected},"familyQuotaRejected":${c.familyQuotaRejected},"duplicateRejected":${c.duplicateRejected},"nodeRejected":${c.nodeRejected},"canonicalRejected":${c.canonicalRejected},"typeRejected":${c.typeRejected}}"""
    }

    private fun sourceCodeNestedIf(expr: Expr, inIf: Boolean = false): Boolean = when (expr) {
        is Expr.If -> inIf || sourceCodeNestedIf(expr.condition, true) ||
            sourceCodeNestedIf(expr.whenTrue, true) || sourceCodeNestedIf(expr.whenFalse, true)
        is Expr.And -> sourceCodeNestedIf(expr.left, inIf) || sourceCodeNestedIf(expr.right, inIf)
        is Expr.Or -> sourceCodeNestedIf(expr.left, inIf) || sourceCodeNestedIf(expr.right, inIf)
        is Expr.Eq -> sourceCodeNestedIf(expr.left, inIf) || sourceCodeNestedIf(expr.right, inIf)
        is Expr.Not -> sourceCodeNestedIf(expr.value, inIf)
        is Expr.Field -> sourceCodeNestedIf(expr.record, inIf)
        is Expr.Let -> sourceCodeNestedIf(expr.value, inIf) || sourceCodeNestedIf(expr.body, inIf)
        is Expr.Call -> expr.args.values.any { sourceCodeNestedIf(it, inIf) }
        is Expr.Arg, is Expr.Const, is Expr.Ref -> false
    }

    private fun grade(
        frozen: Frozen,
        challenge: List<Map<String, String>>,
        heldout: List<Map<String, String>>,
        world: MRXWorld,
        library: List<CapabilityDefinition>,
    ): String {
        val meter = MRX2Cost()
        val ledger = MRX2Ledger(challenge, StudyQueryController(challenge, challenge.size, world.truth),
            challenge.size, meter)
        var chosen: StudyCandidate? = null
        var passes = 0
        for (candidate in frozen.space.trace.candidates(MRX2BStage.RETAINED)) {
            val validation = ledger.validate(candidate.canonical,
                { input -> Pack02Knowledge.predict(candidate.body, input, library) },
                "R31_EXAMINER", challenge.size, meter)
            if (validation.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                passes++
                if (chosen == null) chosen = candidate
                if (passes >= 4) break
            }
        }
        val held = chosen?.let { c ->
            heldout.count { Pack02Knowledge.predict(c.body, it, library) == world.truth(it) }
        }
        return """{"arm":${q(frozen.arm.id)},"frozenDigest":${q(frozen.digest)},"challengePassCount":$passes,"chosen":${if (chosen == null) "null" else q(hash(chosen.canonical))},"heldoutCorrect":${held ?: "null"},"heldoutTotal":${heldout.size},"oracleNew":${meter.oracleNewQueries},"oracleReuse":${meter.oracleReuseUses},"validationInterpreterCalls":${meter.interpreterCalls}}"""
    }

    @Test fun `same PAIRWISE atoms and global budget isolate If starvation and visibility`() {
        val w = MRXWorlds.baseline.single { it.id == "mrx-if-nested-3" }
        val seed = 20261109
        val inputs = C8Domain.all().shuffled(Random(seed))
        val train = inputs.take(40).mapIndexed { i, v -> StudyCase("r31-train-$i", v, w.truth(v)) }
        val challenge = inputs.drop(40).take(20)
        val heldout = inputs.drop(60)
        val library = C8TestBases.bases.library("FULL").definitions
        val arms = listOf(
            Arm("LEGACY_12", MRX2BR31Schedule.LEGACY, 12),
            Arm("QUOTA_IF12", MRX2BR31Schedule.QUOTA_IF_FIRST, 12),
            Arm("QUOTA_IF24", MRX2BR31Schedule.QUOTA_IF_FIRST, 24),
        )
        val dir = Path.of("docs/research/sd03-r31-development-2026-11-07")
        Files.createDirectories(dir)
        val frozenFile = dir.resolve("FROZEN-BEFORE-GRADING.jsonl")
        val gradedFile = dir.resolve("EXAMINER-AFTER-FREEZE.jsonl")
        require(!Files.exists(frozenFile) && !Files.exists(gradedFile)) {
            "R3.1 evidence already exists. Archive the previous attempt before rerun; never overwrite evidence."
        }
        val frozen = mutableListOf<Frozen>()
        for (arm in arms) {
            val space = MRX2BSpace(C8Domain.attributes, library, train, MRX2BStrategy.PAIRWISE,
                atomLimit = 12, maxCandidates = 30_000, consistentLimit = 2_000,
                r31Schedule = arm.schedule, r31Quotas = MRX2BR31Quotas(),
                r31IfLiteralLimit = arm.ifLimit).generate()
            val marker = hash(space.trace.atoms.joinToString("\n") + "\n" + arm.id + "\n" +
                space.trace.candidates(MRX2BStage.GENERATED).joinToString("\n") { it.canonical })
            val nested = space.trace.candidates(MRX2BStage.GENERATED).count { sourceCodeNestedIf(it.body) }
            val line = """{"arm":${q(arm.id)},"digest":${q(marker)},"generated":${space.generated},"trainConsistent":${space.consistent},"retained":${space.retained},"selectedAtomsDigest":${q(hash(space.trace.atoms.joinToString("\n")))},"nestedIfAstCount":$nested,"searchInterpreterCalls":${space.trace.r31.interpreterCalls},"generationWallMillis":${space.wallMillis},"family":{${familyJson(space)}}}"""
            appendFsync(frozenFile,line)
            frozen += Frozen(arm,space,marker)
        }
        val firstAtomSet = frozen.first().space.trace.atoms
        assertTrue(frozen.all { it.space.trace.atoms == firstAtomSet }, "same atoms and ranking required")
        assertEquals(30_000, frozen.first().space.generated)
        assertEquals(0, frozen.first().space.trace.r31.forFamily(MRX2BR31Family.IF).admitted)
        assertTrue(frozen[1].space.trace.r31.forFamily(MRX2BR31Family.IF).admitted > 0)
        assertTrue(frozen[2].space.trace.r31.forFamily(MRX2BR31Family.IF).admitted > 0)
        assertEquals(1728L, frozen[1].space.trace.r31.forFamily(MRX2BR31Family.IF).attempted)
        assertEquals(13824L, frozen[2].space.trace.r31.forFamily(MRX2BR31Family.IF).attempted)
        assertTrue(frozen.all { it.space.generated <= 30_000 })
        assertTrue(frozen.all { it.space.trace.r31.admitted == it.space.generated.toLong() })
        // Examiner may only now score after ALL search arms are frozen to disk.
        for (item in frozen) appendFsync(gradedFile,grade(item,challenge,heldout,w,library))
        // Full-domain GOLD or alternate grammar experiments require separate
        // examiner-side pre-registration. The missing nested AST by itself is NOT
        // proof of semantic inexpressibility.
    }
}
