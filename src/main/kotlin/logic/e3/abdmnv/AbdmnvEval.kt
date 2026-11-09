// 实现者：DeepSeek Harness，2026-11-09，ABDMNV-01 M1 切片。
// 四值求值：Bool3 断言 + 独立 ERROR 通道；复用 logic.capability.EvalResult/CapValue 作为值模型。
// 规则（预注册，写入实现说明）：ERROR 严格支配（任一子项 ERROR 即返回该 ERROR），UNKNOWN 不降级为 FALSE。
package logic.e3.abdmnv

import logic.capability.CapValue
import logic.capability.EvalResult

fun abdBool(v: Boolean): EvalResult = EvalResult.Known(CapValue.Bool(v))
fun abdStr(v: String): EvalResult = EvalResult.Known(CapValue.Str(v))
fun abdInt(v: Long): EvalResult = EvalResult.Known(CapValue.I64(v))
fun abdUnknown(reason: String): EvalResult = EvalResult.Unknown(reason)
fun abdError(code: String): EvalResult = EvalResult.Error(code)

/** 学生可见的世界接口：只读属性与关系；未测量由实现方返回 UNKNOWN(NOT_MEASURED)。 */
interface AbdmnvWorld {
    fun objects(): List<String>
    fun attribute(obj: String, name: String): EvalResult
    fun relation(name: String, a: String, b: String): EvalResult
}

/** 关系成对测量的计费装饰器（v2 §5.4 要求计入 measurement_count）。 */
class AbdmnvCountingWorld(private val inner: AbdmnvWorld) : AbdmnvWorld {
    var relationReads: Int = 0
        private set
    var attributeReads: Int = 0
        private set

    override fun objects(): List<String> = inner.objects()

    override fun attribute(obj: String, name: String): EvalResult {
        attributeReads++
        return inner.attribute(obj, name)
    }

    override fun relation(name: String, a: String, b: String): EvalResult {
        relationReads++
        return inner.relation(name, a, b)
    }
}

/** 确定性求值器。预算耗尽返回 ERROR(resource_limit)，不做静默降级。 */
class AbdmnvEvaluator(
    private val world: AbdmnvWorld,
    private val concepts: Map<AbdmnvVersionRef, AbdmnvConcept> = emptyMap(),
    private val revoked: Set<AbdmnvVersionRef> = emptySet(),
    private val maxSteps: Int = 10_000,
    private val maxDepth: Int = 32,
) {
    private var steps = 0

    fun eval(term: AbdmnvTerm, env: Map<String, EvalResult> = emptyMap()): EvalResult = eval(term, env, 0)

    private fun step(): EvalResult? {
        steps++
        return if (steps > maxSteps) abdError("resource_limit") else null
    }

    private fun eval(t: AbdmnvTerm, env: Map<String, EvalResult>, depth: Int): EvalResult {
        step()?.let { return it }
        if (depth > maxDepth) return abdError("resource_limit")
        return when (t) {
            is AbdmnvTerm.Var -> env[t.name] ?: abdError("unbound_var")
            is AbdmnvTerm.ConstBool -> abdBool(t.value)
            is AbdmnvTerm.ConstObj -> abdStr(t.id)
            is AbdmnvTerm.ConstInt -> abdInt(t.value.toLong())
            is AbdmnvTerm.SetLit -> EvalResult.Known(CapValue.Rec(t.ids.associateWith { CapValue.Bool(true) }))
            is AbdmnvTerm.Attr -> {
                val o = objOf(t.on, env, depth) ?: return abdError("object_required")
                world.attribute(o, t.name)
            }

            is AbdmnvTerm.Rel -> {
                val a = objOf(t.a, env, depth) ?: return abdError("object_required")
                val b = objOf(t.b, env, depth) ?: return abdError("object_required")
                world.relation(t.name, a, b)
            }

            is AbdmnvTerm.Apply -> applyConcept(t, env, depth)
            is AbdmnvTerm.Not -> when (val v = eval(t.value, env, depth + 1)) {
                is EvalResult.Error -> v
                is EvalResult.Unknown -> v
                is EvalResult.Known -> if (v.value is CapValue.Bool) abdBool(!(v.value as CapValue.Bool).value)
                else abdError("type_mismatch")
            }

            is AbdmnvTerm.And -> and(eval(t.left, env, depth + 1), eval(t.right, env, depth + 1))
            is AbdmnvTerm.Or -> or(eval(t.left, env, depth + 1), eval(t.right, env, depth + 1))
            is AbdmnvTerm.Eq -> eq(eval(t.left, env, depth + 1), eval(t.right, env, depth + 1))
            is AbdmnvTerm.Lt -> lt(eval(t.left, env, depth + 1), eval(t.right, env, depth + 1))
            is AbdmnvTerm.Member -> member(t, env, depth)
            is AbdmnvTerm.Exists -> quantify(t.varName, t.over, t.body, env, depth, quantifier = "exists")
            is AbdmnvTerm.Forall -> quantify(t.varName, t.over, t.body, env, depth, quantifier = "forall")
            is AbdmnvTerm.Count -> count(t, env, depth)
        }
    }

    private fun objOf(t: AbdmnvTerm, env: Map<String, EvalResult>, depth: Int): String? =
        when (val v = eval(t, env, depth + 1)) {
            is EvalResult.Known -> (v.value as? CapValue.Str)?.value
            else -> null
        }

    private fun applyConcept(t: AbdmnvTerm.Apply, env: Map<String, EvalResult>, depth: Int): EvalResult {
        if (revoked.contains(t.ref)) return abdError("DEPENDENCY_REVOKED")
        val c = concepts[t.ref] ?: return abdError("unknown_concept")
        val body = c.body
        if (body !is AbdmnvBody.Lambda) return abdError("not_callable")
        if (body.params.size != t.args.size) return abdError("arity_mismatch")
        val local = env.toMutableMap()
        for ((i, arg) in t.args.withIndex()) {
            val v = eval(arg, env, depth + 1)
            if (v is EvalResult.Error) return v
            local[body.params[i]] = v
        }
        return eval(body.body, local, depth + 1)
    }

    private fun and(a: EvalResult, b: EvalResult): EvalResult {
        if (a is EvalResult.Error) return a
        if (b is EvalResult.Error) return b
        if (a is EvalResult.Known && (a.value as? CapValue.Bool)?.value == false) return abdBool(false)
        if (b is EvalResult.Known && (b.value as? CapValue.Bool)?.value == false) return abdBool(false)
        if (a is EvalResult.Unknown) return a
        if (b is EvalResult.Unknown) return b
        val av = (a as? EvalResult.Known)?.value as? CapValue.Bool ?: return abdError("type_mismatch")
        val bv = (b as? EvalResult.Known)?.value as? CapValue.Bool ?: return abdError("type_mismatch")
        return abdBool(av.value && bv.value)
    }

    private fun or(a: EvalResult, b: EvalResult): EvalResult {
        if (a is EvalResult.Error) return a
        if (b is EvalResult.Error) return b
        if (a is EvalResult.Known && (a.value as? CapValue.Bool)?.value == true) return abdBool(true)
        if (b is EvalResult.Known && (b.value as? CapValue.Bool)?.value == true) return abdBool(true)
        if (a is EvalResult.Unknown) return a
        if (b is EvalResult.Unknown) return b
        val av = (a as? EvalResult.Known)?.value as? CapValue.Bool ?: return abdError("type_mismatch")
        val bv = (b as? EvalResult.Known)?.value as? CapValue.Bool ?: return abdError("type_mismatch")
        return abdBool(av.value || bv.value)
    }

    private fun eq(a: EvalResult, b: EvalResult): EvalResult {
        if (a is EvalResult.Error) return a
        if (b is EvalResult.Error) return b
        if (a is EvalResult.Unknown) return a
        if (b is EvalResult.Unknown) return b
        val av = (a as EvalResult.Known).value
        val bv = (b as EvalResult.Known).value
        return when {
            av is CapValue.Bool && bv is CapValue.Bool -> abdBool(av.value == bv.value)
            av is CapValue.Str && bv is CapValue.Str -> abdBool(av.value == bv.value)
            av is CapValue.I64 && bv is CapValue.I64 -> abdBool(av.value == bv.value)
            else -> abdError("type_mismatch")
        }
    }

    private fun lt(a: EvalResult, b: EvalResult): EvalResult {
        if (a is EvalResult.Error) return a
        if (b is EvalResult.Error) return b
        if (a is EvalResult.Unknown) return a
        if (b is EvalResult.Unknown) return b
        val av = (a as? EvalResult.Known)?.value as? CapValue.I64 ?: return abdError("type_mismatch")
        val bv = (b as? EvalResult.Known)?.value as? CapValue.I64 ?: return abdError("type_mismatch")
        return abdBool(av.value < bv.value)
    }

    private fun member(t: AbdmnvTerm.Member, env: Map<String, EvalResult>, depth: Int): EvalResult {
        val set = t.set
        if (set !is AbdmnvTerm.SetLit) return abdError("unsupported_set")
        val v = eval(t.element, env, depth + 1)
        if (v is EvalResult.Error) return v
        if (v is EvalResult.Unknown) return v
        val id = (v as? EvalResult.Known)?.value as? CapValue.Str ?: return abdError("type_mismatch")
        return abdBool(set.ids.contains(id.value))
    }

    private fun domain(over: AbdmnvSort): List<EvalResult> = when (over) {
        AbdmnvSort.Obj -> world.objects().map { abdStr(it) }
        else -> emptyList()
    }

    private fun quantify(
        varName: String,
        over: AbdmnvSort,
        body: AbdmnvTerm,
        env: Map<String, EvalResult>,
        depth: Int,
        quantifier: String,
    ): EvalResult {
        val dom = domain(over)
        if (dom.isEmpty()) return abdError("unsupported_domain")
        val results = dom.map { eval(body, env + (varName to it), depth + 1) }
        results.firstOrNull { it is EvalResult.Error }?.let { return it }
        val bools = results.map { r ->
            when (r) {
                is EvalResult.Known -> (r.value as? CapValue.Bool)?.value ?: return abdError("type_mismatch")
                is EvalResult.Unknown -> null
                else -> return abdError("type_mismatch")
            }
        }
        return if (quantifier == "exists") {
            when {
                bools.any { it == true } -> abdBool(true)
                bools.any { it == null } -> abdUnknown("exists_undetermined")
                else -> abdBool(false)
            }
        } else {
            when {
                bools.any { it == false } -> abdBool(false)
                bools.any { it == null } -> abdUnknown("forall_undetermined")
                else -> abdBool(true)
            }
        }
    }

    private fun count(t: AbdmnvTerm.Count, env: Map<String, EvalResult>, depth: Int): EvalResult {
        val dom = domain(t.over)
        if (dom.isEmpty()) return abdError("unsupported_domain")
        val results = dom.map { eval(t.body, env + (t.varName to it), depth + 1) }
        results.firstOrNull { it is EvalResult.Error }?.let { return it }
        var trues = 0
        var unknowns = 0
        for (r in results) {
            when (r) {
                is EvalResult.Known -> if ((r.value as? CapValue.Bool)?.value == true) trues++ else if (r.value !is CapValue.Bool) return abdError("type_mismatch")
                is EvalResult.Unknown -> unknowns++
                else -> return abdError("type_mismatch")
            }
        }
        val lowOk = holds(t.cmp, trues, t.n)
        val highOk = holds(t.cmp, trues + unknowns, t.n)
        return when {
            lowOk && highOk -> abdBool(true)
            !lowOk && !highOk -> abdBool(false)
            else -> abdUnknown("count_undetermined")
        }
    }

    private fun holds(cmp: AbdmnvCmp, count: Int, n: Int): Boolean = when (cmp) {
        AbdmnvCmp.EQ -> count == n
        AbdmnvCmp.LT -> count < n
        AbdmnvCmp.LE -> count <= n
        AbdmnvCmp.GT -> count > n
        AbdmnvCmp.GE -> count >= n
    }
}
