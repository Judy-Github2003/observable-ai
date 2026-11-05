// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M3-1b 公开样本筛选与可辨识性；只使用调用方提供的公开正负例，不接触 hidden oracle。
// 边界：Unknown 不当 false，Error 不晋升；多个候选都能解释样本时返回 ambiguous，不把搜索第一名当真实规律。
package logic.capability

/** 公开样本：已知输入 + 已知 true/false 标签；不包含隐藏真值或目标 AST。 */
data class PublicExample(val inputs: Map<String, CapValue>, val expected: CapValue.Bool)

enum class EvidenceStatus { IDENTIFIED, AMBIGUOUS, REFUSED }

data class CandidateEvaluation(
    val candidate: ComposedCandidate,
    val matches: Int,
    val mismatches: Int,
    val unknowns: Int,
    val errors: Int,
    val consistent: Boolean,
)

/**
 * 筛选结果：
 * - IDENTIFIED：在公开域上等价的候选只剩一个等价类，返回代表；
 * - AMBIGUOUS：存在多个在公开域上可区分的候选类，给出区分性查询；
 * - REFUSED：样本冲突、样例非法、预算耗尽、假设空间不足或没有一致候选。
 */
data class EvidenceResult(
    val status: EvidenceStatus,
    val reason: String?,
    val survivors: List<ComposedCandidate>,
    val evaluations: List<CandidateEvaluation>,
    val distinguishingQueries: List<Map<String, CapValue>>,
    /** 公开域上参与等价判定的输入点数量；0 表示未提供可枚举域。 */
    val domainSize: Int,
)

/**
 * 候选筛选器：
 * 1) 校验公开样本（非空、无冲突、字段必须在声明 schema 内、值类型匹配）；
 * 2) 逐候选执行全部样本：Known 精确比较，Unknown 不算命中，Error 直接淘汰；
 * 3) 留下与全部样本一致的候选；
 * 4) 在调用方给出的公开查询域上做等价分组：只有一个等价类才算 identified；
 *    多个等价类返回 ambiguous，并给出能在这些候选间产生不同输出的输入。
 */
class CandidateEvidenceFilter(private val resolver: CapabilityResolver) {

    fun filter(
        inputs: Map<String, CapType>,
        candidates: List<ComposedCandidate>,
        examples: List<PublicExample>,
        queryValues: Map<String, List<CapValue>> = emptyMap(),
        budget: EvalBudget = EvalBudget(),
        searchExhausted: Boolean = false,
    ): EvidenceResult {
        if (examples.isEmpty()) return refused("no_public_examples")
        conflictError(inputs, examples)?.let { return refused(it) }
        schemaError(inputs, examples)?.let { return refused(it) }
        // 搜索本身没搜完时，即使有幸存者也不能声称辨识完成。
        if (searchExhausted) return refused("search_budget_exhausted")
        if (candidates.isEmpty()) return refused("hypothesis_space_exhausted")

        val interpreter = CapabilityInterpreter(resolver)
        val evaluation = ArrayList<CandidateEvaluation>(candidates.size)
        for (candidate in candidates) {
            val definition = CapabilityDefinition("__candidate", 1, inputs, CapType.Bool3, candidate.body)
            var matches = 0
            var mismatches = 0
            var unknowns = 0
            var errors = 0
            for (example in examples) {
                val arguments = example.inputs.mapValues { (_, value) -> EvalResult.Known(value) }
                val actual = try {
                    interpreter.execute(definition, arguments, budget)
                } catch (_: Exception) {
                    EvalResult.Error("interpreter_exception")
                }
                when (actual) {
                    is EvalResult.Known -> {
                        if (actual.value == example.expected) matches++ else mismatches++
                    }
                    is EvalResult.Unknown -> unknowns++
                    is EvalResult.Error -> errors++
                }
            }
            evaluation += CandidateEvaluation(
                candidate = candidate,
                matches = matches,
                mismatches = mismatches,
                unknowns = unknowns,
                errors = errors,
                consistent = mismatches == 0 && unknowns == 0 && errors == 0,
            )
        }

        val survivors = evaluation.filter { it.consistent }.map { it.candidate }
        if (survivors.isEmpty()) return EvidenceResult(EvidenceStatus.REFUSED, "no_consistent_candidate", emptyList(), evaluation, emptyList(), 0)

        val domain = publicDomain(inputs, queryValues)
        if (domain.isEmpty()) {
            return if (survivors.size == 1) {
                EvidenceResult(EvidenceStatus.IDENTIFIED, null, survivors, evaluation, emptyList(), 0)
            } else {
                EvidenceResult(EvidenceStatus.AMBIGUOUS, "insufficient_query_domain", survivors, evaluation, emptyList(), 0)
            }
        }

        val signatures = evaluateDomain(inputs, survivors, domain, budget)
        val classes = LinkedHashMap<String, MutableList<ComposedCandidate>>()
        survivors.forEachIndexed { index, candidate ->
            classes.getOrPut(signatures[index]) { mutableListOf() }.add(candidate)
        }
        if (classes.size == 1) {
            // 语法不同但在公开域上不可区分的候选视为一个等价类；代表取节点最少、canonical 最小者。
            val representative = survivors.minWith(compareBy({ it.nodeCount }, { it.canonical }))
            return EvidenceResult(EvidenceStatus.IDENTIFIED, null, listOf(representative), evaluation, emptyList(), domain.size)
        }

        val queries = ArrayList<Map<String, CapValue>>()
        for ((pointIndex, _) in domain.withIndex()) {
            if (queries.size >= 3) break
            if (signatures.map { it[pointIndex] }.toSet().size > 1) queries += domain[pointIndex]
        }
        return EvidenceResult(EvidenceStatus.AMBIGUOUS, null, survivors, evaluation, queries, domain.size)
    }

    private fun refused(reason: String): EvidenceResult =
        EvidenceResult(EvidenceStatus.REFUSED, reason, emptyList(), emptyList(), emptyList(), 0)

    /** 同一组输入出现不同标签，属于证据冲突，不能静默取最后一个。 */
    private fun conflictError(inputs: Map<String, CapType>, examples: List<PublicExample>): String? {
        val labels = HashMap<String, Boolean>()
        for (example in examples) {
            val key = canonicalInputs(example.inputs)
            val previous = labels.put(key, example.expected.value)
            if (previous != null && previous != example.expected.value) return "conflicting_evidence"
        }
        return null
    }

    /** 样例只能包含声明过的参数/字段，且值类型必须匹配；未见属性不能靠“猜”进入候选空间。 */
    private fun schemaError(inputs: Map<String, CapType>, examples: List<PublicExample>): String? {
        for (example in examples) {
            if (example.inputs.keys != inputs.keys) return "invalid_example"
            for ((name, value) in example.inputs) {
                val type = inputs.getValue(name)
                // 先拒绝 schema 外字段，再检查类型；否则“未知属性”会被笼统地报成 invalid_example。
                if (type is CapType.Record && value is CapValue.Rec) {
                    if (value.fields.keys.any { it !in type.fields }) return "unknown_attribute"
                }
                if (!valueMatches(value, type)) return "invalid_example"
            }
        }
        return null
    }

    private fun valueMatches(value: CapValue, type: CapType): Boolean = when (type) {
        CapType.Bool3 -> value is CapValue.Bool
        CapType.Str -> value is CapValue.Str
        CapType.Int64 -> value is CapValue.I64
        is CapType.Record -> value is CapValue.Rec &&
            value.fields.all { (name, child) -> type.fields[name]?.let { valueMatches(child, it) } == true }
    }

    /** 公开查询域：每个参数取调用方给定的值列表的笛卡尔积；上限 256 个点，保证可复现。 */
    private fun publicDomain(inputs: Map<String, CapType>, queryValues: Map<String, List<CapValue>>): List<Map<String, CapValue>> {
        val names = inputs.keys.sorted()
        val options = names.map { queryValues[it].orEmpty() }
        if (options.any { it.isEmpty() }) return emptyList()
        var total = 1
        for (list in options) {
            total *= list.size
            if (total > 256) return emptyList()
        }
        val domain = ArrayList<Map<String, CapValue>>(total)
        val indices = IntArray(names.size)
        while (true) {
            val point = LinkedHashMap<String, CapValue>(names.size)
            names.forEachIndexed { index, name -> point[name] = options[index][indices[index]] }
            domain += point
            var position = names.size - 1
            while (position >= 0) {
                indices[position]++
                if (indices[position] < options[position].size) break
                indices[position] = 0
                position--
            }
            if (position < 0) break
        }
        return domain
    }

    /** 每个候选在公开域上的输出签名；同一签名视为在公开域上不可区分。 */
    private fun evaluateDomain(
        inputs: Map<String, CapType>,
        candidates: List<ComposedCandidate>,
        domain: List<Map<String, CapValue>>,
        budget: EvalBudget,
    ): List<String> {
        val interpreter = CapabilityInterpreter(resolver)
        return candidates.map { candidate ->
            val definition = CapabilityDefinition("__candidate", 1, inputs, CapType.Bool3, candidate.body)
            buildString {
                for (point in domain) {
                    val arguments = point.mapValues { (_, value) -> EvalResult.Known(value) }
                    val result = try {
                        interpreter.execute(definition, arguments, budget)
                    } catch (_: Exception) {
                        EvalResult.Error("interpreter_exception")
                    }
                    append(
                        when (result) {
                            is EvalResult.Known -> (result.value as? CapValue.Bool)?.let { if (it.value) "T" else "F" } ?: "K"
                            is EvalResult.Unknown -> "U"
                            is EvalResult.Error -> "E"
                        },
                    )
                }
            }
        }
    }

    private fun canonicalInputs(inputs: Map<String, CapValue>): String = buildString {
        append('{')
        inputs.toSortedMap().entries.forEachIndexed { index, (name, value) ->
            if (index > 0) append(',')
            append(CanonicalJson.string(name)).append(':').append(encodeValue(value))
        }
        append('}')
    }
}
