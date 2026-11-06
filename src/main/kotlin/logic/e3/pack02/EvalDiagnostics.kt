// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL-02 eval-101 验证失败码诊断；只读 B0 outcome，不修改学生。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path

data class Eval101Diagnostic(
    val status: String,
    val selected: Boolean,
    val outcomeCode: String?,
    val verdict: String?,
    val caseCount: Int,
    val passedCount: Int,
    val definiteCount: Int,
    val trueCount: Int,
    val falseCount: Int,
    val recorded: Boolean,
)

object EvalDiagnostics {
    fun eval101(): Eval101Diagnostic {
        val path = Files.createTempFile("diag-101-", ".sqlite")
        try {
            val (p, q) = base(path)
            val world = ActiveWorlds.generate(101, "and_ac")
            val result = ActiveSession(world.studentView(p, q, 3), world.controller(3), path).run()
            val selected = result.selected ?: return Eval101Diagnostic(result.status, false, null, null, 0, 0, 0, 0, 0, false)
            val definition = CapabilityDefinition(
                "eval101.diag", 1, mapOf("a" to CapType.Str, "b" to CapType.Str, "c" to CapType.Str),
                CapType.Bool3, selected.body,
            )
            val registry = SqliteCapabilityRegistry(path)
            try {
                val proposed = registry.propose(definition, "{\"source\":\"eval_diagnostic\"}")
                if (!proposed.ok) return Eval101Diagnostic(result.status, true, proposed.code, null, 0, 0, 0, 0, 0, false)
                val cases = world.heldout.map {
                    VerificationCase(it.id, it.values.mapValues { (_, v) -> CapValue.Str(v) }, EvalResult.Known(CapValue.Bool(it.label)))
                }
                val suite = TrustedVerificationSuite.create("eval101.diag", 1, cases)
                val suites = TrustedSuiteRegistry()
                suites.register(suite)
                val v = CapabilityVerificationService(registry, suites)
                    .verify(definition.id, definition.version, suite.suiteId, suite.suiteVersion)
                return Eval101Diagnostic(
                    result.status, true, v.outcome.code, v.outcome.verdict.name,
                    v.outcome.caseCount, v.outcome.passedCount, v.outcome.definiteCount,
                    v.outcome.trueCount, v.outcome.falseCount, v.recorded,
                )
            } finally {
                registry.close()
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun base(path: Path): Pair<KnowledgeRef, KnowledgeRef> {
        val p = Pack02Knowledge.learn(
            "diag.p", listOf("a", "b"),
            listOf(ps("0", "0", false), ps("1", "0", true), ps("0", "1", false), ps("1", "1", true)),
            listOf(ps("0", "2", false), ps("1", "2", true), ps("2", "0", false), ps("2", "1", false)),
            path, maxDepth = 2,
        )
        val q = Pack02Knowledge.learn(
            "diag.q", listOf("b", "c"),
            listOf(qs("0", "0", false), qs("0", "1", true), qs("1", "0", false), qs("1", "1", true)),
            listOf(qs("2", "0", false), qs("2", "1", true), qs("0", "2", false), qs("1", "2", false)),
            path, maxDepth = 2,
        )
        return KnowledgeRef(p.definition!!.id, p.definition.version, p.contentHash!!) to
            KnowledgeRef(q.definition!!.id, q.definition.version, q.contentHash!!)
    }

    private fun ps(a: String, b: String, label: Boolean) = Pack02Sample("dp-${a}${b}", mapOf("a" to a, "b" to b), label)
    private fun qs(b: String, c: String, label: Boolean) = Pack02Sample("dq-${b}${c}", mapOf("b" to b, "c" to c), label)
}
