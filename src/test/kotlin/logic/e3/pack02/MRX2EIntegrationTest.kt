// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：MRX-02E 真实学生双失败轨迹探查（先观察，不伪造反馈）。
package logic.e3.pack02

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MRX2EIntegrationTest {
    @Test
    fun `probe real two-failure trajectory`() {
        val world = MRXWorld(
            "mrx2e-parity", MRXCategory.DEPTH, "parity over four attributes",
            { m ->
                listOf(m["a"], m["b"], m["c"], m["d"]).count { it == "1" } % 2 == 1
            },
        )
        val split = MRXWorlds.splitFor(world, 1449)
        val full = C8TestBases.bases.library("FULL")
        val path = C8TestBases.bases.newRun("FULL")
        try {
            val training = split.train.mapIndexed { i, m -> StudyCase("e-$i", m, world.truth(m)) }
            val view = StudyView(
                "mrx2e-probe", "S", training, split.query, 20, full.refs,
                C8Domain.attributes, 3, 50_000, 24, "utility", 0,
            )
            var dynamic: MRX2Result? = null
            var static: MRX2Result? = null
            for (strategy in listOf(MRX2Strategy.DYNAMIC, MRX2Strategy.STATIC)) {
                val cost = MRX2Cost()
                val ledger = MRX2Ledger(split.query, StudyQueryController(split.query, 40, world.truth), 40, cost)
                val result = MRX2Student(
                    view, MRXQuerySource(split.query, 20, world.truth), ledger, full.refs, path,
                    strategy, cost,
                ).run()
                if (strategy == MRX2Strategy.DYNAMIC) dynamic = result else static = result
                println("$strategy status=${result.status} first=${result.firstAction} second=${result.secondAction} steps=${result.steps.size}")
                result.steps.forEach { println("  ${it.index} ${it.action} feedback=${it.feedback} next=${it.nextAction}") }
            }
            val d = dynamic!!
            val s = static!!
            assertEquals("VALIDATE_BASE", d.steps[0].action)
            assertEquals("DNF", d.steps[1].action)
            assertNotEquals(MRX2ValidationStatus.PASS_ON_CHALLENGE.name, d.steps[1].feedback)
            assertEquals("IF", d.steps[2].action)
            assertEquals("CNF", d.steps[3].action)
            assertNotEquals(d.steps[1].action, d.steps[3].action)
            assertEquals("VALIDATE_BASE", s.steps[0].action)
            assertTrue(d.steps.size >= 4)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
