package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Synthetic behavior contracts only; do not count as blind research success. */
class SD03BlindAttributionR2ContractTest {
    private fun fixture(): R2Snapshot {
        val obs = listOf(
            R2Observation("t0", mapOf("a" to "1", "d" to "1"), true),
            R2Observation("t1", mapOf("a" to "1", "d" to "2"), false),
        )
        return R2Snapshot(
            id = "synthetic-r2-0", observations = obs,
            atoms = listOf(
                R2Atom("a1", 1, true, mapOf("t0" to true, "t1" to true), "atom:a1"),
                R2Atom("d0", 2, true, mapOf("t0" to false, "t1" to false), "atom:d0"),
                R2Atom("d1", 3, false, mapOf("t0" to true, "t1" to false), "atom:d1"),
            ),
            lifecycle = R2Lifecycle(128, 0, 0, false, "trace:life"),
            alternatives = listOf(
                R2Alternative(R2Action.BASELINE_10, listOf("a1", "d1"), "preview:base"),
                R2Alternative(R2Action.DIVERSITY_10, listOf("a1", "d0"), "preview:div"),
                R2Alternative(R2Action.PAIRWISE_12, listOf("a1", "d0", "d1"), "preview:p12"),
            ),
            allowedActions = R2Action.entries.toSet(),
        )
    }
    private fun R2Decision.status(c: R2Cause) = hypotheses.single { it.cause == c }.status

    @Test fun `attribute present yet signature indistinguishable is detected`() {
        val d = SD03BlindAttributionR2.decide(fixture())
        assertEquals(R2Status.SUPPORTED, d.status(R2Cause.SELECTED_SIGNATURE_INSUFFICIENT))
        assertEquals(R2Status.SUPPORTED, d.status(R2Cause.DISCARDED_ATOM_CAN_SEPARATE))
        assertEquals(R2Action.BASELINE_10, d.action)
        assertEquals(1, d.blockedWitnesses.size)
        assertTrue(d.actionEvidenceIds.contains("t0") && d.actionEvidenceIds.contains("t1"))
        assertTrue(d.reason.endsWith("NOT_CAUSAL_PROOF"))
    }

    @Test fun `no distinguishing discarded atom cannot authorize repair`() {
        val s = fixture().copy(atoms = fixture().atoms.dropLast(1))
        val safe = s.copy(alternatives = s.alternatives.map { a -> a.copy(selectedAtomIds = a.selectedAtomIds.filter { it != "d1" }) })
        val d = SD03BlindAttributionR2.decide(safe)
        assertEquals(R2Status.SUPPORTED, d.status(R2Cause.NO_ATOM_CAN_SEPARATE))
        assertEquals(R2Action.ABSTAIN, d.action)
    }

    @Test fun `unknown predictions cause abstention rather than false substitute`() {
        val s = fixture()
        val edited = s.copy(atoms = s.atoms.map { if (it.id == "a1") it.copy(predictions = mapOf("t0" to null, "t1" to true)) else it })
        val d = SD03BlindAttributionR2.decide(edited)
        assertEquals(R2Status.SUPPORTED, d.status(R2Cause.UNKNOWN_ATOM_PREDICTION))
        assertEquals(R2Action.ABSTAIN, d.action)
    }

    @Test fun `no train failure no repair trigger`() {
        val s = fixture().copy(lifecycle = R2Lifecycle(128, 1, 1, false, "trace:life"))
        assertEquals(R2Action.ABSTAIN, SD03BlindAttributionR2.decide(s).action)
    }

    @Test fun `candidate generation cap blocks attribution upgrade`() {
        val s = fixture().copy(lifecycle = R2Lifecycle(30000, 0, 0, true, "trace:life"))
        val d = SD03BlindAttributionR2.decide(s)
        assertEquals(R2Action.ABSTAIN, d.action)
        assertEquals(R2Status.SUPPORTED, d.status(R2Cause.GENERATION_CAP_PRESSURE))
    }

    @Test fun `no allowed action means abstain`() {
        val s = fixture().copy(allowedActions = setOf(R2Action.KEEP, R2Action.ABSTAIN))
        assertEquals(R2Action.ABSTAIN, SD03BlindAttributionR2.decide(s).action)
    }

    @Test fun `same trace with new public evidence changes decision`() {
        val s = fixture()
        val noLabelContrast = s.copy(observations = s.observations.map { it.copy(label = true) })
        val d1 = SD03BlindAttributionR2.decide(s)
        val d2 = SD03BlindAttributionR2.decide(noLabelContrast)
        assertEquals(R2Action.BASELINE_10, d1.action)
        assertEquals(R2Action.ABSTAIN, d2.action)
        assertNotEquals(d1.snapshotDigest, d2.snapshotDigest)
        // This is a controlled kernel contract, not a matched real-world causal experiment.
    }

    @Test fun `incomplete observation coverage rejected before decision`() {
        val s = fixture()
        val malformed = s.copy(atoms = s.atoms.map { if (it.id == "d1") it.copy(predictions = mapOf("t0" to true)) else it })
        val failed = runCatching { SD03BlindAttributionR2.decide(malformed) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
    }
}
