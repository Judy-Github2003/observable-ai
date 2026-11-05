// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-01 D2/N02/A-R1 最小联合实验证据；不读取隐藏目标规则。
package logic.e3.pack01

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Pack01SessionTest {

    private fun labels(results: Map<String, Boolean>) = object : PackLabelSource {
        override fun label(sampleId: String): PackLabel? {
            val input = when (sampleId) {
                "q10" -> PackInput("q10", "1", "0")
                "q01" -> PackInput("q01", "0", "1")
                "q11" -> PackInput("q11", "1", "1")
                else -> return null
            }
            return PackLabel(input.sampleId, input.a, input.b, results.getValue(sampleId), "test-oracle")
        }
    }

    private fun session(
        pool: List<PackInput> = listOf(
            PackInput("q10", "1", "0"),
            PackInput("q01", "0", "1"),
            PackInput("q11", "1", "1"),
        ),
    ) = Pack01Session(
        listOf(PackObservation("p00", "0", "0", false)),
        pool,
        labels(mapOf("q10" to true, "q01" to false, "q11" to true)),
    )

    @Test
    fun `D2 keeps multiple hypotheses and exposes ambiguity`() {
        val snapshot = session().initial()
        assertTrue(snapshot.candidates.size > 1, "expected multiple consistent candidates")
        assertTrue(snapshot.status == "AMBIGUOUS", "status=${snapshot.status}")
        assertTrue(snapshot.candidates.map { it.canonical }.distinct().size > 1)
    }

    @Test
    fun `N02 chooses query from candidate prediction differences without labels`() {
        val s = session()
        val initial = s.initial()
        val query = assertNotNull(s.chooseDistinguishing())
        assertTrue(query.distinctSignatures > 1)
        assertTrue(query.input.sampleId !in initial.observations.map { it.id })
        assertTrue(query.predictions.values.distinct().size > 1)
    }

    @Test
    fun `label cannot be requested before a query is frozen`() {
        val s = session()
        assertFailsWith<IllegalStateException> { s.reveal() }
    }

    @Test
    fun `A-R1 eliminates contradicted candidates and keeps before-after history`() {
        val s = session()
        val before = s.initial()
        val query = assertNotNull(s.chooseDistinguishing())
        val frozen = s.freeze(query)
        assertEquals(1, frozen.sequence)
        val result = s.reveal()
        val after = assertNotNull(result.after)
        assertTrue(result.eliminated.isNotEmpty(), "new label must eliminate at least one candidate")
        assertTrue(after.candidates.size < before.candidates.size)
        assertTrue(after.candidates.isNotEmpty())
        assertEquals(before.observations, result.before.observations)
        assertTrue(result.label!!.label)
        assertTrue(result.state == "AMBIGUOUS" || result.state == "IDENTIFIED")
        assertTrue(result.frozen!!.sequence < 2) // prediction before label state transition
    }

    @Test
    fun `all candidates can become undetermined after conflicting evidence`() {
        val s = session()
        val result = s.applyObservation(PackObservation("conflict", "0", "0", true))
        assertEquals("UNDETERMINED", result.state)
        assertNotNull(result.failureCode)
    }

    @Test
    fun `no legal distinguishing query is reported explicitly`() {
        val s = session(pool = emptyList())
        assertNull(s.chooseDistinguishing())
    }

    @Test
    fun `export PACK-01 actual run record`() {
        val s = session()
        val before = s.initial()
        val query = assertNotNull(s.chooseDistinguishing())
        val frozen = s.freeze(query)
        val result = s.reveal()
        val after = assertNotNull(result.after)
        val heldout = PackInput("heldout", "1", "1")
        val rows = buildJsonObject {
            put("pack", "PACK-01")
            put("initialStatus", before.status)
            put("initialCandidates", before.candidates.size)
            put("composed", before.composed)
            put("querySampleId", query.input.sampleId)
            put("queryVisible", buildJsonObject { put("a", query.input.a); put("b", query.input.b) })
            put("queryDistinctPredictions", query.distinctSignatures)
            put("frozenSequence", frozen.sequence)
            put("label", result.label!!.label)
            put("afterStatus", after.status)
            put("afterCandidates", after.candidates.size)
            put("eliminated", result.eliminated.size)
            put("state", result.state)
            put("heldoutPredictions", buildJsonArray {
                after.candidates.forEach { candidate ->
                    addJsonObject {
                        put("candidateId", candidate.id)
                        put("prediction", s.predict(candidate.body, heldout.a, heldout.b))
                    }
                }
            })
            put("beforeCandidates", buildJsonArray {
                before.candidates.forEach { c -> addJsonObject { put("id", c.id); put("canonical", c.canonical) } }
            })
            put("afterCandidatesDetail", buildJsonArray {
                after.candidates.forEach { c -> addJsonObject { put("id", c.id); put("canonical", c.canonical) } }
            })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/pack01-run.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), rows))
    }

    @Test
    fun `end-to-end update changes knowledge and predicts a held-out input`() {
        val s = session()
        val before = s.initial()
        val query = assertNotNull(s.chooseDistinguishing())
        s.freeze(query)
        val result = s.reveal()
        val after = assertNotNull(result.after)
        val changed = before.candidates.map { it.canonical }.toSet() != after.candidates.map { it.canonical }.toSet()
        assertTrue(changed)
        val heldout = PackInput("heldout", "1", "1")
        val prediction = after.candidates.first().let { s.predict(it.body, heldout.a, heldout.b) }
        println("PACK01 before=${before.candidates.size} after=${after.candidates.size} eliminated=${result.eliminated.size} state=${result.state} query=${query.input.sampleId} heldout=${prediction}")
        assertNotNull(prediction)
    }
}
