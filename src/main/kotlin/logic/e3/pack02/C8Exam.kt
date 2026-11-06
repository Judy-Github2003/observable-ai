// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-C8-03 考官侧世界、冻结数据、基础能力安装、验证/激活、成本统计与 JSON 证据。
// 边界：目标函数与 HELDOUT 只在考官侧；学生只拿到 StudyView。正式考试前本文件与学生算法一起冻结。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator

/** 一个研究世界：S 的考官真值独立于任何学生 AST；T 由考官用 S 真值定义。 */
class C8World(
    val id: String,
    val title: String,
    val sessionId: String,
    val sTruth: (Map<String, String>) -> Boolean,
    val sReference: String,
) {
    fun tTruth(input: Map<String, String>): Boolean = sTruth(input) && input["b"] == "1"
    fun tReference(): String = "($sReference) AND b==1"
}

object C8Worlds {
    private fun r(input: Map<String, String>): Boolean = input["a"] == "1" && input["c"] == "1"
    private fun p(input: Map<String, String>): Boolean = input["a"] == "1"
    private fun q(input: Map<String, String>): Boolean = input["c"] == "1"

    // 六个预注册世界：四个主要走 R，两个分别走 P/Q；T 都由考官独立定义为 S_truth AND b==1。
    val all: List<C8World> = listOf(
        C8World("w1-or-r-d1", "R OR d==1", "session-c8-01", { m -> r(m) || m["d"] == "1" }, "R OR d==1"),
        C8World("w2-or-r-d2", "R OR d==2", "session-c8-02", { m -> r(m) || m["d"] == "2" }, "R OR d==2"),
        C8World("w3-xnor-r-d1", "R XNOR d==1", "session-c8-03", { m -> r(m) == (m["d"] == "1") }, "R XNOR d==1"),
        C8World("w4-if-r-d1-d0", "if R then d==1 else d==0", "session-c8-04", { m -> if (r(m)) m["d"] == "1" else m["d"] == "0" }, "if R then d==1 else d==0"),
        C8World("w5-xnor-p-d2", "P XNOR d==2", "session-c8-05", { m -> p(m) == (m["d"] == "2") }, "P XNOR d==2"),
        C8World("w6-or-q-a2", "Q OR a==2", "session-c8-06", { m -> q(m) || m["a"] == "2" }, "Q OR a==2"),
    )
}

/** 学习前冻结的 TRAIN / QUERY / HELDOUT；三组不重叠，S 和 T 共用同一划分。 */
data class C8Split(
    val seed: Int,
    val train: List<Map<String, String>>,
    val query: List<Map<String, String>>,
    val heldout: List<Map<String, String>>,
    val digest: String,
)

object C8Data {
    const val TRAIN_SIZE = 18
    const val QUERY_SIZE = 36
    const val HELDOUT_SIZE = 27

    // S 只使用冻结训练集的前 10 条，保留主动查询空间；T 使用全部 18 条。
    // 两阶段共享 QUERY/HELDOUT 输入，但标签按阶段独立；HELDOUT 标签从不进入学生。
    const val S_TRAIN_SIZE = 9
    const val T_TRAIN_SIZE = 18

    fun freeze(seed: Int): C8Split {
        val shuffled = C8Domain.all().shuffled(kotlin.random.Random(seed.toLong()))
        val train = shuffled.take(TRAIN_SIZE)
        val query = shuffled.drop(TRAIN_SIZE).take(QUERY_SIZE)
        val heldout = shuffled.drop(TRAIN_SIZE + QUERY_SIZE).take(HELDOUT_SIZE)
        require(train.size == TRAIN_SIZE && query.size == QUERY_SIZE && heldout.size == HELDOUT_SIZE)
        val keys = (train + query + heldout).map { C8Domain.key(it) }
        require(keys.size == 81 && keys.toSet().size == 81) { "split is not a disjoint partition" }
        val canonical = buildString {
            for ((name, group) in listOf("train" to train, "query" to query, "heldout" to heldout)) {
                append(name).append(':')
                group.forEach { append(C8Domain.key(it)).append(';') }
            }
        }
        return C8Split(seed, train, query, heldout, sha256(canonical))
    }

    /** 预注册覆盖率检查；不按学生成绩筛选种子。 */
    fun coverage(split: C8Split): List<String> {
        val problems = ArrayList<String>()
        for (world in C8Worlds.all) {
            val sTrainInputs = split.train.take(S_TRAIN_SIZE)
            val sTrain = counts(sTrainInputs, world.sTruth)
            val sHeld = counts(split.heldout, world.sTruth)
            val tTrain = counts(split.train) { world.tTruth(it) }
            val tHeld = counts(split.heldout) { world.tTruth(it) }
            if (sTrain.first < 3 || sTrain.second < 3) problems += "${world.id}:S_train=$sTrain"
            if (sHeld.first < 6 || sHeld.second < 6) problems += "${world.id}:S_heldout=$sHeld"
            if (tTrain.first < 4 || tTrain.second < 6) problems += "${world.id}:T_train=$tTrain"
            if (tHeld.first < 4 || tHeld.second < 6) problems += "${world.id}:T_heldout=$tHeld"
        }
        return problems
    }

    private fun counts(inputs: List<Map<String, String>>, truth: (Map<String, String>) -> Boolean): Pair<Int, Int> {
        val positive = inputs.count(truth)
        return positive to inputs.size - positive
    }
}

/** 冻结预算：正式考试前写进 freeze 清单，考试期间不变。 */
data class C8Budget(
    val maxDepth: Int = 3,
    val maxNodes: Int = 40,
    val maxCandidates: Int = 50_000,
    val maxCallsPerCapability: Int = 24,
    val queryBudget: Int = 20,
)

/** 基础能力组：FULL=P/Q/R，NO-R=P/Q，BASE=仅原语；T 的 NO-S 复用 FULL 但不含 S。 */
data class C8Library(
    val group: String,
    val refs: List<KnowledgeRef>,
    val definitions: List<CapabilityDefinition>,
) {
    fun ref(id: String): KnowledgeRef? = refs.firstOrNull { it.id == id }
}

object C8Setup {
    fun build(path: Path, group: String): C8Library {
        if (group == "BASE") {
            // 即使没有先验能力，也创建空注册表，保证每个基础组都有可复制的数据库文件。
            SqliteCapabilityRegistry(path).close()
            return C8Library("BASE", emptyList(), emptyList())
        }
        val p = learnP(path)
        val q = learnQ(path)
        val refs = mutableListOf(p, q)
        if (group != "NO-R") refs += installR(path, p, q)
        val definitions = Pack02Knowledge.resolveActive(path, refs)
            ?: error("base library is not active for $group")
        return C8Library(group, refs, definitions)
    }

    private fun learnP(path: Path): KnowledgeRef {
        val trace = Pack02Knowledge.learn(
            "c8v3.p", listOf("a", "b"),
            listOf(ps("0", "0", false), ps("1", "0", true), ps("0", "1", false), ps("1", "1", true)),
            listOf(ps("0", "2", false), ps("1", "2", true), ps("2", "0", false), ps("2", "1", false)),
            path, maxDepth = 2,
        )
        require(trace.active) { "P prior not active: ${trace.failureCode}" }
        return KnowledgeRef(trace.definition!!.id, trace.definition.version, trace.contentHash!!)
    }

    private fun learnQ(path: Path): KnowledgeRef {
        val trace = Pack02Knowledge.learn(
            "c8v3.q", listOf("b", "c"),
            listOf(qs("0", "0", false), qs("0", "1", true), qs("1", "0", false), qs("1", "1", true)),
            listOf(qs("2", "0", false), qs("2", "1", true), qs("0", "2", false), qs("1", "2", false)),
            path, maxDepth = 2,
        )
        require(trace.active) { "Q prior not active: ${trace.failureCode}" }
        return KnowledgeRef(trace.definition!!.id, trace.definition.version, trace.contentHash!!)
    }

    /** R 由已冻结的 ACTIVE 会话产生并激活；不是考官直接写 AST。 */
    private fun installR(path: Path, p: KnowledgeRef, q: KnowledgeRef): KnowledgeRef {
        val world = ActiveWorlds.generate(102, "and_ac")
        val result = ActiveSession(world.studentView(p, q, 3), world.controller(3), path).run()
        require(result.status == "IDENTIFIED") { "R prior active session failed: ${result.status}" }
        val activation = world.evaluator().activate(result.selected!!, path)
        require(activation.active) { "R prior activation failed: ${activation.failure}" }
        return KnowledgeRef(activation.definition!!.id, activation.definition.version, activation.contentHash!!)
    }

    private fun ps(a: String, b: String, label: Boolean) =
        Pack02Sample("c8v3p-${a}${b}", mapOf("a" to a, "b" to b), label)

    private fun qs(b: String, c: String, label: Boolean) =
        Pack02Sample("c8v3q-${b}${c}", mapOf("b" to b, "c" to c), label)
}

data class C8Activation(
    val attempted: Boolean,
    val active: Boolean,
    val contentHash: String?,
    val verificationRunId: String?,
    val definition: CapabilityDefinition?,
    val failure: String?,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
)

data class C8Audit(val executed: Int, val correct: Int, val mismatches: Int, val blocked: Int)

data class C8StageRecord(
    val stage: String,
    val group: String,
    val strategy: String,
    val result: StudyStageResult,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val activation: C8Activation?,
    val audit: C8Audit?,
    val truth: String,
    val usesR: Boolean,
    val usesS: Boolean,
)

data class C8RunRecord(
    val world: String,
    val worldTitle: String,
    val group: String,
    val strategy: String,
    val splitSeed: Int,
    val s: C8StageRecord,
    val t: C8StageRecord?,
    val noS: C8StageRecord?,
    val notes: List<String>,
)

/** 考官端验证与激活；学生无法调用。 */
class C8Evaluator(
    private val library: C8Library,
    private val dbPath: Path,
) {
    fun correct(body: Expr, truth: (Map<String, String>) -> Boolean, cases: List<Map<String, String>>): Int =
        cases.count { input -> Pack02Knowledge.predict(body, input, library.definitions) == truth(input) }

    fun activate(
        body: Expr,
        stage: String,
        capabilityId: String,
        truth: (Map<String, String>) -> Boolean,
        split: C8Split,
        heldoutCorrect: Int,
        provenanceExtra: JsonObject,
    ): C8Activation {
        val definition = CapabilityDefinition(
            capabilityId, 1, C8Domain.attributes.associateWith { CapType.Str }, CapType.Bool3, body,
        )
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val provenance = buildJsonObject {
                put("source", "c8_03_experiment")
                put("attribution", "student_composed")
                put("stage", stage)
                put("split_digest", split.digest)
                put("heldout_correct", heldoutCorrect)
                put("heldout_total", split.heldout.size)
                provenanceExtra.forEach { (key, value) -> put(key, value) }
            }.toString()
            val proposed = registry.propose(definition, provenance)
            if (!proposed.ok) {
                return C8Activation(true, false, null, null, null, proposed.code ?: "proposal_failed", heldoutCorrect, split.heldout.size)
            }
            val cases = split.heldout.mapIndexed { index, input ->
                VerificationCase(
                    "$stage-held-$index",
                    input.mapValues { (_, value) -> CapValue.Str(value) },
                    EvalResult.Known(CapValue.Bool(truth(input))),
                )
            }
            val suite = TrustedVerificationSuite.create("${capabilityId}.heldout", 1, cases)
            val suites = TrustedSuiteRegistry()
            suites.register(suite)
            val verification = CapabilityVerificationService(registry, suites)
                .verify(capabilityId, 1, suite.suiteId, suite.suiteVersion)
            if (!verification.recorded || verification.outcome.verdict.name != "PASS") {
                return C8Activation(
                    true, false, proposed.contentHash, verification.runId, null,
                    verification.code ?: "verification_failed", heldoutCorrect, split.heldout.size,
                )
            }
            val activation = CapabilityActivationService(registry, suites)
                .activate(capabilityId, 1, verification.runId!!)
            return C8Activation(
                true, activation.ok, proposed.contentHash, verification.runId,
                if (activation.ok) definition else null,
                if (activation.ok) null else activation.code ?: "activation_failed",
                heldoutCorrect, split.heldout.size,
            )
        } finally {
            registry.close()
        }
    }

    fun audit(definition: CapabilityDefinition, truth: (Map<String, String>) -> Boolean): C8Audit {
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val service = CapabilityExecutionService(registry)
            var executed = 0
            var correct = 0
            var mismatches = 0
            var blocked = 0
            for (input in C8Domain.all()) {
                val result = service.executeActive(
                    definition.id, definition.version,
                    input.mapValues { (_, value) -> EvalResult.Known(CapValue.Str(value)) },
                )
                if (!result.ok) {
                    blocked++
                    continue
                }
                executed++
                val actual = ((result.result as? EvalResult.Known)?.value as? CapValue.Bool)?.value
                if (actual == truth(input)) correct++ else mismatches++
            }
            return C8Audit(executed, correct, mismatches, blocked)
        } finally {
            registry.close()
        }
    }
}

/** 每个基础组构建一次，再复制数据库文件给每个 run，避免激活记录互相污染。 */
class C8Bases {
    private val directory: Path = Files.createTempDirectory("c8v3-bases-")
    private val sources = LinkedHashMap<String, Path>()
    private val libraries = LinkedHashMap<String, C8Library>()

    init {
        for (group in listOf("FULL", "NO-R", "BASE")) {
            val path = directory.resolve("$group.sqlite")
            libraries[group] = C8Setup.build(path, group)
            sources[group] = path
        }
    }

    fun library(group: String): C8Library = libraries.getValue(group)

    fun newRun(group: String): Path {
        val target = Files.createTempFile("c8v3-run-", ".sqlite")
        Files.copy(sources.getValue(group), target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun close() {
        runCatching {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

object C8Experiment {
    val BUDGET = C8Budget()
    const val SPLIT_SEED = 6941

    fun runWorld(
        world: C8World,
        group: String,
        strategy: String,
        withNoS: Boolean,
        bases: C8Bases,
        withT: Boolean = true,
    ): C8RunRecord {
        val split = C8Data.freeze(SPLIT_SEED)
        val runPath = bases.newRun(group)
        val library = bases.library(group)
        try {
            val evaluator = C8Evaluator(library, runPath)
            val sResult = ActiveStudySession(
                StudyView(
                    world.sessionId, "S", training(split, "s", world.sTruth, C8Data.S_TRAIN_SIZE), split.query,
                    BUDGET.queryBudget, library.refs, C8Domain.attributes,
                    BUDGET.maxDepth, BUDGET.maxCandidates, BUDGET.maxCallsPerCapability, strategy, 0,
                ),
                StudyQueryController(split.query, BUDGET.queryBudget, world.sTruth),
                runPath,
            ).run()
            val sStage = evaluateStage(
                world, split, library, evaluator, "S", group, strategy, sResult,
                world.sTruth, "c8v3.S.${world.id}", null,
            )
            val sActivation = sStage.activation
            val sDefinition = sActivation?.definition
            val sRef = if (sActivation?.active == true && sDefinition != null) {
                KnowledgeRef(sDefinition.id, sDefinition.version, sActivation.contentHash!!)
            } else null
            val notes = ArrayList<String>()
            val tStage = if (withT && sRef != null && sDefinition != null && sStage.audit?.mismatches == 0) {
                val tLibrary = C8Library(
                    "FULL+S", library.refs + sRef, library.definitions + sDefinition,
                )
                val tEvaluator = C8Evaluator(tLibrary, runPath)
                val tResult = ActiveStudySession(
                    StudyView(
                        world.sessionId, "T", training(split, "t", world::tTruth, C8Data.T_TRAIN_SIZE), split.query,
                        BUDGET.queryBudget, tLibrary.refs, C8Domain.attributes,
                        BUDGET.maxDepth, BUDGET.maxCandidates, BUDGET.maxCallsPerCapability, strategy, 0,
                    ),
                    StudyQueryController(split.query, BUDGET.queryBudget, world::tTruth),
                    runPath,
                ).run()
                evaluateStage(
                    world, split, tLibrary, tEvaluator, "T", group, strategy, tResult,
                    world::tTruth, "c8v3.T.${world.id}", sRef.id,
                )
            } else {
                if (!withT) notes += "T not run: S-only control"
                else if (sRef == null) notes += "T blocked: S not activated"
                else if (sStage.audit?.mismatches != null && sStage.audit.mismatches > 0) notes += "T blocked: S full-domain audit mismatch"
                null
            }
            val noSStage = if (withNoS) {
                // T 的 NO-S 对照：同一训练/QUERY/HELDOUT 与同一预算，仅不提供 S 依赖。
                val noSResult = ActiveStudySession(
                    StudyView(
                        world.sessionId, "T", training(split, "t", world::tTruth, C8Data.T_TRAIN_SIZE), split.query,
                        BUDGET.queryBudget, library.refs, C8Domain.attributes,
                        BUDGET.maxDepth, BUDGET.maxCandidates, BUDGET.maxCallsPerCapability, strategy, 0,
                    ),
                    StudyQueryController(split.query, BUDGET.queryBudget, world::tTruth),
                    runPath,
                ).run()
                evaluateStage(
                    world, split, library, evaluator, "T-NO-S", "NO-S", strategy, noSResult,
                    world::tTruth, "c8v3.TnoS.${world.id}", null,
                )
            } else null
            return C8RunRecord(
                world.id, world.title, group, strategy, split.seed,
                sStage, tStage, noSStage, notes,
            )
        } finally {
            Files.deleteIfExists(runPath)
        }
    }

    private fun training(
        split: C8Split,
        prefix: String,
        truth: (Map<String, String>) -> Boolean,
        count: Int,
    ): List<StudyCase> =
        split.train.take(count).mapIndexed { index, input -> StudyCase("$prefix-tr-$index", input, truth(input)) }

    private fun evaluateStage(
        world: C8World,
        split: C8Split,
        library: C8Library,
        evaluator: C8Evaluator,
        stage: String,
        group: String,
        strategy: String,
        result: StudyStageResult,
        truth: (Map<String, String>) -> Boolean,
        capabilityId: String,
        sDependency: String?,
    ): C8StageRecord {
        val selected = result.selected
        val heldoutCorrect = selected?.let { evaluator.correct(it.body, truth, split.heldout) } ?: 0
        val activation = selected?.let { candidate ->
            evaluator.activate(
                candidate.body, stage, capabilityId, truth, split, heldoutCorrect,
                buildJsonObject {
                    put("strategy", strategy)
                    put("world", world.id)
                    if (sDependency != null) put("s_dependency", sDependency)
                },
            )
        }
        val audit = activation?.definition?.let { evaluator.audit(it, truth) }
        val rId = library.ref("pack02r.active.r")?.id
        val usesR = rId != null && selected?.dependencyIds?.contains(rId) == true
        val usesS = sDependency != null && selected?.dependencyIds?.contains(sDependency) == true
        return C8StageRecord(
            stage, group, strategy, result, heldoutCorrect, split.heldout.size,
            activation, audit, if (stage == "S") world.sReference else world.tReference(), usesR, usesS,
        )
    }

    /** 开发阶段：W1 上四策略 + 三基础组 + NO-S，W2/W3 只做 utility 泛化检查。 */
    fun runDevelopment(): List<C8RunRecord> {
        val bases = C8Bases()
        try {
            val w1 = C8Worlds.all[0]
            val w2 = C8Worlds.all[1]
            val w3 = C8Worlds.all[2]
            val runs = ArrayList<C8RunRecord>()
            runs += runWorld(w1, "FULL", "utility", true, bases)
            runs += runWorld(w1, "FULL", "divergence", false, bases)
            runs += runWorld(w1, "FULL", "fixed", false, bases)
            runs += runWorld(w1, "FULL", "random", false, bases)
            runs += runWorld(w1, "NO-R", "utility", false, bases)
            runs += runWorld(w1, "BASE", "utility", false, bases)
            runs += runWorld(w2, "FULL", "utility", false, bases)
            runs += runWorld(w3, "FULL", "utility", false, bases)
            return runs
        } finally {
            bases.close()
        }
    }

    /** 正式考试：六个预注册世界，FULL/utility 的 S→T 链；另跑 T 的 NO-S 与 S-only 的 NO-R/BASE 对照。 */
    fun runFormal(): List<C8RunRecord> {
        val bases = C8Bases()
        try {
            val runs = ArrayList<C8RunRecord>()
            for (world in C8Worlds.all) {
                runs += runWorld(world, "FULL", "utility", withNoS = true, bases)
            }
            for (world in C8Worlds.all) {
                runs += runWorld(world, "NO-R", "utility", withNoS = false, bases, withT = false)
                runs += runWorld(world, "BASE", "utility", withNoS = false, bases, withT = false)
            }
            return runs
        } finally {
            bases.close()
        }
    }
}

/** JSON 证据：保留查询轨迹、候选依赖、成本、激活与全输入审计。 */
object C8Json {
    fun write(path: Path, runs: List<C8RunRecord>, meta: JsonObject) {
        val root = buildJsonObject {
            meta.forEach { (key, value) -> put(key, value) }
            put("runs", buildJsonArray { runs.forEach { add(run(it)) } })
        }
        Files.createDirectories(path.parent)
        Files.writeString(path, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
    }

    fun run(record: C8RunRecord): JsonObject = buildJsonObject {
        put("world", record.world)
        put("worldTitle", record.worldTitle)
        put("group", record.group)
        put("strategy", record.strategy)
        put("splitSeed", record.splitSeed)
        put("s", stage(record.s))
        put("t", record.t?.let { stage(it) } ?: JsonNull)
        put("noS", record.noS?.let { stage(it) } ?: JsonNull)
        put("notes", buildJsonArray { record.notes.forEach { add(it) } })
    }

    private fun stage(record: C8StageRecord): JsonObject = buildJsonObject {
        val result = record.result
        put("stage", record.stage)
        put("group", record.group)
        put("strategy", record.strategy)
        put("worldId", result.worldId)
        put("status", result.status)
        put("truth", record.truth)
        put("generated", result.generated)
        put("searchGenerated", result.searchGenerated)
        put("composeReason", result.composeReason)
        put("consistent", result.consistent)
        put("initialCandidates", result.initialCandidates)
        put("initialClasses", result.initialClasses)
        put("remainingCandidates", result.remainingCandidates)
        put("remainingClasses", result.remainingClasses)
        put("budget", result.budget)
        put("failure", result.failure)
        put("selectionReason", result.selectionReason)
        put("usesR", record.usesR)
        put("usesS", record.usesS)
        put("heldoutCorrect", record.heldoutCorrect)
        put("heldoutTotal", record.heldoutTotal)
        put("rounds", buildJsonArray {
            result.rounds.forEach { round ->
                add(buildJsonObject {
                    put("sequence", round.sequence)
                    put("input", input(round.input))
                    put("label", round.label)
                    put("strategy", round.strategy)
                    put("reason", round.reason)
                    put("classesBefore", round.classesBefore)
                    put("classesAfter", round.classesAfter)
                    put("candidatesBefore", round.candidatesBefore)
                    put("candidatesAfter", round.candidatesAfter)
                    put("eliminated", round.eliminated)
                    put("predictedTrue", round.predictedTrue)
                    put("predictedFalse", round.predictedFalse)
                })
            }
        })
        put("selected", result.selected?.let { candidate(it, includeBody = true) } ?: JsonNull)
        put("consistentPreview", buildJsonArray {
            result.consistentPreview.forEach { add(candidate(it, includeBody = false)) }
        })
        put("stats", buildJsonObject {
            put("trainingEvaluations", result.stats.trainingEvaluations)
            put("signatureEvaluations", result.stats.signatureEvaluations)
            put("frozenPredictions", result.stats.frozenPredictions)
            put("interpreterCalls", result.stats.interpreterCalls)
            put("searchGenerated", result.stats.searchGenerated)
            put("composedCandidates", result.stats.composedCandidates)
            put("wallMillis", result.stats.wallMillis)
        })
        put("activation", record.activation?.let {
            buildJsonObject {
                put("attempted", it.attempted)
                put("active", it.active)
                put("contentHash", it.contentHash)
                put("verificationRunId", it.verificationRunId)
                put("failure", it.failure)
                put("heldoutCorrect", it.heldoutCorrect)
                put("heldoutTotal", it.heldoutTotal)
                put("capabilityId", it.definition?.id)
            }
        } ?: JsonNull)
        put("audit", record.audit?.let {
            buildJsonObject {
                put("executed", it.executed)
                put("correct", it.correct)
                put("mismatches", it.mismatches)
                put("blocked", it.blocked)
            }
        } ?: JsonNull)
    }

    private fun candidate(candidate: StudyCandidate, includeBody: Boolean): JsonObject = buildJsonObject {
        put("canonical", candidate.canonical)
        put("nodeCount", candidate.nodeCount)
        put("depth", candidate.depth)
        put("estimatedSteps", candidate.estimatedSteps)
        put("structuralCost", candidate.structuralCost)
        put("dependencyIds", buildJsonArray { candidate.dependencyIds.forEach { add(it) } })
        put("dependencies", buildJsonArray {
            candidate.dependencies.forEach { pin ->
                add(buildJsonObject {
                    put("id", pin.id)
                    put("version", pin.version)
                    put("contentHash", pin.contentHash)
                })
            }
        })
        if (includeBody) put("body", CanonicalJson.expr(candidate.body))
    }

    private fun input(values: Map<String, String>): JsonObject = buildJsonObject {
        values.toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    fun summary(runs: List<C8RunRecord>): JsonObject {
        val stages = runs.flatMap { listOfNotNull(it.s, it.t, it.noS) }
        val wrong = stages.count { it.activation?.active == true && (it.audit == null || it.audit.mismatches > 0) }
        return buildJsonObject {
            put("runs", runs.size)
            put("stages", stages.size)
            put("sIdentified", runs.count { it.s.result.status == "IDENTIFIED" })
            put("sActivated", runs.count { it.s.activation?.active == true })
            put("tAttempted", runs.count { it.t != null })
            put("tActivated", runs.count { it.t?.activation?.active == true })
            put("noSAttempted", runs.count { it.noS != null })
            put("noSActivated", runs.count { it.noS?.activation?.active == true })
            put("wrongActivations", wrong)
            put("totalQueries", stages.sumOf { it.result.rounds.size })
            put("totalGenerated", stages.sumOf { it.result.generated })
            put("totalConsistent", stages.sumOf { it.result.consistent })
            put("totalInterpreterCalls", stages.sumOf { it.result.stats.interpreterCalls })
            put("totalWallMillis", stages.sumOf { it.result.stats.wallMillis })
            put("activatedStages", stages.count { it.activation?.active == true })
        }
    }

    fun freezeManifest(
        studentPath: Path,
        examPath: Path,
        gitHead: String,
        split: C8Split,
    ): JsonObject = buildJsonObject {
        put("experiment", "PACK-02R-C8-03")
        put("frozen_at", Instant.now().toString())
        put("git_head", gitHead)
        put("student_source", studentPath.toString().replace('\\', '/'))
        put("student_sha256", sha256(Files.readString(studentPath)))
        put("exam_source", examPath.toString().replace('\\', '/'))
        put("exam_sha256", sha256(Files.readString(examPath)))
        put("split_seed", split.seed)
        put("split_digest", split.digest)
        put("split_sizes", buildJsonObject {
            put("train", split.train.size)
            put("sTrain", C8Data.S_TRAIN_SIZE)
            put("tTrain", C8Data.T_TRAIN_SIZE)
            put("query", split.query.size)
            put("heldout", split.heldout.size)
        })
        put("budget", buildJsonObject {
            put("maxDepth", C8Experiment.BUDGET.maxDepth)
            put("maxNodes", C8Experiment.BUDGET.maxNodes)
            put("maxCandidates", C8Experiment.BUDGET.maxCandidates)
            put("maxCallsPerCapability", C8Experiment.BUDGET.maxCallsPerCapability)
            put("queryBudget", C8Experiment.BUDGET.queryBudget)
        })
        put("worlds", buildJsonArray {
            C8Worlds.all.forEach { world ->
                add(buildJsonObject {
                    put("id", world.id)
                    put("title", world.title)
                    put("s", world.sReference)
                    put("t", world.tReference())
                })
            }
        })
    }
}
