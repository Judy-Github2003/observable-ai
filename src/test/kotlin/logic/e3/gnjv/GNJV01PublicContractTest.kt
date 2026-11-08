package logic.e3.gnjv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public contract tests. No sealed-world seeds, grading labels or hidden oracle. */
class GNJV01PublicContractTest {
    private val zero = GNJVObservation(false, false)

    @Test fun threeDistinctHypothesesWithIdenticalPassiveObservation() {
        assertEquals(3, GNJV01HypothesisGrammar.candidates().distinct().size)
        assertEquals(3, GNJV01Student().hypotheses().size)
        assertNull(GNJV01Student().identified())
    }

    @Test fun activeChoosesDiscriminativeIntervention() {
        val selection = GNJV01Student().choose(zero)
        assertEquals("DISAGREEMENT_MINIMAX", selection.reason)
        assertEquals(2, selection.distinctPredictions)
        assertEquals(2, selection.worstCaseSurvivors)
        assertEquals(CausalVariable.X, selection.intervention!!.target)
    }

    @Test fun causalOutcomeFiltersWithoutWorldId() {
        val learner = GNJV01Student()
        val trial = GNJVTrial(zero, GNJVIntervention(CausalVariable.X, true), GNJVObservation(true, true))
        val u = learner.update(trial)
        assertEquals(GNJVUpdateStatus.REFINED, u.status)
        assertEquals(1, u.survivors)
        assertTrue(learner.identified() != null)
    }

    @Test fun incompatibleEvidenceNeverCreatesFalseCertainty() {
        val learner = GNJV01Student()
        val bad = GNJVTrial(zero, GNJVIntervention(CausalVariable.X, true), GNJVObservation(false, false))
        val u = learner.update(bad)
        assertEquals(GNJVUpdateStatus.INCONSISTENT_EVIDENCE, u.status)
        assertNull(learner.identified())
        assertNull(learner.choose(zero).intervention)
    }

    @Test fun unknownIsNotCoercedToFalse() {
        val student = GNJV01Student()
        assertNull(student.predict(zero, GNJVIntervention(CausalVariable.X, true)))
    }
}
