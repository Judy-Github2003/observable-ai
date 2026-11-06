// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02C 考官端精确有界覆盖；贪心失败不再自动等于预算内不可行。
package logic.e3.pack02

import kotlin.test.Test
import kotlin.test.assertEquals

class MRX2CExactCoverTest {
    @Test
    fun `budget one impossible budget two feasible and indistinguishable`() {
        val witness = mapOf("x" to true, "y" to true)
        val wrong = listOf(
            mapOf("x" to false, "y" to true),
            mapOf("x" to true, "y" to false),
        )
        val impossible = MRX2CExactCover.audit(witness, wrong, listOf("x", "y"), budget = 1)
        assertEquals(MRX2CCoverStatus.PROVED_IMPOSSIBLE_WITHIN_BUDGET, impossible.status)
        val feasible = MRX2CExactCover.audit(witness, wrong, listOf("x", "y"), budget = 2)
        assertEquals(MRX2CCoverStatus.FEASIBLE_WITHIN_BUDGET, feasible.status)
        assertEquals(setOf("x", "y"), feasible.queryKeys.toSet())
        val same = MRX2CExactCover.audit(
            witness, listOf(mapOf("x" to true, "y" to true)), listOf("x", "y"), budget = 2,
        )
        assertEquals(MRX2CCoverStatus.OBSERVATIONALLY_INDISTINGUISHABLE, same.status)
    }

    @Test
    fun `search limit returns unknown not impossibility`() {
        val witness = mapOf("x" to true)
        val wrong = (0 until 8).map { i ->
            mapOf("x" to false, "y" to (i % 3 == 0))
        }
        val result = MRX2CExactCover.audit(witness, wrong, listOf("x", "y"), budget = 1, nodeLimit = 0)
        assertEquals(MRX2CCoverStatus.UNKNOWN_SEARCH_LIMIT, result.status)
    }
}
