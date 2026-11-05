// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M4-1c 在原位置安全重写公共子树、做有限域等价比较与净收益判定；不覆盖旧版本、不自动发布。
// 边界：重写只把子树替换成锁定 helper 的 Call，不移动短路/分支位置；未通过等价或净收益的候选一律 rejected_candidate。
package logic.capability

enum class DomainKind { EXHAUSTIVE, BOUNDED }

data class RewrittenTask(
    val taskId: String,
    val original: CapabilityDefinition,
    val rewritten: CapabilityDefinition,
    val replacedCount: Int,
)

data class EquivalenceMismatch(
    val taskId: String,
    val point: Map<String, CapValue>,
    val original: EvalResult,
    val rewritten: EvalResult,
)

data class EquivalenceReport(
    val checked: Int,
    val mismatches: List<EquivalenceMismatch>,
    val verified: Boolean,
    val bounded: Boolean,
)

/**
 * 重写结果：
 * - accepted 只有在“类型检查通过 + 等价检查通过 + L_total 净减少”三者同时成立时为 true；
 * - 未通过时保留 reason，调用方可作为 rejected_candidate 留档；
 * - 旧任务定义保留在 original，重写版本使用 version+1 的候选定义，尚未写入注册表。
 */
data class RewriteOutcome(
    val candidate: ExtractHelperCandidate,
    val tasks: List<RewrittenTask>,
    val equivalence: EquivalenceReport,
    val costBefore: Long,
    val costAfter: Long,
    val accepted: Boolean,
    val reason: String?,
)

class SubtreeRewriter(private val library: List<CapabilityDefinition> = emptyList()) {

    fun rewrite(
        tasks: List<TaskProgram>,
        candidate: ExtractHelperCandidate,
        domain: List<Map<String, CapValue>>,
        domainKind: DomainKind = DomainKind.EXHAUSTIVE,
    ): RewriteOutcome {
        val resolver = InMemoryResolver(library + candidate.helper)
        val checker = CapabilityTypeChecker(resolver)
        val byTask = candidate.occurrences.groupBy { it.taskId }
        val rewritten = ArrayList<RewrittenTask>()
        var typeError: String? = null

        for (task in tasks) {
            val occurrences = byTask[task.taskId].orEmpty()
            val paths = outermost(occurrences)
            if (paths.isEmpty()) {
                rewritten += RewrittenTask(task.taskId, task.definition, task.definition, 0)
                continue
            }
            var body = task.definition.body
            for (path in paths.sortedByDescending { it.size }) {
                body = try {
                    replaceAt(body, path, candidate.replacement)
                } catch (_: Exception) {
                    typeError = "rewrite_path_mismatch"
                    body = task.definition.body
                    break
                }
            }
            val definition = CapabilityDefinition(
                task.definition.id,
                task.definition.version + 1,
                task.definition.inputs,
                task.definition.output,
                body,
            )
            val checked = checker.check(definition)
            if (!checked.ok) {
                typeError = checked.code ?: "type_error"
                return outcome(candidate, rewritten, EquivalenceReport(0, emptyList(), false, domainKind == DomainKind.BOUNDED), 0, 0, false, typeError)
            }
            rewritten += RewrittenTask(task.taskId, task.definition, definition, paths.size)
        }

        val equivalence = checkEquivalence(tasks, rewritten, resolver, domain, domainKind)
        val costBefore = tasks.sumOf { CapabilityCost.program(it.definition.body) }
        val costAfter = rewritten.sumOf { CapabilityCost.program(it.rewritten.body) } +
            CapabilityCost.program(candidate.helper.body) +
            CapabilityCost.signature(candidate.helper.inputs, candidate.helper.output) +
            CapabilityCost.LAMBDA
        val accepted = equivalence.verified && costAfter < costBefore
        val reason = when {
            !equivalence.verified -> "behavior_mismatch"
            costAfter >= costBefore -> "no_net_benefit"
            else -> null
        }
        return outcome(candidate, rewritten, equivalence, costBefore, costAfter, accepted, reason)
    }

    private fun outcome(
        candidate: ExtractHelperCandidate,
        tasks: List<RewrittenTask>,
        equivalence: EquivalenceReport,
        before: Long,
        after: Long,
        accepted: Boolean,
        reason: String?,
    ) = RewriteOutcome(candidate, tasks, equivalence, before, after, accepted, reason)

    /** 只保留最外层出现，避免嵌套的同一子树被重复替换或替换后路径失效。 */
    private fun outermost(occurrences: List<SubtreeOccurrence>): List<List<Int>> {
        val paths = occurrences.map { occurrence ->
            occurrence.path.split('/').filter { it.isNotEmpty() }.map { it.toInt() }
        }.sortedBy { it.size }
        val kept = ArrayList<List<Int>>()
        for (path in paths) {
            if (kept.none { prefix -> prefix.size <= path.size && path.subList(0, prefix.size) == prefix }) kept += path
        }
        return kept
    }

    private fun replaceAt(expr: Expr, indices: List<Int>, replacement: Expr): Expr {
        if (indices.isEmpty()) return replacement
        val index = indices.first()
        val children = childrenOf(expr).toMutableList()
        require(index in children.indices) { "path_out_of_range" }
        children[index] = replaceAt(children[index], indices.drop(1), replacement)
        return rebuild(expr, children)
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

    private fun rebuild(expr: Expr, children: List<Expr>): Expr = when (expr) {
        is Expr.Field -> Expr.Field(children[0], expr.name)
        is Expr.Eq -> Expr.Eq(children[0], children[1])
        is Expr.And -> Expr.And(children[0], children[1])
        is Expr.Or -> Expr.Or(children[0], children[1])
        is Expr.Not -> Expr.Not(children[0])
        is Expr.If -> Expr.If(children[0], children[1], children[2])
        is Expr.Let -> Expr.Let(expr.name, children[0], children[1])
        is Expr.Call -> {
            val keys = expr.args.keys.sorted()
            Expr.Call(expr.id, expr.version, expr.bodyHash, keys.mapIndexed { index, key -> key to children[index] }.toMap())
        }
        is Expr.Arg, is Expr.Const, is Expr.Ref -> expr
    }

    private fun checkEquivalence(
        tasks: List<TaskProgram>,
        rewritten: List<RewrittenTask>,
        resolver: CapabilityResolver,
        domain: List<Map<String, CapValue>>,
        domainKind: DomainKind,
    ): EquivalenceReport {
        val byId = rewritten.associateBy { it.taskId }
        val interpreter = CapabilityInterpreter(resolver)
        val mismatches = ArrayList<EquivalenceMismatch>()
        var checked = 0
        for (task in tasks) {
            val rewrittenTask = byId[task.taskId] ?: continue
            for (point in domain) {
                val arguments = point.mapValues { (_, value) -> EvalResult.Known(value) }
                val original = interpreter.execute(task.definition, arguments)
                val changed = interpreter.execute(rewrittenTask.rewritten, arguments)
                checked++
                if (signature(original) != signature(changed)) {
                    mismatches += EquivalenceMismatch(task.taskId, point, original, changed)
                }
            }
        }
        return EquivalenceReport(
            checked = checked,
            mismatches = mismatches,
            verified = mismatches.isEmpty() && checked > 0,
            bounded = domainKind == DomainKind.BOUNDED,
        )
    }

    private fun signature(result: EvalResult): String = when (result) {
        is EvalResult.Known -> "K:" + encodeValue(result.value)
        is EvalResult.Unknown -> "U:" + result.reason
        is EvalResult.Error -> "E:" + result.code
    }
}
