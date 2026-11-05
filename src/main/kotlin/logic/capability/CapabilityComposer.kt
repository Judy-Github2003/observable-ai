// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M3-1a 有界、确定性、强类型候选枚举；只从已激活能力、IR 原语和公开字段/常量组合，不读隐藏 oracle。
// 边界：搜索算法是开发者写的；具体候选能否算 student_composed 要到 M3-1c 注册时按实际来源判定。
package logic.capability

/** 候选来源标记：本阶段只表示“由有界搜索器生成”，不代表学生自主发现。 */
const val COMPOSER_SOURCE = "composer_bounded_search"

/** 合成时的临时能力 ID；只用于类型检查，不写入注册表。 */
private const val COMPOSER_CANDIDATE_ID = "__candidate"

data class ComposeBudget(
    /** 组合层数上限（叶子算第 1 层）。 */
    val maxDepth: Int = 3,
    /** 单个表达式的 AST 节点上限。 */
    val maxNodes: Int = 32,
    /** 目标类型的候选数量上限。 */
    val maxCandidates: Int = 256,
    /** 每个已激活能力最多尝试多少组实参组合，避免组合爆炸。 */
    val maxCallsPerCapability: Int = 8,
)

/**
 * 搜索输入：
 * - inputs/output：目标签名；
 * - constants：公开可见常量（如颜色值、形状值）；
 * - library：调用方提供的已激活能力快照（必须由宿主从注册表取得，不能包含撤销/未激活项）；
 * - budget：深度、节点、候选数、调用组合上限。
 *
 * 故意不包含 hidden oracle、目标 AST、留出测试答案。
 */
data class ComposeRequest(
    val inputs: Map<String, CapType>,
    val output: CapType,
    val constants: List<CapValue> = emptyList(),
    val library: List<CapabilityDefinition> = emptyList(),
    val budget: ComposeBudget = ComposeBudget(),
)

data class ComposedCandidate(
    val body: Expr,
    val canonical: String,
    val astHash: String,
    val nodeCount: Int,
    val depth: Int,
    /** 候选体内所有 Call 的 (id,version,contentHash) 锁定，按 id/version 排序去重。 */
    val dependencies: List<DependencyPin>,
    val source: String = COMPOSER_SOURCE,
)

data class ComposeResult(
    val candidates: List<ComposedCandidate>,
    /** 是否因预算上限停止（candidate_limit / depth_limit）。 */
    val exhausted: Boolean,
    val reason: String?,
    /** 实际加入各类型池的表达式总数。 */
    val generated: Int,
    /** 因 canonical hash 重复而丢弃的数量。 */
    val deduplicated: Int,
    /** 因超过 maxNodes 被丢弃的数量。 */
    val nodeLimitSkipped: Int,
)

/**
 * 有界组合器：
 * 1) 叶子：输入参数、公开常量、Record 字段；
 * 2) 逐层组合：not/and/or/eq 与已激活能力的 call；
 * 3) 每个候选先做 canonical hash 去重，再做完整类型检查；
 * 4) 只做“按类型重建”的安全构造，不交换 and/or/eq 操作数，不做语义归一化；
 * 5) 输出按 (节点数, canonical) 稳定排序。
 */
class CapabilityComposer {

    fun compose(request: ComposeRequest): ComposeResult {
        require(request.budget.maxDepth >= 1) { "invalid_budget" }
        require(request.budget.maxNodes >= 1) { "invalid_budget" }
        require(request.budget.maxCandidates >= 1) { "invalid_budget" }
        require(request.budget.maxCallsPerCapability >= 1) { "invalid_budget" }

        val resolver = InMemoryResolver(request.library)
        val checker = CapabilityTypeChecker(resolver)
        val seen = HashSet<String>()
        val pools = LinkedHashMap<CapType, MutableList<Expr>>()
        var generated = 0
        var deduplicated = 0
        var nodeLimitSkipped = 0
        var stopForCandidateLimit = false
        var reason: String? = null

        fun wellTyped(body: Expr, type: CapType): Boolean =
            checker.check(CapabilityDefinition(COMPOSER_CANDIDATE_ID, 1, request.inputs, type, body)).ok

        fun admit(expr: Expr, type: CapType) {
            if (stopForCandidateLimit) return
            if (countNodes(expr) > request.budget.maxNodes) {
                nodeLimitSkipped++
                return
            }
            val canonical = try {
                CanonicalJson.expr(expr)
            } catch (_: Exception) {
                return
            }
            val hash = sha256("$IR_SEMANTICS_VERSION\n$canonical")
            if (!seen.add(hash)) {
                deduplicated++
                return
            }
            if (!wellTyped(expr, type)) return
            pools.getOrPut(type) { mutableListOf() }.add(expr)
            generated++
            // 候选上限只对目标类型计数，避免中间类型先把名额占满。
            if (type == request.output && pools.getValue(type).size >= request.budget.maxCandidates) {
                stopForCandidateLimit = true
            }
        }

        // 叶子：参数、公开常量、Record 顶层字段。
        for ((name, type) in request.inputs.toSortedMap()) {
            admit(Expr.Arg(name, type), type)
            if (type is CapType.Record) {
                for ((field, fieldType) in type.fields.toSortedMap()) {
                    admit(Expr.Field(Expr.Arg(name, type), field), fieldType)
                }
            }
        }
        for (value in request.constants) {
            val type = typeOfValue(value) ?: continue
            admit(Expr.Const(value), type)
        }

        var depth = 1
        while (depth < request.budget.maxDepth && !stopForCandidateLimit) {
            // 快照当前池，保证本轮只组合上一层已有的表达式，避免同轮递归膨胀。
            val snapshot = pools.mapValues { it.value.toList() }
            depth++
            val before = generated

            val booleans = snapshot[CapType.Bool3].orEmpty()
            for (expr in booleans) admit(Expr.Not(expr), CapType.Bool3)
            for (i in booleans.indices) {
                for (j in i until booleans.size) {
                    admit(Expr.And(booleans[i], booleans[j]), CapType.Bool3)
                    admit(Expr.Or(booleans[i], booleans[j]), CapType.Bool3)
                }
            }
            // eq 只在同类型、非 Record、非布尔? 布尔也可比较；保留 Bool3 相等。
            for ((type, exprs) in snapshot) {
                if (type is CapType.Record) continue
                for (i in exprs.indices) {
                    for (j in i + 1 until exprs.size) {
                        admit(Expr.Eq(exprs[i], exprs[j]), CapType.Bool3)
                    }
                }
            }
            for (definition in request.library) {
                for (call in buildCalls(definition, snapshot, request.budget.maxCallsPerCapability)) {
                    admit(call, definition.output)
                }
            }

            if (generated == before && !stopForCandidateLimit) break
            if (depth >= request.budget.maxDepth && generated > before) {
                reason = "depth_limit"
            }
        }
        if (stopForCandidateLimit) reason = "candidate_limit"

        val outputPool = pools[request.output].orEmpty()
        val candidates = outputPool.mapNotNull { expr ->
            val canonical = try {
                CanonicalJson.expr(expr)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            ComposedCandidate(
                body = expr,
                canonical = canonical,
                astHash = sha256("$IR_SEMANTICS_VERSION\n$canonical"),
                nodeCount = countNodes(expr),
                depth = exprDepth(expr),
                dependencies = collectDependencies(expr),
            )
        }.sortedWith(compareBy({ it.nodeCount }, { it.canonical }))
            .take(request.budget.maxCandidates)

        return ComposeResult(
            candidates = candidates,
            exhausted = reason != null,
            reason = reason,
            generated = generated,
            deduplicated = deduplicated,
            nodeLimitSkipped = nodeLimitSkipped,
        )
    }

    /**
     * 为单个已激活能力枚举实参组合。按参数名排序、按池内顺序做里程表式笛卡尔积，
     * 最多 limit 组；第 0 组是“每个参数都取池中第一个表达式”，保证结果可复现。
     */
    private fun buildCalls(
        definition: CapabilityDefinition,
        pools: Map<CapType, List<Expr>>,
        limit: Int,
    ): List<Expr> {
        val names = definition.inputs.keys.sorted()
        val contentHash = try {
            definition.contentHash()
        } catch (_: Exception) {
            return emptyList()
        }
        if (names.isEmpty()) return listOf(Expr.Call(definition.id, definition.version, contentHash, emptyMap()))
        val options = names.map { pools[definition.inputs.getValue(it)].orEmpty() }
        if (options.any { it.isEmpty() }) return emptyList()

        val result = ArrayList<Expr>(limit)
        val indices = IntArray(names.size)
        while (result.size < limit) {
            val arguments = LinkedHashMap<String, Expr>(names.size)
            names.forEachIndexed { index, name -> arguments[name] = options[index][indices[index]] }
            result += Expr.Call(definition.id, definition.version, contentHash, arguments)
            var position = names.size - 1
            while (position >= 0) {
                indices[position]++
                if (indices[position] < options[position].size) break
                indices[position] = 0
                position--
            }
            if (position < 0) break
        }
        return result
    }
}

/** 常量值的类型；Record 常量在 M1 中不支持，返回 null 表示跳过。 */
internal fun typeOfValue(value: CapValue): CapType? = when (value) {
    is CapValue.Bool -> CapType.Bool3
    is CapValue.Str -> CapType.Str
    is CapValue.I64 -> CapType.Int64
    is CapValue.Rec -> null
}

/** 表达式最大深度；Call 只按节点本身计深度，不内联被调能力正文。 */
internal fun exprDepth(root: Expr): Int {
    var max = 0
    val stack = ArrayDeque<Pair<Expr, Int>>()
    stack.addLast(root to 1)
    while (stack.isNotEmpty()) {
        val (expr, depth) = stack.removeLast()
        if (depth > max) max = depth
        when (expr) {
            is Expr.Arg, is Expr.Const, is Expr.Ref -> Unit
            is Expr.Field -> stack.addLast(expr.record to depth + 1)
            is Expr.Eq -> { stack.addLast(expr.left to depth + 1); stack.addLast(expr.right to depth + 1) }
            is Expr.And -> { stack.addLast(expr.left to depth + 1); stack.addLast(expr.right to depth + 1) }
            is Expr.Or -> { stack.addLast(expr.left to depth + 1); stack.addLast(expr.right to depth + 1) }
            is Expr.Not -> stack.addLast(expr.value to depth + 1)
            is Expr.If -> {
                stack.addLast(expr.condition to depth + 1)
                stack.addLast(expr.whenTrue to depth + 1)
                stack.addLast(expr.whenFalse to depth + 1)
            }
            is Expr.Let -> { stack.addLast(expr.value to depth + 1); stack.addLast(expr.body to depth + 1) }
            is Expr.Call -> expr.args.values.forEach { stack.addLast(it to depth + 1) }
        }
    }
    return max
}

/** 收集候选体内的 Call 锁定，按 (id, version) 去重排序；同一版本不同哈希视为异常并保留首个。 */
internal fun collectDependencies(root: Expr): List<DependencyPin> {
    val pins = LinkedHashMap<Pair<String, Int>, String>()
    val stack = ArrayDeque<Expr>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        when (val expr = stack.removeLast()) {
            is Expr.Call -> {
                val key = expr.id to expr.version
                pins.putIfAbsent(key, expr.bodyHash)
                expr.args.values.forEach { stack.addLast(it) }
            }
            is Expr.Field -> stack.addLast(expr.record)
            is Expr.Eq -> { stack.addLast(expr.left); stack.addLast(expr.right) }
            is Expr.And -> { stack.addLast(expr.left); stack.addLast(expr.right) }
            is Expr.Or -> { stack.addLast(expr.left); stack.addLast(expr.right) }
            is Expr.Not -> stack.addLast(expr.value)
            is Expr.If -> { stack.addLast(expr.condition); stack.addLast(expr.whenTrue); stack.addLast(expr.whenFalse) }
            is Expr.Let -> { stack.addLast(expr.value); stack.addLast(expr.body) }
            is Expr.Arg, is Expr.Const, is Expr.Ref -> Unit
        }
    }
    return pins.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { DependencyPin(it.key.first, it.key.second, it.value) }
}
