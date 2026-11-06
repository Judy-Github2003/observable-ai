// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-EVAL-02 补充冻结考试；预注册 heldout 正反例覆盖，覆盖不合格不运行学生。
package logic.e3.pack02

data class Eval2Spec(val family: String, val seeds: List<Int>, val budget: Int)

data class Eval2Selection(val spec: Eval2Spec, val seed: Int?, val coverage: String)

object EvalExam2 {
    // 预注册；alternates 仅由覆盖率规则选择，不按成绩筛选。
    private val specs = listOf(
        Eval2Spec("and_ac", listOf(1101, 1201, 1301), 3),
        Eval2Spec("and_ac", listOf(1102, 1202, 1302), 3),
        Eval2Spec("or_ac", listOf(2101, 2201, 2301), 3),
        Eval2Spec("or_ac", listOf(2102, 2202, 2302), 3),
        Eval2Spec("outside_b2", listOf(3101, 3201, 3301), 3),
        Eval2Spec("and_ac", listOf(4101, 4201, 4301), 1),
    )

    fun choose(spec: Eval2Spec): Eval2Selection {
        for (seed in spec.seeds) {
            val w = ActiveWorlds.generate(seed, spec.family)
            val pos = w.heldout.count { it.label }
            val neg = w.heldout.size - pos
            if (pos > 0 && neg > 0) return Eval2Selection(spec, seed, "valid positives=$pos negatives=$neg")
        }
        return Eval2Selection(spec, null, "no pre-registered seed has both positive and negative heldout")
    }

    fun runAll(): Pair<List<EvalRun>, List<Eval2Selection>> {
        val out = ArrayList<EvalRun>()
        val selections = ArrayList<Eval2Selection>()
        for (spec in specs) {
            val selected = choose(spec)
            selections += selected
            val seed = selected.seed ?: continue
            for (strategy in listOf("active", "fixed", "random")) {
                val seeds = if (strategy == "random") listOf(11, 12, 13) else listOf(0)
                for (s in seeds) out += EvalExam.runOne(ExamSpec(seed, spec.family, spec.budget), strategy, s)
            }
        }
        return out to selections
    }
}
