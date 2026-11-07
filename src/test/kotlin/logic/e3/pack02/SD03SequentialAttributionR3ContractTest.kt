package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Synthetic protocol contracts only, not evidence of successful autonomous repair. */
class SD03SequentialAttributionR3ContractTest {
    private fun before(): R2Snapshot = R2Snapshot(
        "r3-before", listOf(
            R2Observation("t0", mapOf("a" to "1", "d" to "1"), true),
            R2Observation("t1", mapOf("a" to "1", "d" to "2"), false),
        ), listOf(
            R2Atom("a", 1, true, mapOf("t0" to true, "t1" to true), "atom:a"),
            R2Atom("d0", 2, true, mapOf("t0" to false, "t1" to false), "atom:d0"),
            R2Atom("d1", 3, false, mapOf("t0" to true, "t1" to false), "atom:d1"),
        ), R2Lifecycle(100, 0, 0, false, "life:before"), listOf(
            R2Alternative(R2Action.BASELINE_10, listOf("a", "d1"), "preview:baseline"),
            R2Alternative(R2Action.DIVERSITY_10, listOf("a", "d0"), "preview:diversity"),
            R2Alternative(R2Action.PAIRWISE_12, listOf("a", "d0", "d1"), "preview:pairwise12"),
        ), R2Action.entries.toSet(),
    )
    private fun after(cap: Boolean = false, survivors: Int = 0, stillBlocked: Boolean = false): R2Snapshot {
        val b = before()
        return b.copy(
            id = "r3-after-${cap}-${survivors}-${stillBlocked}",
            atoms = b.atoms.map {
                it.copy(retained = if (stillBlocked) it.id != "d1" else it.id != "d0",
                    evidenceId = "atom:after:${it.id}")
            },
            lifecycle = R2Lifecycle(if (cap) 30000 else 200, survivors, survivors, cap, "life:after"),
        )
    }

    @Test fun `first decision is based only on baseline evidence`() {
        val d = SD03SequentialAttributionR3.first(before())
        assertEquals(R3Action.PROBE_BASELINE_10, d.action)
        assertEquals(R3Stage.BASELINE, d.stage)
    }
    @Test fun `same prefix but different second feedback yields different third decision`() {
        val b = before(); val d = SD03SequentialAttributionR3.first(b)
        val cap = SD03SequentialAttributionR3.afterFirstIntervention(b, d, after(cap = true))
        val grammar = SD03SequentialAttributionR3.afterFirstIntervention(b, d, after(cap = false))
        assertEquals(R3Action.AUDIT_GENERATION_CAP, cap.action)
        assertEquals(R3Action.AUDIT_COMPOSITION_COVERAGE, grammar.action)
        assertTrue(cap.diagnosticOnly && grammar.diagnosticOnly)
        assertNotEquals(cap.snapshotDigest, grammar.snapshotDigest)
        assertEquals(d.snapshotDigest, cap.previousSnapshotDigest)
    }
    @Test fun `recovered train consistency stops before examiner verification`() {
        val b = before(); val d = SD03SequentialAttributionR3.first(b)
        val n = SD03SequentialAttributionR3.afterFirstIntervention(b, d, after(survivors=3))
        assertEquals(R3Action.STOP_FOR_VERIFICATION, n.action)
        assertTrue(n.claims.any { it.finding == R3Finding.TRAIN_SURVIVOR_RECOVERED && it.status == R3Epistemic.SUPPORTED })
    }
    @Test fun `unchanged signature obstruction must not be called repair`() {
        val b = before(); val d = SD03SequentialAttributionR3.first(b)
        val n = SD03SequentialAttributionR3.afterFirstIntervention(b, d, after(stillBlocked=true))
        assertEquals(R3Action.ABSTAIN, n.action)
        assertTrue(n.claims.any { it.finding == R3Finding.INTERVENTION_NOT_EFFECTIVE && it.status == R3Epistemic.SUPPORTED })
    }
    @Test fun `no followup after abstain`() {
        val b = before().copy(alternatives = emptyList())
        val d = SD03SequentialAttributionR3.first(b)
        assertEquals(R3Action.ABSTAIN, d.action)
        assertFailsWith<IllegalArgumentException> {
            SD03SequentialAttributionR3.afterFirstIntervention(b, d, after())
        }
    }
    @Test fun `changed public train label invalidates sequential comparison`() {
        val b = before(); val d = SD03SequentialAttributionR3.first(b)
        val changed = after().copy(observations = after().observations.map { it.copy(label = !it.label) })
        assertFailsWith<IllegalArgumentException> {
            SD03SequentialAttributionR3.afterFirstIntervention(b, d, changed)
        }
    }
    @Test fun `old decision cannot be reused for another baseline`() {
        val b = before(); val d = SD03SequentialAttributionR3.first(b)
        val altered = b.copy(lifecycle = b.lifecycle.copy(generated=101))
        assertFailsWith<IllegalArgumentException> {
            SD03SequentialAttributionR3.afterFirstIntervention(altered, d, after())
        }
    }
    @Test fun `stale snapshot rejected`() {
        val b = before(); val d = SD03SequentialAttributionR3.first(b)
        assertFailsWith<IllegalArgumentException> {
            SD03SequentialAttributionR3.afterFirstIntervention(b, d, b)
        }
    }
    @Test fun `cannot confuse candidate cap with proven causal problem`() {
        val b = before(); val d = SD03SequentialAttributionR3.first(b)
        val n = SD03SequentialAttributionR3.afterFirstIntervention(b, d, after(cap = true))
        assertEquals(R3Action.AUDIT_GENERATION_CAP, n.action)
        assertEquals(true, n.diagnosticOnly)
        assertTrue(n.claims.single { it.finding == R3Finding.GRAMMAR_OR_ENUMERATION_UNRESOLVED }.explanation.contains("grammar"))
    }
}
