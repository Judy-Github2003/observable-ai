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
            world.public.schema, world.public.observations, PublicConstants(constants(world), "schema_domains"), queryDomain(world),
        )
        assertTrue(signal.collisionReport.findings.isEmpty())
        assertNotNull(signal.search)
        assertNotNull(signal.filter)
        assertEquals(3, signal.search!!.maxComposeRounds)
        assertEquals(10000, signal.search!!.maxSteps)
        assertTrue(signal.search!!.maxNodes >= 1)
        assertTrue(signal.search!!.maxCandidates >= 1)
        assertTrue(signal.usableObservationIds.isNotEmpty())
        assertTrue(signal.notes.contains("identified_scope_is_bounded_to_current_hypothesis_and_query_domain"))
        assertEquals(DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE, signal.status)
    }

    @Test
    fun `latent collision is preserved and search is not pretended to resolve it`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val signal = adapter.diagnose(
            world.public.schema, world.public.observations, PublicConstants(constants(world), "schema_domains"), queryDomain(world),
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
            world.public.schema, world.public.observations, PublicConstants(constants(world), "schema_domains"), queryDomain(world),
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
            world.public.schema, world.public.observations, PublicConstants(constants(world), "schema_domains"), queryDomain(world),
        )
        assertTrue(signal.collisionReport.findings.isEmpty())
        assertNotNull(signal.filter)
        assertEquals(DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE, signal.status)
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
        val signal = adapter.diagnose(schema, observations, PublicConstants(listOf("0", "1"), "schema_domains"))
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
        val signal = adapter.diagnose(schema, observations, PublicConstants(listOf("0", "1"), "schema_domains"))
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
            PublicConstants(listOf("0", "1"), "schema_domains"),
        )
        assertEquals(DiagnosticSignalStatus.INVALID_INPUT, signal.status)
        assertTrue(signal.notes.any { it.contains("invalid:invalid_value") })
    }

    @Test
    fun `invalid query points are rejected with machine readable errors`() {
        val schema = VisibleSchema(mapOf("a" to listOf("0", "1"), "b" to listOf("0", "1")))
        val observations = listOf(
            Observation(
                "o1", "obj-1",
                mapOf("a" to ObservedValue.Known("0"), "b" to ObservedValue.Known("0")),
                true, EvidenceProvenance("s", SourceKind.EXTERNAL_PROVIDED, false, true),
            ),
        )
        val constants = PublicConstants(listOf("0", "1"), "schema_domains")
        fun notes(domain: List<Map<String, String>>): List<String> =
            adapter.diagnose(schema, observations, constants, domain).notes

        assertTrue(notes(listOf(mapOf("a" to "0"))).any { it.contains("query_missing_field") })
        assertTrue(notes(listOf(mapOf("a" to "0", "b" to "0", "c" to "x"))).any { it.contains("query_unknown_field") })
        assertTrue(notes(listOf(mapOf("a" to "9", "b" to "0"))).any { it.contains("query_invalid_value") })
        assertTrue(notes(listOf(mapOf("a" to "0", "b" to "0"), mapOf("a" to "0", "b" to "0"))).any { it.contains("duplicate_query_point") })
        assertTrue(notes(List(257) { mapOf("a" to "0", "b" to "0") }).any { it.contains("query_domain_too_large") })
    }

    @Test
    fun `constants must come from public schema domain and have a source`() {
        val schema = VisibleSchema(mapOf("a" to listOf("0", "1")))
        val observations = listOf(
            Observation(
                "o1", "obj-1", mapOf("a" to ObservedValue.Known("0")), true,
                EvidenceProvenance("s", SourceKind.EXTERNAL_PROVIDED, false, true),
            ),
        )
        val badValue = adapter.diagnose(schema, observations, PublicConstants(listOf("9"), "public"))
        assertEquals(DiagnosticSignalStatus.INVALID_INPUT, badValue.status)
        assertTrue(badValue.notes.any { it.contains("constant_not_in_public_domain") })

        val badSource = adapter.diagnose(schema, observations, PublicConstants(listOf("0"), " "))
        assertEquals(DiagnosticSignalStatus.INVALID_INPUT, badSource.status)
        assertTrue(badSource.notes.any { it.contains("blank_constants_source") })
    }

    @Test
    fun `filter domainSize is actual evaluated size and candidate budget is visible`() {
        val world = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)
        val signal = adapter.diagnose(
            world.public.schema,
            world.public.observations,
            PublicConstants(constants(world), "schema_domains"),
            queryDomain(world),
        )
        assertEquals(2, signal.filter!!.domainSize)
        assertEquals(queryDomain(world).size, signal.filter!!.domainSize)

        val tight = adapter.diagnose(
            world.public.schema,
            world.public.observations,
            PublicConstants(constants(world), "schema_domains"),
            queryDomain(world),
            DiagnosticBudget(maxComposeRounds = 3, maxNodes = 32, maxCandidates = 1, maxSteps = 10000),
        )
        assertEquals("candidate_limit", tight.search!!.reason)
        assertEquals(1, tight.search!!.maxCandidates)
    }
}
