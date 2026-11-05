// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A2 生成示例世界数据，供报告复核；这是评测端导出，不进入学生接口。
package logic.e3.evaluator

import kotlinx.serialization.json.*
import logic.e3.ContractJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class E3WorldSampleExportTest {

    @Test
    fun `export seed 42 sample worlds for review`() {
        val out = Path.of(
            System.getenv("E3_SAMPLE_OUT")
                ?: "experiments/representation-evolution-e3/reports/a2-sample-worlds.json",
        )
        val generator = E3WorldGenerator(42L)
        val root = buildJsonObject {
            put("seed", 42)
            put("note", "evaluator-side sample; contains hidden truth and must not be given to the student")
            for (family in WorldFamily.entries) {
                val world = generator.generate(family, 0)
                put(family.name.lowercase(), buildJsonObject {
                    put("public", buildJsonObject {
                        put("worldId", world.public.worldId)
                        put("schema", Json.parseToJsonElement(ContractJson.encodeSchema(world.public.schema)))
                        put("observations", Json.parseToJsonElement(ContractJson.encodeObservations(world.public.observations)))
                        put("queryPool", buildJsonArray {
                            world.public.queryPool.forEach { sample ->
                                addJsonObject {
                                    put("sampleId", sample.sampleId)
                                    put("visible", buildJsonObject { sample.visible.toSortedMap().forEach { (k, v) -> put(k, v) } })
                                }
                            }
                        })
                    })
                    put("truth", buildJsonObject {
                        put("family", world.truth.family.name)
                        put("ruleDescription", world.truth.ruleDescription)
                        if (world.truth.hiddenFeature == null) put("hiddenFeature", JsonNull) else put("hiddenFeature", world.truth.hiddenFeature)
                        put("labels", buildJsonObject { world.truth.truthBySampleId.toSortedMap().forEach { (id, label) -> put(id, label) } })
                        put("fullDomain", buildJsonArray {
                            world.truth.fullDomain.forEach { point ->
                                addJsonObject {
                                    put("sampleId", point.sampleId)
                                    put("visible", buildJsonObject { point.visible.toSortedMap().forEach { (k, v) -> put(k, v) } })
                                    put("hidden", buildJsonObject { point.hidden.toSortedMap().forEach { (k, v) -> put(k, v) } })
                                    put("label", point.label)
                                }
                            }
                        })
                    })
                })
            }
        }
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n")
        assertTrue(Files.size(out) > 0)
    }
}
