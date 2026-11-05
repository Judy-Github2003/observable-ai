// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A2 审查修正（匹配对照）与 A3 世界族；证据层区别不依赖 family 名称。
package logic.e3.evaluator

import logic.e3.*

/**
 * A3 及匹配对照的生成器。所有世界共用同一套公开格式：
 * - 相同 schema（A3 三族：a/b/c；匹配对照：a/b）；
 * - 相同来源格式（source-a / source-b，round 1）；
 * - 中性 worldId（不含 family）。
 */
class E3AdvancedWorldGenerator(private val seed: Long) {

    fun generate(family: WorldFamily, index: Int = 0): EvaluatedWorld = when (family) {
        WorldFamily.NOISY_LABELS -> noisy(index)
        WorldFamily.SEARCH_LIMITED -> searchLimited(index)
        WorldFamily.AMBIGUOUS_EVIDENCE -> ambiguous(index)
        else -> error("advanced_generator_does_not_own:$family")
    }

    /** 同一 schema/值域/来源格式的 SOLVABLE 与 LATENT_ALIASING 对照。 */
    fun matchedPair(index: Int = 0): Pair<EvaluatedWorld, EvaluatedWorld> =
        matchedSolvable(index) to matchedLatent(index + 1)

    private fun random(family: WorldFamily, index: Int) =
        java.util.Random(seed * 1_000_003L + family.ordinal * 97L + index)

    private fun cartesian(fields: List<String>, values: Map<String, List<String>>): List<Map<String, String>> {
        var result = listOf(emptyMap<String, String>())
        for (field in fields) {
            result = result.flatMap { prefix -> values.getValue(field).map { prefix + (field to it) } }
        }
        return result
    }

    private fun observation(
        id: String,
        objectId: String,
        point: Map<String, String>,
        fields: List<String>,
        label: Boolean,
        source: String,
        reliable: Boolean?,
        round: Int = 1,
    ) = Observation(
        observationId = id,
        objectId = objectId,
        visible = fields.sorted().associateWith { ObservedValue.Known(point.getValue(it)) },
        label = label,
        provenance = EvidenceProvenance(source, SourceKind.EXTERNAL_PROVIDED, false, reliable),
        round = round,
    )

    private fun world(fields: List<String>, values: Map<String, List<String>>, worldId: String): PublicWorld =
        PublicWorld(worldId, VisibleSchema(values), emptyList(), emptyList(), emptyList())

    private fun finish(
        worldId: String,
        fields: List<String>,
        values: Map<String, List<String>>,
        observations: List<Observation>,
        query: List<PublicSample>,
        hidden: List<HiddenPoint>,
        fullDomain: List<HiddenPoint>,
        family: WorldFamily,
        rule: String,
        hiddenFeature: String?,
        ruleDepth: Int,
    ): EvaluatedWorld {
        val labels = observations.map { it.observationId to (it.label ?: false) } +
            query.map { it.sampleId to fullDomain.first { p -> p.visible == it.visible }.label } +
            hidden.map { it.sampleId to it.label } +
            fullDomain.map { it.sampleId to it.label }
        val truth = HiddenTruth(
            worldId = worldId,
            family = family,
            ruleDescription = rule,
            hiddenFeature = hiddenFeature,
            truthBySampleId = labels.toMap(),
            fullDomain = fullDomain,
            heldOut = hidden,
            ruleDepth = ruleDepth,
        )
        return EvaluatedWorld(
            PublicWorld(worldId, VisibleSchema(values), observations, observations.map { it.observationId }, query),
            truth,
            PublicQueryOracle.from(query, truth.truthBySampleId),
        )
    }

    private data class BinaryWorld(
        val fields: List<String>,
        val values: Map<String, List<String>>,
        val domain: List<Map<String, String>>,
        val rng: java.util.Random,
        val worldId: String,
    )

    private fun binary(index: Int, family: WorldFamily): BinaryWorld {
        val fields = listOf("a", "b", "c")
        val values = mapOf("a" to listOf("0", "1"), "b" to listOf("0", "1"), "c" to listOf("0", "1"))
        return BinaryWorld(fields, values, cartesian(fields, values), random(family, index), "world-$seed-$index")
    }

    private fun noisy(index: Int): EvaluatedWorld {
        val (fields, values, domain, rng, worldId) = binary(index, WorldFamily.NOISY_LABELS)
        val shuffled = domain.shuffled(rng)
        val base: (Map<String, String>) -> Boolean = { it.getValue("a") == "1" }
        val first = shuffled[0]
        val observations = ArrayList<Observation>()
        // 同一来源、同一对象、不同轮：可靠来源却给出相反标签，形成噪声证据。
        observations += observation("$worldId-P1", "obj-0", first, fields, base(first), "source-a", true, round = 1)
        observations += observation("$worldId-P2", "obj-0", first, fields, !base(first), "source-a", true, round = 2)
        for ((i, point) in shuffled.drop(1).take(3).withIndex()) {
            observations += observation("$worldId-P${i + 3}", "obj-${i + 1}", point, fields, base(point), "source-a", true)
        }
        val hidden = shuffled.drop(4).take(2).mapIndexed { i, point ->
            HiddenPoint("$worldId-H${i + 1}", point, emptyMap(), base(point))
        }
        val query = listOf(PublicSample("$worldId-Q1", shuffled[6]))
        val fullDomain = domain.mapIndexed { i, point -> HiddenPoint("$worldId-D${i + 1}", point, emptyMap(), base(point)) }
        return finish(worldId, fields, values, observations, query, hidden, fullDomain,
            WorldFamily.NOISY_LABELS, "a == 1 (with contradictory reliable labels)", null, 1)
    }

    private fun searchLimited(index: Int): EvaluatedWorld {
        val (fields, values, domain, rng, worldId) = binary(index, WorldFamily.SEARCH_LIMITED)
        val rule: (Map<String, String>) -> Boolean = { p ->
            (p.getValue("a") == "1") xor (p.getValue("b") == "1") xor (p.getValue("c") == "1")
        }
        val shuffled = domain.shuffled(rng)
        val selected = shuffled.take(5)
        val observations = selected.mapIndexed { i, point ->
            observation("$worldId-P${i + 1}", "obj-$i", point, fields, rule(point), "source-a", true)
        }
        val hidden = shuffled.drop(5).take(2).mapIndexed { i, point ->
            HiddenPoint("$worldId-H${i + 1}", point, emptyMap(), rule(point))
        }
        val query = listOf(PublicSample("$worldId-Q1", shuffled[7]))
        val fullDomain = domain.mapIndexed { i, point -> HiddenPoint("$worldId-D${i + 1}", point, emptyMap(), rule(point)) }
        return finish(worldId, fields, values, observations, query, hidden, fullDomain,
            WorldFamily.SEARCH_LIMITED, "a == 1 XOR b == 1 XOR c == 1 (depth 4)", null, 4)
    }

    private fun ambiguous(index: Int): EvaluatedWorld {
        val (fields, values, domain, rng, worldId) = binary(index, WorldFamily.AMBIGUOUS_EVIDENCE)
        val rule: (Map<String, String>) -> Boolean = { it.getValue("a") == "1" }
        val sameAB = domain.filter { it.getValue("a") == it.getValue("b") }.shuffled(rng).take(4)
        val observations = sameAB.mapIndexed { i, point ->
            observation("$worldId-P${i + 1}", "obj-$i", point, fields, rule(point), "source-a", true)
        }
        val differentAB = domain.filter { it.getValue("a") != it.getValue("b") }.shuffled(rng)
        val hidden = differentAB.take(2).mapIndexed { i, point ->
            HiddenPoint("$worldId-H${i + 1}", point, emptyMap(), rule(point))
        }
        val query = listOf(PublicSample("$worldId-Q1", differentAB[2]))
        val fullDomain = domain.mapIndexed { i, point -> HiddenPoint("$worldId-D${i + 1}", point, emptyMap(), rule(point)) }
        return finish(worldId, fields, values, observations, query, hidden, fullDomain,
            WorldFamily.AMBIGUOUS_EVIDENCE, "a == 1 (public also fits b == 1)", null, 1)
    }

    private fun matchedSolvable(index: Int): EvaluatedWorld {
        val fields = listOf("a", "b")
        val values = mapOf("a" to listOf("a0", "a1", "a2"), "b" to listOf("b0", "b1", "b2"))
        val domain = cartesian(fields, values)
        val rng = random(WorldFamily.SOLVABLE, index)
        val rule: (Map<String, String>) -> Boolean = { p -> p.getValue("a") == "a1" && p.getValue("b") == "b1" }
        val shuffled = domain.shuffled(rng)
        val selected = ArrayList<Map<String, String>>()
        shuffled.firstOrNull(rule)?.let(selected::add)
        shuffled.firstOrNull { !rule(it) }?.let(selected::add)
        for (point in shuffled) if (selected.size < 5 && point !in selected) selected += point
        val hiddenPoints = shuffled.filterNot { it in selected }.take(2)
        val queryPoints = shuffled.filterNot { it in selected || it in hiddenPoints }.take(1)
        val worldId = "world-$seed-$index"
        val observations = selected.mapIndexed { i, point ->
            observation("$worldId-P${i + 1}", "obj-$i", point, fields, rule(point), if (i % 2 == 0) "source-a" else "source-b", null)
        }
        val hidden = hiddenPoints.mapIndexed { i, point -> HiddenPoint("$worldId-H${i + 1}", point, emptyMap(), rule(point)) }
        val query = queryPoints.mapIndexed { i, point -> PublicSample("$worldId-Q${i + 1}", point) }
        val fullDomain = domain.mapIndexed { i, point -> HiddenPoint("$worldId-D${i + 1}", point, emptyMap(), rule(point)) }
        return finish(worldId, fields, values, observations, query, hidden, fullDomain,
            WorldFamily.SOLVABLE, "a == a1 AND b == b1", null, 2)
    }

    private fun matchedLatent(index: Int): EvaluatedWorld {
        val fields = listOf("a", "b")
        val values = mapOf("a" to listOf("a0", "a1", "a2"), "b" to listOf("b0", "b1", "b2"))
        val domain = cartesian(fields, values)
        val rng = random(WorldFamily.LATENT_ALIASING, index)
        val worldId = "world-$seed-$index"
        val rule: (Map<String, String>, Boolean) -> Boolean = { p, latent -> (p.getValue("a") == "a1") xor latent }
        val shuffled = domain.shuffled(rng)
        val collision = shuffled[0]
        val others = shuffled.drop(1).take(3)
        val hiddenPoints = shuffled.drop(4).take(2)
        val queryPoint = shuffled[6]
        val observations = ArrayList<Observation>()
        observations += observation("$worldId-P1", "obj-0a", collision, fields, rule(collision, false), "source-a", null)
        observations += observation("$worldId-P2", "obj-0b", collision, fields, rule(collision, true), "source-b", null)
        for ((i, point) in others.withIndex()) {
            observations += observation("$worldId-P${i + 3}", "obj-${i + 1}", point, fields, rule(point, false), if (i % 2 == 0) "source-a" else "source-b", null)
        }
        val hidden = hiddenPoints.mapIndexed { i, point ->
            HiddenPoint("$worldId-H${i + 1}", point, mapOf("latent" to (i % 2).toString()), rule(point, i % 2 == 1))
        }
        val query = listOf(PublicSample("$worldId-Q1", queryPoint))
        val fullDomain = domain.flatMapIndexed { i, point ->
            listOf(false, true).mapIndexed { j, latent ->
                HiddenPoint("$worldId-D${i * 2 + j + 1}", point, mapOf("latent" to if (latent) "1" else "0"), rule(point, latent))
            }
        }
        return finish(worldId, fields, values, observations, query, hidden, fullDomain,
            WorldFamily.LATENT_ALIASING, "a == a1 XOR latent", "latent", 2)
    }
}
