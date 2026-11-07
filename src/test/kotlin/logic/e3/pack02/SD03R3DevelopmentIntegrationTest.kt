package logic.e3.pack02

import logic.capability.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R3 DEVELOPMENT integration. Known task(s), never count as independent blind exam.
 * Chronological files are durable before any post-decision rerun or grading.
 */
class SD03R3DevelopmentIntegrationTest {
    private fun q(x: String) = CanonicalJson.string(x)
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun appendDurable(dir: Path, filename: String, line: String) {
        Files.createDirectories(dir)
        java.nio.channels.FileChannel.open(dir.resolve(filename),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            val buf = StandardCharsets.UTF_8.encode(line + "\n")
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(true)
        }
    }
    private fun snapshotJson(s: R2Snapshot): String {
        val observations = s.observations.joinToString(",") { o ->
            """{"id":${q(o.id)},"inputs":{${o.input.toSortedMap().entries.joinToString(",") { (k,v) -> "${q(k)}:${q(v)}" }}},"label":${o.label}}"""
        }
        val atoms = s.atoms.joinToString(",") { a ->
            """{"id":${q(a.id)},"rank":${a.rank},"retained":${a.retained},"evidenceId":${q(a.evidenceId)},"predictions":{${a.predictions.toSortedMap().entries.joinToString(",") { (k,v) -> "${q(k)}:${v ?: "null"}" }}}}"""
        }
        val alternatives = s.alternatives.joinToString(",") { alt ->
            """{"action":${q(alt.action.name)},"selectedAtomIds":[${alt.selectedAtomIds.joinToString(",") { q(it) }}],"evidenceId":${q(alt.evidenceId)}}"""
        }
        val l=s.lifecycle
        return """{"id":${q(s.id)},"digest":${q(SD03BlindAttributionR2.snapshotDigest(s))},"observations":[$observations],"atoms":[$atoms],"lifecycle":{"generated":${l.generated},"trainConsistent":${l.trainConsistent},"retained":${l.retained},"capHit":${l.generationCapHit},"evidenceId":${q(l.evidenceId)}},"alternatives":[$alternatives],"allowed":[${s.allowedActions.map { q(it.name) }.sorted().joinToString(",")}]}"""
    }
    private fun decisionJson(d: R3Decision): String {
        val claims=d.claims.joinToString(",") { c ->
            """{"finding":${q(c.finding.name)},"status":${q(c.status.name)},"evidenceIds":[${c.evidenceIds.joinToString(",") { q(it) }}],"explanation":${q(c.explanation)}}"""
        }
        return """{"stage":${q(d.stage.name)},"snapshotId":${q(d.snapshotId)},"snapshotDigest":${q(d.snapshotDigest)},"previousSnapshotDigest":${d.previousSnapshotDigest?.let(::q) ?: "null"},"previousAction":${d.previousAction?.let { q(it.name) } ?: "null"},"action":${q(d.action.name)},"reason":${q(d.reasonCode)},"diagnosticOnly":${d.diagnosticOnly},"claims":[$claims],"evidenceIds":[${d.evidenceIds.joinToString(",") { q(it) }}]}"""
    }
    private data class Grade(val passes: Int, val heldoutCorrect: Int?, val oracleNew: Int,
                             val oracleReuse: Int, val interpreterCalls: Int)
    private fun grade(space: MRX2BSpaceResult, challenge: List<Map<String,String>>,
                      heldout: List<Map<String,String>>, truth: (Map<String,String>) -> Boolean,
                      lib: List<CapabilityDefinition>): Grade {
        val cost=MRX2Cost()
        val ledger=MRX2Ledger(challenge, StudyQueryController(challenge, challenge.size, truth), challenge.size, cost)
        var passes=0; var selected: StudyCandidate? = null
        for (candidate in space.trace.candidates(MRX2BStage.RETAINED)) {
            val check=ledger.validate(candidate.canonical,
                { input -> Pack02Knowledge.predict(candidate.body,input,lib) }, "R3_GRADE", challenge.size, cost)
            if (check.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                passes++; if(selected==null) selected=candidate
                if (passes >= 4) break
            }
        }
        return Grade(passes,selected?.let { c -> heldout.count { Pack02Knowledge.predict(c.body,it,lib)==truth(it) } },
            cost.oracleNewQueries,cost.oracleReuseUses,cost.interpreterCalls)
    }
    private fun rerun(action: R3Action, train: List<StudyCase>, lib: List<CapabilityDefinition>): MRX2BSpaceResult? =
        when (action) {
            R3Action.PROBE_BASELINE_10 -> MRX2BSpace(C8Domain.attributes,lib,train,MRX2BStrategy.BASELINE,atomLimit=10).generate()
            R3Action.PROBE_DIVERSITY_10 -> MRX2BSpace(C8Domain.attributes,lib,train,MRX2BStrategy.DIVERSITY,atomLimit=10).generate()
            R3Action.PROBE_PAIRWISE_12 -> MRX2BSpace(C8Domain.attributes,lib,train,MRX2BStrategy.PAIRWISE,atomLimit=12).generate()
            else -> null
        }
    @Test fun `R3 sequential rerun and truthful diagnostics on development worlds`() {
        val scenarios=listOf("mrx-if-nested-3" to 20261109, "mrx-depth-w4" to 20261110)
        val lib=C8TestBases.bases.library("FULL").definitions
        val dir=Path.of("docs/research/sd03-r3-development-2026-11-07")
        // Do not overwrite prior logs silently. DeepSeek should use fresh run-specific path and Console run ID.
        for ((worldId,seed) in scenarios) {
            val world=MRXWorlds.baseline.single { it.id == worldId }
            val split=C8Domain.all().shuffled(Random(seed))
            val train=split.take(40).mapIndexed { idx, m -> StudyCase("t-$idx",m,world.truth(m)) }
            val challenge=split.drop(40).take(20)
            val heldout=split.drop(60)
            val token=sha("$worldId:$seed").take(16)
            val baseline=MRX2BSpace(C8Domain.attributes,lib,train,MRX2BStrategy.PAIRWISE,atomLimit=10).generate()
            val preview=SD03R2RepoSnapshotAdapter.build(token,train,lib,baseline)
            val before=preview.snapshot
            // 1. freeze snapshot and decision BEFORE any post-decision student action or grading.
            appendDurable(dir,"CHRONOLOGY.jsonl", """{"task":${q(token)},"event":"FROZEN_BEFORE","snapshot":${snapshotJson(before)}}""")
            val first=SD03SequentialAttributionR3.first(before)
            appendDurable(dir,"CHRONOLOGY.jsonl", """{"task":${q(token)},"event":"DECISION_0","decision":${decisionJson(first)}}""")
            val repair=rerun(first.action,train,lib)
            var second: R3Decision?=null
            if(repair!=null) {
                val after=SD03R3TraceAdapter.afterRealSearch(before,first.action,repair,token)
                appendDurable(dir,"CHRONOLOGY.jsonl", """{"task":${q(token)},"event":"FROZEN_AFTER","snapshot":${snapshotJson(after)}}""")
                second=SD03SequentialAttributionR3.afterFirstIntervention(before,first,after)
                appendDurable(dir,"CHRONOLOGY.jsonl", """{"task":${q(token)},"event":"DECISION_1","decision":${decisionJson(second)}}""")
                // Diagnostic next actions are not repairs. Instrument actual producer before counting them as completed.
                if(second.diagnosticOnly) {
                    val generated=repair.trace.candidates(MRX2BStage.GENERATED)
                    appendDurable(dir,"CHRONOLOGY.jsonl", """{"task":${q(token)},"event":"DIAGNOSTIC_SUMMARY","request":${q(second.action.name)},"generated":${generated.size},"capPressure":${repair.generated >= 30000},"type":"OBSERVATION_NOT_PROOF"}""")
                }
            }
            // 2. Only the examiner (after decision freeze) may score CHALLENGE and HELDOUT.
            val control=grade(baseline,challenge,heldout,world.truth,lib)
            val genericSearch=MRX2BSpace(C8Domain.attributes,lib,train,MRX2BStrategy.DIVERSITY,atomLimit=10).generate()
            val generic=grade(genericSearch,challenge,heldout,world.truth,lib)
            val attribution=if(repair!=null) grade(repair,challenge,heldout,world.truth,lib) else control
            for((arm,result) in listOf("CONTROL" to control,"GENERIC" to generic,"ATTRIBUTION" to attribution)) {
                appendDurable(dir,"ARMS.jsonl", """{"task":${q(token)},"seed":$seed,"arm":${q(arm)},"sourcePassCount":${result.passes},"heldoutCorrect":${result.heldoutCorrect ?: "null"},"heldoutTotal":21,"oracleNew":${result.oracleNew},"oracleReuse":${result.oracleReuse},"validationInterpreterCalls":${result.interpreterCalls},"previewInterpreterCalls":${if(arm=="ATTRIBUTION") preview.previewInterpreterCalls else 0},"reusedControl":${arm=="ATTRIBUTION" && repair==null},"generationInterpreterCalls":"NOT_INSTRUMENTED"}""")
            }
            assertEquals(40, train.size)
            println("R3 dev=$token first=${first.action} next=${second?.action} (NOT independent closed book)")
        }
    }
}
