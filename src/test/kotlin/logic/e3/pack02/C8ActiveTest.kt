// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-C8-03 学生主动学习能力断言；不是只跑实验写 JSON。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C8ActiveTest {
    private val split: C8Split get() = C8Data.freeze(C8Experiment.SPLIT_SEED)
    private val world: C8World get() = C8Worlds.all[0]

    private fun sView(
        path: Path,
        library: C8Library,
        strategy: String = "utility",
        queryPool: List<Map<String, String>> = split.query,
        training: List<StudyCase> = sTraining(),
    ): StudyView = StudyView(
        world.sessionId, "S", training, queryPool, 20, library.refs, C8Domain.attributes,
        3, 50_000, 24, strategy, 0,
    )

    private fun sTraining(): List<StudyCase> =
        split.train.take(C8Data.S_TRAIN_SIZE).mapIndexed { index, input ->
            StudyCase("s-tr-$index", input, world.sTruth(input))
        }

    private fun tTraining(): List<StudyCase> =
        split.train.take(C8Data.T_TRAIN_SIZE).mapIndexed { index, input ->
            StudyCase("t-tr-$index", input, world.tTruth(input))
        }

    @Test
    fun `S active learning identifies activates and T uses S dependency`() {
        val run = C8Experiment.runWorld(world, "FULL", "utility", withNoS = true, C8TestBases.bases)
        val s = run.s
        assertEquals("IDENTIFIED", s.result.status)
        assertTrue(s.result.initialClasses > 1, "active learning must start from real ambiguity")
        assertTrue(s.result.rounds.isNotEmpty(), "S must issue at least one active query")
        assertTrue(s.result.rounds.all { it.eliminated > 0 }, "each query must eliminate candidates")
        assertEquals(s.result.rounds.size, s.result.rounds.map { it.sequence }.distinct().size)
        assertEquals(s.result.rounds.size, s.result.rounds.map { C8Domain.key(it.input) }.distinct().size)
        assertEquals(27, s.heldoutCorrect)
        assertTrue(s.activation?.active == true)
        assertEquals(0, s.audit?.mismatches)
        assertTrue(s.usesR, "W1 should select an R-using candidate without preferredId")
        assertTrue(s.result.selectionReason!!.contains("strategy=utility"))

        val t = assertNotNull(run.t)
        assertEquals("IDENTIFIED", t.result.status)
        assertTrue(t.usesS, "T must depend on the activated S")
        assertTrue(t.activation?.active == true)
        assertEquals(27, t.heldoutCorrect)
        assertEquals(0, t.audit?.mismatches)

        val noS = assertNotNull(run.noS)
        assertEquals("NO_CANDIDATE", noS.result.status)
        assertNull(noS.result.selected)
        assertNull(noS.activation)
    }

    @Test
    fun `query controller refuses heldout duplicate out-of-pool and over-budget queries`() {
        val path = C8TestBases.bases.newRun("FULL")
        try {
            val library = C8TestBases.bases.library("FULL")
            val view = sView(path, library)
            val fields = view.javaClass.declaredFields.map { it.name }
            assertFalse(fields.any { it in setOf("family", "target", "truth", "heldout", "oracle") }, fields.toString())

            val controller = StudyQueryController(split.query, 20, world.sTruth)
            val result = ActiveStudySession(view, controller, path).run()
            assertEquals("IDENTIFIED", result.status)
            assertEquals(result.rounds.size, controller.oracleCallCount())
            assertTrue(result.rounds.isNotEmpty())

            assertFailsWith<IllegalArgumentException> {
                controller.freeze(99, split.heldout[0], mapOf("x" to true), listOf("x"))
            }
            val first = result.rounds[0]
            assertFailsWith<IllegalArgumentException> {
                controller.freeze(99, first.input, mapOf("x" to true), listOf("x"))
            }
            val outside = C8Domain.all().first { it !in split.query && it !in split.heldout }
            assertFailsWith<IllegalArgumentException> {
                controller.freeze(99, outside, mapOf("x" to true), listOf("x"))
            }
            val limited = StudyQueryController(split.query, 1, world.sTruth)
            val frozen = limited.freeze(0, split.query[0], mapOf("x" to true), listOf("x"))
            limited.reveal(frozen)
            assertFailsWith<IllegalArgumentException> {
                limited.freeze(1, split.query[1], mapOf("x" to true), listOf("x"))
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `verification failure never activates a capability`() {
        val path = C8TestBases.bases.newRun("FULL")
        try {
            val library = C8TestBases.bases.library("FULL")
            val evaluator = C8Evaluator(library, path)
            val wrong = Expr.Eq(Expr.Arg("a", CapType.Str), Expr.Const(CapValue.Str("1")))
            val correct = evaluator.correct(wrong, world.sTruth, split.heldout)
            assertTrue(correct < split.heldout.size, "wrong candidate must not pass heldout")
            val activation = evaluator.activate(
                wrong, "S", "c8v3.test.bad", world.sTruth, split, correct, buildJsonObject {},
            )
            assertTrue(activation.attempted)
            assertFalse(activation.active)
            assertNotNull(activation.verificationRunId)
            val registry = SqliteCapabilityRegistry(path)
            try {
                assertTrue(registry.activeCapabilities().none { it.id == "c8v3.test.bad" })
            } finally {
                registry.close()
            }
            val execRegistry = SqliteCapabilityRegistry(path)
            val execution = try {
                CapabilityExecutionService(execRegistry).executeActive(
                    "c8v3.test.bad", 1,
                    mapOf("a" to EvalResult.Known(CapValue.Str("1"))),
                )
            } finally {
                execRegistry.close()
            }
            assertFalse(execution.ok)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `unidentifiable target or empty query pool stops without activation`() {
        val path = C8TestBases.bases.newRun("FULL")
        try {
            val library = C8TestBases.bases.library("FULL")
            val noQueryView = sView(path, library, queryPool = emptyList())
            val noQuery = ActiveStudySession(noQueryView, StudyQueryController(emptyList(), 5, world.sTruth), path).run()
            assertEquals("NO_DISTINGUISHING_QUERY", noQuery.status)
            assertNull(noQuery.selected)
            assertTrue(noQuery.consistent > 1)

            val parity = C8World(
                "w-parity", "parity", "session-parity",
                { m ->
                    (m["a"] == "1") xor (m["b"] == "2") xor (m["c"] == "1") xor (m["d"] == "2")
                },
                "a1 XOR b2 XOR c1 XOR d2",
            )
            val parityView = StudyView(
                parity.sessionId, "S",
                split.train.take(C8Data.S_TRAIN_SIZE).mapIndexed { i, m -> StudyCase("p-$i", m, parity.sTruth(m)) },
                split.query, 20, library.refs, C8Domain.attributes, 3, 50_000, 24, "utility", 0,
            )
            val parityResult = ActiveStudySession(
                parityView, StudyQueryController(split.query, 20, parity.sTruth), path,
            ).run()
            assertTrue(parityResult.status == "IDENTIFIED" || parityResult.status == "BUDGET_EXHAUSTED" || parityResult.status == "NO_CANDIDATE")
            if (parityResult.selected != null) {
                val evaluator = C8Evaluator(library, path)
                val correct = evaluator.correct(parityResult.selected.body, parity.sTruth, split.heldout)
                assertTrue(correct < split.heldout.size)
                val activation = evaluator.activate(
                    parityResult.selected.body, "S", "c8v3.test.parity", parity.sTruth, split, correct, buildJsonObject {},
                )
                assertFalse(activation.active)
            } else {
                assertNull(parityResult.selected)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `activated S can be executed and is an actual T dependency`() {
        val path = C8TestBases.bases.newRun("FULL")
        try {
            val library = C8TestBases.bases.library("FULL")
            val evaluator = C8Evaluator(library, path)
            val sResult = ActiveStudySession(
                sView(path, library), StudyQueryController(split.query, 20, world.sTruth), path,
            ).run()
            assertEquals("IDENTIFIED", sResult.status)
            val sCandidate = assertNotNull(sResult.selected)
            val sCorrect = evaluator.correct(sCandidate.body, world.sTruth, split.heldout)
            assertEquals(27, sCorrect)
            val sActivation = evaluator.activate(
                sCandidate.body, "S", "c8v3.test.S.exec", world.sTruth, split, sCorrect, buildJsonObject {},
            )
            assertTrue(sActivation.active)
            val sDefinition = assertNotNull(sActivation.definition)
            val sRef = KnowledgeRef(sDefinition.id, sDefinition.version, sActivation.contentHash!!)

            val registry = SqliteCapabilityRegistry(path)
            try {
                val execution = CapabilityExecutionService(registry).executeActive(
                    sDefinition.id, sDefinition.version,
                    mapOf(
                        "a" to EvalResult.Known(CapValue.Str("1")),
                        "b" to EvalResult.Known(CapValue.Str("0")),
                        "c" to EvalResult.Known(CapValue.Str("1")),
                        "d" to EvalResult.Known(CapValue.Str("0")),
                    ),
                )
                assertTrue(execution.ok)
                assertEquals(true, ((execution.result as EvalResult.Known).value as CapValue.Bool).value)
            } finally {
                registry.close()
            }

            val tLibrary = C8Library("FULL+S", library.refs + sRef, library.definitions + sDefinition)
            val tEvaluator = C8Evaluator(tLibrary, path)
            val tView = StudyView(
                world.sessionId, "T", tTraining(), split.query, 20, tLibrary.refs, C8Domain.attributes,
                3, 50_000, 24, "utility", 0,
            )
            val tResult = ActiveStudySession(
                tView, StudyQueryController(split.query, 20, world::tTruth), path,
            ).run()
            assertEquals("IDENTIFIED", tResult.status)
            val tCandidate = assertNotNull(tResult.selected)
            assertTrue(tCandidate.dependencyIds.contains(sDefinition.id))
            assertEquals(27, tEvaluator.correct(tCandidate.body, world::tTruth, split.heldout))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `fixed and random controls never fake activation`() {
        for (strategy in listOf("fixed", "random")) {
            val run = C8Experiment.runWorld(world, "FULL", strategy, withNoS = false, C8TestBases.bases)
            assertTrue(run.s.result.rounds.size <= C8Experiment.BUDGET.queryBudget)
            if (run.s.result.status == "IDENTIFIED") {
                assertTrue(run.s.activation?.active == true, "strategy=$strategy")
                assertEquals(0, run.s.audit?.mismatches, "strategy=$strategy")
            } else {
                assertNull(run.s.result.selected)
                assertNull(run.s.activation)
            }
        }
    }

    @Test
    fun `frozen split is disjoint complete and coverage checked`() {
        assertEquals(18, split.train.size)
        assertEquals(36, split.query.size)
        assertEquals(27, split.heldout.size)
        val keys = (split.train + split.query + split.heldout).map { C8Domain.key(it) }
        assertEquals(81, keys.size)
        assertEquals(81, keys.toSet().size)
        assertTrue(C8Data.coverage(split).isEmpty(), C8Data.coverage(split).toString())
        assertEquals(split.digest, C8Data.freeze(C8Experiment.SPLIT_SEED).digest)
    }
}
