// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A4 碰撞检测测试；只验证可观察标签冲突，不推断噪声/隐藏属性。
package logic.e3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E3CollisionDetectorTest {

    private val schema = VisibleSchema(mapOf("a" to listOf("0", "1"), "b" to listOf("0", "1")))

    private fun obs(
        id: String,
        source: String = "source-a",
        round: Int = 1,
        a: ObservedValue = ObservedValue.Known("1"),
        b: ObservedValue = ObservedValue.Known("0"),
        label: Boolean? = true,
    ) = Observation(
        observationId = id,
        objectId = "obj-$id",
        visible = mapOf("a" to a, "b" to b),
        label = label,
        provenance = EvidenceProvenance(source, SourceKind.EXTERNAL_PROVIDED, false, reliableDeterministicLabels = null),
        round = round,
    )

    @Test
    fun `multiple sources with same visible vector conflict`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(obs("o1", source = "source-a"), obs("o2", source = "source-b", label = false)),
        )
        assertEquals(1, report.findings.size)
        val finding = report.findings.single()
        assertEquals(listOf("o1", "o2"), finding.participants.map { it.observationId })
        assertEquals(setOf("source-a", "source-b"), finding.participants.map { it.sourceId }.toSet())
        assertEquals(setOf(true, false), finding.participants.map { it.label }.toSet())
        assertTrue(report.excludedUnknownValueIds.isEmpty())
    }

    @Test
    fun `same source different rounds conflict is reported with rounds`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(obs("o1", source = "source-a", round = 1), obs("o2", source = "source-a", round = 2, label = false)),
        )
        val participants = report.findings.single().participants
        assertEquals(listOf(1, 2), participants.map { it.round })
        assertEquals(setOf("source-a"), participants.map { it.sourceId }.toSet())
    }

    @Test
    fun `unknown values are excluded and do not create false collision`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(
                obs("o1", a = ObservedValue.Unknown, label = true),
                obs("o2", a = ObservedValue.Known("1"), label = false),
            ),
        )
        assertTrue(report.findings.isEmpty())
        assertEquals(listOf("o1"), report.excludedUnknownValueIds)
    }

    @Test
    fun `unlabeled observations are excluded`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(obs("o1", label = null), obs("o2", label = false)),
        )
        assertTrue(report.findings.isEmpty())
        assertEquals(listOf("o1"), report.excludedUnlabeledIds)
    }

    @Test
    fun `same label or different features produce no collision`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(obs("o1"), obs("o2"), obs("o3", b = ObservedValue.Known("1"), label = false)),
        )
        assertTrue(report.findings.isEmpty())
        assertTrue(report.validationErrors.isEmpty())
    }

    @Test
    fun `multiple feature groups are reported independently and stably`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(
                obs("b2", b = ObservedValue.Known("1"), label = false),
                obs("a1", label = true),
                obs("a2", label = false),
                obs("b1", b = ObservedValue.Known("1"), label = true),
            ),
        )
        assertEquals(2, report.findings.size)
        assertEquals(report.findings.map { it.featureKey }.sorted(), report.findings.map { it.featureKey })
        assertTrue(report.findings.all { it.participants.map { p -> p.observationId } == it.participants.map { p -> p.observationId }.sorted() })
    }

    @Test
    fun `invalid input returns machine readable errors rather than collision`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(obs("o1", a = ObservedValue.Known("9"))),
        )
        assertTrue(report.findings.isEmpty())
        assertTrue(report.validationErrors.any { it.code == "invalid_value" })
    }

    @Test
    fun `report encoding contains no world or hidden explanation fields`() {
        val report = ObservableCollisionDetector.detect(
            schema,
            listOf(obs("o1"), obs("o2", label = false)),
        )
        val json = ContractJson.encodeCollisionReport(report)
        assertTrue(json.contains("featureKey"))
        assertTrue(json.contains("participants"))
        assertFalse(json.contains("worldKind"))
        assertFalse(json.contains("hiddenFeature"))
        assertFalse(json.contains("targetAst"))
        assertFalse(json.contains("noise"))
        assertFalse(json.contains("hiddenAttribute"))
    }
}
