// 参考实现：云端 ChatGPT，2026-11-04；DSH（本会话）集成、测试、修复。
// B0 M1 reference: strict static typing, pinned dependencies, cycle checks.
package logic.capability

class CapabilityCheckException(val code: String, message: String = code) : RuntimeException(message)

data class TypeCheckResult(val ok: Boolean, val code: String? = null)

class CapabilityTypeChecker(private val resolver: CapabilityResolver) {
    fun check(def: CapabilityDefinition): TypeCheckResult = try {
        if (countNodes(def.body) > MAX_AST_NODES) fail("ast_node_limit")
        checkDefinition(def, mutableSetOf(), 0)
        TypeCheckResult(true)
    } catch (e: CapabilityCheckException) {
        TypeCheckResult(false, e.code)
    } catch (_: IllegalArgumentException) {
        TypeCheckResult(false, "invalid_type")
    }

    private fun checkDefinition(def: CapabilityDefinition, active: MutableSet<Pair<String, Int>>, callDepth: Int) {
        if (callDepth > 16) fail("call_depth_limit")
        if (def.id.isBlank() || def.version <= 0 || def.inputs.keys.any(String::isBlank)) fail("invalid_signature")
        if (!active.add(def.id to def.version)) fail("cyclic_dependency")
        try {
            val actual = infer(def.body, def.inputs, emptyMap(), active, callDepth, 0)
            if (actual != def.output) fail("return_type_mismatch")
        } finally {
            active.remove(def.id to def.version)
        }
    }

    private fun infer(
        e: Expr, params: Map<String, CapType>, locals: Map<String, CapType>,
        active: MutableSet<Pair<String, Int>>, callDepth: Int, astDepth: Int
    ): CapType {
        if (astDepth > 32) fail("ast_depth_limit")
        fun child(x: Expr): CapType = infer(x, params, locals, active, callDepth, astDepth + 1)
        fun isBool(x: Expr) { if (child(x) != CapType.Bool3) fail("expected_bool3") }
        return when (e) {
            is Expr.Arg -> {
                if (params[e.name] != e.type) fail("arg_type_mismatch")
                e.type
            }
            is Expr.Const -> when (e.value) {
                is CapValue.Bool -> CapType.Bool3
                is CapValue.Str -> CapType.Str
                is CapValue.I64 -> CapType.Int64
                is CapValue.Rec -> fail("record_literal_not_supported")
            }
            is Expr.Field -> {
                val r = child(e.record) as? CapType.Record ?: fail("expected_record")
                r.fields[e.name] ?: fail("unknown_field")
            }
            is Expr.Eq -> {
                val l = child(e.left)
                val r = child(e.right)
                if (l != r) fail("eq_type_mismatch")
                if (l is CapType.Record) fail("eq_record_not_supported")
                CapType.Bool3
            }
            is Expr.And -> { isBool(e.left); isBool(e.right); CapType.Bool3 }
            is Expr.Or -> { isBool(e.left); isBool(e.right); CapType.Bool3 }
            is Expr.Not -> { isBool(e.value); CapType.Bool3 }
            is Expr.If -> {
                isBool(e.condition)
                val a = child(e.whenTrue)
                val b = child(e.whenFalse)
                if (a != b) fail("branch_type_mismatch")
                a
            }
            is Expr.Let -> {
                if (e.name.isBlank() || e.name in params || e.name in locals) fail("invalid_binding")
                val valueType = child(e.value)
                infer(e.body, params, locals + (e.name to valueType), active, callDepth, astDepth + 1)
            }
            is Expr.Ref -> locals[e.name] ?: fail("unbound_ref")
            is Expr.Call -> {
                val target = resolver.resolve(e.id, e.version) ?: fail("missing_dependency")
                // 2026-11-04 审查修正：先确保被调定义可规范化，再比对 hash；不可规范化统一报结构化错误。
                val targetHash = try {
                    target.contentHash()
                } catch (_: IllegalStateException) {
                    fail("invalid_dependency_definition")
                } catch (_: IllegalArgumentException) {
                    fail("invalid_dependency_definition")
                }
                if (targetHash != e.bodyHash) fail("dependency_hash_mismatch")
                if (target.inputs.keys != e.args.keys) fail("call_argument_mismatch")
                for ((name, argument) in e.args) {
                    if (child(argument) != target.inputs[name]) fail("call_argument_type_mismatch")
                }
                checkDefinition(target, active, callDepth + 1)
                target.output
            }
        }
    }

    private fun fail(code: String): Nothing = throw CapabilityCheckException(code)
}
