package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exploratory integration/observer test. The six task definitions are visible in
 * this repository: NOT a formally independent closed-book generalization test.
 * Tests execute real student searches, each arm with separate challenge ledger.
 * The examiner's truth/heldout are used exclusively after the blind decision.
 */
class SD03BlindAttributionR2IntegrationTest {
    private data class Outcome(
        val sourcePassCount: Int,
        val selectedCanonical: String?,
        val heldoutCorrect: Int?,
        val generated: Int,
        val trainConsistent: Int,
        val retained: Int,
        val oracleNew: Int,
        val oracleReuse: Int,
        val validationInterpreterCalls: Int,
        val generationWallMillis: Long,
    )
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun q(s: String) = CanonicalJson.string(s)

    private fun grade(
        space: MRX2BSpaceResult,
        challenge: List<Map<String, String>>,
        heldout: List<Map<String, String>>,
        truth: (Map<String, String>) -> Boolean,
        library: List<CapabilityDefinition>,
    ): Outcome {
        val meter = MRX2Cost()
        val ledger = MRX2Ledger(challenge, StudyQueryController(challenge, challenge.size, truth), challenge.size, meter)
        var selected: StudyCandidate? = null
        var passes = 0
        for (c in space.trace.candidates(MRX2BStage.RETAINED)) {
            val check = ledger.validate(c.canonical,
                { input -> Pack02Knowledge.predict(c.body, input, library) }, "R2_GRADE", challenge.size, meter)
            if (check.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                passes++
                if (selected == null) selected = c
                if (passes >= 4) break
            }
        }
        // Only examiner-side grading after the candidate has been frozen.
        val heldCorrect = selected?.let { chosen ->
            heldout.count { Pack02Knowledge.predict(chosen.body, it, library) == truth(it) }
        }
        return Outcome(passes, selected?.canonical, heldCorrect, space.generated,
            space.consistent, space.retained, meter.oracleNewQueries, meter.oracleReuseUses,
            meter.interpreterCalls, space.wallMillis)
    }

    private fun snapshotJson(s: R2Snapshot, digest: String): String {
        val obs = s.observations.joinToString(",") { o ->
            val fields = o.input.toSortedMap().entries.joinToString(",") { (k,v) -> "${q(k)}:${q(v)}" }
            """{"id":${q(o.id)},"input":{$fields},"label":${o.label}}"""
        }
        val atoms = s.atoms.joinToString(",") { a ->
            val predictions = a.predictions.toSortedMap().entries.joinToString(",") { (k,v) -> "${q(k)}:${if (v == null) "null" else v}" }
            """{"id":${q(a.id)},"rank":${a.rank},"retained":${a.retained},"evidence":${q(a.evidenceId)},"predictions":{$predictions}}"""
        }
        val alts = s.alternatives.joinToString(",") { a ->
            """{"action":${q(a.action.name)},"atoms":[${a.selectedAtomIds.joinToString(",") { q(it) }}],"evidence":${q(a.evidenceId)}}"""
        }
        val lc = s.lifecycle
        return """{"id":${q(s.id)},"digest":${q(digest)},"observations":[$obs],"atoms":[$atoms],"lifecycle":{"generated":${lc.generated},"consistent":${lc.trainConsistent},"retained":${lc.retained},"capHit":${lc.generationCapHit},"evidence":${q(lc.evidenceId)}},"alternatives":[$alts],"allowed":[${s.allowedActions.map { q(it.name) }.sorted().joinToString(",")}]}"""
    }

    @Test fun `R2 decisions use genuine TRAIN preview and independent arm grading`() {
        val categories = listOf(MRXCategory.EXPRESSIBLE, MRXCategory.DEPTH, MRXCategory.IF_STRUCTURE)
        // These are additional DEVELOPMENT examples, not new closed-book tasks.
        val worlds = categories.flatMap { cat ->
            MRXWorlds.baseline.filter { it.category == cat }.sortedBy { it.id }.drop(2).take(2)
        }
        assertEquals(6, worlds.size)
        val library = C8TestBases.bases.library("FULL").definitions
        val lines = mutableListOf<String>()
        val decisions = mutableListOf<String>()
        val snapshots = mutableListOf<String>()
        for (world in worlds) for (seed in listOf(20261109, 20261110)) {
            val inputs = C8Domain.all().shuffled(Random(seed))
            val train = inputs.take(40).mapIndexed { i, m -> StudyCase("t-$i", m, world.truth(m)) }
            val challenge = inputs.drop(40).take(20)
            val heldout = inputs.drop(60)
            val token = sha("${world.id}:$seed").take(16)
            val base = MRX2BSpace(C8Domain.attributes, library, train, MRX2BStrategy.PAIRWISE, atomLimit=10).generate()
            val build = SD03R2RepoSnapshotAdapter.build(token, train, library, base)
            val decision = SD03BlindAttributionR2.decide(build.snapshot)
            // Decision must be fixed and recorded BEFORE any challenge grading below.
            snapshots += snapshotJson(build.snapshot, decision.snapshotDigest)
            val claims = decision.hypotheses.joinToString(",") { h ->
                """{"cause":${q(h.cause.name)},"status":${q(h.status.name)},"evidence":[${h.evidenceIds.joinToString(",") { q(it) }}],"explanation":${q(h.explanation)}}"""
            }
            val blocked = decision.blockedWitnesses.joinToString(",") { w ->
                """{"left":${q(w.leftId)},"right":${q(w.rightId)},"changed":[${w.differentAttributes.joinToString(",") { q(it) }}],"discardedSeparators":[${w.discardedSeparators.joinToString(",") { q(it) }}],"evidence":[${w.evidenceIds.joinToString(",") { q(it) }}]}"""
            }
            decisions += """{"task":${q(token)},"seed":$seed,"snapshotDigest":${q(decision.snapshotDigest)},"action":${q(decision.action.name)},"reason":${q(decision.reason)},"blocked":[$blocked],"hypotheses":[$claims],"evidenceIds":[${decision.actionEvidenceIds.joinToString(",") { q(it) }}]}"""
            val control = grade(base, challenge, heldout, world.truth, library)
            val diversity = MRX2BSpace(C8Domain.attributes, library, train, MRX2BStrategy.DIVERSITY, atomLimit=10).generate()
            val generic = grade(diversity, challenge, heldout, world.truth, library)
            val attributionSpace = when (decision.action) {
                R2Action.BASELINE_10 -> MRX2BSpace(C8Domain.attributes, library, train, MRX2BStrategy.BASELINE, atomLimit=10).generate()
                R2Action.DIVERSITY_10 -> MRX2BSpace(C8Domain.attributes, library, train, MRX2BStrategy.DIVERSITY, atomLimit=10).generate()
                R2Action.PAIRWISE_12 -> MRX2BSpace(C8Domain.attributes, library, train, MRX2BStrategy.PAIRWISE, atomLimit=12).generate()
                else -> null // Reuses CONTROL result; DO NOT call it an independent rerun.
            }
            val attr = if (attributionSpace == null) control else grade(attributionSpace, challenge, heldout, world.truth, library)
            for ((arm, result) in listOf("CONTROL" to control, "GENERIC" to generic, "ATTRIBUTION" to attr)) {
                val reused = arm == "ATTRIBUTION" && attributionSpace == null
                lines += """{"task":${q(token)},"seed":$seed,"arm":${q(arm)},"action":${q(if (arm == "CONTROL") "KEEP" else if (arm == "GENERIC") "DIVERSITY_10" else decision.action.name)},"sourcePassCount":${result.sourcePassCount},"heldoutCorrect":${result.heldoutCorrect ?: "null"},"heldoutTotal":21,"generated":${result.generated},"trainConsistent":${result.trainConsistent},"retained":${result.retained},"oracleNew":${result.oracleNew},"oracleReuse":${result.oracleReuse},"validationInterpreterCalls":${result.validationInterpreterCalls},"generationWallMillis":${result.generationWallMillis},"previewInterpreterCalls":${if (arm == "ATTRIBUTION") build.previewInterpreterCalls else 0},"reusedControl":$reused,"generationInterpreterCalls":"NOT_INSTRUMENTED"}"""
            }
        }
        val dir = Path.of("docs/research/sd03-blind-r2-2026-11-07")
        Files.createDirectories(dir)
        Files.write(dir.resolve("DECISIONS-R2.jsonl"), decisions)
        Files.write(dir.resolve("SNAPSHOTS-R2.jsonl"), snapshots)
        Files.write(dir.resolve("ARMS-R2.jsonl"), lines)
        assertEquals(12, decisions.size)
        assertEquals(12, snapshots.size)
        assertEquals(36, lines.size)
    }
}
