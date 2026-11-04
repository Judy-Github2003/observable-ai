// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 受限假设空间的候选枚举；只从输入 schema 生成，不包含任何具体颜色/形状/对象 ID。
package logic.discovery

internal object HypothesisGenerator {

    /**
     * 枚举顺序固定：先单条件（字段顺序 × 值顺序），再双条件（字段对顺序 × 值对顺序）。
     * 去重按规范化后的规则 hash；Kotlin 的 distinctBy 保留首次出现，因此顺序可复现。
     */
    fun generate(schema: LinkedHashMap<String, List<String>>): List<Rule> {
        val fields = schema.keys.toList()
        val rules = ArrayList<Rule>()

        for (field in fields) {
            for (value in schema.getValue(field)) {
                rules += Rule(listOf(Clause(field, value)))
            }
        }

        for (i in fields.indices) {
            for (j in i + 1 until fields.size) {
                val left = fields[i]
                val right = fields[j]
                for (leftValue in schema.getValue(left)) {
                    for (rightValue in schema.getValue(right)) {
                        rules += Rule(listOf(Clause(left, leftValue), Clause(right, rightValue))).canonical(fields)
                    }
                }
            }
        }
        return rules.distinctBy { it.hash() }
    }

    /** 候选集合 hash：按当前顺序拼接单规则 hash，保证同一候选集合得到同一摘要。 */
    fun setHash(rules: List<Rule>): String = sha256(rules.joinToString("|") { it.hash() })
}

internal fun Rule.userText(): String = if (clauses.size == 1) {
    "${clauses[0].field} == ${clauses[0].eq}"
} else {
    clauses.joinToString(" AND ") { "(${it.field} == ${it.eq})" }
}
