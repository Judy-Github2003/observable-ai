// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 W4 与负例开发实验证据；与正式考试分离，不进入正式成绩。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

data class MRXDevOutcome(
    val world: String,
    val category: String,
    val seed: Int,
    val baseStatus: String,
    val baseMismatch: Int,
    val chosenAction: String,
    val studentStatus: String,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val activation: Boolean,
    val failure: String?,
    val diagnosis: List<String>,
    val trials: List<String>,
    val queries: Int,
    val generated: Int,
)

object MRXDevelopment {
    fun run(): List<MRXDevOutcome> {
        val bases = C8Bases()
        try {
            val cases = listOf(
                "mrx-depth-w4" to 1449,
                "mrx-ambig-1" to 1857,
                "mrx-miss-or-r-d1" to 2020,
                "mrx-noise-or-r-d1" to 2227,
            )
            return cases.map { (id, seed) ->
                runOne(MRXWorlds.baseline.first { it.id == id }, seed, bases)
            }
        } finally {
            bases.close()
        }
    }

    private fun runOne(world: MRXWorld, seed: Int, bases: C8Bases): MRXDevOutcome {
        val full = bases.library("FULL")
        val baseRefs = if (world.libraryMode == "NO-R") bases.library("NO-R").refs else full.refs
        val path = bases.newRun("FULL")
        try {
            val split = MRXWorlds.splitFor(world, seed)
            val training = ArrayList<StudyCase>()
            split.train.forEachIndexed { i, m -> training += StudyCase("d-$i", m, world.truth(m)) }
            if (world.noise) training += StudyCase("d-conflict", split.train.first(), !world.truth(split.train.first()))
            val view = StudyView(
                "mrx-dev-${world.id}", "S", training, split.query, 20, baseRefs,
                C8Domain.attributes, 3, world.maxCandidates, 24, "utility", 0,
            )
            val challenge = StudyQueryController(split.challenge, 24, world.truth)
            val result = MRXStudent(
                view, MRXQuerySource(split.query, 20, world.truth),
                split.challenge, challenge, full.refs, path,
            ).run()
            val selected = result.selected
            val held = selected?.let { candidate ->
                split.heldout.count { input ->
                    Pack02Knowledge.predict(candidate.body, input, full.definitions) == world.truth(input)
                }
            } ?: 0
            val activation = selected?.let { candidate ->
                C8Evaluator(full, path).activate(
                    candidate.body, "S", "mrx.dev.${world.id}", world.truth,
                    C8Split(seed, split.train, split.query, split.heldout, split.digest), held,
                    buildJsonObject { put("phase", "development") },
                )
            }
            return MRXDevOutcome(
                world.id, world.category.wire, seed, result.baseStatus, result.baseChallengeMismatch,
                result.chosenAction, result.status, held, split.heldout.size,
                activation?.active == true, activation?.failure ?: result.failure,
                result.diagnosis.map { it.id }, result.trials.map { "${it.action}:${it.status}:${it.challengeMismatch}" },
                result.totalQueries, result.totalGenerated,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    fun write(outcomes: List<MRXDevOutcome>, path: Path) {
        val root = buildJsonObject {
            put("experiment", "MRX-01")
            put("phase", "development_w4_and_negatives")
            put("outcomes", buildJsonArray {
                outcomes.forEach { o ->
                    add(buildJsonObject {
                        put("world", o.world)
                        put("category", o.category)
                        put("seed", o.seed)
                        put("baseStatus", o.baseStatus)
                        put("baseMismatch", o.baseMismatch)
                        put("chosenAction", o.chosenAction)
                        put("studentStatus", o.studentStatus)
                        put("heldoutCorrect", o.heldoutCorrect)
                        put("heldoutTotal", o.heldoutTotal)
                        put("activation", o.activation)
                        put("failure", o.failure)
                        put("diagnosis", buildJsonArray { o.diagnosis.forEach { add(it) } })
                        put("trials", buildJsonArray { o.trials.forEach { add(it) } })
                        put("queries", o.queries)
                        put("generated", o.generated)
                    })
                }
            })
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
    }
}
