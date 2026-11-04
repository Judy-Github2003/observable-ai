// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 有限域与三值逻辑的性质测试；只验证语义，不重复场景测试。
package logic.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryPropertyTest {

    private val schema = linkedMapOf(
        "颜色" to listOf("红色", "蓝色"),
        "形状" to listOf("圆形", "方形"),
    )

    @Test
    fun `domain assignments enumerate the full finite space`() {
        val assignments = ConsistencyEngine.domainAssignments(schema)
        assertEquals(4, assignments.size)
        assertEquals(4, ConsistencyEngine.domainSize(schema))
        assertEquals(4, assignments.map { it["颜色"] to it["形状"] }.distinct().size)
    }

    @Test
    fun `three valued logic treats missing attribute as unknown but false dominates`() {
        val redAndCircle = Rule(listOf(Clause("颜色", "红色"), Clause("形状", "圆形")))
        assertEquals(Tri.TRUE, RuleInterpreter.evaluate(redAndCircle, mapOf("颜色" to "红色", "形状" to "圆形")))
        assertEquals(Tri.FALSE, RuleInterpreter.evaluate(redAndCircle, mapOf("颜色" to "蓝色", "形状" to "圆形")))
        assertEquals(Tri.UNKNOWN, RuleInterpreter.evaluate(redAndCircle, mapOf("颜色" to "红色")))
        // 已知 false 优先于缺失，不能被 unknown 掩盖。
        assertEquals(Tri.FALSE, RuleInterpreter.evaluate(redAndCircle, mapOf("形状" to "方形")))
    }

    @Test
    fun `clause order does not change semantics and equivalence is exhaustive`() {
        val left = Rule(listOf(Clause("颜色", "红色"), Clause("形状", "圆形")))
        val right = Rule(listOf(Clause("形状", "圆形"), Clause("颜色", "红色"))).canonical(schema.keys.toList())
        assertTrue(ConsistencyEngine.equivalent(left, right, schema))
        assertEquals(left.hash(), right.hash(), "规范化后同一规则应有同一 hash")

        val redOnly = Rule(listOf(Clause("颜色", "红色")))
        assertFalse(ConsistencyEngine.equivalent(redOnly, left, schema), "红 vs 红且圆在红方上不同")
    }

    @Test
    fun `equivalence classes partition surviving candidates`() {
        val candidates = listOf(
            Rule(listOf(Clause("颜色", "红色"))),
            Rule(listOf(Clause("形状", "圆形"))),
            Rule(listOf(Clause("颜色", "红色"), Clause("形状", "圆形"))),
        )
        val classes = ConsistencyEngine.equivalenceClasses(candidates, schema)
        assertEquals(3, classes.size)
        assertEquals(candidates.size, classes.sumOf { it.size })
    }

    @Test
    fun `conflict detection keeps both labels`() {
        val observations = listOf(
            Observation("A", true, "e1", "teacher_oracle", ""),
            Observation("A", false, "e2", "teacher_oracle", ""),
            Observation("B", false, "e3", "teacher_oracle", ""),
        )
        assertEquals(listOf("A"), ConsistencyEngine.conflictingObjects(observations))
    }

    @Test
    fun `promoted rule json round trip keeps domain size`() {
        val promoted = PromotedRule("id-1", Rule(listOf(Clause("字段", "值"))), listOf("e-1"), "2026-11-04", 12)
        val restored = PromotedRule.fromJson(promoted.toJson())
        assertEquals("id-1", restored.ruleId)
        assertEquals(12, restored.domainSize, "domain_size 不能在反序列化时丢失")
        assertEquals(promoted.rule.hash(), restored.rule.hash())
    }
}
