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
 * Public development integration. The world truth is read ONLY by the examiner
 * to generate PUBLIC TRAIN labels and grade AFTER all proposals are fsync-frozen.
 * NOT an independently sealed heldout benchmark.
 */
class SD03E1DevelopmentIntegrationTest {
    private data class Task(val id: String, val seed: Int)
    private data class Arm(
        val name: String,
        val body: Expr?,
        val candidates: List<Expr>,
        val canonical: String?,
        val studentTrace: String,
        val searchCalls: Long,
        val studentWallMillis: Long,
    )
    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun q(s: String) = CanonicalJson.string(s)
    private fun appendFreeze(path: Path, line: String) {
        Files.createDirectories(path.parent)
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }
    private fun appendExaminer(path: Path, line: String) {
        Files.createDirectories(path.parent)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }
    private fun grade(
        taskToken: String, arm: Arm,
        challenge: List<Map<String, String>>, heldout: List<Map<String, String>>,
        truth: (Map<String, String>) -> Boolean,
        library: List<CapabilityDefinition>,
    ): String {
        val cost = MRX2Cost()
        val ledger = MRX2Ledger(challenge, StudyQueryController(challenge, challenge.size, truth),
            challenge.size, cost)
        var checkedCandidates = 0
        var passes = 0
        var chosen: Expr? = null
        for (body in arm.candidates) {
            val id = CanonicalJson.expr(body)
            checkedCandidates++
            val result = ledger.validate(id,
                { input -> Pack02Knowledge.predict(body, input, library) },
                "E1_EXAMINER", challenge.size, cost)
            if (result.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                passes++
                if (chosen == null) chosen = body
                if (passes >= 4) break
            }
        }
        val heldoutCorrect = chosen?.let { selected ->
            heldout.count { input -> Pack02Knowledge.predict(selected, input, library) == truth(input) }
        }
        return """{"task":${q(taskToken)},"arm":${q(arm.name)},"freezeDigest":${q(sha(arm.studentTrace))},"challengePassCount":$passes,"challengeCandidatesChecked":$checkedCandidates,"selectedExprHash":${chosen?.let { q(sha(CanonicalJson.expr(it))) } ?: "null"},"heldoutCorrect":${heldoutCorrect ?: "null"},"heldoutTotal":${heldout.size},"oracleNew":${cost.oracleNewQueries},"oracleReuse":${cost.oracleReuseUses},"gradeInterpreterCalls":${cost.interpreterCalls}}"""
    }

    @Test fun `actual evidence composition vs bounded enumeration same TRAIN cases`() {
        val tests = listOf(
            Task("mrx-base-or-r-d1", 20261111),
            Task("mrx-depth-w4", 20261110),
            Task("mrx-depth-r-xor-b2", 20261112),
            Task("mrx-if-nested-3", 20261109),
        )
        val library = C8TestBases.bases.library("FULL").definitions
        val outDir = Path.of("docs/research/sd03-e1-development-2026-11-07")
        Files.createDirectories(outDir)
        // Never replace a previous run's evidence. Archive prior runs externally.
        val marker = outDir.resolve("E1-RUN-STARTED.txt")
        require(!Files.exists(marker)) { "E1 evidence already exists: archive first, do not overwrite" }
        appendFreeze(marker,"E1 development attempt: started; no grades yet")
        var count = 0
        for (task in tests) {
            val world = MRXWorlds.baseline.single { it.id == task.id }
            val inputs = C8Domain.all().shuffled(Random(task.seed))
            val train = inputs.take(40).mapIndexed { i, v -> StudyCase("e1-train-$i", v, world.truth(v)) }
            val challenge = inputs.drop(40).take(20)
            val heldout = inputs.drop(60)
            val token = sha("${world.id}|${task.seed}").take(16)
            val frozen = ArrayList<Arm>()

            fun enumerationArm(name: String, useR32: Boolean): Arm {
                val started = System.nanoTime()
                val space = if (useR32) MRX2BSpace(
                    C8Domain.attributes,library,train,MRX2BStrategy.PAIRWISE,
                    atomLimit=12,maxCandidates=30_000,consistentLimit=2_000,
                    r31Schedule=MRX2BR31Schedule.QUOTA_IF_FIRST,
                    r31Quotas=MRX2BR31Quotas(),r31IfLiteralLimit=24,
                    r32IfPolicy=MRX2BR32IfPolicy.ROTATING_WITH_NESTED,
                ).generate() else MRX2BSpace(
                    C8Domain.attributes,library,train,MRX2BStrategy.PAIRWISE,
                    atomLimit=10,maxCandidates=30_000,consistentLimit=2_000,
                ).generate()
                val bodies = space.trace.candidates(MRX2BStage.RETAINED).map { it.body }
                val trace = """{"task":${q(token)},"arm":${q(name)},"trainCount":${train.size},"generated":${space.generated},"trainConsistent":${space.consistent},"retained":${space.retained},"searchInterpreterCalls":${space.trace.r31.interpreterCalls},"searchWallMillis":${space.wallMillis},"atomsHash":${q(sha(space.trace.atoms.joinToString("|")))},"candidateHashes":[${bodies.joinToString(",") { q(sha(CanonicalJson.expr(it))) }}]}"""
                return Arm(name,space.selected?.body,bodies,space.selected?.canonical,trace,
                    space.trace.r31.interpreterCalls,(System.nanoTime()-started)/1_000_000)
            }
            fun compositionArm(name: String, depth: Int): Arm {
                val started = System.nanoTime()
                val proposal = SD03E1RepoEvidenceAdapter.buildAndPropose(train,library,E1Config(
                    maxDepth=depth,beamWidthPerNode=5,maxTreeNodes=63,
                    maxPartitionAttempts=30_000,minExamplesPerChild=1,
                ))
                val r = proposal.result
                // Candidate is never eligible for Challenge if the independent TRAIN
                // executor did not confirm 40/40 (or if search was exhausted).
                val valid = r.status == E1Status.TRAIN_CONSISTENT_PROPOSAL &&
                    proposal.typecheckOk && proposal.realTrainKnown == train.size &&
                    proposal.realTrainCorrect == train.size
                val bodies = if (valid) listOf(requireNotNull(proposal.candidateBody)) else emptyList()
                val decisions = r.decisionEvidence.joinToString(",") { ev ->
                    """{"path":${q(ev.path)},"atomHash":${q(sha(ev.atomId))},"trainEvidenceIds":[${ev.supportingTrainingIds.joinToString(",") { q(it) }}],"trueCaseIds":[${ev.trueTrainingIds.joinToString(",") { q(it) }}],"falseCaseIds":[${ev.falseTrainingIds.joinToString(",") { q(it) }}],"gain":${ev.informationGain},"atomEvidence":${q(ev.atomEvidenceId)}}"""
                }
                val trace = """{"task":${q(token)},"arm":${q(name)},"trainDigest":${q(proposal.trainDigest)},"traceDigest":${q(proposal.traceDigest)},"status":${q(r.status.name)},"trainCorrectByTree":${r.trainCorrect},"realTrainCorrect":${proposal.realTrainCorrect},"realTrainKnown":${proposal.realTrainKnown},"typecheckOk":${proposal.typecheckOk},"trainCount":${train.size},"proposedNodes":${r.proposedNodes},"treeDepth":${r.maxDepthReached},"splitAttempts":${r.splitAttempts},"predictionReads":${r.predictionReads},"unknownPredictionsSkipped":${r.unknownPredictionsSkipped},"memoHits":${r.memoHits},"budgetHit":${r.budgetHit},"atomPreviewInterpreterCalls":${proposal.atomPreviewInterpreterCalls},"realTrainInterpreterCalls":${proposal.realTrainInterpreterCalls},"sourceAtomCount":${proposal.sourceAtomCount},"candidateEligible":$valid,"candidateCanonical":${proposal.candidateCanonical?.let(::q) ?: "null"},"decisions":[$decisions]}"""
                return Arm(name,proposal.candidateBody,bodies,proposal.candidateCanonical,trace,
                    proposal.atomPreviewInterpreterCalls+proposal.realTrainInterpreterCalls,
                    (System.nanoTime()-started)/1_000_000)
            }

            frozen += enumerationArm("LEGACY_10",false)
            frozen += enumerationArm("R32_NESTED_12",true)
            frozen += compositionArm("EVIDENCE_DEPTH_1",1)
            frozen += compositionArm("EVIDENCE_DEPTH_4",4)
            // Freeze all student outputs BEFORE examiner reveals any CHALLENGE labels.
            for (arm in frozen) {
                appendFreeze(outDir.resolve("FROZEN-$token-${arm.name}.json"),arm.studentTrace)
                count++
            }
            // Separate ledgers and equal CHALLENGE pools for each arm.
            for (arm in frozen) {
                appendExaminer(outDir.resolve("EXAMINER-AFTER-FREEZE.jsonl"),
                    grade(token,arm,challenge,heldout,world.truth,library))
            }
        }
        assertEquals(16,count)
        assertTrue(Files.exists(outDir.resolve("EXAMINER-AFTER-FREEZE.jsonl")))
    }
}
