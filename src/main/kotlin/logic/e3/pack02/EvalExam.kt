// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL-B 多世界考试；只使用冻结的 StudentView/QueryController/ActiveSession。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path

data class EvalRun(
    val world: String,
    val family: String,
    val strategy: String,
    val seed: Int,
    val status: String,
    val generated: Int,
    val initialCandidates: Int,
    val initialClasses: Int,
    val queries: Int,
    val remainingCandidates: Int,
    val remainingClasses: Int,
    val activated: Boolean,
    val heldoutCorrect: Int?,
    val failure: String?,
)

object EvalExam {
    private val specs = listOf(
        ExamSpec(101, "and_ac", 3),
        ExamSpec(102, "and_ac", 3),
        ExamSpec(201, "or_ac", 3),
        ExamSpec(202, "or_ac", 3),
        ExamSpec(301, "outside_b2", 3),
        ExamSpec(401, "and_ac", 1),
    )

    fun runAll(): List<EvalRun> {
        val out = ArrayList<EvalRun>()
        for (spec in specs) {
            for (strategy in listOf("active", "fixed", "random")) {
                val seeds = if (strategy == "random") listOf(1, 2, 3) else listOf(0)
                for (seed in seeds) out += runOne(spec, strategy, seed)
            }
        }
        return out
    }

    internal fun runOne(spec: ExamSpec, strategy: String, seed: Int): EvalRun {
        val path = Files.createTempFile("eval-", ".sqlite")
        return try {
            val (p, q) = base(path)
            val world = ActiveWorlds.generate(spec.seed, spec.family)
            val view = world.studentView(p, q, spec.budget)
            val controller = world.controller(spec.budget)
            val result = ActiveSession(view, controller, path, strategy = strategy, seed = seed).run()
            val activation = result.selected?.let { world.evaluator().activate(it, path) }
            val defs = Pack02Knowledge.resolveActive(path, listOf(p, q)) ?: emptyList()
            val correct = result.selected?.let { candidate ->
                world.evaluator().heldoutForEvaluator().count { s ->
                    Pack02Knowledge.predict(candidate.body, s.values, defs) == s.label
                }
            }
            EvalRun(
                world = "eval-${spec.seed}",
                family = spec.family,
                strategy = if (strategy == "random") "random-$seed" else strategy,
                seed = spec.seed,
                status = result.status,
                generated = result.generated,
                initialCandidates = result.initialCandidates,
                initialClasses = result.initialClasses,
                queries = result.rounds.size,
                remainingCandidates = result.remainingCandidates.size,
                remainingClasses = result.remainingClasses,
                activated = activation?.active == true,
                heldoutCorrect = correct,
                failure = activation?.failure ?: result.failure,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun base(path: Path): Pair<KnowledgeRef, KnowledgeRef> {
        val p = Pack02Knowledge.learn(
            "eval.p", listOf("a", "b"),
            listOf(ps("0", "0", false), ps("1", "0", true), ps("0", "1", false), ps("1", "1", true)),
            listOf(ps("0", "2", false), ps("1", "2", true), ps("2", "0", false), ps("2", "1", false)),
            path, maxDepth = 2,
        )
        val q = Pack02Knowledge.learn(
            "eval.q", listOf("b", "c"),
            listOf(qs("0", "0", false), qs("0", "1", true), qs("1", "0", false), qs("1", "1", true)),
            listOf(qs("2", "0", false), qs("2", "1", true), qs("0", "2", false), qs("1", "2", false)),
            path, maxDepth = 2,
        )
        return KnowledgeRef(p.definition!!.id, p.definition.version, p.contentHash!!) to
            KnowledgeRef(q.definition!!.id, q.definition.version, q.contentHash!!)
    }

    private fun ps(a: String, b: String, label: Boolean) = Pack02Sample("p-${a}${b}", mapOf("a" to a, "b" to b), label)
    private fun qs(b: String, c: String, label: Boolean) = Pack02Sample("q-${b}${c}", mapOf("b" to b, "c" to c), label)
}

data class ExamSpec(val seed: Int, val family: String, val budget: Int)
