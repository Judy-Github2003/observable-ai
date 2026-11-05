// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M4-1b L2 跨任务完全相同子树识别，提出 extract_helper 候选；本步不改写任何正式程序。
// 安全边界：只提取不含 Ref（局部变量捕获）的闭合子树；子树内的 Arg 作为 helper 形参显式传入；
//           只在原位置替换，不把子树移出短路/分支位置；候选必须由 M4-1c 重新验证后才能激活。
package logic.capability

/** 一个独立已验证任务程序；taskId 用于证明同一子树跨至少两个任务出现。 */
data class TaskProgram(val taskId: String, val definition: CapabilityDefinition)

data class SubtreeOccurrence(val taskId: String, val path: String)

data class ExtractHelperCandidate(
    val helperId: String,
    val helper: CapabilityDefinition,
    val bodyCanonical: String,
    val bodyHash: String,
    val nodeCount: Int,
    val occurrences: List<SubtreeOccurrence>,
    val dependencies: List<DependencyPin>,
    /** M4-1c 在原位置替换时使用的 Call；只引用 helper 哈希与自由参数，不移动子树。 */
    val replacement: Expr.Call,
)

data class ExtractionResult(
    val candidates: List<ExtractHelperCandidate>,
    val scannedTasks: Int,
    val scannedNodes: Int,
)

/**
 * 完全相同子树抽取：
 * 1) 遍历每个任务正文的所有非根节点；
 * 2) 跳过含 Ref 的子树（可能捕获 let 局部变量），也跳过无法推断类型的节点；
 * 3) 按 canonical AST 哈希 + 输出类型分组，只有跨 ≥2 个不同 taskId 且达到最小节点数的组才成为候选；
 * 4) helper ID 由正文哈希派生，原任务定义保持不变。
 */
class SubtreeExtractor(
    private val resolver: CapabilityResolver,
    private val minNodes: Int = 3,
) {

    fun extract(tasks: List<TaskProgram>): ExtractionResult {
        require(minNodes >= 1) { "invalid_min_nodes" }
        val groups = LinkedHashMap<Pair<String, CapType>, MutableList<SubtreeOccurrence>>()
        val bodies = LinkedHashMap<Pair<String, CapType>, Expr>()
        var scannedNodes = 0

        for (task in tasks) {
            walk(task.definition.body, task.taskId, "", isRoot = true) { expr, path ->
                scannedNodes++
                if (containsRef(expr)) return@walk
                if (countNodes(expr) < minNodes) return@walk
                val type = try {
                    infer(expr, task.definition.inputs, emptyMap())
                } catch (_: Exception) {
                    null
                } ?: return@walk
                val canonical = try {
                    CanonicalJson.expr(expr)
                } catch (_: Exception) {
                    return@walk
                }
                val hash = sha256("$IR_SEMANTICS_VERSION\n$canonical")
                val key = hash to type
                groups.getOrPut(key) { mutableListOf() } += SubtreeOccurrence(task.taskId, path)
                bodies.putIfAbsent(key, expr)
            }
        }

        val candidates = ArrayList<ExtractHelperCandidate>()
        for ((key, occurrences) in groups) {
            // 必须跨至少两个任务；同一任务内重复出现不算独立证据。
            if (occurrences.map { it.taskId }.toSet().size < 2) continue
            val body = bodies.getValue(key) ?: continue
            val arguments = collectFreeArgs(body)
            val inputs = arguments.mapValues { (_, type) -> type }.toSortedMap()
            val helperId = "helper.${key.first.take(12)}"
            val helper = try {
                CapabilityDefinition(helperId, 1, inputs, key.second, body)
            } catch (_: Exception) {
                continue
            }
            candidates += ExtractHelperCandidate(
                helperId = helperId,
                helper = helper,
                bodyCanonical = CanonicalJson.expr(body),
                bodyHash = key.first,
                nodeCount = countNodes(body),
                occurrences = occurrences.sortedWith(compareBy({ it.taskId }, { it.path })),
                dependencies = collectDependencies(body),
                replacement = Expr.Call(
                    helperId,
                    1,
                    helper.contentHash(),
                    inputs.mapValues { (name, type) -> Expr.Arg(name, type) },
                ),
            )
        }
        return ExtractionResult(
            candidates = candidates.sortedWith(compareBy({ it.nodeCount }, { it.bodyCanonical })),
            scannedTasks = tasks.size,
            scannedNodes = scannedNodes,
        )
    }

    /** 先序遍历，path 用 /0、/1 等编号，便于 M4-1c 精确定位替换点。 */
    private fun walk(expr: Expr, taskId: String, path: String, isRoot: Boolean, visit: (Expr, String) -> Unit) {
        if (!isRoot) visit(expr, path)
        val children = childrenOf(expr)
        children.forEachIndexed { index, child ->
            val childPath = if (path.isEmpty()) index.toString() else "$path/$index"
            walk(child, taskId, childPath, isRoot = false, visit = visit)
        }
    }

    private fun childrenOf(expr: Expr): List<Expr> = when (expr) {
        is Expr.Arg, is Expr.Const, is Expr.Ref -> emptyList()
        is Expr.Field -> listOf(expr.record)
        is Expr.Eq -> listOf(expr.left, expr.right)
        is Expr.And -> listOf(expr.left, expr.right)
        is Expr.Or -> listOf(expr.left, expr.right)
        is Expr.Not -> listOf(expr.value)
        is Expr.If -> listOf(expr.condition, expr.whenTrue, expr.whenFalse)
        is Expr.Let -> listOf(expr.value, expr.body)
        is Expr.Call -> expr.args.entries.sortedBy { it.key }.map { it.value }
    }

    private fun containsRef(root: Expr): Boolean {
        var found = false
        fun visit(expr: Expr) {
            if (found) return
            if (expr is Expr.Ref) {
                found = true
                return
            }
            childrenOf(expr).forEach(::visit)
        }
        visit(root)
        return found
    }

    private fun collectFreeArgs(root: Expr): Map<String, CapType> {
        val result = LinkedHashMap<String, CapType>()
        fun visit(expr: Expr) {
            when (expr) {
                is Expr.Arg -> result.putIfAbsent(expr.name, expr.type)
                else -> childrenOf(expr).forEach(::visit)
            }
        }
        visit(root)
        return result.toSortedMap()
    }

    /** 只覆盖 M1 的确定性节点；Ref 在抽取前已排除，这里保留以处理 Let 内部类型推断。 */
    private fun infer(expr: Expr, inputs: Map<String, CapType>, locals: Map<String, CapType>): CapType? {
        return when (expr) {
            is Expr.Arg -> inputs[expr.name]
            is Expr.Const -> typeOfValue(expr.value)
            is Expr.Ref -> locals[expr.name]
            is Expr.Field -> (infer(expr.record, inputs, locals) as? CapType.Record)?.fields?.get(expr.name)
            is Expr.Eq, is Expr.And, is Expr.Or, is Expr.Not -> CapType.Bool3
            is Expr.If -> {
                val a = infer(expr.whenTrue, inputs, locals)
                val b = infer(expr.whenFalse, inputs, locals)
                if (a == b) a else null
            }
            is Expr.Let -> {
                val valueType = infer(expr.value, inputs, locals) ?: return null
                infer(expr.body, inputs, locals + (expr.name to valueType))
            }
            is Expr.Call -> resolver.resolve(expr.id, expr.version)?.output
        }
    }
}
