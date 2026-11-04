// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 证据筛选、冲突检测、有限域等价类判定；候选只被证据淘汰，不按“简单优先”偷选。
package logic.discovery

internal object ConsistencyEngine {

    /** 规则与全部历史观察一致，才算仍可行；未知预测也不能解释已观察标签。 */
    fun consistent(rule: Rule, observations: List<Observation>, objects: Map<String, Map<String, String>>): Boolean {
        for (observation in observations) {
            val features = objects[observation.objectId] ?: return false
            if (RuleInterpreter.evaluate(rule, features) != Tri.of(observation.label)) return false
        }
        return true
    }

    fun surviving(candidates: List<Rule>, state: LearningState): List<Rule> =
        candidates.filter { consistent(it, state.observations, state.objects) }

    /** 同一对象同时出现 true 与 false，视为真实证据冲突；保留双方，不取最后写入。 */
    fun conflictingObjects(observations: List<Observation>): List<String> =
        observations.groupBy { it.objectId }
            .filterValues { list -> list.map { it.label }.distinct().size > 1 }
            .keys.sorted()

    /** 完整有限属性空间的笛卡尔积；schema 在 start 时已校验非空，这里只负责展开。 */
    fun domainAssignments(schema: LinkedHashMap<String, List<String>>): List<Map<String, String>> {
        var result: List<Map<String, String>> = listOf(emptyMap())
        for ((field, values) in schema) {
            val next = ArrayList<Map<String, String>>(result.size * values.size)
            for (assignment in result) {
                for (value in values) {
                    next += LinkedHashMap(assignment).apply { put(field, value) }
                }
            }
            result = next
        }
        return result
    }

    fun domainSize(schema: LinkedHashMap<String, List<String>>): Int =
        schema.values.fold(1) { acc, values -> acc * values.size }

    fun equivalent(left: Rule, right: Rule, schema: LinkedHashMap<String, List<String>>): Boolean =
        domainAssignments(schema).all { assignment ->
            RuleInterpreter.evaluate(left, assignment) == RuleInterpreter.evaluate(right, assignment)
        }

    /**
     * 把候选按“在完整有限域上的预测向量”分组；同组即语义等价。
     * 第一项作为代表，保持输入顺序，方便复现晋升结果。
     */
    fun equivalenceClasses(candidates: List<Rule>, schema: LinkedHashMap<String, List<String>>): List<List<Rule>> {
        val assignments = domainAssignments(schema)
        val groups = LinkedHashMap<String, MutableList<Rule>>()
        for (candidate in candidates) {
            val signature = assignments.joinToString("|") { RuleInterpreter.evaluate(candidate, it).name }
            groups.getOrPut(signature) { mutableListOf() } += candidate
        }
        return groups.values.toList()
    }

    /** 被新证据淘汰的候选，逐条记录预测值和证据，供事后复核。 */
    fun eliminations(
        before: List<Rule>,
        after: List<Rule>,
        observation: Observation,
        objects: Map<String, Map<String, String>>,
    ): List<Elimination> {
        val survivors = after.map { it.hash() }.toSet()
        val features = objects[observation.objectId] ?: return emptyList()
        return before.filter { it.hash() !in survivors }.map { rule ->
            Elimination(
                ruleHash = rule.hash(),
                ruleText = rule.userText(),
                evidenceId = observation.evidenceId,
                predicted = RuleInterpreter.evaluate(rule, features).wire(),
                observed = observation.label,
            )
        }
    }
}
