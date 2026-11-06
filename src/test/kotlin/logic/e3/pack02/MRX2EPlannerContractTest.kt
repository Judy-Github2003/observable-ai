// 参考行为契约测试：先以同包名放入项目的 src/test/kotlin/logic/e3/pack02/。
// 必须在 DYNAMIC 骨架上出现行为 RED（而不是编译失败）；真实端到端仍需另写测试。
package logic.e3.pack02

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MRX2EPlannerContractTest {
    private fun feedback(
        action: String, status: String, versionBefore: Int, versionAfter: Int,
    ) = MRX2EActionFeedback(
        action = action, status = status,
        evidenceVersionBefore = versionBefore, evidenceVersionAfter = versionAfter,
        candidateCountBefore = 7, candidateCountAfter = 5,
        oracleNew = versionAfter - versionBefore,
        oracleReused = 0, source = "UNIT_FIXTURE",
    )

    private fun snapshot(secondStatus: String, strategy: String = "DYNAMIC"): MRX2EPlannerInput {
        val version = if (secondStatus == "COUNTEREXAMPLE") 2 else 1
        return MRX2EPlannerInput(
            strategy = strategy,
            actionHistory = listOf(
                feedback("KNOWLEDGE", "NO_CANDIDATE", 0, 1),
                feedback("DNF", secondStatus, 1, version),
            ),
            allowedNextActions = listOf("IF", "CNF", "BUDGET"),
            frozenInitialQueue = listOf("KNOWLEDGE", "DNF", "IF", "CNF", "BUDGET"),
            remainingOracleBudget = 4,
            oracleRequiredActions = setOf("IF", "CNF", "BUDGET"),
            evidenceVersion = version,
            evidenceDigest = "paid-label-version-$version",
            candidateDigest = "candidate-trace-$secondStatus",
            trainingConflict = false,
            hasLegalExperiment = true,
        )
    }

    @Test
    fun `two real-shape failed feedbacks should change third DYNAMIC choice`() {
        val planner = MRX2EPlanner()
        val a = planner.choose(snapshot("NO_CANDIDATE"))
        val b = planner.choose(snapshot("COUNTEREXAMPLE"))
        assertNotEquals("STOP", a.action, "branch A must attempt legitimate third action")
        assertNotEquals("STOP", b.action, "branch B must attempt legitimate third action")
        assertNotEquals(a.action, b.action, "DYNAMIC must react to second failure feedback")
        assertTrue(a.action in listOf("IF", "CNF", "BUDGET"))
        assertTrue(b.action in listOf("IF", "CNF", "BUDGET"))
        assertEquals(1, a.evidenceVersionUsed)
        assertEquals(2, b.evidenceVersionUsed)
    }

    @Test
    fun `STATIC frozen third action must not change across paired feedbacks`() {
        val planner = MRX2EPlanner()
        val a = planner.choose(snapshot("NO_CANDIDATE", "STATIC"))
        val b = planner.choose(snapshot("COUNTEREXAMPLE", "STATIC"))
        assertEquals("IF", a.action)
        assertEquals(a.action, b.action)
        assertEquals("FROZEN_INITIAL_QUEUE", b.reason)
    }

    @Test
    fun `same snapshot is deterministic without mutation`() {
        val snapshot = snapshot("NO_CANDIDATE")
        val frozen = snapshot.copy(actionHistory = snapshot.actionHistory.toList())
        val planner = MRX2EPlanner()
        assertEquals(planner.choose(snapshot), planner.choose(snapshot))
        assertEquals(frozen, snapshot)
    }

    @Test
    fun `training conflict stops safely`() {
        val input = snapshot("COUNTEREXAMPLE").copy(trainingConflict = true)
        val decision = MRX2EPlanner().choose(input)
        assertEquals("STOP", decision.action)
        assertEquals("TRAINING_CONFLICT", decision.reason)
    }

    @Test
    fun `zero oracle budget cannot choose oracle-required action`() {
        val input = snapshot("COUNTEREXAMPLE").copy(remainingOracleBudget = 0)
        val decision = MRX2EPlanner().choose(input)
        assertEquals("STOP", decision.action)
    }

    @Test
    fun `no legal action stops`() {
        val input = snapshot("NO_CANDIDATE").copy(
            allowedNextActions = emptyList(), hasLegalExperiment = false,
        )
        val decision = MRX2EPlanner().choose(input)
        assertEquals("STOP", decision.action)
        assertEquals("NO_LEGAL_EXPERIMENT", decision.reason)
    }
}
