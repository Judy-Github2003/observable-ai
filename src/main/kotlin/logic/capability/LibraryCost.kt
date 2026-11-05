// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M4-1a 冻结 L_total 成本模型，并验收 M2a 已有的 bodies 正文共享（L1），不再造第二套去重引擎。
// 边界：权重在看到实验结果前冻结；任何改动都要记录并重跑后续 M4/M5，不能按成绩回头调权重。
package logic.capability

/**
 * 冻结成本模型（2026-11-05）：
 * - 节点权重只用于库/任务程序的静态长度比较，不代表运行时耗时；
 * - Call 成本含固定基座、每个实参和实参表达式自身成本；
 * - 签名成本计入每个输入参数的声明成本与输出类型成本；
 * - 接口开销按“每个能力暴露一个接口”计 1，λ=1。
 *
 * 公式：L_total = Σ 唯一正文成本 + Σ 签名成本 + λ × 能力接口数。
 */
object CapabilityCost {
    const val ARG = 1L
    const val CONST = 1L
    const val FIELD = 2L
    const val EQ = 2L
    const val AND = 2L
    const val OR = 2L
    const val NOT = 1L
    const val IF = 2L
    const val LET = 2L
    const val REF = 1L
    const val CALL_BASE = 3L
    const val CALL_ARG = 1L
    const val LAMBDA = 1L

    fun type(type: CapType): Long = when (type) {
        CapType.Bool3, CapType.Str, CapType.Int64 -> 1L
        is CapType.Record -> 1L + type.fields.values.sumOf { type(it) }
    }

    fun signature(inputs: Map<String, CapType>, output: CapType): Long =
        1L + type(output) + inputs.values.sumOf { 1L + type(it) }

    fun program(expr: Expr): Long = when (expr) {
        is Expr.Arg -> ARG
        is Expr.Const -> CONST
        is Expr.Ref -> REF
        is Expr.Field -> FIELD + program(expr.record)
        is Expr.Eq -> EQ + program(expr.left) + program(expr.right)
        is Expr.And -> AND + program(expr.left) + program(expr.right)
        is Expr.Or -> OR + program(expr.left) + program(expr.right)
        is Expr.Not -> NOT + program(expr.value)
        is Expr.If -> IF + program(expr.condition) + program(expr.whenTrue) + program(expr.whenFalse)
        is Expr.Let -> LET + program(expr.value) + program(expr.body)
        is Expr.Call -> CALL_BASE + CALL_ARG * expr.args.size + expr.args.values.sumOf { program(it) }
    }

    fun canonicalBytes(expr: Expr): Long = CanonicalJson.expr(expr).toByteArray(Charsets.UTF_8).size.toLong()
}

/** 单个正文被哪些能力共享；用于说明 L1 去重真的发生了，而不是重复落盘。 */
data class SharedBody(val bodyHash: String, val capabilityIds: List<String>, val byteSize: Long, val programCost: Long)

data class L1Evidence(
    val capabilityCount: Int,
    val uniqueBodyCount: Int,
    val sharedBodyCount: Int,
    val uniqueBodyBytes: Long,
    val bodies: List<SharedBody>,
    val bodyCost: Long,
    val signatureCost: Long,
    val interfaceCost: Long,
    val totalCost: Long,
)

sealed interface L1AuditResult {
    data class Ok(val evidence: L1Evidence) : L1AuditResult
    data class Failed(val code: String) : L1AuditResult
}

/** 库内全部能力的读取结果；任一能力不可检查时整体失败，避免用残缺库算成本。 */
sealed interface CapabilityInventory {
    data class Ok(val definitions: List<CapabilityDefinition>) : CapabilityInventory
    data class Failed(val code: String) : CapabilityInventory
}

/**
 * L1 验收：读取全部能力，按 canonical AST 哈希归并正文，统计共享情况并计算冻结成本。
 * 不新增“正文去重器”：落盘共享由 M2a 的 bodies(body_hash PRIMARY KEY) 完成，本类只核对。
 */
internal class L1LibraryAudit(private val registry: SqliteCapabilityRegistry) {

    fun audit(): L1AuditResult {
        val inventory = registry.capabilityInventory()
        if (inventory is CapabilityInventory.Failed) return L1AuditResult.Failed(inventory.code)
        val definitions = (inventory as CapabilityInventory.Ok).definitions

        val byHash = LinkedHashMap<String, MutableList<CapabilityDefinition>>()
        for (definition in definitions) {
            val hash = try {
                CanonicalJson.astHash(definition.body)
            } catch (_: Exception) {
                return L1AuditResult.Failed("invalid_definition")
            }
            byHash.getOrPut(hash) { mutableListOf() }.add(definition)
        }

        var bodyCost = 0L
        var uniqueBytes = 0L
        val shared = ArrayList<SharedBody>()
        for ((hash, group) in byHash) {
            val body = group.first().body
            val cost = CapabilityCost.program(body)
            val bytes = CapabilityCost.canonicalBytes(body)
            bodyCost += cost
            uniqueBytes += bytes
            if (group.size > 1) {
                shared += SharedBody(hash, group.map { "${it.id}@${it.version}" }.sorted(), bytes, cost)
            }
        }
        // 同名能力不同版本、但正文相同的，同样只计一次正文成本；签名逐个计。
        val signatureCost = definitions.sumOf { CapabilityCost.signature(it.inputs, it.output) }
        val interfaceCost = CapabilityCost.LAMBDA * definitions.size
        val evidence = L1Evidence(
            capabilityCount = definitions.size,
            uniqueBodyCount = byHash.size,
            sharedBodyCount = shared.size,
            uniqueBodyBytes = uniqueBytes,
            bodies = shared.sortedBy { it.bodyHash },
            bodyCost = bodyCost,
            signatureCost = signatureCost,
            interfaceCost = interfaceCost,
            totalCost = bodyCost + signatureCost + interfaceCost,
        )
        return L1AuditResult.Ok(evidence)
    }
}
