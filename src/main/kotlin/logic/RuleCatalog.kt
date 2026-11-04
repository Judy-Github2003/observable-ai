// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：学前24课批量契约要求规则目录带版本、课程来源、作者、获取方式和代码锚点，
// 运行时事件必须引用同一个 rule_id/version；本文件只读取和校验规则目录，不生成学生答案。
package logic

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 规则目录格式版本；与 rules.json 的 catalog_version 相互独立，便于之后兼容迁移。 */
const val BATCH_RULE_CATALOG_VERSION = "batch-rules-v1"

/**
 * 所有批量操作使用的稳定规则 ID。
 *
 * 代码分支、rules.json 和运行事件共用这些常量，避免某一处改名后报告与执行对不上。
 * 这不是学生可读知识，也不按题目或实体名切换答案。
 */
object RuleIds {
    const val WORLD_LOAD = "batch.world_load"
    const val OBJECT_IDENTITY = "batch.object_identity"
    const val ATTRIBUTE = "batch.attribute"
    const val QUANTITY_COMPARE = "batch.quantity_compare"
    const val CATEGORY_MEMBERS = "batch.category_members"
    const val FILTER_ALL = "batch.filter_all"
    const val RELATION = "batch.relation"
    const val ACTION_SEQUENCE = "batch.action_sequence"
    const val COUNT_PAIR = "batch.count_pair"
    const val COUNT_COMPARE = "batch.count_compare"
    const val ARITHMETIC = "batch.arithmetic"
    const val PATTERN_NEXT = "batch.pattern_next"
    const val TIME_ORDER = "batch.time_order"
    const val OBSERVATION_COMPARE = "batch.observation_compare"
    const val SENTENCE = "batch.sentence"
    const val STORY = "batch.story"
    const val REFERENCE = "batch.reference"
    const val POLICY = "batch.policy"
    const val TURN = "batch.turn"
    const val COMPOSE = "batch.compose"
    const val CLAIM = "batch.claim"
    const val RETRACT = "batch.retract"
    const val MISSING_ASK = "batch.missing_ask"

    /** 测试和批启动自检用：所有必须出现在目录中的规则。 */
    val ALL = listOf(
        WORLD_LOAD, OBJECT_IDENTITY, ATTRIBUTE, QUANTITY_COMPARE, CATEGORY_MEMBERS,
        FILTER_ALL, RELATION, ACTION_SEQUENCE, COUNT_PAIR, COUNT_COMPARE, ARITHMETIC,
        PATTERN_NEXT, TIME_ORDER, OBSERVATION_COMPARE, SENTENCE, STORY, REFERENCE,
        POLICY, TURN, COMPOSE, CLAIM, RETRACT, MISSING_ASK,
    )
}

/**
 * 一条规则的可追溯元数据。
 *
 * extra 保留 rules.json 的扩展字段（例如 reused_in、operations），报告可原样展示，
 * 但核心审计只依赖下面这些稳定字段。
 */
data class RuleInfo(
    val ruleId: String,
    val version: String,
    val title: String,
    val introducedLesson: String,
    val acquiredAt: String,
    val reason: String,
    val author: String,
    val acquisitionMethod: String,
    val sourceFile: String,
    val function: String,
    val sourceAnchor: String,
    val extra: JsonObject = JsonObject(emptyMap()),
)

/**
 * 带版本的规则目录。
 *
 * 启动时一次性读入并校验；运行中不再访问文件，避免同一批次中途换规则。
 * sha256 由原始字节计算，供批量元数据和报告核对规则目录版本。
 */
class RuleCatalog private constructor(
    private val rules: Map<String, RuleInfo>,
    val catalogPath: Path?,
    val sha256: String,
    val catalogVersion: String,
    val raw: JsonObject?,
) {
    /** 取一条规则；缺失时直接失败，不能带着空规则继续写“成功”事件。 */
    fun require(ruleId: String): RuleInfo =
        rules[ruleId] ?: throw IllegalStateException("规则目录缺少规则：$ruleId")

    /** 软查询；供报告或维护工具使用。 */
    fun find(ruleId: String): RuleInfo? = rules[ruleId]

    /** 只读视图，保持插入顺序。 */
    fun all(): List<RuleInfo> = rules.values.toList()

    /** 批启动自检：缺任何代码使用的规则都在处理请求前报错。 */
    fun verifyRequired(ruleIds: Collection<String> = RuleIds.ALL) {
        val missing = ruleIds.filterNot { rules.containsKey(it) }
        require(missing.isEmpty()) {
            "规则目录缺少代码需要的规则：${missing.joinToString("、")}"
        }
    }

    companion object {
        /** rules.json 每项至少包含的字段；缺一即拒绝加载，避免报告展示不完整归因。 */
        private val REQUIRED_FIELDS = listOf(
            "rule_id", "version", "title", "introduced_lesson", "acquired_at", "reason",
            "author", "acquisition_method", "source_file", "function", "source_anchor",
        )

        /**
         * 从显式路径加载规则目录。
         *
         * 允许根节点是数组或 {"rules":[...]}，两种格式组B都必须兼容；其他根节点视为配置错误。
         */
        fun load(path: Path): RuleCatalog {
            val bytes = Files.readAllBytes(path)
            val text = bytes.toString(Charsets.UTF_8)
            val rootElement = try {
                Json.parseToJsonElement(text)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("规则目录不是合法 JSON：$path", error)
            }
            val root = rootElement as? JsonObject
            val ruleArray = when (rootElement) {
                is JsonArray -> rootElement
                is JsonObject -> rootElement["rules"] as? JsonArray
                    ?: throw IllegalArgumentException("规则目录对象必须包含 rules 数组：$path")
                else -> throw IllegalArgumentException("规则目录根节点必须是数组或对象：$path")
            }
            val parsed = LinkedHashMap<String, RuleInfo>()
            ruleArray.forEachIndexed { index, element ->
                val item = element as? JsonObject
                    ?: throw IllegalArgumentException("规则目录 rules[$index] 必须是对象")
                REQUIRED_FIELDS.forEach { field ->
                    val value = item[field] as? JsonPrimitive
                    require(value != null && value.isString && value.content.isNotBlank()) {
                        "规则目录 rules[$index].$field 必须是非空字符串"
                    }
                }
                val ruleId = item.string("rule_id")
                val sourceAnchor = item.string("source_anchor")
                require(sourceAnchor == "RULE:$ruleId") {
                    "规则 $ruleId 的 source_anchor 应为 RULE:$ruleId，实际为 $sourceAnchor"
                }
                require(!parsed.containsKey(ruleId)) { "规则目录包含重复 rule_id：$ruleId" }
                parsed[ruleId] = RuleInfo(
                    ruleId = ruleId,
                    version = item.string("version"),
                    title = item.string("title"),
                    introducedLesson = item.string("introduced_lesson"),
                    acquiredAt = item.string("acquired_at"),
                    reason = item.string("reason"),
                    author = item.string("author"),
                    acquisitionMethod = item.string("acquisition_method"),
                    sourceFile = item.string("source_file"),
                    function = item.string("function"),
                    sourceAnchor = sourceAnchor,
                    extra = JsonObject(item.filterKeys { it !in REQUIRED_FIELDS }),
                )
            }
            return RuleCatalog(
                rules = parsed,
                catalogPath = path,
                sha256 = sha256(bytes),
                catalogVersion = (root?.get("catalog_version") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: BATCH_RULE_CATALOG_VERSION,
                raw = root,
            )
        }

        /**
         * 默认查找规则目录。
         *
         * 优先环境变量和系统属性（宿主冻结版本时显式传入），否则从工作目录及上层目录寻找
         * 项目内固定位置；这样从运行输出目录启动也能找到同一份目录。
         */
        fun findDefaultPath(): Path? {
            val explicit = System.getenv("LOGIC_RULE_CATALOG")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("logic.rule.catalog")?.takeIf { it.isNotBlank() }
            if (explicit != null) {
                val path = Path.of(explicit)
                if (Files.isRegularFile(path)) return path
            }
            val relative = Path.of("experiments", "curriculum", "batch-v1", "rules.json")
            var cursor: Path? = Path.of("").toAbsolutePath().normalize()
            repeat(8) {
                val candidate = cursor?.resolve(relative)
                if (candidate != null && Files.isRegularFile(candidate)) return candidate
                cursor = cursor?.parent
            }
            return null
        }

        /** 找不到目录时给出明确错误；不凭空编造规则元数据。 */
        fun loadDefault(): RuleCatalog {
            val path = findDefaultPath()
                ?: throw IllegalStateException(
                    "找不到 rules.json；请设置 LOGIC_RULE_CATALOG 或从项目根目录运行批量入口。",
                )
            return load(path)
        }

        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun JsonObject.string(key: String): String =
            (getValue(key) as JsonPrimitive).content
    }
}
