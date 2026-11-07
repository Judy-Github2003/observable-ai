// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-02 参数化结构归纳；先给保守骨架，契约 RED 后再实现 typed LGG。
package logic.e3.pack02

import logic.capability.*

data class SD02VerifiedSource(
    val sourceId: String,
    val version: Int,
    val contentHash: String,
    val body: Expr,
    val inputTypes: Map<String, CapType>,
    val validationScope: String,
    val evidenceIds: List<String>,
)

data class SD02TypedHole(val id: String, val expectedType: CapType, val bindingClass: String)

data class SD02GeneralizedPattern(
    val patternId: String,
    val sourceIds: List<String>,
    val holes: List<SD02TypedHole>,
    val pattern: Expr,
    val substitutionsBySource: Map<String, Map<String, Expr>>,
    val reconstructionOk: Boolean,
    val degenerate: Boolean,
    val rejectReason: String?,
    /** 两个来源共同出现、且未被 holes 替代的自由变量；必须显式成为宏参数。 */
    val freeArgs: List<SD02TypedHole> = emptyList(),
)

object SD02AntiUnify {
    private class Reject(val code: String) : Exception(code)

    fun generalize(sources: List<SD02VerifiedSource>): SD02GeneralizedPattern {
        if (sources.size < 2) {
            return rejected(sources, "INSUFFICIENT_VERIFIED_SOURCES")
        }
        if (sources.map { it.body }.distinct().size == 1) {
            return rejected(sources, "DEGENERATE_IDENTICAL_SOURCES")
        }
        val holes = LinkedHashMap<Pair<Expr, Expr>, SD02TypedHole>()
        val subs = sources.associate { it.sourceId to LinkedHashMap<String, Expr>() }
        fun holeType(e: Expr): CapType = when (e) {
            is Expr.Arg -> e.type
            is Expr.Const -> when (e.value) {
                is CapValue.Str -> CapType.Str
                is CapValue.I64 -> CapType.Int64
                is CapValue.Bool -> CapType.Bool3
                is CapValue.Rec -> CapType.Record(e.value.fields.mapValues { CapType.Str })
            }
            else -> CapType.Bool3
        }
        fun hole(a: Expr, b: Expr, s1: MutableMap<String, Expr>, s2: MutableMap<String, Expr>): Expr {
            val key = a to b
            val h = holes.getOrPut(key) {
                SD02TypedHole("h${holes.size}", holeType(a), "VALUE_ARGUMENT")
            }
            s1[h.id] = a
            s2[h.id] = b
            return Expr.Arg(h.id, h.expectedType)
        }
        fun walk(a: Expr, b: Expr, s1: MutableMap<String, Expr>, s2: MutableMap<String, Expr>): Expr {
            if (a == b) return a
            if (a is Expr.Arg && b is Expr.Arg && a.type == b.type) return hole(a, b, s1, s2)
            if (a is Expr.Const && b is Expr.Const && holeType(a) == holeType(b)) return hole(a, b, s1, s2)
            return when {
                a is Expr.Eq && b is Expr.Eq -> Expr.Eq(walk(a.left, b.left, s1, s2), walk(a.right, b.right, s1, s2))
                a is Expr.And && b is Expr.And -> Expr.And(walk(a.left, b.left, s1, s2), walk(a.right, b.right, s1, s2))
                a is Expr.Or && b is Expr.Or -> Expr.Or(walk(a.left, b.left, s1, s2), walk(a.right, b.right, s1, s2))
                a is Expr.Not && b is Expr.Not -> Expr.Not(walk(a.value, b.value, s1, s2))
                a is Expr.If && b is Expr.If -> Expr.If(
                    walk(a.condition, b.condition, s1, s2),
                    walk(a.whenTrue, b.whenTrue, s1, s2),
                    walk(a.whenFalse, b.whenFalse, s1, s2),
                )
                a is Expr.Call && b is Expr.Call && a.id == b.id && a.version == b.version &&
                    a.bodyHash == b.bodyHash && a.args.keys == b.args.keys ->
                    Expr.Call(a.id, a.version, a.bodyHash, a.args.mapValues { (k, v) -> walk(v, b.args.getValue(k), s1, s2) })
                a is Expr.Field && b is Expr.Field && a.name == b.name -> Expr.Field(walk(a.record, b.record, s1, s2), a.name)
                else -> throw Reject("IRRELEVANT_SOURCE_DIFFERENCE")
            }
        }
        return try {
            // 两两按固定顺序折叠；首版只用两个来源，后续逐步归纳。
            val first = sources[0]
            val second = sources[1]
            val pattern = walk(first.body, second.body, subs.getValue(first.sourceId), subs.getValue(second.sourceId))
            val nonTrivial = holes.isNotEmpty() && (pattern !is Expr.Arg)
            val reconOk = sources.all { source ->
                val map = subs.getValue(source.sourceId)
                reconstruct(pattern, map) == source.body
            }
            val holeIds = holes.values.map { it.id }.toSet()
            val free = collectFreeArgs(pattern, holeIds)
            SD02GeneralizedPattern(
                patternId = "sd02-${pattern.hashCode().toUInt().toString(16)}",
                sourceIds = sources.map { it.sourceId },
                holes = holes.values.toList(),
                pattern = pattern,
                substitutionsBySource = subs,
                reconstructionOk = reconOk,
                degenerate = !nonTrivial,
                rejectReason = if (nonTrivial) null else "DEGENERATE_PATTERN",
                freeArgs = free,
            )
        } catch (e: Reject) {
            rejected(sources, e.code)
        }
    }

    private fun rejected(sources: List<SD02VerifiedSource>, reason: String) = SD02GeneralizedPattern(
        "none", sources.map { it.sourceId }, emptyList(), Expr.Const(CapValue.Bool(false)),
        emptyMap(), false, true, reason,
    )

    private fun collectFreeArgs(pattern: Expr, holeIds: Set<String>): List<SD02TypedHole> {
        val out = LinkedHashMap<String, SD02TypedHole>()
        fun walk(e: Expr, bound: Set<String>) {
            when (e) {
                is Expr.Arg -> if (e.name !in holeIds && e.name !in bound) {
                    out.putIfAbsent(e.name, SD02TypedHole(e.name, e.type, "SHARED_FREE_ARG"))
                }
                is Expr.Const -> Unit
                is Expr.Field -> walk(e.record, bound)
                is Expr.Eq -> { walk(e.left, bound); walk(e.right, bound) }
                is Expr.And -> { walk(e.left, bound); walk(e.right, bound) }
                is Expr.Or -> { walk(e.left, bound); walk(e.right, bound) }
                is Expr.Not -> walk(e.value, bound)
                is Expr.If -> { walk(e.condition, bound); walk(e.whenTrue, bound); walk(e.whenFalse, bound) }
                is Expr.Let -> { walk(e.value, bound); walk(e.body, bound + e.name) }
                is Expr.Ref -> Unit // local refs are not macro inputs; unresolved refs are rejected by typecheck
                is Expr.Call -> e.args.values.forEach { walk(it, bound) }
            }
        }
        walk(pattern, emptySet())
        return out.values.toList()
    }

    fun reconstruct(pattern: Expr, substitutions: Map<String, Expr>): Expr = when (pattern) {
        is Expr.Arg -> substitutions[pattern.name] ?: pattern
        is Expr.Const -> pattern
        is Expr.Field -> Expr.Field(reconstruct(pattern.record, substitutions), pattern.name)
        is Expr.Eq -> Expr.Eq(reconstruct(pattern.left, substitutions), reconstruct(pattern.right, substitutions))
        is Expr.And -> Expr.And(reconstruct(pattern.left, substitutions), reconstruct(pattern.right, substitutions))
        is Expr.Or -> Expr.Or(reconstruct(pattern.left, substitutions), reconstruct(pattern.right, substitutions))
        is Expr.Not -> Expr.Not(reconstruct(pattern.value, substitutions))
        is Expr.If -> Expr.If(
            reconstruct(pattern.condition, substitutions),
            reconstruct(pattern.whenTrue, substitutions),
            reconstruct(pattern.whenFalse, substitutions),
        )
        is Expr.Let -> Expr.Let(pattern.name, reconstruct(pattern.value, substitutions), reconstruct(pattern.body, substitutions))
        is Expr.Ref -> pattern
        is Expr.Call -> Expr.Call(pattern.id, pattern.version, pattern.bodyHash, pattern.args.mapValues { (_, v) -> reconstruct(v, substitutions) })
    }
}
