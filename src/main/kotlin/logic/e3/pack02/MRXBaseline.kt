// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 第一阶段：用冻结 C8-03 学生在 20+ 个新世界上建立未知任务基线。
// 边界：只调用冻结 ActiveStudySession；不改学生、不把基线结果当作外部闭卷。
package logic.e3.pack02

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

data class MRXBaselineRecord(
    val world: String,
    val category: String,
    val seed: Int,
    val status: String,
    val generated: Int,
    val consistent: Int,
    val initialClasses: Int,
    val remainingClasses: Int,
    val queries: Int,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val activation: Boolean,
    val failure: String?,
    val selectedNodes: Int,
    val selectedDeps: List<String>,
    val interpreterCalls: Int,
    val wallMillis: Long,
)

object MRXBaseline {
    fun run(): List<MRXBaselineRecord> {
        val bases = C8Bases()
        try {
            val records = ArrayList<MRXBaselineRecord>()
            MRXWorlds.baseline.forEachIndexed { index, world ->
                val used = HashSet<Int>()
                repeat(2) { repetition ->
                    val (seed, split) = MRXWorlds.findSeed(world, from = 1200 + index * 41 + repetition * 700, avoid = used)
                    used += seed
                    records += runOne(world, split, bases)
                }
            }
            return records
        } finally {
            bases.close()
        }
    }

    private fun runOne(world: MRXWorld, split: MRXSplit, bases: C8Bases): MRXBaselineRecord {
        val library = bases.library(world.libraryMode)
        val runPath = bases.newRun(world.libraryMode)
        try {
            val training = ArrayList<StudyCase>()
            split.train.forEachIndexed { index, input ->
                training += StudyCase("mrx-tr-$index", input, world.truth(input))
            }
            if (world.noise) {
                // 同一输入出现相反标签：这是观察冲突，不是目标规律。
                training += StudyCase("mrx-tr-conflict", split.train.first(), !world.truth(split.train.first()))
            }
            val view = StudyView(
                "mrx-session-${world.id}", "S", training, split.query, 20, library.refs,
                C8Domain.attributes, 3, world.maxCandidates, 24, "utility", 0,
            )
            val result = ActiveStudySession(
                view, StudyQueryController(split.query, 20, world.truth), runPath,
            ).run()
            val c8Split = C8Split(seed = split.seed, train = split.train, query = split.query, heldout = split.heldout, digest = split.digest)
            val evaluator = C8Evaluator(library, runPath)
            val selected = result.selected
            val correct = selected?.let { evaluator.correct(it.body, world.truth, split.heldout) } ?: 0
            val activation = selected?.let {
                evaluator.activate(
                    it.body, "S", "mrx.baseline.${world.id}.${split.seed}", world.truth, c8Split, correct,
                    buildJsonObject { put("phase", "baseline") },
                )
            }
            return MRXBaselineRecord(
                world.id, world.category.wire, split.seed, result.status,
                result.generated, result.consistent, result.initialClasses, result.remainingClasses,
                result.rounds.size, correct, split.heldout.size,
                activation?.active == true, activation?.failure ?: result.failure,
                selected?.nodeCount ?: 0, selected?.dependencyIds ?: emptyList(),
                result.stats.interpreterCalls, result.stats.wallMillis,
            )
        } finally {
            Files.deleteIfExists(runPath)
        }
    }

    fun write(records: List<MRXBaselineRecord>, path: Path) {
        val summary = buildJsonObject {
            put("worlds", MRXWorlds.baseline.size)
            put("runs", records.size)
            put("success", records.count { it.activation })
            put("identified", records.count { it.status == "IDENTIFIED" })
            put("verificationRejected", records.count { it.status == "IDENTIFIED" && !it.activation })
            put("noCandidate", records.count { it.status == "NO_CANDIDATE" })
            put("searchLimited", records.count { it.status == "SEARCH_LIMITED" })
            put("wrongIdentification", records.count { it.heldoutCorrect < it.heldoutTotal && it.status == "IDENTIFIED" })
            put("byCategory", buildJsonObject {
                records.groupBy { it.category }.forEach { (category, group) ->
                    put(category, buildJsonObject {
                        put("runs", group.size)
                        put("activated", group.count { it.activation })
                        put("identified", group.count { it.status == "IDENTIFIED" })
                    })
                }
            })
        }
        val root = buildJsonObject {
            put("experiment", "MRX-01")
            put("phase", "baseline_frozen_c8_03")
            put("summary", summary)
            put("runs", buildJsonArray {
                records.forEach { record ->
                    add(buildJsonObject {
                        put("world", record.world)
                        put("category", record.category)
                        put("seed", record.seed)
                        put("status", record.status)
                        put("generated", record.generated)
                        put("consistent", record.consistent)
                        put("initialClasses", record.initialClasses)
                        put("remainingClasses", record.remainingClasses)
                        put("queries", record.queries)
                        put("heldoutCorrect", record.heldoutCorrect)
                        put("heldoutTotal", record.heldoutTotal)
                        put("activation", record.activation)
                        put("failure", record.failure)
                        put("selectedNodes", record.selectedNodes)
                        put("selectedDeps", buildJsonArray { record.selectedDeps.forEach { add(it) } })
                        put("interpreterCalls", record.interpreterCalls)
                        put("wallMillis", record.wallMillis)
                    })
                }
            })
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
    }
}
