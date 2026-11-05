// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 公平预算计量；区分估计/实际 Filter 工作、查询请求与成功探测。
package logic.e3

data class E3UsageSnapshot(
    val composerCalls: Int,
    val generatedCandidates: Int,
    val estimatedFilterEvaluations: Int,
    val observedFilterExampleEvaluations: Int,
    val observedFilterQueryDomainEvaluations: Int,
    val representativeFilterQueryDomainEvaluations: Int,
    val preCompressionConsistentCandidates: Int,
    val interpreterExecutions: Int,
    val publicInstanceRequests: Int,
    val repeatRequests: Int,
    val totalProbeCost: Int,
    val successfulProbeCount: Int,
    val independentEvidenceCount: Int,
    val elapsedNanos: Long,
)

/** 单线程实验计量器；只做计数，不参与诊断结论。 */
class E3UsageMeter {
    private var composerCalls = 0
    private var generatedCandidates = 0
    private var estimatedFilterEvaluations = 0
    private var observedFilterExampleEvaluations = 0
    private var observedFilterQueryDomainEvaluations = 0
    private var representativeFilterQueryDomainEvaluations = 0
    private var preCompressionConsistentCandidates = 0
    private var interpreterExecutions = 0
    private var publicInstanceRequests = 0
    private var repeatRequests = 0
    private var totalProbeCost = 0
    private var successfulProbeCount = 0
    private var independentEvidenceCount = 0
    private val startedAt = System.nanoTime()

    fun recordCompose(generated: Int) {
        composerCalls++
        generatedCandidates += generated
    }

    /**
     * estimated：按“全部候选 × 样本数”的上界；
     * observed：Filter 实际返回的 evaluations 数 × 样本数（早期拒绝时 evaluations 为空）。
     */
    fun recordFilter(
        estimatedCandidateCount: Int,
        exampleCount: Int,
        observedCandidateCount: Int,
        observedDomainEvaluations: Int,
        representativeDomainEvaluations: Int,
        consistentCandidateCount: Int,
    ) {
        estimatedFilterEvaluations += estimatedCandidateCount * exampleCount
        observedFilterExampleEvaluations += observedCandidateCount * exampleCount
        observedFilterQueryDomainEvaluations += observedDomainEvaluations
        representativeFilterQueryDomainEvaluations += representativeDomainEvaluations
        preCompressionConsistentCandidates += consistentCandidateCount
    }

    fun recordInterpreter(executions: Int) {
        interpreterExecutions += executions
    }

    fun recordPublicInstanceRequest() {
        publicInstanceRequests++
    }

    fun recordRepeatRequest() {
        repeatRequests++
    }

    /** 只对成功写入新观察的记录计费；失败/重复请求 cost=0。 */
    fun recordProbeSuccess(cost: Int, independent: Boolean) {
        totalProbeCost += cost
        successfulProbeCount++
        if (independent) independentEvidenceCount++
    }

    fun snapshot(): E3UsageSnapshot = E3UsageSnapshot(
        composerCalls = composerCalls,
        generatedCandidates = generatedCandidates,
        estimatedFilterEvaluations = estimatedFilterEvaluations,
        observedFilterExampleEvaluations = observedFilterExampleEvaluations,
        observedFilterQueryDomainEvaluations = observedFilterQueryDomainEvaluations,
        representativeFilterQueryDomainEvaluations = representativeFilterQueryDomainEvaluations,
        preCompressionConsistentCandidates = preCompressionConsistentCandidates,
        interpreterExecutions = interpreterExecutions,
        publicInstanceRequests = publicInstanceRequests,
        repeatRequests = repeatRequests,
        totalProbeCost = totalProbeCost,
        successfulProbeCount = successfulProbeCount,
        independentEvidenceCount = independentEvidenceCount,
        elapsedNanos = System.nanoTime() - startedAt,
    )
}
