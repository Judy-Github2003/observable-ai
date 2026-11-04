// 参考实现：云端 ChatGPT，2026-11-04；DSH（本会话）集成、测试、修复。
// B0 M1 reference: pure, deterministic evaluator. Unknown is not Error or false.
package logic.capability

sealed interface EvalResult {
    data class Known(val value: CapValue) : EvalResult
    data class Unknown(val reason: String) : EvalResult
    data class Error(val code: String) : EvalResult
}

data class EvalBudget(val maxSteps: Int = 10_000, val maxCallDepth: Int = 16)

class CapabilityInterpreter(private val resolver: CapabilityResolver) {
    fun execute(
        def: CapabilityDefinition,
        args: Map<String, EvalResult>,
        budget: EvalBudget = EvalBudget()
    ): EvalResult {
        if (budget.maxSteps < 1 || budget.maxCallDepth < 1) return EvalResult.Error("invalid_budget")
        val checked = CapabilityTypeChecker(resolver).check(def)
        if (!checked.ok) return EvalResult.Error(checked.code ?: "type_error")
        if (args.keys != def.inputs.keys) return EvalResult.Error("input_argument_mismatch")
        for ((name, typed) in def.inputs) {
            val v = args[name] ?: return EvalResult.Error("input_argument_mismatch")
            if (v is EvalResult.Known && !matches(v.value, typed)) return EvalResult.Error("input_type_mismatch")
            if (v is EvalResult.Error) return v
        }
        return Session(budget).run(def, args, 1)
    }

    private fun matches(v: CapValue, type: CapType): Boolean = when (type) {
        CapType.Bool3 -> v is CapValue.Bool
        CapType.Str -> v is CapValue.Str
        CapType.Int64 -> v is CapValue.I64
        is CapType.Record -> v is CapValue.Rec &&
            v.fields.all { (name, child) -> type.fields[name]?.let { matches(child, it) } == true }
    }

    private inner class Session(val budget: EvalBudget) {
        private var steps = 0
        fun run(def: CapabilityDefinition, args: Map<String, EvalResult>, depth: Int): EvalResult {
            if (depth > budget.maxCallDepth) return EvalResult.Error("resource_limit")
            return eval(def.body, args, emptyMap(), depth)
        }

        private fun eval(e: Expr, args: Map<String, EvalResult>, locals: Map<String, EvalResult>, depth: Int): EvalResult {
            steps++
            if (steps > budget.maxSteps) return EvalResult.Error("resource_limit")
            fun child(x: Expr): EvalResult = eval(x, args, locals, depth)
            return when (e) {
                is Expr.Arg -> args[e.name] ?: EvalResult.Error("missing_arg")
                is Expr.Const -> EvalResult.Known(e.value)
                is Expr.Ref -> locals[e.name] ?: EvalResult.Error("unbound_ref")
                is Expr.Field -> when (val r = child(e.record)) {
                    is EvalResult.Error -> r
                    is EvalResult.Unknown -> r
                    is EvalResult.Known -> {
                        val rec = r.value as CapValue.Rec
                        rec.fields[e.name]?.let { EvalResult.Known(it) } ?: EvalResult.Unknown("missing_attribute")
                    }
                }
                is Expr.Eq -> {
                    val a = child(e.left)
                    if (a is EvalResult.Error) return a
                    val b = child(e.right)
                    if (b is EvalResult.Error) return b
                    if (a is EvalResult.Unknown || b is EvalResult.Unknown) EvalResult.Unknown("unknown_operand")
                    else EvalResult.Known(CapValue.Bool((a as EvalResult.Known).value == (b as EvalResult.Known).value))
                }
                is Expr.And -> combineAndOr(e.left, e.right, args, locals, depth, and = true)
                is Expr.Or -> combineAndOr(e.left, e.right, args, locals, depth, and = false)
                is Expr.Not -> when (val a = child(e.value)) {
                    is EvalResult.Error -> a
                    is EvalResult.Unknown -> a
                    is EvalResult.Known -> EvalResult.Known(CapValue.Bool(!(a.value as CapValue.Bool).value))
                }
                is Expr.If -> when (val cond = child(e.condition)) {
                    is EvalResult.Error -> cond
                    is EvalResult.Unknown -> EvalResult.Unknown("condition_unknown")
                    is EvalResult.Known -> child(if ((cond.value as CapValue.Bool).value) e.whenTrue else e.whenFalse)
                }
                is Expr.Let -> {
                    val v = child(e.value)
                    if (v is EvalResult.Error) v
                    else eval(e.body, args, locals + (e.name to v), depth)
                }
                is Expr.Call -> {
                    val callee = resolver.resolve(e.id, e.version) ?: return EvalResult.Error("missing_dependency")
                    if (callee.contentHash() != e.bodyHash) return EvalResult.Error("dependency_hash_mismatch")
                    val callArgs = linkedMapOf<String, EvalResult>()
                    for (name in callee.inputs.keys.sorted()) {
                        val argExpr = e.args[name] ?: return EvalResult.Error("call_argument_mismatch")
                        val result = child(argExpr)
                        if (result is EvalResult.Error) return result
                        callArgs[name] = result
                    }
                    run(callee, callArgs, depth + 1)
                }
            }
        }

        private fun combineAndOr(
            left: Expr, right: Expr,
            args: Map<String, EvalResult>, locals: Map<String, EvalResult>,
            depth: Int, and: Boolean
        ): EvalResult {
            val l = eval(left, args, locals, depth)
            if (l is EvalResult.Error) return l
            // Short-circuit when the left result is decisive: false AND X; true OR X.
            val decisive = if (and) false else true
            if (l is EvalResult.Known && (l.value as CapValue.Bool).value == decisive) return l
            val r = eval(right, args, locals, depth)
            if (r is EvalResult.Error) return r
            if (r is EvalResult.Known && (r.value as CapValue.Bool).value == decisive) return r
            if (l is EvalResult.Unknown || r is EvalResult.Unknown) return EvalResult.Unknown("unknown_operand")
            // Both definite and neither decisive => true for AND; false for OR.
            return EvalResult.Known(CapValue.Bool(and))
        }
    }
}
