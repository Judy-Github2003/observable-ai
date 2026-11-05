// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A5 生成真实诊断信号样本；仅使用 PublicWorld，不含 family/HiddenTruth。
package logic.e3

import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.WorldFamily
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class E3A5SampleExportTest {

    @Test
    fun `export diagnostic signals for four public worlds`() {
        val cases = listOf(
            "solvable" to E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0),
            "latent" to E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0),
            "search_limited" to E3WorldGenerator(107L).generate(WorldFamily.SEARCH_LIMITED, 0),
            "ambiguous" to E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0),
        )
        val adapter = E3B0DiagnosticAdapter()
        val root = buildJsonObject {
            put("note", "A5 real adapter outputs; public inputs only")
            put("cases", buildJsonArray {
                for ((name, world) in cases) {
                    val signal = adapter.diagnose(
                        world.public.schema,
                        world.public.observations,
                        world.public.schema.fields.values.flatten().distinct(),
                        world.public.queryPool.map { it.visible },
                    )
                    addJsonObject {
                        put("case", name)
                        put("status", signal.status.name)
                        put("usableObservationIds", buildJsonArray { signal.usableObservationIds.forEach { add(it) } })
                        put("excludedUnknownValueIds", buildJsonArray { signal.excludedUnknownValueIds.forEach { add(it) } })
                        put("collisionFindings", signal.collisionReport.findings.size)
                        put("collisionParticipants", buildJsonArray {
                            signal.collisionReport.findings.flatMap { it.participants }.forEach { p ->
                                addJsonObject {
                                    put("observationId", p.observationId)
                                    put("objectId", p.objectId)
                                    put("sourceId", p.sourceId)
                                    put("round", p.round)
                                    put("label", p.label)
                                }
                            }
                        })
                        if (signal.search == null) put("search", JsonNull) else put("search", buildJsonObject {
                            put("maxDepth", signal.search!!.maxDepth)
                            put("generated", signal.search!!.generated)
                            put("exhausted", signal.search!!.exhausted)
                            if (signal.search!!.reason == null) put("reason", JsonNull) else put("reason", signal.search!!.reason)
                            put("candidateCount", signal.search!!.candidateCount)
                        })
                        if (signal.filter == null) put("filter", JsonNull) else put("filter", buildJsonObject {
                            put("status", signal.filter!!.status)
                            if (signal.filter!!.reason == null) put("reason", JsonNull) else put("reason", signal.filter!!.reason)
                            put("survivorCount", signal.filter!!.survivorCount)
                            put("queryCount", signal.filter!!.queryCount)
                            put("domainSize", signal.filter!!.domainSize)
                        })
                        put("notes", buildJsonArray { signal.notes.forEach { add(it) } })
                    }
                }
            })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/a5-diagnostic-signals.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n")
        assertTrue(Files.size(out) > 0)
    }
}
