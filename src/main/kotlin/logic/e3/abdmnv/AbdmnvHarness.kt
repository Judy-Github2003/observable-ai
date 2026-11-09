// 实现者：DeepSeek Harness，2026-11-09，ABDMNV-01 M1 切片。
// 公开 harness：T2/T3/T5 的最小机械检查（不涉及隐藏真值）。
package logic.e3.abdmnv

import logic.capability.CapValue
import logic.capability.EvalResult

object AbdmnvHarness {
    fun containsObjectConstant(t: AbdmnvTerm): Boolean = when (t) {
        is AbdmnvTerm.ConstObj -> true
        is AbdmnvTerm.Attr -> containsObjectConstant(t.on)
        is AbdmnvTerm.Rel -> containsObjectConstant(t.a) || containsObjectConstant(t.b)
        is AbdmnvTerm.Apply -> t.args.any(::containsObjectConstant)
        is AbdmnvTerm.Not -> containsObjectConstant(t.value)
        is AbdmnvTerm.And -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is AbdmnvTerm.Or -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is AbdmnvTerm.Eq -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is AbdmnvTerm.Lt -> containsObjectConstant(t.left) || containsObjectConstant(t.right)
        is AbdmnvTerm.Member -> containsObjectConstant(t.element) || containsObjectConstant(t.set)
        is AbdmnvTerm.Exists -> containsObjectConstant(t.body)
        is AbdmnvTerm.Forall -> containsObjectConstant(t.body)
        is AbdmnvTerm.Count -> containsObjectConstant(t.body)
        else -> false
    }

    /** T2：概念里出现具体对象常量 → 视为 ID 依赖（v2 §6.8 T2 的最小公开检查）。 */
    fun idDependent(c: AbdmnvConcept): Boolean {
        val body = c.body
        return body is AbdmnvBody.Lambda && containsObjectConstant(body.body)
    }

    fun outputs(c: AbdmnvConcept, world: AbdmnvWorld, objects: List<String>): List<EvalResult> {
        val body = c.body as? AbdmnvBody.Lambda ?: return emptyList()
        val ev = AbdmnvEvaluator(world)
        return objects.map { obj -> ev.eval(body.body, mapOf(body.params[0] to abdStr(obj))) }
    }

    /** T3：同一概念在两个只差隐藏属性值的世界上，行为是否随证据变化。 */
    fun behaviorallySensitive(c: AbdmnvConcept, worldA: AbdmnvWorld, worldB: AbdmnvWorld, objects: List<String>): Boolean =
        outputs(c, worldA, objects) != outputs(c, worldB, objects)

    /** T5：两个目标在验证对象上行为等价 → 目标歧义（应 UNSCORABLE，而不是“平凡概念”）。 */
    fun ambiguousTargets(t1: AbdmnvConcept, t2: AbdmnvConcept, world: AbdmnvWorld, objects: List<String>): Boolean {
        val a = outputs(t1, world, objects)
        val b = outputs(t2, world, objects)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.any { it !is EvalResult.Known } || b.any { it !is EvalResult.Known }) return false
        return a.indices.all { i ->
            (a[i] as EvalResult.Known).value == (b[i] as EvalResult.Known).value
        }
    }

    fun truthOn(c: AbdmnvConcept, world: AbdmnvWorld, objects: List<String>): List<Boolean?> =
        outputs(c, world, objects).map { r ->
            when (r) {
                is EvalResult.Known -> (r.value as? CapValue.Bool)?.value
                else -> null
            }
        }
}
