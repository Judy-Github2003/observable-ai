// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A5 适配器测试；验证真实 B0 调用、Unknown 区分、冲突保留与预算信号。
package logic.e3

import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.WorldFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E3B0DiagnosticAdapterTest {

    private val adapter = E3B0DiagnosticAdapter()

    private fun constants(world: logic.e3.evaluator.EvaluatedWorld): List<String> =
        world.public.schema.fields.values.flatten().distinct()

    private fun queryDomain(world: logic.e3.evaluator.EvaluatedWorld): List<Map<String, String>> =
        world.public.queryPool.map { it.visible }

    @Test
    fun `solvable world produces bounded composer and filter traces`() {
        val world = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)
        val signal = adapter.diagnose(
            world.public.schema, world.public.observations, constants(world), queryDomain(world),
        )
        assertTrue(signal.collisionReport.findings.isEmpty())
        assertNotNull(signal.search)
        assertNotNull(signal.filter)
        assertEquals(3, signal.search!!.maxDepth)
        assertTrue(signal.usableObservationIds.isNotEmpty())
        assertTrue(signal.notes.contains("identified_scope_is_bounded_to_current_hypothesis_and_query_domain"))
        assertTrue(
            signal.status == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE ||
                signal.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE ||
                signal.status == DiagnosticSignalStatus.REFUSED,
        )
    }

    @Test
    fun `latent collision is preserved and search is not pretended to resolve it`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val signal = adapter.diagnose(
            world.public.schema, world.public.observations, constants(world), queryDomain(world),
        )
        assertEquals(DiagnosticSignalStatus.OBSERVATION_COLLISION, signal.status)
        assertTrue(signal.collisionReport.findings.isNotEmpty())
        assertNull(signal.search)
        assertNull(signal.filter)
        assertTrue(signal.collisionReport.findings.single().participants.all { it.objectId.isNotBlank() })
        assertTrue(signal.notes.any { it.contains("composer_search_skipped") })
    }

    @Test
    fun `search limited records depth limit without claiming rule absence`() {
        val world = E3WorldGenerator(107L).generate(WorldFamily.SEARCH_LIMITED, 0)
        val signal = adapter.diagnose(
            world.public.schema, world.public.observations, constants(world), queryDomain(world),
        )
        assertTrue(signal.collisionReport.findings.isEmpty())
        assertNotNull(signal.search)
        assertEquals("depth_limit", signal.search!!.reason)
        assertTrue(signal.notes.any { it.contains("does NOT prove") })
        assertNotNull(signal.filter)
    }

    @Test
    fun `ambiguous world records ambiguity rather than forcing an answer`() {
        val world = E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        val signal = adapter.diagnose(
            world.public.schema, world.public.observations, constants(world), queryDomain(world),
        )
        assertTrue(signal.collisionReport.findings.isEmpty())
        assertNotNull(signal.filter)
        assertTrue(
            signal.status == DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE ||
                signal.status == DiagnosticSignalStatus.REFUSED,
        )
    }

    @Test
    fun `partial unknown observations are excluded not stringified`() {
        val schema = VisibleSchema(mapOf("a" to listOf("0", "1"), "b" to listOf("0", "1")))
        val observations = listOf(
            Observation(
                "complete-1", "obj-1",
                mapOf("a" to ObservedValue.Known("1"), "b" to ObservedValue.Known("0")),
                true, EvidenceProvenance("s1", SourceKind.EXTERNAL_PROVIDED, false, true),
            ),
            Observation(
                "partial-1", "obj-2",
                mapOf("a" to ObservedValue.Known("0"), "b" to ObservedValue.Unknown),
                false, EvidenceProvenance("s1", SourceKind.EXTERNAL_PROVIDED, false, true),
            ),
        )
        val signal = adapter.diagnose(schema, observations, listOf("0", "1"))
        assertEquals(listOf("complete-1"), signal.usableObservationIds)
        assertEquals(listOf("partial-1"), signal.excludedUnknownValueIds)
        assertNotNull(signal.search)
    }

    @Test
    fun `all unknown yields explicit no complete observations status`() {
        val schema = VisibleSchema(mapOf("a" to listOf("0", "1")))
        val observations = listOf(
            Observation(
                "u1", "obj-1",
                mapOf("a" to ObservedValue.Unknown),
                true, EvidenceProvenance("s1", SourceKind.EXTERNAL_PROVIDED, false, true),
            ),
        )
        val signal = adapter.diagnose(schema, observations, listOf("0", "1"))
        assertEquals(DiagnosticSignalStatus.NO_COMPLETE_OBSERVATIONS, signal.status)
        assertNull(signal.search)
    }

    @Test
    fun `invalid observations return machine readable errors`() {
        val schema = VisibleSchema(mapOf("a" to listOf("0", "1")))
        val signal = adapter.diagnose(
            schema,
            listOf(
                Observation(
                    "bad", "obj-1", mapOf("a" to ObservedValue.Known("9")), true,
                    EvidenceProvenance("s1", SourceKind.EXTERNAL_PROVIDED, false, true),
                ),
            ),
            listOf("0", "1"),
        )
        assertEquals(DiagnosticSignalStatus.INVALID_INPUT, signal.status)
        assertTrue(signal.notes.any { it.contains("invalid:invalid_value") })
    }
}
