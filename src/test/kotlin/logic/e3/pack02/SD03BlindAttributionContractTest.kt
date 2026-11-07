package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Synthetic contracts, NOT scientific validation or blinded evaluation. */
class SD03BlindAttributionContractTest {
    private fun training() = listOf(
        BAObservation("train:0", mapOf("a" to "1", "d" to "1"), true),
        BAObservation("train:1", mapOf("a" to "1", "d" to "2"), false),
    )
    private fun snapshot(atoms: List<BARankedAtom>, trainConsistent: Int = 0) = BABlindSnapshot(
        "public-run-0", training(), atoms,
        BACandidateLifecycle(100, trainConsistent, trainConsistent, false, "trace:life"),
        setOf(BAAction.KEEP, BAAction.DIVERSITY_10, BAAction.ABSTAIN),
    )
    private fun atom(name: String, selected: Boolean = true,
        quality: BADependencyQuality = BADependencyQuality.COMPLETE_OVERAPPROX) =
        BARankedAtom("atom:$name", if (name == "a") 1 else 2, selected,
            setOf(name), quality, "trace:atom:$name")

    @Test fun `missing distinguishing feature proposes bounded diagnostic repair`() {
        val d = SD03BlindAttributor.decide(snapshot(listOf(atom("a"))))
        assertEquals(BAAction.DIVERSITY_10, d.action)
        assertEquals(BAEpistemic.SUPPORTED, d.claims.first().status)
        assertTrue(d.rationaleEvidenceIds.contains("train:0"))
        assertTrue(d.rationaleEvidenceIds.contains("train:1"))
    }

    @Test fun `represented feature does not force a repair`() {
        val d = SD03BlindAttributor.decide(snapshot(listOf(atom("a"), atom("d"))))
        assertEquals(BAAction.ABSTAIN, d.action)
        assertEquals(BAEpistemic.REFUTED, d.claims.first().status)
    }

    @Test fun `unresolved call dependency prevents unsupported attribution`() {
        val d = SD03BlindAttributor.decide(snapshot(listOf(atom("a", quality = BADependencyQuality.UNKNOWN))))
        assertEquals(BAAction.ABSTAIN, d.action)
        assertEquals(BAEpistemic.UNKNOWN, d.claims.first().status)
    }

    @Test fun `action not in preregistered permitted set is not executed`() {
        val s = snapshot(listOf(atom("a"))).copy(allowedActions = setOf(BAAction.KEEP, BAAction.ABSTAIN))
        assertEquals(BAAction.ABSTAIN, SD03BlindAttributor.decide(s).action)
    }

    @Test fun `nonzero train survivors stop the specific atom repair heuristic`() {
        assertEquals(BAAction.ABSTAIN,
            SD03BlindAttributor.decide(snapshot(listOf(atom("a")), 1)).action)
    }

    @Test fun `no distinguishing pairs means insufficient evidence`() {
        val s = snapshot(listOf(atom("a"))).copy(observations = listOf(training()[0]))
        assertEquals(BAAction.ABSTAIN, SD03BlindAttributor.decide(s).action)
    }
}
