// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02 第二关配对实验：相同初始状态，首轮验证反馈不同，第二步行动必须不同。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MRX2PairTest {
    @Test
    fun `same first action different feedback produces different second action`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val seed = 1449
        val split = MRXWorlds.splitFor(world, seed)
        val bases = C8TestBases.bases
        val full = bases.library("FULL")
        val training = split.train.mapIndexed { i, m -> StudyCase("p-$i", m, world.truth(m)) }
        val view = StudyView(
            "mrx2-pair", "S", training, split.query, 20, full.refs,
            C8Domain.attributes, 3, world.maxCandidates, 24, "utility", 0,
        )
        // 先得到同一基础候选；两个环境的 TRAIN/QUERY 完全相同。
        val probePath = bases.newRun("FULL")
        val baseCandidate = try {
            ActiveStudySession(
                view, StudyQueryController(split.query, 20, world.truth), probePath,
            ).run().selected
        } finally {
            Files.deleteIfExists(probePath)
        }
        checkNotNull(baseCandidate)
        // 环境 A：CHALLENGE 有真实反例。环境 B：CHALLENGE 标签与基础候选一致。
        val targetB = { input: Map<String, String> ->
            Pack02Knowledge.predict(baseCandidate.body, input, full.definitions) ?: false
        }
        val pathA = bases.newRun("FULL")
        val pathB = bases.newRun("FULL")
        try {
            val costA = MRX2Cost()
            val ledgerA = MRX2Ledger(split.challenge, StudyQueryController(split.challenge, 24, world.truth), 24, costA)
            val resultA = MRX2Student(
                view, MRXQuerySource(split.query, 20, world.truth), ledgerA, full.refs, pathA,
                MRX2Strategy.DYNAMIC, costA,
            ).run()
            val costB = MRX2Cost()
            val ledgerB = MRX2Ledger(split.challenge, StudyQueryController(split.challenge, 24, targetB), 24, costB)
            val resultB = MRX2Student(
                view, MRXQuerySource(split.query, 20, targetB), ledgerB, full.refs, pathB,
                MRX2Strategy.DYNAMIC, costB,
            ).run()

            println("A first=${resultA.steps.first().action} feedback=${resultA.steps.first().feedback} second=${resultA.secondAction} status=${resultA.status}")
            println("B first=${resultB.steps.first().action} feedback=${resultB.steps.first().feedback} second=${resultB.secondAction} status=${resultB.status}")
            println("COST A new=${resultA.cost.oracleNewQueries} reuse=${resultA.cost.oracleReuseUses} gen=${resultA.cost.generatedCandidates} calls=${resultA.cost.interpreterCalls} wall=${resultA.cost.totalWallMillis}")
            println("COST B new=${resultB.cost.oracleNewQueries} reuse=${resultB.cost.oracleReuseUses} gen=${resultB.cost.generatedCandidates} calls=${resultB.cost.interpreterCalls} wall=${resultB.cost.totalWallMillis}")
            assertEquals("VALIDATE_BASE", resultA.steps.first().action)
            assertEquals("VALIDATE_BASE", resultB.steps.first().action)
            assertEquals(MRX2ValidationStatus.COUNTEREXAMPLE.name, resultA.steps.first().feedback)
            assertEquals(MRX2ValidationStatus.PASS_ON_CHALLENGE.name, resultB.steps.first().feedback)
            assertEquals("DNF", resultA.secondAction)
            assertEquals("STOP", resultB.secondAction)
            assertNotEquals(resultA.secondAction, resultB.secondAction)
            assertTrue(resultA.steps.first().oracleNew > 0 || resultA.steps.first().checks > 0)
            assertTrue(resultA.cost.oracleNewQueries + resultA.cost.oracleReuseUses > 0)
            val heldoutKeys = split.heldout.map { C8Domain.key(it) }.toSet()
            assertTrue(ledgerA.entries().none { it.inputKey in heldoutKeys })
            assertTrue(ledgerB.entries().none { it.inputKey in heldoutKeys })
        } finally {
            Files.deleteIfExists(pathA)
            Files.deleteIfExists(pathB)
        }
    }

    @Test
    fun `second pair missing knowledge feedback selects knowledge or stop`() {
        val world = MRXWorlds.baseline.first { it.id == "mrx-miss-or-r-d1" }
        val seed = 2020
        val split = MRXWorlds.splitFor(world, seed)
        val bases = C8TestBases.bases
        val full = bases.library("FULL")
        val noR = bases.library("NO-R")
        val training = split.train.mapIndexed { i, m -> StudyCase("p-$i", m, world.truth(m)) }
        val view = StudyView(
            "mrx2-pair-miss", "S", training, split.query, 20, noR.refs,
            C8Domain.attributes, 3, world.maxCandidates, 24, "utility", 0,
        )
        val probePath = bases.newRun("FULL")
        val baseCandidate = try {
            ActiveStudySession(
                view, StudyQueryController(split.query, 20, world.truth), probePath,
            ).run().selected
        } finally {
            Files.deleteIfExists(probePath)
        }
        checkNotNull(baseCandidate)
        val targetB = { input: Map<String, String> ->
            Pack02Knowledge.predict(baseCandidate.body, input, full.definitions) ?: false
        }
        val pathA = bases.newRun("FULL")
        val pathB = bases.newRun("FULL")
        try {
            val costA = MRX2Cost()
            val resultA = MRX2Student(
                view, MRXQuerySource(split.query, 20, world.truth),
                MRX2Ledger(split.challenge, StudyQueryController(split.challenge, 24, world.truth), 24, costA),
                full.refs, pathA, MRX2Strategy.DYNAMIC, costA,
            ).run()
            val costB = MRX2Cost()
            val resultB = MRX2Student(
                view, MRXQuerySource(split.query, 20, targetB),
                MRX2Ledger(split.challenge, StudyQueryController(split.challenge, 24, targetB), 24, costB),
                full.refs, pathB, MRX2Strategy.DYNAMIC, costB,
            ).run()
            println("MISS A first=${resultA.steps.first().feedback} second=${resultA.secondAction}")
            println("MISS B first=${resultB.steps.first().feedback} second=${resultB.secondAction}")
            assertEquals(MRX2ValidationStatus.COUNTEREXAMPLE.name, resultA.steps.first().feedback)
            assertEquals(MRX2ValidationStatus.PASS_ON_CHALLENGE.name, resultB.steps.first().feedback)
            assertTrue(resultA.secondAction in setOf("KNOWLEDGE", "DNF"))
            assertEquals("STOP", resultB.secondAction)
            assertNotEquals(resultA.secondAction, resultB.secondAction)
        } finally {
            Files.deleteIfExists(pathA)
            Files.deleteIfExists(pathB)
        }
    }
}
