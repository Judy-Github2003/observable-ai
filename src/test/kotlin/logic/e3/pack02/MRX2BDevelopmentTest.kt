// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02B A/B/C 开发对照与 G0–G4 原始 JSON；不强制新策略获胜。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class MRX2BDevelopmentTest {
    @Test
    fun `A B C strategies and G0 G4 audit`() {
        val full = C8TestBases.bases.library("FULL")
        val worlds = listOf(
            "xor-d1-d2" to { m: Map<String, String> -> (m["d"] == "1") != (m["d"] == "2") },
            "w4-if" to { m: Map<String, String> ->
                if (m["a"] == "1" && m["c"] == "1") m["d"] == "1" else m["d"] == "0"
            },
        )
        val records = ArrayList<JsonObject>()
        for ((name, truth) in worlds) {
            val training = C8Domain.all().filterIndexed { i, _ -> i % 5 == 0 }
                .mapIndexed { i, m -> StudyCase("d-$i", m, truth(m)) }
            for (strategy in MRX2BStrategy.entries) {
                val result = MRX2BSpace(C8Domain.attributes, full.definitions, training, strategy).generate()
                val audit = MRX2BAuditor.auditAfterExam(
                    result.trace, C8Domain.all(), C8Domain.all(), truth, full.definitions, result.selected, 12,
                )
                println("$name ${strategy.wire} gen=${result.generated} cons=${result.consistent} " +
                    "ret=${result.retained} pairs=${result.trace.pairGains.size} g0=${audit.g0} g1=${audit.g1GeneratedCorrect} " +
                    "g2=${audit.g2RetainedCorrect} g3=${audit.g3} g4=${audit.g4SelectedCorrect}")
                records += buildJsonObject {
                    put("world", name)
                    put("strategy", strategy.wire)
                    put("generated", result.generated)
                    put("consistent", result.consistent)
                    put("retained", result.retained)
                    put("pairEvaluations", result.trace.pairGains.size)
                    put("g0", audit.g0)
                    put("g1", audit.g1GeneratedCorrect)
                    put("g2", audit.g2RetainedCorrect)
                    put("g3", audit.g3)
                    put("g4", audit.g4SelectedCorrect)
                    put("queriesNeeded", audit.queriesNeeded)
                    put("queryBudget", audit.queryBudget)
                    put("correctPrunes", audit.correctPrunes.size)
                    put("atoms", buildJsonArray { result.trace.atoms.forEach { add(it) } })
                }
            }
        }
        val root = buildJsonObject {
            put("experiment", "MRX-02B")
            put("phase", "development_search_strategies")
            put("note", "full-domain truth used only by examiner audit after generation")
            put("records", buildJsonArray { records.forEach { add(it) } })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/mrx-02b-search.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
        assertTrue(Files.size(out) > 1000)
        val pairwise = records.filter { it["strategy"]!!.jsonPrimitive.content == "PAIRWISE" }
        assertTrue(pairwise.all { it["pairEvaluations"]!!.jsonPrimitive.int > 0 })
    }
}
