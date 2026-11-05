// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M5 同预算对照实验：A 独立求解、B 复用已激活能力、C 在 B 上启用 L1/L2 抽取；D=L3 未实现。
// 边界：生成器只在测试端持有目标规则/世界类型，组合器只拿公开样本；结果如实报告，不预设 C 必须更好。
package logic.capability

import kotlinx.serialization.json.*
import java.util.Random

enum class BenchmarkFamily { REUSE, SIMILAR, NO_STRUCTURE, BOUNDARY }

enum class TaskOutcome { CORRECT, WRONG, UNKNOWN, ERROR, AMBIGUOUS, REFUSED }

data class BenchmarkConfig(
    val seed: Long = 20261105L,
    val tasksPerFamily: Int = 2,
    val generatorVersion: String = "b0-m5-v1",
    val composeBudget: ComposeBudget = ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 3000),
    val maxSteps: Int = 10_000,
)

data class BenchmarkTask(
    val id: String,
    val scenario: String,
    val family: BenchmarkFamily,
    val inputs: Map<String, CapType>,
    /** 只用于生成标签与判分，绝不传给组合器。 */
    val target: Expr,
    val publicExamples: List<PublicExample>,
    val developmentExamples: List<PublicExample>,
    val domain: List<Map<String, CapValue>>,
)

data class TaskResult(
    val taskId: String,
    val scenario: String,
    val family: String,
    val group: String,
    val outcome: TaskOutcome,
    val searchCandidates: Int,
    val elapsedMs: Long,
    val reusedLibrary: Boolean,
    val bodyCost: Long,
)

data class GroupResult(
    val group: String,
    val tasks: List<TaskResult>,
    val correct: Int,
    val wrong: Int,
    val unknown: Int,
    val error: Int,
    val ambiguous: Int,
    val refused: Int,
    val searchCandidates: Int,
    val elapsedMs: Long,
    val peakMemoryBytes: Long,
    val libraryCost: Long,
    val taskCost: Long,
    val reuseCount: Int,
) {
    val total: Int get() = tasks.size
    val staticCost: Long get() = libraryCost + taskCost
}

data class BenchmarkReport(
    val config: BenchmarkConfig,
    val groups: List<GroupResult>,
    val generatedTasks: Int,
    val trainTasks: Int,
    val developmentTasks: Int,
    val helperCapabilities: List<String>,
)

/** 固定场景：三个符号世界，各三字段；基础库提供“复合原语”，使 B 组有可复用能力而 A 组没有。 */
private data class Scenario(
    val id: String,
    val type: CapType.Record,
    val fields: List<String>,
    val values: Map<String, List<String>>,
    val base: List<CapabilityDefinition>,
    val domain: List<Map<String, CapValue>>,
)

private fun recordType(fields: List<String>): CapType.Record = CapType.Record(fields.associateWith { CapType.Str })

private fun record(fields: List<String>, values: Map<String, String>): CapValue.Rec =
    CapValue.Rec(fields.associateWith { CapValue.Str(values.getValue(it)) })

private fun cartesian(fields: List<String>, values: Map<String, List<String>>): List<Map<String, String>> {
    var result = listOf(emptyMap<String, String>())
    for (field in fields) {
        val next = ArrayList<Map<String, String>>()
        for (prefix in result) for (value in values.getValue(field)) next += prefix + (field to value)
        result = next
    }
    return result
}

private fun scenarioFor(id: String): Scenario {
    val fields: List<String>
    val values: Map<String, List<String>>
    val baseId: String
    val baseField1: Int
    val baseField2: Int
    when (id) {
        "item" -> {
            fields = listOf("color", "shape", "size")
            values = mapOf("color" to listOf("red", "blue"), "shape" to listOf("circle", "square"), "size" to listOf("small", "large"))
            baseId = "base.red_round"
            baseField1 = 0
            baseField2 = 1
        }
        "access" -> {
            fields = listOf("role", "level", "dept")
            values = mapOf("role" to listOf("admin", "guest"), "level" to listOf("3", "1"), "dept" to listOf("sales", "it"))
            baseId = "base.admin_level3"
            baseField1 = 0
            baseField2 = 1
        }
        else -> {
            fields = listOf("power", "port", "cable")
            values = mapOf("power" to listOf("usb", "dc"), "port" to listOf("c", "a"), "cable" to listOf("short", "long"))
            baseId = "base.usb_c"
            baseField1 = 0
            baseField2 = 1
        }
    }
    val type = recordType(fields)
    val arg = Expr.Arg("item", type)
    fun eq(field: String, value: String) =
        Expr.Eq(Expr.Field(arg, field), Expr.Const(CapValue.Str(value)))
    val baseBody = Expr.And(eq(fields[baseField1], values.getValue(fields[baseField1])[0]), eq(fields[baseField2], values.getValue(fields[baseField2])[0]))
    val base = listOf(CapabilityDefinition(baseId, 1, mapOf("item" to type), CapType.Bool3, baseBody))
    val domain = cartesian(fields, values).map { point -> mapOf("item" to record(fields, point)) }
    return Scenario(id, type, fields, values, base, domain)
}

private val SCENARIOS = listOf("item", "access", "device").map(::scenarioFor)

/** 目标只用于生成标签；组合器永远拿不到它。 */
private fun targetFor(scenario: Scenario, family: BenchmarkFamily, index: Int): Expr {
    val type = scenario.type
    val arg = Expr.Arg("item", type)
    fun eq(field: String, value: String) = Expr.Eq(Expr.Field(arg, field), Expr.Const(CapValue.Str(value)))
    val f0 = scenario.fields[0]
    val f1 = scenario.fields[1]
    val f2 = scenario.fields[2]
    val base = scenario.base.first().body
    return when (family) {
        // 复合原语内联 + 第三个字段：A 的搜索深度不够，B/C 可用基础库复合原语。
        BenchmarkFamily.REUSE -> Expr.And(base, eq(f2, scenario.values.getValue(f2)[index % 2]))
        // 相似但语义不同：与基础库共享形状，常量不同；不得误合并。
        BenchmarkFamily.SIMILAR -> Expr.And(
            eq(f0, scenario.values.getValue(f0)[1]),
            eq(f1, scenario.values.getValue(f1)[0]),
        )
        // 无公共结构负对照：两个独立字段条件。
        BenchmarkFamily.NO_STRUCTURE -> Expr.And(
            eq(f1, scenario.values.getValue(f1)[1]),
            eq(f2, scenario.values.getValue(f2)[index % 2]),
        )
        // 超出预算：三层嵌套 AND，A/B/C 都应拒绝或给出错误候选。
        BenchmarkFamily.BOUNDARY -> Expr.And(
            base,
            Expr.And(eq(f2, scenario.values.getValue(f2)[0]), eq(f0, scenario.values.getValue(f0)[1])),
        )
    }
}

private fun evaluate(expr: Expr, point: Map<String, CapValue>, inputs: Map<String, CapType>, resolver: CapabilityResolver): Boolean =
    CapabilityInterpreter(resolver).execute(
        CapabilityDefinition("_probe", 1, inputs, CapType.Bool3, expr),
        point.mapValues { (_, value) -> EvalResult.Known(value) },
    ).let { it is EvalResult.Known && (it.value as CapValue.Bool).value }

class BenchmarkRunner(private val config: BenchmarkConfig = BenchmarkConfig()) {

    fun run(): BenchmarkReport {
        require(config.tasksPerFamily >= 2) { "tasks_per_family must be >= 2 for a train/development split" }
        val tasks = generateTasks()
        val train = tasks.filter { it.id.endsWith(".0") }
        val development = tasks.filterNot { it.id.endsWith(".0") }

        val baseLibraries = SCENARIOS.associate { it.id to it.base }
        val helpers = learnHelpers(train, baseLibraries)
        val libraryA = emptyMap<String, List<CapabilityDefinition>>()
        val libraryB = baseLibraries.mapValues { it.value }
        val libraryC = baseLibraries.mapValues { (id, base) -> base + helpers.filter { it.id.startsWith("helper.$id.") } }

        val groups = listOf(
            runGroup("A", development, libraryA),
            runGroup("B", development, libraryB),
            runGroup("C", development, libraryC),
        )
        return BenchmarkReport(
            config = config,
            groups = groups,
            generatedTasks = tasks.size,
            trainTasks = train.size,
            developmentTasks = development.size,
            helperCapabilities = helpers.map { it.id },
        )
    }

    private fun generateTasks(): List<BenchmarkTask> {
        val result = ArrayList<BenchmarkTask>()
        var index = 0
        for (scenario in SCENARIOS) {
            for (family in BenchmarkFamily.entries) {
                for (i in 0 until config.tasksPerFamily) {
                    val target = targetFor(scenario, family, i)
                    val labeled = scenario.domain.map { point ->
                        point to evaluate(target, point, mapOf("item" to scenario.type), InMemoryResolver(scenario.base))
                    }
                    // 公开样本优先放正例，保证筛选至少有真值；其余留给开发集。
                    val positives = labeled.filter { it.second }.map { it.first }
                    val negatives = labeled.filterNot { it.second }.map { it.first }
                    val publicPoints = (positives.take(1) + negatives.take(5)).ifEmpty { negatives.take(6) }
                    val developmentPoints = scenario.domain.filterNot { it in publicPoints }
                    val taskId = "${scenario.id}.${family.name.lowercase()}.$i"
                    result += BenchmarkTask(
                        id = taskId,
                        scenario = scenario.id,
                        family = family,
                        inputs = mapOf("item" to scenario.type),
                        target = target,
                        publicExamples = publicPoints.map { point -> PublicExample(point, CapValue.Bool(evaluate(target, point, mapOf("item" to scenario.type), InMemoryResolver(scenario.base)))) },
                        developmentExamples = developmentPoints.map { point -> PublicExample(point, CapValue.Bool(evaluate(target, point, mapOf("item" to scenario.type), InMemoryResolver(scenario.base)))) },
                        domain = scenario.domain,
                    )
                    index++
                }
            }
        }
        return result.sortedBy { it.id }
    }

    /** C 组：只在训练集已解决程序上做 L2 抽取；没有净收益就保持与 B 相同。 */
    private fun learnHelpers(
        train: List<BenchmarkTask>,
        baseLibraries: Map<String, List<CapabilityDefinition>>,
    ): List<CapabilityDefinition> {
        val helpers = ArrayList<CapabilityDefinition>()
        for (scenario in SCENARIOS) {
            val scenarioTrain = train.filter { it.scenario == scenario.id }
            val solvedBodies = ArrayList<TaskProgram>()
            for (task in scenarioTrain) {
                val solved = solve(task, scenario.base) ?: continue
                solvedBodies += TaskProgram(task.id, solved)
            }
            if (solvedBodies.size < 2) continue
            val extraction = SubtreeExtractor(InMemoryResolver(scenario.base)).extract(solvedBodies)
            for (candidate in extraction.candidates) {
                val helper = candidate.helper.copy(id = "helper.${scenario.id}.${candidate.bodyHash.take(10)}")
                val rewriter = SubtreeRewriter(scenario.base)
                val outcome = rewriter.rewrite(
                    solvedBodies,
                    candidate.copy(helper = helper, replacement = Expr.Call(helper.id, 1, helper.contentHash(), candidate.replacement.args)),
                    scenario.domain,
                )
                if (outcome.accepted) {
                    helpers += helper
                    break
                }
            }
        }
        return helpers
    }

    private fun solve(task: BenchmarkTask, library: List<CapabilityDefinition>): CapabilityDefinition? {
        val composed = CapabilityComposer().compose(
            ComposeRequest(
                inputs = task.inputs,
                output = CapType.Bool3,
                constants = constantsFor(task.scenario),
                library = library,
                budget = config.composeBudget,
            ),
        )
        val evidence = CandidateEvidenceFilter(InMemoryResolver(library)).filter(
            inputs = task.inputs,
            candidates = composed.candidates,
            examples = task.publicExamples,
            queryValues = task.domain.firstOrNull()?.let { mapOf("item" to task.domain.map { p -> p.getValue("item") }) } ?: emptyMap(),
            searchExhausted = composed.reason == "candidate_limit",
        )
        if (evidence.status != EvidenceStatus.IDENTIFIED) return null
        val body = evidence.survivors.single().body
        return CapabilityDefinition("solved.${task.id}", 1, task.inputs, CapType.Bool3, body)
    }

    private fun constantsFor(scenario: String): List<CapValue> {
        val def = SCENARIOS.single { it.id == scenario }
        return def.values.values.flatten().map { CapValue.Str(it) }
    }

    private fun runGroup(group: String, tasks: List<BenchmarkTask>, libraries: Map<String, List<CapabilityDefinition>>): GroupResult {
        val results = ArrayList<TaskResult>()
        var peak = 0L
        for (task in tasks) {
            val library = libraries[task.scenario].orEmpty()
            val started = System.nanoTime()
            val composed = CapabilityComposer().compose(
                ComposeRequest(
                    inputs = task.inputs,
                    output = CapType.Bool3,
                    constants = constantsFor(task.scenario),
                    library = library,
                    budget = config.composeBudget,
                ),
            )
            val evidence = CandidateEvidenceFilter(InMemoryResolver(library)).filter(
                inputs = task.inputs,
                candidates = composed.candidates,
                examples = task.publicExamples,
                queryValues = mapOf("item" to task.domain.map { it.getValue("item") }),
                searchExhausted = composed.reason == "candidate_limit",
            )
            val elapsed = (System.nanoTime() - started) / 1_000_000
            var outcome = when (evidence.status) {
                EvidenceStatus.AMBIGUOUS -> TaskOutcome.AMBIGUOUS
                EvidenceStatus.REFUSED -> TaskOutcome.REFUSED
                EvidenceStatus.IDENTIFIED -> TaskOutcome.ERROR
            }
            var bodyCost = 0L
            var reused = false
            if (evidence.status == EvidenceStatus.IDENTIFIED) {
                val candidate = evidence.survivors.single()
                bodyCost = CapabilityCost.program(candidate.body)
                reused = collectDependencies(candidate.body).isNotEmpty()
                val definition = CapabilityDefinition("solved.${task.id}", 1, task.inputs, CapType.Bool3, candidate.body)
                val interpreter = CapabilityInterpreter(InMemoryResolver(library + definition))
                outcome = grade(definition, task.developmentExamples, interpreter)
            }
            val used = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            if (used > peak) peak = used
            results += TaskResult(
                taskId = task.id,
                scenario = task.scenario,
                family = task.family.name,
                group = group,
                outcome = outcome,
                searchCandidates = composed.generated,
                elapsedMs = elapsed,
                reusedLibrary = reused,
                bodyCost = bodyCost,
            )
        }
        val solved = results.filter { it.outcome == TaskOutcome.CORRECT }
        val libraryDefinitions = libraries.values.flatten().distinctBy { it.id to it.version }
        // 成本统计只按已解决任务的实际候选正文；这里用 TaskResult.bodyCost 汇总，库成本按 M4 公式。
        val uniqueBodies = libraryDefinitions.map { it.body }.distinctBy { CanonicalJson.astHash(it) }
        val libraryCost = uniqueBodies.sumOf { CapabilityCost.program(it) } +
            libraryDefinitions.sumOf { CapabilityCost.signature(it.inputs, it.output) } +
            CapabilityCost.LAMBDA * libraryDefinitions.size
        return GroupResult(
            group = group,
            tasks = results,
            correct = solved.size,
            wrong = results.count { it.outcome == TaskOutcome.WRONG },
            unknown = results.count { it.outcome == TaskOutcome.UNKNOWN },
            error = results.count { it.outcome == TaskOutcome.ERROR },
            ambiguous = results.count { it.outcome == TaskOutcome.AMBIGUOUS },
            refused = results.count { it.outcome == TaskOutcome.REFUSED },
            searchCandidates = results.sumOf { it.searchCandidates },
            elapsedMs = results.sumOf { it.elapsedMs },
            peakMemoryBytes = peak,
            libraryCost = libraryCost,
            taskCost = results.sumOf { it.bodyCost },
            reuseCount = results.count { it.reusedLibrary },
        )
    }

    private fun grade(definition: CapabilityDefinition, examples: List<PublicExample>, interpreter: CapabilityInterpreter): TaskOutcome {
        var wrong = false
        var unknown = false
        var error = false
        for (example in examples) {
            val arguments = example.inputs.mapValues { (_, value) -> EvalResult.Known(value) }
            when (val actual = interpreter.execute(definition, arguments)) {
                is EvalResult.Known -> if (actual.value != example.expected) wrong = true
                is EvalResult.Unknown -> unknown = true
                is EvalResult.Error -> error = true
            }
        }
        return when {
            error -> TaskOutcome.ERROR
            unknown -> TaskOutcome.UNKNOWN
            wrong -> TaskOutcome.WRONG
            else -> TaskOutcome.CORRECT
        }
    }
}

/** 机器可读输出；不含目标规则，便于外部审查者只依赖公开数据复跑。 */
fun BenchmarkReport.toJson(): String = buildJsonObject {
    put("generator_version", config.generatorVersion)
    put("seed", config.seed)
    put("tasks_per_family", config.tasksPerFamily)
    put("generated_tasks", generatedTasks)
    put("train_tasks", trainTasks)
    put("development_tasks", developmentTasks)
    put("helper_capabilities", buildJsonArray { helperCapabilities.forEach { add(it) } })
    put("groups", buildJsonArray {
        groups.forEach { group ->
            addJsonObject {
                put("group", group.group)
                put("total", group.total)
                put("correct", group.correct)
                put("wrong", group.wrong)
                put("unknown", group.unknown)
                put("error", group.error)
                put("ambiguous", group.ambiguous)
                put("refused", group.refused)
                put("search_candidates", group.searchCandidates)
                put("elapsed_ms", group.elapsedMs)
                put("peak_memory_bytes", group.peakMemoryBytes)
                put("library_cost", group.libraryCost)
                put("task_cost", group.taskCost)
                put("static_cost", group.staticCost)
                put("reuse_count", group.reuseCount)
                put("tasks", buildJsonArray {
                    group.tasks.forEach { task ->
                        addJsonObject {
                            put("task", task.taskId)
                            put("scenario", task.scenario)
                            put("family", task.family)
                            put("outcome", task.outcome.name)
                            put("search_candidates", task.searchCandidates)
                            put("elapsed_ms", task.elapsedMs)
                            put("reused_library", task.reusedLibrary)
                            put("body_cost", task.bodyCost)
                        }
                    }
                })
            }
        }
    })
}.toString()
