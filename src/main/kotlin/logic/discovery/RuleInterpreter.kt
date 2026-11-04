// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 三值求值；缺属性必须保持 unknown，AND 中已知 false 优先。
package logic.discovery

internal enum class Tri {
    TRUE, FALSE, UNKNOWN;

    fun wire(): String = when (this) {
        TRUE -> "true"
        FALSE -> "false"
        UNKNOWN -> "unknown"
    }

    companion object {
        fun of(label: Boolean): Tri = if (label) TRUE else FALSE
    }
}

internal object RuleInterpreter {

    /**
     * AND 语义：
     * - 任一条已知为 false → FALSE（即使其它字段缺失）；
     * - 否则任一条缺字段 → UNKNOWN；
     * - 否则 → TRUE。
     * 单条件规则同样走这里，避免出现两套求值逻辑。
     */
    fun evaluate(rule: Rule, features: Map<String, String>): Tri {
        var hasUnknown = false
        for (clause in rule.clauses) {
            val value = features[clause.field]
            if (value == null) {
                hasUnknown = true
            } else if (value != clause.eq) {
                return Tri.FALSE
            }
        }
        return if (hasUnknown) Tri.UNKNOWN else Tri.TRUE
    }
}
