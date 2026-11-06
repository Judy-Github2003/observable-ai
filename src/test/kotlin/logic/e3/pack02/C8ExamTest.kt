// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：C8-03 开发矩阵与六世界可表达性检查；正式考试另行冻结后运行。
package logic.e3.pack02

import kotlinx.serialization.json.*
import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C8ExamTest {
    private val split: C8Split get() = C8Data.freeze(C8Experiment.SPLIT_SEED)

    @Test
    fun `development matrix runs strategies groups and T no-S control`() {
        val runs = C8Experiment.runDevelopment()
        val w1 = runs.first { it.world == "w1-or-r-d1" && it.group == "FULL" && it.strategy == "utility" }
        assertTrue(w1.s.activation?.active == true, "W1 S must activate")
        assertTrue(w1.s.usesR, "W1 FULL utility should choose R without a hint")
        val t = assertNotNull(w1.t, "W1 T must run after S activation")
        assertTrue(t.usesS, "W1 T must depend on S")
        assertTrue(t.activation?.active == true, "W1 T must activate")

        assertEquals(
            setOf("utility", "divergence", "fixed", "random"),
            runs.filter { it.world == "w1-or-r-d1" && it.group == "FULL" }.map { it.strategy }.toSet(),
        )
        assertTrue(runs.any { it.group == "NO-R" })
        assertTrue(runs.any { it.group == "BASE" })
        assertTrue(runs.any { it.noS != null })

        for (run in runs) {
            for (stage in listOfNotNull(run.s, run.t, run.noS)) {
                if (stage.result.status == "IDENTIFIED") {
                    assertNotNull(stage.result.selected, "${run.world}/${run.strategy}/${stage.stage}")
                } else {
                    assertNull(stage.result.selected)
                }
                if (stage.activation?.active == true) {
                    assertEquals(stage.heldoutTotal, stage.heldoutCorrect, "${run.world}/${run.strategy}/${stage.stage}")
                    assertEquals(0, stage.audit?.mismatches, "${run.world}/${run.strategy}/${stage.stage}")
                } else {
                    assertNull(stage.audit)
                }
            }
        }

        val summary = C8Json.summary(runs)
        assertEquals(0, summary["wrongActivations"]!!.jsonPrimitive.int)
        val meta = buildJsonObject {
            put("experiment", "PACK-02R-C8-03")
            put("phase", "development")
            put("split_seed", split.seed)
            put("split_digest", split.digest)
            put("split_sizes", buildJsonObject {
                put("train", split.train.size)
                put("sTrain", C8Data.S_TRAIN_SIZE)
                put("tTrain", C8Data.T_TRAIN_SIZE)
                put("query", split.query.size)
                put("heldout", split.heldout.size)
            })
            put("budget", buildJsonObject {
                put("maxDepth", C8Experiment.BUDGET.maxDepth)
                put("maxCandidates", C8Experiment.BUDGET.maxCandidates)
                put("maxCallsPerCapability", C8Experiment.BUDGET.maxCallsPerCapability)
                put("queryBudget", C8Experiment.BUDGET.queryBudget)
            })
            put("summary", summary)
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack02r-c8-03-development.json")
        C8Json.write(out, runs, meta)
        assertTrue(Files.size(out) > 1000)
    }

    @Test
    fun `all six worlds are expressible and produce an S candidate`() {
        for (world in C8Worlds.all) {
            val path = C8TestBases.bases.newRun("FULL")
            try {
                val library = C8TestBases.bases.library("FULL")
                val reference = reference(world, library)
                assertEquals(
                    81,
                    C8Domain.all().count { Pack02Knowledge.predict(reference, it, library.definitions) == world.sTruth(it) },
                    "${world.id}: reference body",
                )
                val training = split.train.take(C8Data.S_TRAIN_SIZE).mapIndexed { index, input ->
                    StudyCase("s-$index", input, world.sTruth(input))
                }
                val view = StudyView(
                    world.sessionId, "S", training, split.query, 20, library.refs, C8Domain.attributes,
                    3, 50_000, 24, "utility", 0,
                )
                val result = ActiveStudySession(
                    view, StudyQueryController(split.query, 20, world.sTruth), path,
                ).run()
                assertEquals("IDENTIFIED", result.status, "${world.id}: ${result.failure}")
                assertNotNull(result.selected)
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

    /** 考官侧参考表达式只用于证明目标在冻结预算内可表达，不进入学生。 */
    private fun reference(world: C8World, library: C8Library): Expr {
        fun arg(name: String) = Expr.Arg(name, CapType.Str)
        fun eq(name: String, value: String) = Expr.Eq(arg(name), Expr.Const(CapValue.Str(value)))
        fun call(id: String, args: Map<String, Expr>): Expr {
            val definition = library.definitions.first { it.id == id }
            return Expr.Call(definition.id, definition.version, definition.contentHash(), args)
        }
        val p = call("c8v3.p", mapOf("a" to arg("a"), "b" to arg("b")))
        val q = call("c8v3.q", mapOf("b" to arg("b"), "c" to arg("c")))
        val r = call("pack02r.active.r", mapOf("a" to arg("a"), "b" to arg("b"), "c" to arg("c")))
        return when (world.id) {
            "w1-or-r-d1" -> Expr.Or(r, eq("d", "1"))
            "w2-or-r-d2" -> Expr.Or(r, eq("d", "2"))
            "w3-xnor-r-d1" -> Expr.Eq(r, eq("d", "1"))
            "w4-if-r-d1-d0" -> Expr.If(r, eq("d", "1"), eq("d", "0"))
            "w5-xnor-p-d2" -> Expr.Eq(p, eq("d", "2"))
            "w6-or-q-a2" -> Expr.Or(q, eq("a", "2"))
            else -> error("unknown world")
        }
    }
}
