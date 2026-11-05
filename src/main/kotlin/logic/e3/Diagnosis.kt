// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A1 诊断输出契约；允许多原因并存与 UNDETERMINED，不包含世界标签或隐藏真值。
package logic.e3

enum class FindingKind {
    SUPPORTED_WITHIN_BOUNDED_H,
    MULTIPLE_HYPOTHESES,
    OBSERVED_LABEL_COLLISION,
    SEARCH_TRUNCATED,
    LABEL_INSTABILITY_OBSERVED,
    UNDETERMINED,
}

/** 结论适用范围：假设空间、观察域、查询域分开写，避免把“有限域唯一”升级成全局真理。 */
data class EvidenceScope(
    val hypothesisSpace: String,
    val observationDomain: String,
    val queryDomain: String? = null,
)

data class DiagnosticFinding(
    val kind: FindingKind,
    val evidenceIds: List<String>,
    val scope: EvidenceScope,
    /** 产生该结论的源假设（可多个），与观察结论分开。 */
    val sourceHypotheses: List<String> = emptyList(),
    /** 从证据直接得到的观察结论；不要求解释真实世界原因。 */
    val observationConclusion: String? = null,
    val assumptions: List<String> = emptyList(),
    val alternativeExplanations: List<String> = emptyList(),
)

/**
 * 诊断报告：
 * - findings 可多标签共存；UNDETERMINED 不是失败，而是“现有证据不足以区分”；
 * - strategySource 固定为 designer_primitive；具体世界的判断才归 student_diagnosed；
 * - 绝不包含 worldKind/hiddenFeature/targetAst 字段。
 */
data class DiagnosticReport(
    val findings: List<DiagnosticFinding>,
    val proposedProbe: ProbeRequest?,
    val stoppedBecause: String,
    val strategySource: String = E3_STRATEGY_SOURCE,
) {
    init {
        require(findings.isNotEmpty()) { "diagnostic_report_requires_finding" }
        require(stoppedBecause.isNotBlank()) { "diagnostic_report_requires_stop_reason" }
    }
}
