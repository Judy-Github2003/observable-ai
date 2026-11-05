// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 公平预算计量；不修改 B0，只在适配器/诊断器边界累计实际调用。
package logic.e3

data class E3UsageSnapshot(
    val composerCalls: Int,
    val generatedCandidates: Int,
    val filterCandidateEvaluations: Int,
    val interpreterExecutions: Int,
    val publicQueries: Int,
    val queryCost: Int,
    val elapsedNanos: Long,
)

/** 单线程实验计量器；只做计数，不参与诊断结论。 */
class E3UsageMeter {
    private var composerCalls = 0
    private var generatedCandidates = 0
    private var filterCandidateEvaluations = 0
    private var interpreterExecutions = 0
    private var publicQueries = 0
    private var queryCost = 0
    private var startedAt = System.nanoTime()

    fun recordCompose(generated: Int) {
        composerCalls++
        generatedCandidates += generated
    }

    fun recordFilter(candidateCount: Int, exampleCount: Int) {
        filterCandidateEvaluations += candidateCount * exampleCount
    }

    fun recordInterpreter(executions: Int) {
        interpreterExecutions += executions
    }

    fun recordQuery(cost: Int = 1) {
        publicQueries++
        queryCost += cost
    }

    fun snapshot(): E3UsageSnapshot = E3UsageSnapshot(
        composerCalls = composerCalls,
        generatedCandidates = generatedCandidates,
        filterCandidateEvaluations = filterCandidateEvaluations,
        interpreterExecutions = interpreterExecutions,
        publicQueries = publicQueries,
        queryCost = queryCost,
        elapsedNanos = System.nanoTime() - startedAt,
    )
}
