// 修复轮（DeepSeek Harness, 2026-11-09）：概念内容哈希。
// 覆盖 v2 §6.11 的 definition + params + scope + deps（含 pinned hash）；不包含 id/version/evidence/自身 hash，
// 因此同一内容在不同对象 ID 下哈希稳定，而依赖 pin 改变必然改变哈希。
package observable.ai.abdmnv.m1

object CombinedConceptHashing {
    fun contentCanonical(c: Concept): String = buildString {
        append("(content")
        append(" (kind ").append(c.kind.name.lowercase()).append(")")
        append(" (output ").append(sortText(c.output)).append(")")
        append(" (params ").append(paramsText(c.params)).append(")")
        append(" (body ").append(termText(c.body)).append(")")
        append(" (scope ").append(if (c.worldScope == null) "(global)" else "(world ${c.worldScope})").append(")")
        append(" (deps ").append(depsText(c.dependencies)).append(")")
        append(")")
    }

    fun contentHash(c: Concept): String = CanonicalSExpr.sha256(contentCanonical(c))

    /** 把 ref.hash 绑到真实内容；已绑定的概念重复 seal 是幂等的。 */
    fun seal(c: Concept): Concept = c.copy(ref = c.ref.copy(hash = contentHash(c)))

    fun sortText(s: Sort): String = when (s) {
        Sort.Bool -> "bool"
        Sort.Int -> "int"
        Sort.Object -> "obj"
        is Sort.Enum -> "(enum ${s.name})"
        is Sort.Set -> "(set ${sortText(s.element)})"
    }

    fun paramsText(ps: List<Param>): String =
        "(" + ps.joinToString(" ") { "(${it.name} ${sortText(it.sort)})" } + ")"

    /** 依赖写 id@version#hash：pinned hash 进入内容哈希。 */
    fun depsText(ds: Set<ConceptRef>): String =
        "(" + ds.sortedWith(compareBy({ it.id }, { it.version }))
            .joinToString(" ") { "${it.id}@${it.version}#${it.hash}" } + ")"

    fun termText(t: Term): String = when (t) {
        is Term.Var -> "(var ${t.name})"
        is Term.Constant -> "(const ${valueText(t.value)})"
        is Term.Attribute -> "(attr ${t.channel} ${termText(t.of)})"
        is Term.PairRelation -> "(rel ${t.channel} ${termText(t.left)} ${termText(t.right)})"
        is Term.Call -> "(apply ${t.ref.id}@${t.ref.version}#${t.ref.hash}" +
            (if (t.args.isEmpty()) "" else " " + t.args.joinToString(" ") { termText(it) }) + ")"
        is Term.Not -> "(not ${termText(t.child)})"
        is Term.And -> "(and ${termText(t.left)} ${termText(t.right)})"
        is Term.Or -> "(or ${termText(t.left)} ${termText(t.right)})"
        is Term.Eq -> "(eq ${termText(t.left)} ${termText(t.right)})"
        is Term.LessThan -> "(lt ${termText(t.left)} ${termText(t.right)})"
        is Term.Member -> "(member ${termText(t.value)} ${termText(t.set)})"
        is Term.Exists -> "(exists (${t.variable} obj) ${termText(t.body)})"
        is Term.ForAll -> "(forall (${t.variable} obj) ${termText(t.body)})"
        is Term.CountAtLeast -> "(count>= (${t.variable} obj) ${termText(t.body)} ${t.minimum})"
    }

    fun valueText(v: TypedValue): String = when (v) {
        is TypedValue.Bool -> "bool:${v.value}"
        is TypedValue.IntNumber -> "int:${v.value}"
        is TypedValue.EnumSymbol -> "enum:${v.enumId}:${v.value}"
        is TypedValue.ObjectId -> "obj:${v.id}"
        is TypedValue.SetValue -> "set:{" + v.values.map { valueText(it) }.sorted().joinToString(",") + "}"
    }
}
