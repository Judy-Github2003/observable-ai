// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：MRX 实时假说契约：先观察业务 RED，再实现 update/evaluate。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MRXLiveHypothesisContractTest {
    private fun obs(
        id: String = "obs-1",
        feedback: String = "COUNTEREXAMPLE",
        counterexamples: Int = 1,
        remaining: Int = 10,
        ledgerRefs: List<String> = listOf("entry:abc:0"),
    ) = MRXLiveObservation(id, 1, "REAL_EXECUTOR", feedback, counterexamples, remaining, ledgerRefs, null)

    @Test
    fun `initial hypotheses are builtin and unknown`() {
        val initial = MRXLiveEngine.initial()
        assertEquals(0, initial.version)
        assertEquals(4, initial.hypotheses.size)
        assertTrue(initial.hypotheses.all { it.origin == "BUILTIN_BASELINE" && it.status == MRXLiveHypothesisStatus.UNKNOWN })
        assertTrue(initial.hypotheses.none { "obs-1" in it.supportIds })
    }

    @Test
    fun `real counterexample observation updates only after it exists`() {
        val initial = MRXLiveEngine.initial()
        val updated = MRXLiveEngine.update(initial, obs())
        val counter = updated.hypotheses.single { it.id == "H_COUNTEREXAMPLE_PRESSURE" }
        assertEquals(MRXLiveHypothesisStatus.SUPPORTED, counter.status)
        assertTrue("obs-1" in counter.supportIds)
        assertEquals(1, updated.version)
        assertEquals(1, counter.lastChangedVersion)
        assertNotEquals(initial.evidenceDigest, updated.evidenceDigest)
        assertTrue(initial.hypotheses.single { it.id == "H_COUNTEREXAMPLE_PRESSURE" }.supportIds.isEmpty())
    }

    @Test
    fun `unknown is distinct from false and does not refute`() {
        val updated = MRXLiveEngine.update(MRXLiveEngine.initial(), obs(feedback = "UNKNOWN_PREDICTION", counterexamples = 0))
        assertEquals(MRXLiveHypothesisStatus.SUPPORTED, updated.hypotheses.single { it.id == "H_UNKNOWN_EXECUTION" }.status)
        assertEquals(MRXLiveHypothesisStatus.UNKNOWN, updated.hypotheses.single { it.id == "H_COUNTEREXAMPLE_PRESSURE" }.status)
        assertTrue(updated.hypotheses.single { it.id == "H_COUNTEREXAMPLE_PRESSURE" }.refuteIds.isEmpty())
    }

    @Test
    fun `budget exhaustion updates its own hypothesis only`() {
        val updated = MRXLiveEngine.update(MRXLiveEngine.initial(), obs(feedback = "BUDGET_EXHAUSTED", counterexamples = 0, remaining = 0))
        assertEquals(MRXLiveHypothesisStatus.SUPPORTED, updated.hypotheses.single { it.id == "H_BUDGET_LIMIT" }.status)
        assertEquals(MRXLiveHypothesisStatus.UNKNOWN, updated.hypotheses.single { it.id == "H_UNKNOWN_EXECUTION" }.status)
    }

    @Test
    fun `evaluation reads hypothesis discrimination not feedback code`() {
        val updated = MRXLiveEngine.update(MRXLiveEngine.initial(), obs())
        val dnf = MRXLiveActionEvaluation(
            "DNF", true, setOf("H_STRUCTURAL_COVERAGE", "H_COUNTEREXAMPLE_PRESSURE"),
            listOf("prediction:dnf"), 2.0, null,
        )
        val budget = MRXLiveActionEvaluation(
            "BUDGET", true, setOf("H_BUDGET_LIMIT"), listOf("prediction:budget"), 1.0, null,
        )
        val decision = MRXLiveEngine.evaluate(updated, listOf(dnf, budget), 10)
        assertEquals("DNF", decision.chosenAction)
        assertTrue(decision.rationale.contains("H_"))
        assertEquals(updated.version, decision.snapshotVersion)
    }

    @Test
    fun `all unknown actions request information instead of guessing`() {
        val initial = MRXLiveEngine.initial()
        val a = MRXLiveActionEvaluation("DNF", true, null, emptyList(), null, "no model")
        val b = MRXLiveActionEvaluation("IF", true, null, emptyList(), null, "no model")
        val decision = MRXLiveEngine.evaluate(initial, listOf(a, b), 10)
        assertTrue(decision.chosenAction in setOf("STOP", "REQUEST_TEACHER", "INSUFFICIENT_INFORMATION"))
        assertTrue(decision.rationale.contains("UNKNOWN") || decision.rationale.contains("unknown"))
    }

    @Test
    fun `live frozen and no-hyp ablations have different causal inputs`() {
        val counter = MRXLiveEngine.update(MRXLiveEngine.initial(), obs("obs-counter", "COUNTEREXAMPLE", 1))
        val budget = MRXLiveEngine.update(MRXLiveEngine.initial(), obs("obs-budget", "BUDGET_EXHAUSTED", 0, remaining = 0))
        val actions = MRXLiveActionModel.evaluations()
        val liveA = MRXLiveAblation.decide("LIVE_HYPOTHESIS", counter, MRXLiveEngine.initial(), actions, 10, emptySet())
        val liveB = MRXLiveAblation.decide("LIVE_HYPOTHESIS", budget, MRXLiveEngine.initial(), actions, 10, emptySet())
        assertNotEquals(liveA.chosenAction, liveB.chosenAction)
        val frozenA = MRXLiveAblation.decide("LIVE_FROZEN_BELIEF", counter, MRXLiveEngine.initial(), actions, 10, emptySet())
        val frozenB = MRXLiveAblation.decide("LIVE_FROZEN_BELIEF", budget, MRXLiveEngine.initial(), actions, 10, emptySet())
        assertEquals(frozenA.chosenAction, frozenB.chosenAction)
        val noHypA = MRXLiveAblation.decide("LIVE_NO_HYP", counter, MRXLiveEngine.initial(), actions, 10, emptySet())
        val noHypB = MRXLiveAblation.decide("LIVE_NO_HYP", budget, MRXLiveEngine.initial(), actions, 10, emptySet())
        assertEquals(noHypA.chosenAction, noHypB.chosenAction)
        assertEquals("DNF", noHypA.chosenAction)
        assertTrue(noHypA.evaluations.isEmpty())
        assertTrue(noHypA.rationale.contains("NO_HYP"))
    }

    @Test
    fun `changing only evidence id does not change content decision`() {
        val a = MRXLiveEngine.update(MRXLiveEngine.initial(), obs("obs-a", "COUNTEREXAMPLE", 1))
        val b = MRXLiveEngine.update(MRXLiveEngine.initial(), obs("obs-b", "COUNTEREXAMPLE", 1))
        val actions = MRXLiveActionModel.evaluations()
        val da = MRXLiveAblation.decide("LIVE_HYPOTHESIS", a, MRXLiveEngine.initial(), actions, 10, emptySet())
        val db = MRXLiveAblation.decide("LIVE_HYPOTHESIS", b, MRXLiveEngine.initial(), actions, 10, emptySet())
        assertEquals(da.chosenAction, db.chosenAction)
    }

    @Test
    fun `historical counterexample survives later pass`() {
        val counter = MRXLiveEngine.update(MRXLiveEngine.initial(), obs("obs-counter", "COUNTEREXAMPLE", 1))
        val afterPass = MRXLiveEngine.update(counter, obs("obs-pass", "PASS_ON_CHALLENGE", 0))
        val h = afterPass.hypotheses.single { it.id == "H_COUNTEREXAMPLE_PRESSURE" }
        assertTrue("obs-counter" in h.supportIds)
        assertNotEquals(MRXLiveHypothesisStatus.REFUTED, h.status)
        assertTrue(h.refuteIds.isEmpty())
    }

    @Test
    fun `action not legal is never selected`() {
        val updated = MRXLiveEngine.update(MRXLiveEngine.initial(), obs())
        val illegal = MRXLiveActionEvaluation(
            "DNF", false, setOf("H_COUNTEREXAMPLE_PRESSURE"), listOf("prediction:dnf"), 1.0, null,
        )
        val legal = MRXLiveActionEvaluation(
            "CNF", true, setOf("H_STRUCTURAL_COVERAGE"), listOf("prediction:cnf"), 2.0, null,
        )
        val decision = MRXLiveEngine.evaluate(updated, listOf(illegal, legal), 10)
        assertEquals("CNF", decision.chosenAction)
    }
}
