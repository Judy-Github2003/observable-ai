// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A2 生成器自检；验证可复现、种子变化、公开/隐藏分层与 oracle。
package logic.e3.evaluator

import logic.e3.ContractJson
import logic.e3.FeatureKey
import logic.e3.featureKey
import logic.e3.validateObservations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E3WorldGeneratorTest {

    private fun signature(world: EvaluatedWorld): String = buildString {
        append(world.public.worldId)
        append('|')
        append(ContractJson.encodeSchema(world.public.schema))
        append('|')
        append(ContractJson.encodeObservations(world.public.observations))
        append('|')
        append(world.public.queryPool.joinToString(";") { it.sampleId + ":" + it.visible.toSortedMap() })
        append('|')
        append(world.truth.fullDomain.joinToString(";") { it.sampleId + ":" + it.label })
    }

    @Test
    fun `same seed reproduces both world families`() {
        for (family in WorldFamily.entries) {
            val first = E3WorldGenerator(42L).generate(family, 0)
            val second = E3WorldGenerator(42L).generate(family, 0)
            assertEquals(signature(first), signature(second), "family=$family")
        }
    }

    @Test
    fun `different seeds change public data or truth`() {
        for (family in WorldFamily.entries) {
            val signatures = (1L..5L).map { seed -> signature(E3WorldGenerator(seed).generate(family, 0)) }
            assertTrue(signatures.toSet().size > 1, "family=$family should vary across seeds")
        }
    }

    @Test
    fun `public world never exposes hidden feature names`() {
        for (family in WorldFamily.entries) {
            val world = E3WorldGenerator(7L).generate(family, 0)
            val encoded = ContractJson.encodeObservations(world.public.observations) +
                ContractJson.encodeSchema(world.public.schema) +
                world.public.toString()
            assertFalse(encoded.contains("latent"), "public data must not expose hidden feature: $family")
            assertFalse(encoded.contains("worldKind"), "public data must not expose world kind")
            assertFalse(encoded.contains("targetAst"), "public data must not expose target AST")
            assertTrue(world.public.schema.fields.keys.none { it == "latent" })
        }
    }

    @Test
    fun `oracle labels every declared domain point consistently`() {
        for (family in WorldFamily.entries) {
            val world = E3WorldGenerator(11L).generate(family, 0)
            val oracle = E3WorldOracle(world.truth)
            for (point in world.truth.fullDomain) {
                assertEquals(point.label, oracle.label(point.sampleId), "family=$family id=${point.sampleId}")
            }
        }
    }

    @Test
    fun `latent world contains same-visible different-label evidence`() {
        val world = E3WorldGenerator(3L).generate(WorldFamily.LATENT_ALIASING, 0)
        val groups = world.public.observations.groupBy { featureKey(it).canonical }
        assertTrue(groups.values.any { group -> group.mapNotNull { it.label }.toSet() == setOf(true, false) })
    }

    @Test
    fun `public observations are valid and hidden points are disjoint`() {
        for (family in WorldFamily.entries) {
            val world = E3WorldGenerator(5L).generate(family, 0)
            assertTrue(validateObservations(world.public.schema, world.public.observations).ok, "family=$family")
            val publicKeys = world.public.observations.map { it.visible.toSortedMap() }.toSet()
            val hiddenKeys = world.truth.fullDomain
                .filter { it.sampleId.contains("-H") }
                .map { it.visible.toSortedMap() }
                .toSet()
            assertTrue(publicKeys.intersect(hiddenKeys).isEmpty(), "hidden held-out must not reuse public visible vectors")
        }
    }

    @Test
    fun `query pool has no labels and is disjoint from hidden`() {
        val world = E3WorldGenerator(13L).generate(WorldFamily.SOLVABLE, 0)
        val queryKeys = world.public.queryPool.map { it.visible.toSortedMap() }.toSet()
        val hiddenKeys = world.truth.fullDomain.filter { it.sampleId.contains("-H") }.map { it.visible.toSortedMap() }.toSet()
        assertTrue(queryKeys.intersect(hiddenKeys).isEmpty())
        assertTrue(world.public.queryPool.all { it.sampleId.isNotBlank() })
    }

    @Test
    fun `feature key still distinguishes unknown from literal question mark`() {
        val world = E3WorldGenerator(17L).generate(WorldFamily.SOLVABLE, 0)
        val first = world.public.observations.first()
        val known = first.copy(visible = first.visible + ("material" to logic.e3.ObservedValue.Known("?")))
        val unknown = first.copy(visible = first.visible + ("material" to logic.e3.ObservedValue.Unknown))
        val a: FeatureKey = featureKey(known)
        val b: FeatureKey = featureKey(unknown)
        assertTrue(a.canonical != b.canonical)
    }
}
