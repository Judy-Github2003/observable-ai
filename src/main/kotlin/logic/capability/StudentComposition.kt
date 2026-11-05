// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M3-1c 把自动组合出的候选按 student_composed 提交为 candidate，再走 M2b 验证/激活门禁。
// 边界：组合与筛选由开发者算法完成；只有具体候选被证据选中才记 student_composed，不得声称学生发明了搜索算法。
package logic.capability

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 组合流程停在哪个阶段；每个阶段都如实返回，失败/歧义/拒答不伪装成成功。 */
enum class CompositionStage { PROPOSED, AMBIGUOUS, REFUSED, REJECTED, ACTIVATED }

data class HeldOutEvaluation(
    val total: Int,
    val matches: Int,
    val mismatches: Int,
    val unknowns: Int,
    val errors: Int,
    val executionErrors: Int = 0,
)

internal data class CompositionResult(
    val stage: CompositionStage,
    val capabilityId: String?,
    val capabilityVersion: Int?,
    val candidate: ComposedCandidate?,
    val evidence: EvidenceResult?,
    val verification: VerificationOutcome?,
    val activation: ActivationResult?,
    val code: String?,
    val detail: String?,
    /** 搜索实际加入池的表达式数，便于报告“搜了多少”。 */
    val searchCandidates: Int,
    val budgetReason: String?,
    /** 组合+筛选的墙钟耗时，仅作报告信息，不参与任何判定。 */
    val elapsedMillis: Long = 0,
)

/**
 * 组合流水线：
 * 1) 从注册表取“已激活且依赖健康”的库快照；
 * 2) 有界搜索 + 公开样本筛选；
 * 3) 只有 IDENTIFIED 的候选才提交为 candidate，provenance 记 student_composed；
 * 4) 验证与激活必须由调用方显式执行，搜索本身不能写 verified/active。
 */
internal class StudentCompositionService(
    private val registry: SqliteCapabilityRegistry,
    private val suites: TrustedSuiteRegistry,
    private val composer: CapabilityComposer = CapabilityComposer(),
) {

    fun composeAndPropose(
        capabilityId: String,
        version: Int,
        inputs: Map<String, CapType>,
        output: CapType,
        constants: List<CapValue>,
        examples: List<PublicExample>,
        queryValues: Map<String, List<CapValue>> = emptyMap(),
        composeBudget: ComposeBudget = ComposeBudget(),
        evalBudget: EvalBudget = EvalBudget(),
    ): CompositionResult {
        val startedAt = System.nanoTime()
        val library = registry.activeCapabilities()
        val composed = composer.compose(
            ComposeRequest(inputs = inputs, output = output, constants = constants, library = library, budget = composeBudget),
        )
        // 搜索被候选上限截断时，残缺候选不能用来声称辨识完成。
        val searchExhausted = composed.reason == "candidate_limit"
        val evidence = CandidateEvidenceFilter(registry).filter(
            inputs = inputs,
            candidates = composed.candidates,
            examples = examples,
            queryValues = queryValues,
            budget = evalBudget,
            searchExhausted = searchExhausted,
        )
        if (evidence.status != EvidenceStatus.IDENTIFIED) {
            val stage = if (evidence.status == EvidenceStatus.AMBIGUOUS) CompositionStage.AMBIGUOUS else CompositionStage.REFUSED
            return CompositionResult(
                stage = stage,
                capabilityId = capabilityId,
                capabilityVersion = version,
                candidate = null,
                evidence = evidence,
                verification = null,
                activation = null,
                code = evidence.reason,
                detail = null,
                searchCandidates = composed.generated,
                budgetReason = composed.reason,
                elapsedMillis = elapsed(startedAt),
            )
        }

        val candidate = evidence.survivors.single()
        val definition = CapabilityDefinition(capabilityId, version, inputs, output, candidate.body)
        val provenance = buildJsonObject {
            put("acquisition_method", "student_composed")
            put("proposer", "local_composer")
            put("teacher_calls", 0)
            put("composer_source", candidate.source)
            put("search_candidates", composed.generated)
            put("budget_reason", composed.reason ?: "not_exhausted")
            put("public_examples", examples.size)
        }.toString()
        val proposal = try {
            registry.propose(definition, provenance)
        } catch (_: Exception) {
            RegistryResult(false, "proposal_failed", null)
        }
        if (!proposal.ok) {
            return CompositionResult(
                stage = CompositionStage.REJECTED,
                capabilityId = capabilityId,
                capabilityVersion = version,
                candidate = candidate,
                evidence = evidence,
                verification = null,
                activation = null,
                code = proposal.code ?: "proposal_failed",
                detail = null,
                searchCandidates = composed.generated,
                budgetReason = composed.reason,
                elapsedMillis = elapsed(startedAt),
            )
        }
        return CompositionResult(
            stage = CompositionStage.PROPOSED,
            capabilityId = capabilityId,
            capabilityVersion = version,
            candidate = candidate,
            evidence = evidence,
            verification = null,
            activation = null,
            code = null,
            detail = null,
            searchCandidates = composed.generated,
            budgetReason = composed.reason,
            elapsedMillis = elapsed(startedAt),
        )
    }

    /** 独立验证套件通过并激活；任何一步失败都返回 REJECTED，不跳过门禁。 */
    fun verifyAndActivate(
        capabilityId: String,
        version: Int,
        suiteId: String,
        suiteVersion: Int,
        evalBudget: EvalBudget = EvalBudget(),
    ): CompositionResult {
        val verification = CapabilityVerificationService(registry, suites)
            .verify(capabilityId, version, suiteId, suiteVersion, evalBudget)
        if (!verification.recorded) {
            return rejected(capabilityId, version, null, verification.outcome, null, verification.code ?: "verification_not_recorded")
        }
        val outcome = verification.outcome
        if (outcome.verdict != VerificationVerdict.PASS) {
            return rejected(capabilityId, version, null, verification.outcome, null, outcome.code ?: "verification_failed")
        }
        val activation = CapabilityActivationService(registry, suites).activate(capabilityId, version, verification.runId!!)
        if (!activation.ok) {
            return rejected(capabilityId, version, null, verification.outcome, activation, activation.code ?: "activation_failed")
        }
        return CompositionResult(
            stage = CompositionStage.ACTIVATED,
            capabilityId = capabilityId,
            capabilityVersion = version,
            candidate = null,
            evidence = null,
            verification = outcome,
            activation = activation,
            code = null,
            detail = activation.detail,
            searchCandidates = 0,
            budgetReason = null,
            elapsedMillis = 0,
        )
    }

    private fun elapsed(): Long = 0

    private fun elapsed(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun rejected(
        capabilityId: String,
        version: Int,
        candidate: ComposedCandidate?,
        verification: VerificationOutcome,
        activation: ActivationResult?,
        code: String,
    ) = CompositionResult(
        stage = CompositionStage.REJECTED,
        capabilityId = capabilityId,
        capabilityVersion = version,
        candidate = candidate,
        evidence = null,
        verification = verification,
        activation = activation,
        code = code,
        detail = activation?.detail,
        searchCandidates = 0,
        budgetReason = null,
        elapsedMillis = 0,
    )
}

/** 留出集评估：只在组合/筛选完成后调用，不参与候选生成；结果用于报告而非学习。 */
class HeldOutEvaluator(private val resolver: CapabilityResolver) {

    fun evaluate(
        capabilityId: String,
        version: Int,
        examples: List<PublicExample>,
        budget: EvalBudget = EvalBudget(),
    ): HeldOutEvaluation {
        val definition = resolver.resolve(capabilityId, version)
            ?: return HeldOutEvaluation(examples.size, 0, 0, 0, 0, executionErrors = 1)
        val interpreter = CapabilityInterpreter(resolver)
        var matches = 0
        var mismatches = 0
        var unknowns = 0
        var errors = 0
        for (example in examples) {
            val arguments = example.inputs.mapValues { (_, value) -> EvalResult.Known(value) }
            val actual = try {
                interpreter.execute(definition, arguments, budget)
            } catch (_: Exception) {
                EvalResult.Error("interpreter_exception")
            }
            when (actual) {
                is EvalResult.Known -> if (actual.value == example.expected) matches++ else mismatches++
                is EvalResult.Unknown -> unknowns++
                is EvalResult.Error -> errors++
            }
        }
        return HeldOutEvaluation(examples.size, matches, mismatches, unknowns, errors)
    }
}
