// 合并实现（DeepSeek Harness, 2026-11-09）：T2/T3/T5 公开 harness，接到参考实现的 Term/StudentView。
package observable.ai.abdmnv.m1

object CombinedHarness {
    fun containsObjectConstant(t: Term): Boolean = when (t) {
        is Term.Constant -> t.value is TypedValue.ObjectId
        is Term.Attribute -> containsObjectConstant(t.of)
        is Term.PairRelation -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is Term.Call -> t.args.any(::containsObjectConstant)
        is Term.Not -> containsObjectConstant(t.child)
        is Term.And -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is Term.Or -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is Term.Eq -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is Term.LessThan -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is Term.Member -> containsObjectConstant(t.value) || containsObjectConstant(t.set)
        is Term.Exists -> containsObjectConstant(t.body)
        is Term.ForAll -> containsObjectConstant(t.body)
        is Term.CountAtLeast -> containsObjectConstant(t.body)
        is Term.Var -> false
    }

    /** T2：概念正文里出现具体对象常量 → ID 依赖（v2 §6.8 的最小公开检查）。 */
    fun idDependent(c: Concept): Boolean = containsObjectConstant(c.body)

    fun truths(c: Concept, view: StudentView, catalog: ConceptCatalog, objects: List<String>): List<Truth> {
        val evaluator = M1Evaluator(view, catalog)
        return objects.map { evaluator.evaluate(c, listOf(it)).truth() }
    }

    /** T3：同一概念在两个只差证据的世界上的行为是否改变（不要求 AST 改变）。 */
    fun behaviorallySensitive(
        c: Concept,
        viewA: StudentView,
        viewB: StudentView,
        catalog: ConceptCatalog,
        objects: List<String>,
    ): Boolean = truths(c, viewA, catalog, objects) != truths(c, viewB, catalog, objects)

    /** T5：两个目标在相同输入上真值完全相同 → 目标歧义（应 UNSCORABLE，而非判“平凡概念”）。 */
    fun ambiguousTargets(a: TargetSpec, b: TargetSpec): Boolean =
        a.inputs == b.inputs && a.truth == b.truth
}
