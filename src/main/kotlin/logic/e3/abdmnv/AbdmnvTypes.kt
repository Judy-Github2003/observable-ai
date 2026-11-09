// 实现者：DeepSeek Harness，2026-11-09，ABDMNV-01 M1 切片。
// 最小类型检查：Sort 推导 + 概念 kind/正文/深度约束。错误以封闭码返回，不抛异常。
package logic.e3.abdmnv

sealed interface AbdmnvTypeResult {
    data class Ok(val sort: AbdmnvSort) : AbdmnvTypeResult
    data class Bad(val code: String) : AbdmnvTypeResult
}

data class AbdmnvSymbols(
    val attributes: Map<String, AbdmnvSort> = emptyMap(),
    val relations: Map<String, AbdmnvSort> = emptyMap(),
    val concepts: Map<AbdmnvVersionRef, AbdmnvConcept> = emptyMap(),
)

class AbdmnvTypeChecker(
    private val symbols: AbdmnvSymbols,
    private val maxDepth: Int = 4,
) {
    fun checkConcept(c: AbdmnvConcept): String? {
        if (c.depth() > maxDepth) return "DEPTH_LIMIT"
        if (c.params.map { it.name }.size != c.params.map { it.name }.distinct().size) return "DUPLICATE_PARAM"
        val body = c.body
        when (c.kind) {
            AbdmnvKind.ATTRIBUTE -> {
                if (body !is AbdmnvBody.Channel) return "ATTRIBUTE_REQUIRES_CHANNEL"
                if (c.params.isNotEmpty()) return "ATTRIBUTE_HAS_PARAMS"
                if (c.sort !is AbdmnvSort.Bool && c.sort !is AbdmnvSort.Int64 && c.sort !is AbdmnvSort.Enum) {
                    return "ATTRIBUTE_BAD_SORT"
                }
            }

            AbdmnvKind.CATEGORY -> {
                if (body !is AbdmnvBody.Lambda || body.params.size != 1) return "CATEGORY_REQUIRES_UNARY_LAMBDA"
                if (c.sort !is AbdmnvSort.Bool) return "CATEGORY_SORT_NOT_BOOL"
            }

            AbdmnvKind.RELATION -> {
                if (body !is AbdmnvBody.Lambda || body.params.size != 2) return "RELATION_REQUIRES_BINARY_LAMBDA"
                val s = c.sort
                if (s !is AbdmnvSort.Rel || s.left !is AbdmnvSort.Obj || s.right !is AbdmnvSort.Obj) {
                    return "RELATION_BAD_SORT"
                }
            }

            AbdmnvKind.COMPOSITE -> {
                if (body is AbdmnvBody.Lambda && !containsApply(body.body)) return "COMPOSITE_WITHOUT_APPLY"
            }
        }
        val env = mutableMapOf<String, AbdmnvSort>()
        c.params.forEach { p -> env[p.name] = p.sort }
        if (body is AbdmnvBody.Lambda) body.params.forEach { p -> env[p] = AbdmnvSort.Obj }
        val bodyTerm: AbdmnvTerm? = when (body) {
            is AbdmnvBody.Lambda -> body.body
            is AbdmnvBody.Channel -> null
        }
        if (bodyTerm != null) {
            when (val r = checkTerm(bodyTerm, env)) {
                is AbdmnvTypeResult.Bad -> return r.code
                is AbdmnvTypeResult.Ok -> when (c.kind) {
                    AbdmnvKind.CATEGORY -> if (r.sort !is AbdmnvSort.Bool) return "CATEGORY_BODY_NOT_BOOL"
                    AbdmnvKind.RELATION -> if (r.sort !is AbdmnvSort.Bool) return "RELATION_BODY_NOT_BOOL"
                    else -> Unit
                }
            }
        }
        return null
    }

    fun checkTerm(t: AbdmnvTerm, env: Map<String, AbdmnvSort>): AbdmnvTypeResult {
        return when (t) {
        is AbdmnvTerm.Var -> env[t.name]?.let { AbdmnvTypeResult.Ok(it) } ?: AbdmnvTypeResult.Bad("unbound_var")
        is AbdmnvTerm.ConstBool -> AbdmnvTypeResult.Ok(AbdmnvSort.Bool)
        is AbdmnvTerm.ConstObj -> AbdmnvTypeResult.Ok(AbdmnvSort.Obj)
        is AbdmnvTerm.ConstInt -> AbdmnvTypeResult.Ok(AbdmnvSort.Int64)
        is AbdmnvTerm.SetLit -> AbdmnvTypeResult.Ok(AbdmnvSort.SetOf(AbdmnvSort.Obj))
        is AbdmnvTerm.Attr -> {
            val on = checkTerm(t.on, env)
            if (on is AbdmnvTypeResult.Bad) return on
            if ((on as AbdmnvTypeResult.Ok).sort !is AbdmnvSort.Obj) return AbdmnvTypeResult.Bad("ATTRIBUTE_ON_NON_OBJECT")
            symbols.attributes[t.name]?.let { AbdmnvTypeResult.Ok(it) }
                ?: AbdmnvTypeResult.Bad("UNKNOWN_ATTRIBUTE:${t.name}")
        }

        is AbdmnvTerm.Rel -> {
            val a = checkTerm(t.a, env)
            val b = checkTerm(t.b, env)
            if (a is AbdmnvTypeResult.Bad) return a
            if (b is AbdmnvTypeResult.Bad) return b
            if ((a as AbdmnvTypeResult.Ok).sort !is AbdmnvSort.Obj) return AbdmnvTypeResult.Bad("RELATION_ON_NON_OBJECT")
            if ((b as AbdmnvTypeResult.Ok).sort !is AbdmnvSort.Obj) return AbdmnvTypeResult.Bad("RELATION_ON_NON_OBJECT")
            val declared = symbols.relations[t.name] ?: return AbdmnvTypeResult.Bad("UNKNOWN_RELATION:$t.name")
            if (declared !is AbdmnvSort.Rel) return AbdmnvTypeResult.Bad("RELATION_SYMBOL_NOT_REL")
            AbdmnvTypeResult.Ok(AbdmnvSort.Bool)
        }

        is AbdmnvTerm.Apply -> {
            val c = symbols.concepts[t.ref] ?: return AbdmnvTypeResult.Bad("unknown_concept")
            if (c.params.size != t.args.size) return AbdmnvTypeResult.Bad("arity_mismatch")
            for ((i, arg) in t.args.withIndex()) {
                val r = checkTerm(arg, env)
                if (r is AbdmnvTypeResult.Bad) return r
                if ((r as AbdmnvTypeResult.Ok).sort != c.params[i].sort) {
                    return AbdmnvTypeResult.Bad("argument_sort_mismatch")
                }
            }
            AbdmnvTypeResult.Ok(c.sort)
        }

        is AbdmnvTerm.Not -> boolOf(checkTerm(t.value, env))
        is AbdmnvTerm.And -> boolOf(checkTerm(t.left, env)).let {
            if (it is AbdmnvTypeResult.Bad) it else boolOf(checkTerm(t.right, env))
        }

        is AbdmnvTerm.Or -> boolOf(checkTerm(t.left, env)).let {
            if (it is AbdmnvTypeResult.Bad) it else boolOf(checkTerm(t.right, env))
        }

        is AbdmnvTerm.Eq -> {
            val l = checkTerm(t.left, env)
            if (l is AbdmnvTypeResult.Bad) return l
            val r = checkTerm(t.right, env)
            if (r is AbdmnvTypeResult.Bad) return r
            if ((l as AbdmnvTypeResult.Ok).sort != (r as AbdmnvTypeResult.Ok).sort) {
                return AbdmnvTypeResult.Bad("EQ_SORT_MISMATCH")
            }
            AbdmnvTypeResult.Ok(AbdmnvSort.Bool)
        }

        is AbdmnvTerm.Lt -> {
            val l = checkTerm(t.left, env)
            val r = checkTerm(t.right, env)
            if (l is AbdmnvTypeResult.Bad) return l
            if (r is AbdmnvTypeResult.Bad) return r
            if ((l as AbdmnvTypeResult.Ok).sort !is AbdmnvSort.Int64) return AbdmnvTypeResult.Bad("LT_REQUIRES_INT")
            if ((r as AbdmnvTypeResult.Ok).sort !is AbdmnvSort.Int64) return AbdmnvTypeResult.Bad("LT_REQUIRES_INT")
            AbdmnvTypeResult.Ok(AbdmnvSort.Bool)
        }

        is AbdmnvTerm.Member -> {
            val e = checkTerm(t.element, env)
            val s = checkTerm(t.set, env)
            if (e is AbdmnvTypeResult.Bad) return e
            if (s is AbdmnvTypeResult.Bad) return s
            val setSort = (s as AbdmnvTypeResult.Ok).sort
            if (setSort !is AbdmnvSort.SetOf) return AbdmnvTypeResult.Bad("MEMBER_ON_NON_SET")
            if ((e as AbdmnvTypeResult.Ok).sort != setSort.element) return AbdmnvTypeResult.Bad("MEMBER_SORT_MISMATCH")
            AbdmnvTypeResult.Ok(AbdmnvSort.Bool)
        }

        is AbdmnvTerm.Exists -> quantified(t.varName, t.over, t.body, env)
        is AbdmnvTerm.Forall -> quantified(t.varName, t.over, t.body, env)
        is AbdmnvTerm.Count -> quantified(t.varName, t.over, t.body, env)
        }
    }

    private fun quantified(varName: String, over: AbdmnvSort, body: AbdmnvTerm, env: Map<String, AbdmnvSort>): AbdmnvTypeResult {
        if (over !is AbdmnvSort.Obj) return AbdmnvTypeResult.Bad("UNSUPPORTED_DOMAIN")
        val r = checkTerm(body, env + (varName to over))
        return boolOf(r)
    }

    private fun boolOf(r: AbdmnvTypeResult): AbdmnvTypeResult = when (r) {
        is AbdmnvTypeResult.Bad -> r
        is AbdmnvTypeResult.Ok -> if (r.sort is AbdmnvSort.Bool) r else AbdmnvTypeResult.Bad("BOOL_REQUIRED")
    }

    private fun containsApply(t: AbdmnvTerm): Boolean = when (t) {
        is AbdmnvTerm.Apply -> true
        is AbdmnvTerm.Not -> containsApply(t.value)
        is AbdmnvTerm.And -> containsApply(t.left) || containsApply(t.right)
        is AbdmnvTerm.Or -> containsApply(t.left) || containsApply(t.right)
        is AbdmnvTerm.Eq -> containsApply(t.left) || containsApply(t.right)
        is AbdmnvTerm.Lt -> containsApply(t.left) || containsApply(t.right)
        is AbdmnvTerm.Member -> containsApply(t.element) || containsApply(t.set)
        is AbdmnvTerm.Attr -> containsApply(t.on)
        is AbdmnvTerm.Rel -> containsApply(t.a) || containsApply(t.b)
        is AbdmnvTerm.Exists -> containsApply(t.body)
        is AbdmnvTerm.Forall -> containsApply(t.body)
        is AbdmnvTerm.Count -> containsApply(t.body)
        else -> false
    }
}

