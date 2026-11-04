// 本文件原始实现：Codex，2026-11-04。
// 本次中文注释与可读步骤日志：DeepSeek Harness 补充，2026-11-04。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改业务、接口或日志行为。
// 局部注释审计：Codex，2026-11-04；区分懒创建适配器与每次独立启动教师进程。
// 本次第一课入口：Codex，2026-11-04；接入本地对象学习模块，隔离问答库和教师，并区分两类数据库配置提示。
// 本次第二课入口：DeepSeek Harness，2026-11-04；接入通用属性模块，使用 --lesson 2 与独立课程库，保持旧命令不变。
// 本批批量入口：DeepSeek Harness，2026-11-04；在原模式解析前把 --batch/--batch-out 分流给 BatchCli，
// 不创建教师或问答库；旧 V0/V1/课1/课2 参数和默认值保持不变。
package logic

import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

/** 命令行帮助文本；保持原有参数、默认值和权限说明。 */
private const val HELP = """逻辑 AI / V1 问答与学前第一、二课 V2
用法：logic-learner [选项] 问题

  --student v0|v1         默认 v1；v0 保留只转发的基线
  --teacher deepseek       默认：调用现有 DeepSeek Harness Agent
  --teacher mock           离线模拟教师，不调用外部服务
  --no-teacher             禁用教师；已存问题可复用，未命中返回 unknown
  --memory 文件            默认：runs/memory.db（mock 单独使用 mock-memory.db）
  --forget                 撤销这个问题的记忆，不调用教师
  --events 文件            默认：runs/events.jsonl
  --timeout-seconds 秒     默认：120
  --help                  显示帮助

第一课（全部本地执行，不调用在线教师）：
  --lesson 1 导入 <材料JSON路径>
  --lesson 1 教 <对象ID> <名称>
  --lesson 1 查 <对象ID>
  --lesson 1 找 <名称>
  --lesson 1 比较 <对象ID1> <对象ID2>
  --lesson-db 文件         可选；默认 runs/lessons/preschool-001/objects.db
  第一课日志默认 runs/lessons/preschool-001/events.jsonl 及相邻中文步骤日志。

第二课（全部本地执行，不调用在线教师）：
  --lesson 2 导入 <材料JSON路径>
  --lesson 2 查 <属性名> <对象ID>
  --lesson 2 找 <属性名> <属性值>
  --lesson 2 比较 <属性名> <对象ID1> <对象ID2>
  --lesson 2 教 <属性名> <对象ID> <属性值>
  --lesson-db 文件         可选；默认 runs/lessons/preschool-002/attributes.db
  第二课日志默认 runs/lessons/preschool-002/events.jsonl 及相邻中文步骤日志。

批量（受控世界、逐行 JSON、不调用教师）：
  --batch <requests.jsonl> --batch-out <目录>
  结果逐行输出到 stdout，同时写目录内 results.jsonl、events.jsonl 和 state/。

可选环境变量：DSH_INSTALL_DIR（默认 C:\dsh）
V1 只精确复用原始问题的教师回复，仍未核验；尚无语义理解或推理能力。
"""

/**
 * 程序入口：解析参数，按版本准备记忆与教师，交给 Student 处理，并输出答案。
 * 步骤日志属于训练设施，不代表学生学习能力，也不记录教师内部推理。
 */
fun main(args: Array<String>) {
    // 在 try 外保留引用，便于失败路径也尽力写入步骤日志。
    var runEvents: Events? = null
    try {
        // --help 只打印帮助并立即返回：不创建记忆、教师或日志文件。
        if (args.contentEquals(arrayOf("--help"))) { println(HELP); return }
        // 批量入口在解析旧参数之前分流；批内错误由 BatchCli 逐行输出，不构造任何教师。
        if (args.any { it == "--batch" || it == "--batch-out" }) {
            exitProcess(runBatch(args))
        }
        // 以下变量保存一次 CLI 调用的全部配置；先给默认值，再由参数逐个覆盖。
        var teacherName = "deepseek" // 默认调用真实 DSH 教师
        var studentVersion = "v1" // V1 有精确记忆；V0 保留只转发基线
        var teacherEnabled = true // --no-teacher 时置 false，后面用 null 教师表示
        var memoryPath: Path? = null // null 表示按教师类型选择默认数据库
        var forget = false // --forget 只走撤销分支，不调用教师
        var eventsPath = Path.of("runs/events.jsonl") // JSONL 事件默认位置
        var timeoutSeconds = 120L // 教师调用总超时，稍后限制在 1..600 秒
        // 第一课是新增的学生操作入口；未选择时，下面的旧 V0/V1 路径保持原样。
        var lessonId: String? = null
        var lessonDbPath: Path? = null // 单独存对象与名称，避免改写原问答记忆库
        var eventsProvided = false // 显式 --events 必须优先于课程默认日志位置
        var studentProvided = false // 课程使用固定能力版本，避免与 --student v0/v1 混用
        val words = mutableListOf<String>() // 收集非选项参数，最后按原顺序拼成问题
        var i = 0 // 手动索引：带值的选项需要先读下一个参数
        // 读取当前选项后面的值，并推进 i；外层循环末尾还会再执行一次 i++。
        fun value(): String {
            require(i + 1 < args.size) { "参数 ${args[i]} 缺少值。" }
            return args[++i]
        }
        while (i < args.size) {
            // 选项分支只更新配置；不以 -- 开头的普通词才进入问题文本。
            when (val argument = args[i]) {
                "--student" -> { studentVersion = value(); studentProvided = true }
                "--lesson" -> lessonId = value()
                "--lesson-db" -> lessonDbPath = Path.of(value())
                "--teacher" -> teacherName = value()
                "--no-teacher" -> teacherEnabled = false
                "--memory" -> memoryPath = Path.of(value())
                "--forget" -> forget = true
                "--events" -> { eventsPath = Path.of(value()); eventsProvided = true }
                "--timeout-seconds" -> timeoutSeconds = value().toLongOrNull()
                    ?.takeIf { it in 1..600 } ?: throw IllegalArgumentException("超时秒数须在 1 到 600 之间。")
                else -> {
                    // 未知 -- 选项直接报配置错误；普通词按出现顺序留给问题。
                    require(!argument.startsWith("--")) { "未知参数：$argument" }
                    words += argument
                }
            }
            i++
        }

        if (lessonId != null) {
            // 课程在创建 AnswerMemory 或任何 Teacher 之前分流；两课都无需外部模型。
            require(lessonId == "1" || lessonId == "2") { "目前只支持 --lesson 1 或 --lesson 2。" }
            require(!studentProvided) { "课程固定为 V2-L1/V2-L2，请不要同时指定 --student。" }
            require(memoryPath == null && !forget) { "课程使用 --lesson-db 和明确教学，不使用问答库的 --memory/--forget。" }
            when (lessonId) {
                "1" -> {
                    require(words.isNotEmpty()) { "请提供第一课操作：导入、教、查、找或比较。" }
                    val directory = Path.of("runs/lessons/preschool-001")
                    val events = Events(
                        if (eventsProvided) eventsPath else directory.resolve("events.jsonl"),
                        studentVersion = "V2-L1",
                    )
                    runEvents = events
                    events.record("lesson_mode", "harness", function = "Main.main",
                        description = "选择第一课：本地对象与名称操作，不创建外部教师", phase = "success") {
                        put("lesson_id", JsonPrimitive(PRESCHOOL_LESSON_ID))
                        put("teacher_invocation_count", JsonPrimitive(0))
                        put("model_call_count", JsonPrimitive(0))
                    }
                    // 只初始化专用对象库，通用读写与查询由第一课模块完成。
                    events.step("ObjectNames.init", "开始初始化第一课专用对象知识库", phase = "start")
                    val objects = ObjectNames(lessonDbPath ?: directory.resolve("objects.db"))
                    events.step("ObjectNames.init", "第一课对象知识库初始化完成", phase = "success")
                    val response = LessonOne(objects, events).execute(words)
                    // 结构化结果单独走 stdout，完整函数/步骤仍写入配套日志。
                    events.step("Main.main", "开始输出第一课操作结果", phase = "start")
                    print(response)
                    events.step("Main.main", "第一课操作结果已输出", phase = "success")
                }
                "2" -> {
                    require(words.isNotEmpty()) { "请提供第二课操作：导入、查、找、比较或教。" }
                    val directory = Path.of("runs/lessons/preschool-002")
                    val events = Events(
                        if (eventsProvided) eventsPath else directory.resolve("events.jsonl"),
                        studentVersion = "V2-L2",
                    )
                    runEvents = events
                    events.record("lesson_mode", "harness", function = "Main.main",
                        description = "选择第二课：本地对象与属性操作，不创建外部教师", phase = "success") {
                        // 课程标识和零教师调用计数与第一课保持同一种可追溯格式。
                        put("lesson_id", JsonPrimitive(PRESCHOOL_LESSON_TWO_ID))
                        put("teacher_invocation_count", JsonPrimitive(0))
                        put("model_call_count", JsonPrimitive(0))
                    }
                    // 第二课使用独立属性库，避免改写第一课或 V1 问答记忆。
                    events.step("AttributeMemory.init", "开始初始化第二课专用属性知识库", phase = "start")
                    val lessonMemory = AttributeMemory(lessonDbPath ?: directory.resolve("attributes.db"))
                    events.step("AttributeMemory.init", "第二课属性知识库初始化完成", phase = "success")
                    val response = LessonTwo(lessonMemory, events).execute(words)
                    events.step("Main.main", "开始输出第二课操作结果", phase = "start")
                    print(response)
                    events.step("Main.main", "第二课操作结果已输出", phase = "success")
                }
            }
            return
        }
        require(lessonDbPath == null) { "--lesson-db 需要配合 --lesson 1 或 --lesson 2 使用。" }
        // 多个普通参数用单个空格连接，保留用户给出的词序；空白问题直接拒绝。
        val question = words.joinToString(" ")
        require(question.isNotBlank()) { "请提供问题。运行 --help 查看用法。" }
        // 先校验枚举与问题，避免创建事件文件/数据库后才发现参数错误。
        require(studentVersion in setOf("v0", "v1")) { "student 只能是 v0 或 v1。" }
        require(teacherName in setOf("deepseek", "mock")) { "teacher 只能是 deepseek 或 mock。" }

        // 创建记录器后，后续步骤会同时写入 JSONL 和相邻中文步骤日志。
        val events = Events(eventsPath, studentVersion = studentVersion.uppercase())
        runEvents = events
        events.step("Main.main", "命令行参数解析与基本校验完成", phase = "success")

        // V1 才初始化 SQLite 记忆；V0 保持每次直接问教师的基线行为。
        // mock 默认使用独立数据库，避免离线演示污染真实教师记忆。
        val memory = if (studentVersion == "v1") {
            events.step("AnswerMemory.init", "开始初始化 V1 记忆数据库", phase = "start")
            AnswerMemory(memoryPath
                ?: Path.of(if (teacherName == "mock") "runs/mock-memory.db" else "runs/memory.db"))
        } else null
        if (memory != null) {
            // 构造成功只说明 schema 已就绪；真正的查询/保存错误由 AnswerMemory 抛出。
            events.step("AnswerMemory.init", "V1 记忆初始化完成", phase = "success")
        } else {
            events.step("Main.main", "V0 不启用本地记忆", phase = "info")
        }

        if (forget) {
            // 撤销只改 verification_status，不删除来源行，也不调用教师。
            require(memory != null) { "--forget 仅适用于 V1。" }
            events.step("AnswerMemory.revoke", "开始撤销该问题的记忆", phase = "start")
            val revoked = memory.revoke(question)
            events.record(
                "memory_revoked", "harness",
                function = "AnswerMemory.revoke",
                description = if (revoked) "记忆撤销完成" else "没有可撤销的记忆",
                phase = if (revoked) "success" else "info",
            ) {
                // question_key 仍是原始问题；revoked 表示本次是否真的改动了记录。
                put("question_key", kotlinx.serialization.json.JsonPrimitive(question))
                put("revoked", kotlinx.serialization.json.JsonPrimitive(revoked))
            }
            println(if (revoked) "已撤销该问题的记忆。" else "没有可撤销的记忆。")
            events.step("Main.main", "撤销结果已输出", phase = "success")
            return
        }

        // teacherEnabled=false 时 teacher 为 null；Student 只查记忆，miss 返回 unknown。
        // mock 用于离线；deepseek 通过懒加载避免在不需要时启动外部进程。
        val teacher = if (!teacherEnabled) null else when (teacherName) {
            "mock" -> MockTeacher()
            "deepseek" -> object : Teacher {
                override val runtime = "deepseek-harness"
                override val provider = "DeepSeek"
                override val model = "deepseek-flash"
                // 首次实际调用时才读取安装路径并创建适配器；每次 delegate.answer 独立启动教师进程。
                // onStep 把教师可见步骤写进同一 run_id 的日志。
                private val delegate by lazy {
                    CliTeacher.deepSeek(
                        Path.of(System.getenv("DSH_INSTALL_DIR")?.takeIf { it.isNotBlank() } ?: "C:\\dsh"),
                        Path.of("").toAbsolutePath(), Duration.ofSeconds(timeoutSeconds),
                        onStep = { phase, description ->
                            events.step("CliTeacher.answer", description, phase = phase, actor = "teacher")
                        },
                    )
                }
                override fun answer(question: String, observe: (ToolEvent) -> Unit) = delegate.answer(question, observe)
            }
            else -> throw IllegalArgumentException("teacher 只能是 deepseek 或 mock。")
        }

        // Student 负责记忆查询、教师调用和保存；Main 只处理来源提示与最终输出。
        val answer = Student(teacher, memory).answer(question, events)
        if (studentVersion == "v1") {
            // 来源和未核验提示走 stderr；stdout 仍只输出答案或 unknown JSON。
            val source = when (answer.source) {
                "local_memory" -> "本地记忆（原始运行 ${answer.sourceRunId}）"
                "external_teacher" -> "DeepSeek 教师"
                "mock_teacher" -> "mock 教师"
                else -> "未知：教师已关闭，且没有可用记忆"
            }
            System.err.println("来源：$source${if (answer.source != "unknown") "；教师原始回复，未核验" else ""}")
        }
        // 输出前记录开始，print 之后才记录成功；print 不加换行以保留原始答案边界。
        events.step("Main.main", "开始输出答案到标准输出", phase = "start")
        print(answer.text)
        events.step("Main.main", "答案已输出到标准输出", phase = "success")
    } catch (error: TeacherException) {
        // 教师异常已有稳定错误码；先尽力记录失败步骤，再按原退出码 1 退出。
        logFailure(runEvents, "Main.main", "教师调用失败（${error.code}），程序退出")
        System.err.println("${error.code}: ${redact(error.message.orEmpty())}")
        exitProcess(1)
    } catch (error: IllegalArgumentException) {
        // 参数/配置错误使用退出码 2，与运行期错误区分。
        logFailure(runEvents, "Main.main", "配置错误，程序退出")
        System.err.println("配置错误：${redact(error.message.orEmpty())}")
        exitProcess(2)
    } catch (_: java.sql.SQLException) {
        // 数据库错误不能静默跳过记忆；明确提示后按运行期错误退出。
        logFailure(runEvents, "Main.main", "记忆数据库错误，程序退出")
        System.err.println("记忆数据库错误：请检查数据库文件与路径（问答使用 --memory，课程使用 --lesson-db），未静默跳过记忆。")
        exitProcess(1)
    } catch (_: Exception) {
        // 兜底覆盖事件路径、文件权限等运行环境问题；不向终端泄露内部异常细节。
        logFailure(runEvents, "Main.main", "运行失败，程序退出")
        System.err.println("运行失败：请检查事件文件路径和本地运行环境。")
        exitProcess(1)
    }
}

/** 失败时尽力追写一行步骤；日志自身失败不能覆盖原始错误和退出码。 */
private fun logFailure(events: Events?, function: String, description: String) {
    events ?: return
    try {
        events.step(function, description, phase = "failure")
    } catch (_: Exception) {
        // 辅助日志失败时保留原始错误和退出码。
    }
}

/**
 * 解析唯一的批量参数并交给 BatchCli。
 *
 * 只接受 --batch 与 --batch-out，且各出现一次；不再混用旧的 --lesson/--teacher 等参数，
 * 避免一次调用同时走两条不同的执行路径。parse 失败会抛出 IllegalArgumentException，
 * 由 main 按配置错误退出；批量请求本身的 error 不会改变进程退出码。
 */
private fun runBatch(args: Array<String>): Int {
    var requests: Path? = null
    var output: Path? = null
    var i = 0
    while (i < args.size) {
        when (val argument = args[i]) {
            "--batch" -> {
                require(requests == null) { "--batch 重复。" }
                require(i + 1 < args.size) { "--batch 缺少请求文件路径。" }
                requests = Path.of(args[++i])
            }
            "--batch-out" -> {
                require(output == null) { "--batch-out 重复。" }
                require(i + 1 < args.size) { "--batch-out 缺少输出目录。" }
                output = Path.of(args[++i])
            }
            else -> throw IllegalArgumentException("批量模式不接受参数：$argument")
        }
        i++
    }
    val requestsPath = requireNotNull(requests) { "批量模式需要 --batch <requests.jsonl>。" }
    val outputPath = requireNotNull(output) { "批量模式需要 --batch-out <directory>。" }
    return BatchCli.run(requestsPath, outputPath)
}
