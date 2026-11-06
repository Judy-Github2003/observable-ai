// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 六条件扩大评测：C8-03、MRX-01、FIXED、EVIDENCE、VALUE、ORACLE。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

enum class MRXRCondition(val wire: String) {
    C8("C8-03"),
    MRX01("MRX-01"),
    FIXED("FIXED"),
    EVIDENCE("EVIDENCE"),
    VALUE("VALUE"),
    ORACLE("ORACLE-OPERATOR"),
}

data class MRXRRecord(
    val world: String,
    val category: String,
    val condition: String,
    val seed: Int,
    val baseStatus: String,
    val baseValidation: String,
    val chosenAction: String,
    val expected: List<String>,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val activation: Boolean,
    val failure: String?,
    val queries: Int,
    val generated: Int,
    val evidenceEntries: Int,
    val evidenceReused: Int,
    val selectedNodes: Int,
    val selectedDeps: List<String>,
    val diagnosis: List<String>,
    val identifiability: String,
    val calls: Int,
    val wallMillis: Long,
)

object MRXRExam {
    fun runFormal(): List<MRXRRecord> = run(MRXRWorlds.formal)

    fun run(worlds: List<MRXWorld>, seeds: Map<String, Int> = emptyMap()): List<MRXRRecord> {
        val bases = C8Bases()
        try {
            val out = ArrayList<MRXRRecord>()
            worlds.forEachIndexed { index, world ->
                val (seed, split) = seeds[world.id]?.let { explicit ->
                    explicit to MRXRWorlds.splitFor(world, explicit)
                } ?: MRXRWorlds.findSeed(world, from = 8100 + index * 151)
                for (condition in MRXRCondition.entries) out += runOne(world, split, condition, bases)
            }
            return out
        } finally {
            bases.close()
        }
    }

    private fun runOne(world: MRXWorld, split: MRXSplit, condition: MRXRCondition, bases: C8Bases): MRXRRecord {
        val full = bases.library("FULL")
        val baseRefs = if (world.libraryMode == "NO-R") bases.library("NO-R").refs else full.refs
        val path = bases.newRun("FULL")
        try {
            val training = ArrayList<StudyCase>()
            split.train.forEachIndexed { i, m -> training += StudyCase("r-$i", m, world.truth(m)) }
            if (world.noise) training += StudyCase("r-conflict", split.train.first(), !world.truth(split.train.first()))
            val view = StudyView(
                "mrxr-formal-${world.id}", "S", training, split.query, 20, baseRefs,
                C8Domain.attributes, 3, world.maxCandidates, 24, "utility", 0,
            )
            val c8Split = C8Split(split.seed, split.train, split.query, split.heldout, split.digest)
            val evaluator = C8Evaluator(full, path)
            val challenge = StudyQueryController(split.challenge, 24, world.truth)
            val base = ActiveStudySession(view, StudyQueryController(split.query, 20, world.truth), path).run()
            var selected: StudyCandidate? = base.selected
            var held = 0
            var activation: C8Activation? = null
            var queries = base.rounds.size
            var generated = base.generated
            var entries = 0
            var reused = 0
            var diagnosis = emptyList<String>()
            var ident = ""
            var chosen = "NONE"
            var failure: String? = base.failure
            var calls = base.stats.interpreterCalls

            when (condition) {
                MRXRCondition.C8 -> Unit
                MRXRCondition.MRX01 -> {
                    val result = MRXStudent(
                        view, MRXQuerySource(split.query, 20, world.truth),
                        split.challenge, challenge, full.refs, path,
                    ).run()
                    selected = result.selected
                    chosen = result.chosenAction
                    diagnosis = result.diagnosis.map { it.id }
                    ident = "n/a"
                    queries = result.totalQueries
                    generated = result.totalGenerated
                    calls = result.totalCalls
                    failure = result.failure
                }
                MRXRCondition.FIXED, MRXRCondition.EVIDENCE, MRXRCondition.VALUE -> {
                    val ledger = MRXRChallengeLedger(split.challenge, challenge, 24)
                    val strategy = when (condition) {
                        MRXRCondition.FIXED -> MRXRStrategy.FIXED
                        MRXRCondition.EVIDENCE -> MRXRStrategy.EVIDENCE
                        else -> MRXRStrategy.VALUE
                    }
                    val result = MRXRStudent(
                        view, MRXQuerySource(split.query, 20, world.truth), ledger, full.refs, path, strategy,
                    ).run()
                    selected = result.selected
                    chosen = result.chosenAction
                    diagnosis = result.diagnosis.map { it.id }
                    ident = "${result.identifiability.top}/${result.identifiability.indistinguishable}/${result.identifiability.distinguishingActions}"
                    queries = result.queries
                    generated = result.generated
                    entries = result.evidenceEntries
                    reused = result.evidenceReused
                    calls = result.calls
                    failure = result.trials.lastOrNull()?.reason ?: result.baseValidation
                }
                MRXRCondition.ORACLE -> {
                    val expected = world.expectedUpgrades.firstOrNull() ?: "NONE"
                    if (expected != "NONE") {
                        val ledger = MRXRChallengeLedger(split.challenge, challenge, 24)
                        val result = MRXRStudent(
                            view, MRXQuerySource(split.query, 20, world.truth), ledger, full.refs, path,
                            MRXRStrategy.EVIDENCE, forcedAction = expected,
                        ).run()
                        selected = result.selected
                        chosen = result.chosenAction
                        diagnosis = result.diagnosis.map { it.id }
                        ident = result.identifiability.top.toString()
                        queries = result.queries
                        generated = result.generated
                        entries = result.evidenceEntries
                        reused = result.evidenceReused
                        calls = result.calls
                        failure = result.trials.lastOrNull()?.reason ?: result.baseValidation
                    }
                }
            }
            held = selected?.let { candidate ->
                split.heldout.count { input -> Pack02Knowledge.predict(candidate.body, input, full.definitions) == world.truth(input) }
            } ?: 0
            activation = selected?.let { candidate ->
                evaluator.activate(
                    candidate.body, "S", "mrxr.${world.id}.${condition.wire}", world.truth,
                    c8Split, held, buildJsonObject { put("condition", condition.wire) },
                )
            }
            return MRXRRecord(
                world.id, world.category.wire, condition.wire, split.seed,
                base.status, "n/a", chosen, world.expectedUpgrades.toList(),
                held, split.heldout.size, activation?.active == true, activation?.failure ?: failure,
                queries, generated, entries, reused, selected?.nodeCount ?: 0, selected?.dependencyIds ?: emptyList(),
                diagnosis, ident, calls, base.stats.wallMillis,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    fun write(records: List<MRXRRecord>, path: Path) {
        val summary = buildJsonObject {
            put("runs", records.size)
            for (condition in MRXRCondition.entries) {
                val group = records.filter { it.condition == condition.wire }
                put(condition.wire, buildJsonObject {
                    put("runs", group.size)
                    put("activated", group.count { it.activation })
                    put("heldoutFull", group.count { it.heldoutCorrect == it.heldoutTotal })
                    put("queries", group.sumOf { it.queries })
                    put("generated", group.sumOf { it.generated })
                    put("evidenceEntries", group.sumOf { it.evidenceEntries })
                    put("evidenceReused", group.sumOf { it.evidenceReused })
                    put("calls", group.sumOf { it.calls })
                })
            }
            put("wrongActivations", records.count { it.activation && it.heldoutCorrect < it.heldoutTotal })
        }
        val root = buildJsonObject {
            put("experiment", "MRX-01R")
            put("phase", "six_condition")
            put("summary", summary)
            put("runs", buildJsonArray {
                records.forEach { r ->
                    add(buildJsonObject {
                        put("world", r.world)
                        put("category", r.category)
                        put("condition", r.condition)
                        put("seed", r.seed)
                        put("baseStatus", r.baseStatus)
                        put("baseValidation", r.baseValidation)
                        put("chosenAction", r.chosenAction)
                        put("expected", buildJsonArray { r.expected.forEach { add(it) } })
                        put("heldoutCorrect", r.heldoutCorrect)
                        put("heldoutTotal", r.heldoutTotal)
                        put("activation", r.activation)
                        put("failure", r.failure)
                        put("queries", r.queries)
                        put("generated", r.generated)
                        put("evidenceEntries", r.evidenceEntries)
                        put("evidenceReused", r.evidenceReused)
                        put("selectedNodes", r.selectedNodes)
                        put("selectedDeps", buildJsonArray { r.selectedDeps.forEach { add(it) } })
                        put("diagnosis", buildJsonArray { r.diagnosis.forEach { add(it) } })
                        put("identifiability", r.identifiability)
                        put("calls", r.calls)
                        put("wallMillis", r.wallMillis)
                    })
                }
            })
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
    }
}
