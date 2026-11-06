// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 四条件正式对照；使用未参与开发的正式世界与预注册种子选择规则。
// 边界：失败世界保留；正式考试结果不用于回改学生。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

enum class MRXCondition(val wire: String) {
    BASELINE("BASELINE"),
    DEPTH("DEPTH"),
    ORACLE("ORACLE-OPERATOR"),
    AUTONOMOUS("AUTONOMOUS"),
}

data class MRXFormalRecord(
    val world: String,
    val category: String,
    val condition: String,
    val seed: Int,
    val baseStatus: String,
    val baseMismatch: Int,
    val chosenAction: String,
    val expected: List<String>,
    val studentStatus: String,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val activation: Boolean,
    val failure: String?,
    val queries: Int,
    val generated: Int,
    val selectedNodes: Int,
    val selectedDeps: List<String>,
    val calls: Int,
    val wallMillis: Long,
    val diagnosis: List<String>,
)

object MRXExam {
    fun runFormal(): List<MRXFormalRecord> = run(MRXWorlds.formal)

    /** 同一四条件 runner；开发期用 baseline 世界自检，正式期用 formal 世界。 */
    fun run(worlds: List<MRXWorld>): List<MRXFormalRecord> {
        val bases = C8Bases()
        try {
            val records = ArrayList<MRXFormalRecord>()
            worlds.forEachIndexed { index, world ->
                // 预注册规则：从固定起点找第一个满足覆盖率（及歧义例外位置）的种子，不看学生成绩。
                val (_, split) = MRXWorlds.findSeed(world, from = 7100 + index * 137)
                for (condition in MRXCondition.entries) {
                    records += runOne(world, split, condition, bases)
                }
            }
            return records
        } finally {
            bases.close()
        }
    }

    private fun runOne(world: MRXWorld, split: MRXSplit, condition: MRXCondition, bases: C8Bases): MRXFormalRecord {
        val full = bases.library("FULL")
        val baseRefs = if (world.libraryMode == "NO-R") bases.library("NO-R").refs else full.refs
        val path = bases.newRun("FULL")
        try {
            val training = ArrayList<StudyCase>()
            split.train.forEachIndexed { i, m -> training += StudyCase("f-$i", m, world.truth(m)) }
            if (world.noise) training += StudyCase("f-conflict", split.train.first(), !world.truth(split.train.first()))
            val view = StudyView(
                "mrx-formal-${world.id}", "S", training, split.query, 20, baseRefs,
                C8Domain.attributes, if (condition == MRXCondition.DEPTH) 4 else 3,
                if (condition == MRXCondition.DEPTH) 50_000 else world.maxCandidates,
                24, "utility", 0,
            )
            val c8Split = C8Split(split.seed, split.train, split.query, split.heldout, split.digest)
            val evaluator = C8Evaluator(full, path)
            val challenge = StudyQueryController(split.challenge, 24, world.truth)
            val studentResult: MRXStudentResult? = when (condition) {
                MRXCondition.BASELINE, MRXCondition.DEPTH -> null
                MRXCondition.ORACLE -> {
                    val expected = world.expectedUpgrades.firstOrNull() ?: "NONE"
                    if (expected == "NONE") {
                        null
                    } else {
                        MRXStudent(
                            view, MRXQuerySource(split.query, 20, world.truth),
                            split.challenge, challenge, full.refs, path, forcedAction = expected,
                        ).run()
                    }
                }
                MRXCondition.AUTONOMOUS -> MRXStudent(
                    view, MRXQuerySource(split.query, 20, world.truth),
                    split.challenge, challenge, full.refs, path,
                ).run()
            }
            val baseResult = if (studentResult == null) {
                ActiveStudySession(
                    view, StudyQueryController(split.query, 20, world.truth), path,
                ).run()
            } else null
            val selected = studentResult?.selected ?: baseResult?.selected
            val held = selected?.let { candidate ->
                split.heldout.count { input ->
                    Pack02Knowledge.predict(candidate.body, input, full.definitions) == world.truth(input)
                }
            } ?: 0
            val activation = selected?.let { candidate ->
                evaluator.activate(
                    candidate.body, "S", "mrx.formal.${world.id}.${condition.wire}", world.truth,
                    c8Split, held, buildJsonObject { put("condition", condition.wire) },
                )
            }
            return MRXFormalRecord(
                world = world.id,
                category = world.category.wire,
                condition = condition.wire,
                seed = split.seed,
                baseStatus = studentResult?.baseStatus ?: baseResult?.status ?: "NONE",
                baseMismatch = studentResult?.baseChallengeMismatch ?: 0,
                chosenAction = studentResult?.chosenAction ?: if (condition == MRXCondition.DEPTH) "DEPTH" else "NONE",
                expected = world.expectedUpgrades.toList(),
                studentStatus = studentResult?.status ?: (baseResult?.status ?: "NONE"),
                heldoutCorrect = held,
                heldoutTotal = split.heldout.size,
                activation = activation?.active == true,
                failure = activation?.failure ?: studentResult?.failure ?: baseResult?.failure,
                queries = studentResult?.totalQueries ?: (baseResult?.rounds?.size ?: 0),
                generated = studentResult?.totalGenerated ?: (baseResult?.generated ?: 0),
                selectedNodes = selected?.nodeCount ?: 0,
                selectedDeps = selected?.dependencyIds ?: emptyList(),
                calls = studentResult?.totalCalls ?: (baseResult?.stats?.interpreterCalls ?: 0),
                wallMillis = studentResult?.wallMillis ?: (baseResult?.stats?.wallMillis ?: 0),
                diagnosis = studentResult?.diagnosis?.map { it.id } ?: emptyList(),
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    fun write(records: List<MRXFormalRecord>, path: Path) {
        val summary = buildJsonObject {
            put("runs", records.size)
            for (condition in MRXCondition.entries) {
                val group = records.filter { it.condition == condition.wire }
                put(condition.wire, buildJsonObject {
                    put("runs", group.size)
                    put("activated", group.count { it.activation })
                    put("correctOperator", group.count { it.chosenAction in it.expected || (it.expected.isEmpty() && it.chosenAction in setOf("NONE", "NO_ACTION")) })
                    put("unnecessaryUpgrade", group.count { it.expected.isEmpty() && it.chosenAction !in setOf("NONE", "NO_ACTION", "DEPTH") })
                    put("queries", group.sumOf { it.queries })
                    put("generated", group.sumOf { it.generated })
                    put("calls", group.sumOf { it.calls })
                })
            }
            put("wrongActivations", records.count { it.activation && it.heldoutCorrect < it.heldoutTotal })
        }
        val root = buildJsonObject {
            put("experiment", "MRX-01")
            put("phase", "formal_four_conditions")
            put("summary", summary)
            put("runs", buildJsonArray {
                records.forEach { r ->
                    add(buildJsonObject {
                        put("world", r.world)
                        put("category", r.category)
                        put("condition", r.condition)
                        put("seed", r.seed)
                        put("baseStatus", r.baseStatus)
                        put("baseMismatch", r.baseMismatch)
                        put("chosenAction", r.chosenAction)
                        put("expected", buildJsonArray { r.expected.forEach { add(it) } })
                        put("studentStatus", r.studentStatus)
                        put("heldoutCorrect", r.heldoutCorrect)
                        put("heldoutTotal", r.heldoutTotal)
                        put("activation", r.activation)
                        put("failure", r.failure)
                        put("queries", r.queries)
                        put("generated", r.generated)
                        put("selectedNodes", r.selectedNodes)
                        put("selectedDeps", buildJsonArray { r.selectedDeps.forEach { add(it) } })
                        put("calls", r.calls)
                        put("wallMillis", r.wallMillis)
                        put("diagnosis", buildJsonArray { r.diagnosis.forEach { add(it) } })
                    })
                }
            })
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
    }
}
