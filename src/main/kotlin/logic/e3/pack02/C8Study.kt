// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-C8-02 四属性 S/T 自主组合实验；考官侧目标独立于学生 R AST。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

data class StudyStage(
    val name: String,
    val status: String,
    val selectedUsesR: Boolean,
    val selectedUsesS: Boolean,
    val heldoutCorrect: Int,
    val activated: Boolean,
    val failure: String?,
    val generated: Int,
    val consistent: Int,
)

data class StudyResult(
    val rActive: Boolean,
    val s: StudyStage,
    val t: StudyStage?,
    val sActivationId: String?,
    val tActivationId: String?,
)

object C8Study {
    private const val MAX_DEPTH = 3
    private const val MAX_CAND = 20000

    fun run(): StudyResult {
        val path = Files.createTempFile("c8study-", ".sqlite")
        try {
            val (p, q, r, rDef) = buildR(path)
            val samples = samples()
            val sTruth = { m: Map<String, String> -> (m["a"] == "1" && m["c"] == "1") || m["d"] == "1" }
            val trainS = samples.take(8).mapIndexed { i, m -> Pack02Sample("s-tr-$i", m, sTruth(m)) }
            val heldS = samples.drop(8).mapIndexed { i, m -> Pack02Sample("s-ho-$i", m, sTruth(m)) }
            val fullS = Pack02Knowledge.reuse(
                "study.s.full", listOf("a", "b", "c", "d"), trainS, heldS, path,
                listOf(p, q, r), maxDepth = MAX_DEPTH, maxCandidates = MAX_CAND,
            )
            val sBody = fullS.selectedBody
            val sStage = StudyStage(
                "S", fullS.filterStatus, sBody?.let { r.id in Pack02Knowledge.idsIn(it) } ?: false, false,
                fullS.heldoutCorrect, false, fullS.failureCode, fullS.generated, fullS.consistent,
            )
            if (sBody == null || fullS.heldoutCorrect != heldS.size) {
                return StudyResult(true, sStage, null, null, null)
            }
            val sActivation = activate(sBody, "pack02r.study.s", listOf("a", "b", "c", "d"), heldS, path)
            val sRef = sActivation.definition?.let { d -> KnowledgeRef(d.id, d.version, sActivation.contentHash!!) }
            if (!sActivation.active || sRef == null) {
                return StudyResult(true, sStage.copy(activated = false, failure = sActivation.failure), null, null, null)
            }
            val sStageActive = sStage.copy(activated = true, failure = null)
            val sTruthT = { m: Map<String, String> ->
                ((m["a"] == "1" && m["c"] == "1") || m["d"] == "1") && m["b"] == "1"
            }
            val trainT = samples.take(8).mapIndexed { i, m -> Pack02Sample("t-tr-$i", m, sTruthT(m)) }
            val heldT = samples.drop(8).mapIndexed { i, m -> Pack02Sample("t-ho-$i", m, sTruthT(m)) }
            val fullT = Pack02Knowledge.reuse(
                "study.t.full", listOf("a", "b", "c", "d"), trainT, heldT, path,
                listOf(p, q, r, sRef), maxDepth = MAX_DEPTH, maxCandidates = MAX_CAND,
            )
            val tBody = fullT.selectedBody
            val tStage = StudyStage(
                "T", fullT.filterStatus, tBody?.let { r.id in Pack02Knowledge.idsIn(it) } ?: false,
                tBody?.let { sRef.id in Pack02Knowledge.idsIn(it) } ?: false,
                fullT.heldoutCorrect, false, fullT.failureCode, fullT.generated, fullT.consistent,
            )
            val tActivation = if (tBody != null && fullT.heldoutCorrect == heldT.size) {
                activate(tBody, "pack02r.study.t", listOf("a", "b", "c", "d"), heldT, path)
            } else null
            return StudyResult(
                rActive = true, s = sStageActive, t = tStage.copy(activated = tActivation?.active == true),
                sActivationId = sRef.id,
                tActivationId = tActivation?.definition?.id,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun buildR(path: Path): Quad<KnowledgeRef, KnowledgeRef, KnowledgeRef, CapabilityDefinition> {
        val p = Pack02Knowledge.learn(
            "study.p", listOf("a", "b"),
            listOf(ps("0", "0", false), ps("1", "0", true), ps("0", "1", false), ps("1", "1", true)),
            listOf(ps("0", "2", false), ps("1", "2", true), ps("2", "0", false), ps("2", "1", false)),
            path, maxDepth = 2,
        )
        val q = Pack02Knowledge.learn(
            "study.q", listOf("b", "c"),
            listOf(qs("0", "0", false), qs("0", "1", true), qs("1", "0", false), qs("1", "1", true)),
            listOf(qs("2", "0", false), qs("2", "1", true), qs("0", "2", false), qs("1", "2", false)),
            path, maxDepth = 2,
        )
        val pRef = KnowledgeRef(p.definition!!.id, p.definition.version, p.contentHash!!)
        val qRef = KnowledgeRef(q.definition!!.id, q.definition.version, q.contentHash!!)
        val world = ActiveWorlds.generate(102, "and_ac")
        val result = ActiveSession(world.studentView(pRef, qRef, 3), world.controller(3), path).run()
        val activation = world.evaluator().activate(result.selected!!, path)
        require(activation.active) { "R not active: ${activation.failure}" }
        return Quad(pRef, qRef, KnowledgeRef(activation.definition!!.id, 1, activation.contentHash!!), activation.definition)
    }

    private fun activate(body: Expr, id: String, inputs: List<String>, held: List<Pack02Sample>, path: Path): ActiveActivation {
        val definition = CapabilityDefinition(id, 1, inputs.associateWith { CapType.Str }, CapType.Bool3, body)
        val registry = SqliteCapabilityRegistry(path)
        try {
            val p = registry.propose(definition, """{"source":"c8_study","attribution":"student_composed"}""")
            if (!p.ok) return ActiveActivation(false, null, null, null, p.code ?: "proposal_failed")
            val cases = held.map { VerificationCase(it.id, it.values.mapValues { (_, v) -> CapValue.Str(v) }, EvalResult.Known(CapValue.Bool(it.label))) }
            if (cases.size < 4) return ActiveActivation(false, p.contentHash, null, null, "heldout_too_small")
            val suite = TrustedVerificationSuite.create("$id.heldout", 1, cases)
            val suites = TrustedSuiteRegistry()
            suites.register(suite)
            val v = CapabilityVerificationService(registry, suites).verify(id, 1, suite.suiteId, suite.suiteVersion)
            if (!v.recorded || v.outcome.verdict.name != "PASS") return ActiveActivation(false, p.contentHash, v.runId, null, v.code ?: "verification_failed")
            val a = CapabilityActivationService(registry, suites).activate(id, 1, v.runId!!)
            return ActiveActivation(a.ok, p.contentHash, v.runId, if (a.ok) definition else null, if (a.ok) null else a.code)
        } finally {
            registry.close()
        }
    }

    private fun samples(): List<Map<String, String>> {
        val all = ArrayList<Map<String, String>>()
        for (a in listOf("0", "1")) for (b in listOf("0", "1")) for (c in listOf("0", "1")) for (d in listOf("0", "1")) {
            all += mapOf("a" to a, "b" to b, "c" to c, "d" to d)
        }
        return all.shuffled(Random(2026))
    }

    private fun ps(a: String, b: String, label: Boolean) = Pack02Sample("sp-${a}${b}", mapOf("a" to a, "b" to b), label)
    private fun qs(b: String, c: String, label: Boolean) = Pack02Sample("sq-${b}${c}", mapOf("b" to b, "c" to c), label)
}

data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
