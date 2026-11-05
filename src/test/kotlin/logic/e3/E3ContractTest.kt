// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A1 契约测试；只验证数据边界与稳定编码，不实现诊断算法。
package logic.e3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E3ContractTest {

    private val schema = VisibleSchema(
        mapOf(
            "material" to listOf("copper", "iron"),
            "seal" to listOf("open", "sealed"),
        ),
    )

    private fun observation(
        id: String = "o1",
        objectId: String = "obj-1",
        visible: Map<String, ObservedValue> = mapOf(
            "material" to ObservedValue.Known("copper"),
            "seal" to ObservedValue.Known("sealed"),
        ),
        label: Boolean? = true,
        round: Int = 1,
        source: String = "fixture-1",
    ) = Observation(
        observationId = id,
        objectId = objectId,
        visible = visible,
        label = label,
        provenance = EvidenceProvenance(source, SourceKind.EXTERNAL_PROVIDED, requestedByStudent = false),
        round = round,
    )

    @Test
    fun `valid observation with unknown value and unknown label is accepted`() {
        val result = validateObservations(
            schema,
            listOf(observation(visible = mapOf("material" to ObservedValue.Unknown, "seal" to ObservedValue.Known("open")), label = null)),
        )
        assertTrue(result.ok)
        assertEquals(1, result.validObservations.size)
    }

    @Test
    fun `missing field and unknown field are distinct machine readable errors`() {
        val missing = validateObservations(schema, listOf(observation(visible = mapOf("material" to ObservedValue.Known("copper")))))
        assertFalse(missing.ok)
        assertTrue(missing.errors.any { it.code == "missing_field" && it.field == "seal" })

        val extra = validateObservations(
            schema,
            listOf(observation(visible = mapOf(
                "material" to ObservedValue.Known("copper"),
                "seal" to ObservedValue.Known("open"),
                "hidden" to ObservedValue.Known("x"),
            ))),
        )
        assertTrue(extra.errors.any { it.code == "unknown_field" && it.field == "hidden" })
    }

    @Test
    fun `value outside public domain is rejected`() {
        val result = validateObservations(
            schema,
            listOf(observation(visible = mapOf("material" to ObservedValue.Known("gold"), "seal" to ObservedValue.Known("open")))),
        )
        assertTrue(result.errors.any { it.code == "invalid_value" && it.field == "material" })
    }

    @Test
    fun `duplicate observation id and duplicate source round are rejected`() {
        val duplicateId = validateObservations(schema, listOf(observation(id = "same"), observation(id = "same")))
        assertTrue(duplicateId.errors.any { it.code == "duplicate_observation_id" })

        // 同一来源在同一 object+round 重复写：拒绝，避免冒充新证据。
        val duplicateSource = validateObservations(
            schema,
            listOf(
                observation(id = "a", objectId = "obj", round = 1, source = "source-1"),
                observation(id = "b", objectId = "obj", round = 1, source = "source-1"),
            ),
        )
        assertTrue(duplicateSource.errors.any { it.code == "duplicate_source_round" })

        // 不同来源独立测量同一对象、同一轮：允许，作为独立证据保留。
        val independent = validateObservations(
            schema,
            listOf(
                observation(id = "a", objectId = "obj", round = 1, source = "source-1"),
                observation(id = "b", objectId = "obj", round = 1, source = "source-2"),
            ),
        )
        assertTrue(independent.ok)
    }

    @Test
    fun `feature key is stable and distinguishes unknown from concrete value`() {
        val first = observation(visible = linkedMapOf(
            "seal" to ObservedValue.Known("open"),
            "material" to ObservedValue.Known("copper"),
        ))
        val second = observation(visible = linkedMapOf(
            "material" to ObservedValue.Known("copper"),
            "seal" to ObservedValue.Known("open"),
        ))
        assertEquals(featureKey(first).canonical, featureKey(second).canonical)

        val unknown = observation(visible = mapOf("material" to ObservedValue.Unknown, "seal" to ObservedValue.Known("open")))
        assertTrue(featureKey(unknown).canonical.contains("material=U"))
        assertTrue(featureKey(unknown).canonical != featureKey(first).canonical)
    }

    @Test
    fun `known special characters never collide with unknown or each other`() {
        val values = listOf("?", "\\", "|", "=", "U", "K:?", "", "a=b|c\\d")
        val keys = values.map { value ->
            featureKey(observation(visible = mapOf(
                "material" to ObservedValue.Known(value),
                "seal" to ObservedValue.Known("open"),
            ))).canonical
        }
        assertEquals(values.size, keys.toSet().size, "known values must encode distinctly: $keys")
        val unknownKey = featureKey(observation(visible = mapOf(
            "material" to ObservedValue.Unknown,
            "seal" to ObservedValue.Known("open"),
        ))).canonical
        assertFalse(unknownKey in keys)
    }

    @Test
    fun `observation JSON is stable across map insertion order`() {
        val first = observation(visible = linkedMapOf(
            "seal" to ObservedValue.Known("open"),
            "material" to ObservedValue.Known("copper"),
        ))
        val second = observation(visible = linkedMapOf(
            "material" to ObservedValue.Known("copper"),
            "seal" to ObservedValue.Known("open"),
        ))
        assertEquals(ContractJson.encodeObservation(first), ContractJson.encodeObservation(second))
        assertEquals(
            ContractJson.encodeObservations(listOf(first, observation(id = "o0"))),
            ContractJson.encodeObservations(listOf(observation(id = "o0"), first)),
        )
    }

    @Test
    fun `report supports multiple findings and undetermined without forbidden fields`() {
        val finding = DiagnosticFinding(
            kind = FindingKind.OBSERVED_LABEL_COLLISION,
            evidenceIds = listOf("o1", "o2"),
            scope = EvidenceScope("bounded IR depth<=3", "7 public observations", "query_pool C2"),
            assumptions = listOf("labels are deterministic"),
            alternativeExplanations = listOf("label noise", "missing context"),
        )
        val undetermined = finding.copy(kind = FindingKind.UNDETERMINED, evidenceIds = emptyList())
        val report = DiagnosticReport(
            findings = listOf(finding, undetermined),
            proposedProbe = ProbeRequest.RepeatLabel("obj-1"),
            stoppedBecause = "cannot distinguish noise from representation gap",
        )
        val json = ContractJson.encodeReport(report)
        assertTrue(json.contains("OBSERVED_LABEL_COLLISION"))
        assertTrue(json.contains("UNDETERMINED"))
        assertTrue(json.contains("REPEAT_LABEL"))
        assertFalse(json.contains("worldKind"))
        assertFalse(json.contains("hiddenFeature"))
        assertFalse(json.contains("targetAst"))
    }

    @Test
    fun `blank identifiers and invalid round are rejected`() {
        val result = validateObservations(
            schema,
            listOf(observation(id = " ", objectId = "", round = 0)),
        )
        assertTrue(result.errors.any { it.code == "blank_observation_id" })
        assertTrue(result.errors.any { it.code == "blank_object_id" })
        assertTrue(result.errors.any { it.code == "invalid_round" })
    }

    @Test
    fun `schema with empty domain is rejected`() {
        val errors = validateSchema(VisibleSchema(mapOf("material" to emptyList())))
        assertTrue(errors.any { it.code == "schema_empty_domain" })
    }
}
