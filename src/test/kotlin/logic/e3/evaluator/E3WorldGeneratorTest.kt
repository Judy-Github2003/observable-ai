// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A2/A3 生成器自检；签名只比较世界内容，不比较 seed 或 ID。
package logic.e3.evaluator

import logic.capability.*
import kotlinx.serialization.json.*
import logic.e3.ContractJson
import logic.e3.FeatureKey
import logic.e3.ObservedValue
import logic.e3.featureKey
import logic.e3.validateObservations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E3WorldGeneratorTest {

    /** 只比较世界实际内容：schema、观察的可见值/标签/来源格式、查询池、真值；不含 seed/ID。 */
    private fun signature(world: EvaluatedWorld): String = buildString {
        append(ContractJson.encodeSchema(world.public.schema))
        append('|')
        world.public.observations
            .map { obs ->
                obs.visible.entries.sortedBy { it.key }.joinToString(",") { (k, v) ->
                    k + "=" + when (v) {
                        is ObservedValue.Known -> "K:" + v.value
                        ObservedValue.Unknown -> "U"
                    }
                } + "#" + obs.label + "#" + obs.provenance.sourceId + "#" +
                    obs.provenance.reliableDeterministicLabels + "#" + obs.round
            }
            .sorted()
            .forEach { append(it).append(';') }
        append('|')
        world.public.queryPool.map { it.visible.toSortedMap().toString() }.sorted().forEach { append(it).append(';') }
        append('|')
        world.truth.fullDomain
            .map { it.visible.toSortedMap().toString() + "#" + it.label }
            .sorted()
            .forEach { append(it).append(';') }
    }

    @Test
    fun `same seed reproduces every world family`() {
        for (family in WorldFamily.entries) {
            val first = E3WorldGenerator(42L).generate(family, 0)
            val second = E3WorldGenerator(42L).generate(family, 0)
            assertEquals(signature(first), signature(second), "family=$family")
        }
    }

    @Test
    fun `different seeds change world content without relying on ids`() {
        for (family in WorldFamily.entries) {
            val signatures = (1L..7L).map { seed -> signature(E3WorldGenerator(seed).generate(family, 0)) }
            assertTrue(signatures.toSet().size > 1, "family=$family should vary across seeds")
        }
    }

    @Test
    fun `public world id is opaque and does not expose seed or index`() {
        val seed = 987654321L
        for (family in WorldFamily.entries) {
            val worldId = E3WorldGenerator(seed).generate(family, 3).public.worldId
            assertTrue(Regex("^w-[0-9a-f]{16}$").matches(worldId), "opaque id: $worldId")
            assertFalse(worldId.contains(seed.toString()))
            assertFalse(worldId.contains("seed"))
            assertFalse(worldId.contains("index"))
        }
    }

    @Test
    fun `A3 families share the same public sample count`() {
        val families = listOf(WorldFamily.NOISY_LABELS, WorldFamily.SEARCH_LIMITED, WorldFamily.AMBIGUOUS_EVIDENCE)
        for (family in families) {
            val world = E3WorldGenerator(43L).generate(family, 0)
            assertEquals(5, world.public.observations.size, "family=$family")
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
            assertFalse(encoded.contains("worldKind"))
            assertFalse(encoded.contains("targetAst"))
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
    fun `public query oracle only accepts query pool ids`() {
        val world = E3WorldGenerator(19L).generate(WorldFamily.LATENT_ALIASING, 0)
        val view = world.studentView()
        val queryId = view.public.queryPool.single().sampleId
        assertTrue(view.query.query(queryId) is PublicQueryAnswer.Labeled)
        for (forbidden in world.truth.heldOut.map { it.sampleId } + world.truth.fullDomain.filter { it.sampleId.contains("-D") }.map { it.sampleId }) {
            assertEquals(PublicQueryAnswer.Rejected("query_not_allowed"), view.query.query(forbidden))
        }
        assertEquals(setOf(queryId), view.query.allowedIds)
    }

    @Test
    fun `latent world contains same-visible different-label evidence`() {
        val world = E3WorldGenerator(3L).generate(WorldFamily.LATENT_ALIASING, 0)
        val groups = world.public.observations.groupBy { featureKey(it).canonical }
        assertTrue(groups.values.any { group -> group.mapNotNull { it.label }.toSet() == setOf(true, false) })
    }

    @Test
    fun `public observations are valid and public query held-out vectors are disjoint`() {
        for (family in WorldFamily.entries) {
            val world = E3WorldGenerator(5L).generate(family, 0)
            assertTrue(validateObservations(world.public.schema, world.public.observations).ok, "family=$family")
            val publicKeys = world.public.observations.map { it.visible.toSortedMap() }.toSet()
            val queryKeys = world.public.queryPool.map { it.visible.toSortedMap() }.toSet()
            val heldOutKeys = world.truth.heldOut.map { it.visible.toSortedMap() }.toSet()
            assertTrue(publicKeys.intersect(queryKeys).isEmpty(), "public/query overlap: $family")
            assertTrue(publicKeys.intersect(heldOutKeys).isEmpty(), "public/heldOut overlap: $family")
            assertTrue(queryKeys.intersect(heldOutKeys).isEmpty(), "query/heldOut overlap: $family")
            assertEquals(2, world.truth.heldOut.size, "heldOut must be explicit and non-empty: $family")
        }
    }

    @Test
    fun `feature key still distinguishes unknown from literal question mark`() {
        val world = E3WorldGenerator(17L).generate(WorldFamily.SOLVABLE, 0)
        val first = world.public.observations.first()
        val known = first.copy(visible = first.visible + ("material" to ObservedValue.Known("?")))
        val unknown = first.copy(visible = first.visible + ("material" to ObservedValue.Unknown))
        val a: FeatureKey = featureKey(known)
        val b: FeatureKey = featureKey(unknown)
        assertTrue(a.canonical != b.canonical)
    }

    // ---------- 匹配对照与 A3 证据 ----------

    @Test
    fun `matched pair shares schema domains and source format`() {
        val (solvable, latent) = E3AdvancedWorldGenerator(23L).matchedPair(0)
        assertEquals(solvable.public.schema, latent.public.schema)
        assertEquals(solvable.public.observations.size, latent.public.observations.size)
        assertEquals(solvable.public.queryPool.size, latent.public.queryPool.size)
        assertEquals(solvable.truth.heldOut.size, latent.truth.heldOut.size)
        assertEquals(
            solvable.public.observations.groupingBy { it.provenance.sourceId }.eachCount().keys,
            latent.public.observations.groupingBy { it.provenance.sourceId }.eachCount().keys,
        )
        assertEquals(
            solvable.public.observations.map { it.provenance.reliableDeterministicLabels }.toSet(),
            latent.public.observations.map { it.provenance.reliableDeterministicLabels }.toSet(),
        )
        assertFalse(solvable.public.worldId.contains("solvable"))
        assertFalse(latent.public.worldId.contains("latent"))
    }

    @Test
    fun `noisy labels expose reliable-source contradiction`() {
        val world = E3WorldGenerator(29L).generate(WorldFamily.NOISY_LABELS, 0)
        val groups = world.public.observations.groupBy { featureKey(it).canonical }
        assertTrue(groups.values.any { group ->
            group.map { it.label }.toSet() == setOf(true, false) &&
                group.map { it.provenance.sourceId }.toSet().size == 1 &&
                group.all { it.provenance.reliableDeterministicLabels == true }
        })
        assertTrue(world.truth.family == WorldFamily.NOISY_LABELS)
    }

    @Test
    fun `ambiguous evidence fits two different shallow hypotheses`() {
        val world = E3WorldGenerator(31L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0)
        fun fits(field: String): Boolean = world.public.observations.all { obs ->
            (obs.visible.getValue(field) as ObservedValue.Known).value == "1" == obs.label
        }
        assertTrue(fits("a"))
        assertTrue(fits("b"))
        assertTrue(world.public.observations.groupBy { featureKey(it).canonical }.values.all { group ->
            group.map { it.label }.toSet().size == 1
        })
    }

    @Test
    fun `search limited low budget composer control is recorded`() {
        val world = E3WorldGenerator(37L).generate(WorldFamily.SEARCH_LIMITED, 0)
        val type = CapType.Record(world.public.schema.fields.keys.sorted().associateWith { CapType.Str })
        val inputs = mapOf("x" to type)
        val constants = listOf("0", "1").map { CapValue.Str(it) }
        val interpreter = CapabilityInterpreter(InMemoryResolver(emptyList()))
        val records = ArrayList<kotlinx.serialization.json.JsonObject>()
        for (depth in listOf(2, 3)) {
            val composed = CapabilityComposer().compose(
                ComposeRequest(inputs, CapType.Bool3, constants, emptyList(), ComposeBudget(depth, 32, 3000)),
            )
            var fitted = 0
            for (candidate in composed.candidates) {
                val definition = CapabilityDefinition("a3.control", 1, inputs, CapType.Bool3, candidate.body)
                val ok = world.public.observations.all { obs ->
                    val args = mapOf("x" to EvalResult.Known(CapValue.Rec(
                        obs.visible.mapValues { (_, v) -> CapValue.Str((v as ObservedValue.Known).value) },
                    )))
                    val actual = interpreter.execute(definition, args)
                    actual is EvalResult.Known && (actual.value as CapValue.Bool).value == obs.label
                }
                if (ok) fitted++
            }
            records += kotlinx.serialization.json.buildJsonObject {
                put("maxDepth", depth)
                put("generated", composed.generated)
                if (composed.reason == null) put("reason", kotlinx.serialization.json.JsonNull) else put("reason", composed.reason)
                put("candidate_count", composed.candidates.size)
                put("candidates_fitting_public", fitted)
            }
        }
        val out = java.nio.file.Path.of("experiments/representation-evolution-e3/reports/a3-search-limited-composer.json")
        java.nio.file.Files.createDirectories(out.parent)
        java.nio.file.Files.writeString(out, kotlinx.serialization.json.Json { prettyPrint = true }
            .encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(records)) + "\n")
        assertEquals(2, records.size)
    }

    @Test
    fun `search limited has no shallow literal combination consistent`() {
        val world = E3WorldGenerator(41L).generate(WorldFamily.SEARCH_LIMITED, 0)
        val fields = world.public.schema.fields.keys.toList()
        val literals = fields.flatMap { field ->
            listOf("0", "1").map { value -> { p: Map<String, String> -> p.getValue(field) == value } }
        }
        val negated = literals.map { lit -> { p: Map<String, String> -> !lit(p) } }
        val atoms = literals + negated
        val binaries = atoms.flatMap { a -> atoms.flatMap { b -> listOf< (Map<String, String>) -> Boolean >(
            { p -> a(p) && b(p) },
            { p -> a(p) || b(p) },
        ) } }
        val candidates = atoms + binaries
        assertTrue(candidates.none { candidate ->
            world.public.observations.all { obs ->
                val visible = obs.visible.mapValues { (_, v) -> (v as ObservedValue.Known).value }
                candidate(visible) == obs.label
            }
        })
    }

    @Test
    fun `search limited truth is deeper and no simple field rule fits`() {
        val world = E3WorldGenerator(37L).generate(WorldFamily.SEARCH_LIMITED, 0)
        assertTrue(world.truth.ruleDepth > 3)
        fun fits(field: String): Boolean = world.public.observations.all { obs ->
            (obs.visible.getValue(field) as ObservedValue.Known).value == "1" == obs.label
        }
        assertFalse(fits("a"))
        assertFalse(fits("b"))
        assertFalse(fits("c"))
        val groups = world.public.observations.groupBy { featureKey(it).canonical }
        assertTrue(groups.values.none { group -> group.map { it.label }.toSet().size > 1 })
    }
}
