// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A1 公共数据契约；只定义学生可见的观察/来源/特征键，不包含世界类别或隐藏真值。
package logic.e3

/** 可见字段与公开取值域；字段名和取值都不含世界类别、隐藏特征或目标 AST。 */
data class VisibleSchema(val fields: Map<String, List<String>>) {
    fun fieldNames(): List<String> = fields.keys.sorted()
}

/**
 * 可见值三态：
 * - Known：看到具体值；
 * - Unknown：字段存在但值未知（与“字段缺失”严格不同）；
 * - 字段缺失 = Map 中没有该 key，由校验器报 missing_field。
 */
sealed interface ObservedValue {
    data class Known(val value: String) : ObservedValue
    data object Unknown : ObservedValue
}

enum class SourceKind { EXTERNAL_PROVIDED, STUDENT_REQUESTED, REPEAT_MEASUREMENT }

/** 观察来源；可靠性声明由公开输入提供，不能由生成器世界类型推断。 */
data class EvidenceProvenance(
    val sourceId: String,
    val sourceKind: SourceKind,
    val requestedByStudent: Boolean,
    /** true/false 表示公开声明；null 表示未声明，不得在诊断中当成 true。 */
    val reliableDeterministicLabels: Boolean? = null,
)

data class Observation(
    val observationId: String,
    val objectId: String,
    val visible: Map<String, ObservedValue>,
    /** null 表示标签未被观察到；不能当成 false。 */
    val label: Boolean?,
    val provenance: EvidenceProvenance,
    val round: Int = 1,
    val batchId: String? = null,
)

/**
 * 稳定的可见向量键。字段按名称排序；未知值编码为 `?`，与任何具体字符串不同；
 * 转义 `\`、`|`、`=`，避免简单拼接产生歧义。
 */
data class FeatureKey(val fields: List<Pair<String, String?>>) {
    val canonical: String
        get() = fields.joinToString("|") { (name, value) -> escape(name) + "=" + (value?.let(::escape) ?: "?") }

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '|' -> append("\\|")
                '=' -> append("\\=")
                else -> append(ch)
            }
        }
    }
}

fun featureKey(observation: Observation): FeatureKey = FeatureKey(
    observation.visible.entries
        .sortedBy { it.key }
        .map { (name, value) -> name to (value as? ObservedValue.Known)?.value },
)

/** 规则/诊断来源固定标注；不能把开发者写的诊断算法写成学生自主发明。 */
const val E3_STRATEGY_SOURCE = "designer_primitive"
