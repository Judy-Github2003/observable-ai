// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-2b 正式执行入口 executeActive：每次先过激活与传递依赖门禁，再交给 M1 解释器。
// 边界：同进程内的 resolve/解释器仍是内部开发与验证接口，不是对恶意调用者的硬隔离；权限沙箱留后续。
package logic.capability

/**
 * 正式执行结果：
 * - ok=true 表示“门禁通过且确实执行了”，不代表结果一定可判定；
 * - result 可能是 Known / Unknown / Error，调用方必须区分，不能把后两者当成功预测；
 * - ok=false 表示被门禁拒绝，code 给出具体原因（未激活、依赖失效、body 损坏等）。
 */
internal data class ActiveExecutionResult(
    val ok: Boolean,
    val code: String?,
    val result: EvalResult?,
    val capabilityId: String,
    val capabilityVersion: Int,
) {
    val resultKind: String
        get() = when (result) {
            is EvalResult.Known -> "known"
            is EvalResult.Unknown -> "unknown"
            is EvalResult.Error -> "error"
            null -> "not_executed"
        }
}

/**
 * 正式应用入口：只允许执行“当前仍处于逻辑激活、且整条依赖链健康”的能力版本。
 * 与 CapabilityRegistry.resolve / CapabilityInterpreter 的区别：后者保留给开发与验证，
 * 不检查激活状态；正式路由必须调用本类。
 */
internal class CapabilityExecutionService(private val registry: SqliteCapabilityRegistry) {

    fun executeActive(
        capabilityId: String,
        capabilityVersion: Int,
        args: Map<String, EvalResult>,
        budget: EvalBudget = EvalBudget(),
    ): ActiveExecutionResult {
        // 1) 当前定义必须可检查：body/签名/依赖表/语义版本任一损坏都拒绝执行。
        val inspected = registry.inspect(capabilityId, capabilityVersion)
        val definition = inspected.definition
        val contentHash = inspected.contentHash
        if (!inspected.ok || definition == null || contentHash == null) {
            return rejected(capabilityId, capabilityVersion, inspected.code ?: "inspection_failed")
        }

        // 2) 必须有激活记录，且激活时绑定的内容哈希与当前定义一致。
        val activation = registry.readActivation(capabilityId, capabilityVersion)
            ?: return rejected(capabilityId, capabilityVersion, "not_active")
        if (activation.contentHash != contentHash) {
            return rejected(capabilityId, capabilityVersion, "activation_mismatch")
        }

        // 3) 激活记录引用的验证必须是 pass 且仍绑定同一能力版本/哈希；防止库内记录被改写后继续执行。
        val run = registry.readVerificationRun(activation.verificationRunId)
            ?: return rejected(capabilityId, capabilityVersion, "verification_not_found")
        if (run.verdict != "pass") return rejected(capabilityId, capabilityVersion, "verification_failed")
        if (run.capabilityId != capabilityId || run.capabilityVersion != capabilityVersion || run.contentHash != contentHash) {
            return rejected(capabilityId, capabilityVersion, "verification_mismatch")
        }

        // 4) 每次执行都重新核对传递依赖，不能因为曾经激活过就默认依赖仍然健康。
        ActivationGate(registry).dependencyError(capabilityId, capabilityVersion)?.let {
            return rejected(capabilityId, capabilityVersion, it)
        }

        // 5) 门禁通过后交给 M1 解释器；预算与资源限制沿用 M1 语义。
        val result = CapabilityInterpreter(registry).execute(definition, args, budget)
        return ActiveExecutionResult(true, null, result, capabilityId, capabilityVersion)
    }

    private fun rejected(capabilityId: String, capabilityVersion: Int, code: String) =
        ActiveExecutionResult(false, code, null, capabilityId, capabilityVersion)
}
