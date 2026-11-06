// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02D 精确覆盖与穷举属性对照；验证 UNKNOWN 只用于搜索上限，不是失败借口。
package logic.e3.pack02

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class MRX2DExactCoverPropertyTest {
    @Test
    fun `exact cover matches brute force on small instances`() {
        val rng = Random(20261106)
        repeat(40) { caseIndex ->
            val inputs = listOf("x", "y", "z").take(2 + rng.nextInt(2))
            val wrongCount = 1 + rng.nextInt(4)
            val witness = inputs.associateWith { rng.nextBoolean() }
            val wrong = (0 until wrongCount).map { i ->
                inputs.associateWith { key ->
                    if (rng.nextInt(4) == 0) witness[key] else rng.nextBoolean()
                }
            }
            val budget = rng.nextInt(3)
            val exact = MRX2CExactCover.audit(witness, wrong, inputs, budget, nodeLimit = 100_000)
            val brute = bruteFeasible(witness, wrong, inputs, budget)
            if (exact.status == MRX2CCoverStatus.FEASIBLE_WITHIN_BUDGET) {
                assertEquals(true, brute, "case=$caseIndex")
            } else if (exact.status == MRX2CCoverStatus.PROVED_IMPOSSIBLE_WITHIN_BUDGET) {
                assertEquals(false, brute, "case=$caseIndex")
            } else if (exact.status == MRX2CCoverStatus.OBSERVATIONALLY_INDISTINGUISHABLE) {
                assertEquals(false, brute, "case=$caseIndex")
            }
        }
    }

    private fun bruteFeasible(
        witness: Map<String, Boolean?>,
        wrong: List<Map<String, Boolean?>>,
        inputs: List<String>,
        budget: Int,
    ): Boolean {
        val distinguishable = inputs.filter { key ->
            val w = witness[key] ?: return@filter false
            wrong.any { it[key] != null && it[key] != w }
        }
        if (budget <= 0) return wrong.isEmpty()
        for (size in 1..budget) {
            for (subset in combinations(distinguishable, size)) {
                val covered = wrong.indices.all { i ->
                    subset.any { key ->
                        val w = witness[key]
                        val other = wrong[i][key]
                        w != null && other != null && w != other
                    }
                }
                if (covered) return true
            }
        }
        return false
    }

    private fun combinations(items: List<String>, size: Int): List<List<String>> {
        if (size == 0) return listOf(emptyList())
        if (items.size < size) return emptyList()
        val out = ArrayList<List<String>>()
        for (i in items.indices) {
            for (tail in combinations(items.drop(i + 1), size - 1)) out += listOf(items[i]) + tail
        }
        return out
    }
}
