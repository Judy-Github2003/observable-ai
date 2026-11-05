// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A6 真实预算对照输出；只使用 PublicWorld，不含 family/HiddenTruth。
package logic.e3

import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.WorldFamily
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class E3A6SampleExportTest {

    @Test
    fun `export budget diagnosis for public worlds`() {
        val cases = listOf(
            "solvable" to E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0),
            "search_limited" to E3WorldGenerator(107L).generate(WorldFamily.SEARCH_LIMITED, 0),
            "ambiguous" to E3WorldGenerator(109L).generate(WorldFamily.AMBIGUOUS_EVIDENCE, 0),
            "latent" to E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0),
        )
        val diagnoser = E3SearchBudgetDiagnoser()
        val root = buildJsonObject {
            put("note", "A6 budget diagnosis; public inputs only; no family/truth fields in inputs")
            put("cases", buildJsonArray {
                for ((name, world) in cases) {
                    val constants = PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains")
                    val query = world.public.queryPool.map { it.visible }
                    val result = diagnoser.diagnose(
                        world.public.schema, world.public.observations, constants, query,
                        DiagnosticBudget(maxComposeRounds = 2, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000),
                        DiagnosticBudget(maxComposeRounds = 3, maxNodes = 32, maxCandidates = 3000, maxSteps = 10000),
                    )
                    addJsonObject {
                        put("case", name)
                        put("verdict", result.verdict.name)
                        put("facts", buildJsonObject {
                            put("budgetSensitive", result.facts.budgetSensitive)
                            put("multipleBehaviorClasses", result.facts.multipleBehaviorClasses)
                            put("noConsistentCandidate", result.facts.noConsistentCandidate)
                            put("searchTruncated", result.facts.searchTruncated)
                            put("reasonUndetermined", result.facts.reasonUndetermined)
                            put("observationCollision", result.facts.observationCollision)
                        })
                        put("budgetChanges", buildJsonArray { result.budgetChanges.forEach { add(it) } })
                        put("runs", buildJsonArray {
                            result.runs.forEach { run ->
                                addJsonObject {
                                    put("maxComposeRounds", run.budget.maxComposeRounds)
                                    put("status", run.signal.status.name)
                                    if (run.signal.search == null) put("search", JsonNull) else put("search", buildJsonObject {
                                        put("generated", run.signal.search!!.generated)
                                        put("reason", run.signal.search!!.reason)
                                        put("candidateCount", run.signal.search!!.candidateCount)
                                        put("maxCandidates", run.signal.search!!.maxCandidates)
                                    })
                                    if (run.signal.filter == null) put("filter", JsonNull) else put("filter", buildJsonObject {
                                        put("status", run.signal.filter!!.status)
                                        put("reason", run.signal.filter!!.reason)
                                        put("survivorCount", run.signal.filter!!.survivorCount)
                                        put("domainSize", run.signal.filter!!.domainSize)
                                    })
                                    put("behaviorClasses", run.behaviorClasses.size)
                                    put("consistentCandidateCount", run.layers.consistentWithEvidence)
                                    put("filterRepresentativeCount", run.layers.filterRepresentatives)
                                    put("composerGeneratedCount", run.layers.composerGenerated)
                                    put("behaviorEvalMaxSteps", run.behaviorEvalMaxSteps)
                                }
                            }
                        })
                        put("notes", buildJsonArray { result.notes.forEach { add(it) } })
                    }
                }
            })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/a6-budget-diagnosis.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root) + "\n")
        assertTrue(Files.size(out) > 0)
    }
}
