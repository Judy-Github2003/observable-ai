// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M5 验收测试 G01–G03；生成器确定性、切分与分组指标完整性，不评估研究结论。
package logic.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkTest {

    private val config = BenchmarkConfig(seed = 7L, tasksPerFamily = 2)

    @Test
    fun `G01 generator produces fixed task counts and train development split`() {
        val report = BenchmarkRunner(config).run()
        assertEquals(24, report.generatedTasks)
        assertEquals(12, report.trainTasks)
        assertEquals(12, report.developmentTasks)
        assertTrue(report.groups.all { it.total == 12 })
        assertEquals(listOf("A", "B", "C"), report.groups.map { it.group })
    }

    @Test
    fun `G02 same seed yields same per task outcomes`() {
        val first = BenchmarkRunner(config).run()
        val second = BenchmarkRunner(config).run()
        val a = first.groups.flatMap { group -> group.tasks.map { it.taskId to it.outcome } }
        val b = second.groups.flatMap { group -> group.tasks.map { it.taskId to it.outcome } }
        assertEquals(a, b)
    }

    @Test
    fun `G03 group counters add up and json is machine readable`() {
        val report = BenchmarkRunner(config).run()
        for (group in report.groups) {
            assertEquals(
                group.total,
                group.correct + group.wrong + group.unknown + group.error + group.ambiguous + group.refused,
            )
            assertTrue(group.searchCandidates > 0)
            assertTrue(group.staticCost >= group.taskCost)
        }
        val json = report.toJson()
        assertTrue(json.contains("\"generator_version\":\"b0-m5-v1\""))
        assertTrue(json.contains("\"group\":\"A\""))
        assertTrue(json.contains("\"tasks\""))
    }
}
