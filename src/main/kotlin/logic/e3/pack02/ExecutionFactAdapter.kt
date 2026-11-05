// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：PACK-02R-AUTH；删除公开 fromResult，执行结果只能通过 active execution 生成 VerifiedFact。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path

/**
 * 可信证据适配器：
 * - 只接受注册表 active 快照中精确匹配 id/version/contentHash 的能力；
 * - 只执行正式 executeActive 路径；
 * - 只有 Known(true) 才签发 VerifiedFact；
 * - 命题绑定为 capability id（业务命题映射须在更高层显式声明，不能由调用者硬编码 R）。
 */
object ExecutionFactAdapter {

    fun fromExecution(dbPath: Path, ref: KnowledgeRef, input: Map<String, String>): VerifiedFact? {
        val active = Pack02Knowledge.resolveActive(dbPath, listOf(ref)) ?: return null
        val definition = active.singleOrNull() ?: return null
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val result = CapabilityExecutionService(registry).executeActive(
                definition.id, definition.version,
                input.mapValues { EvalResult.Known(CapValue.Str(it.value)) },
                EvalBudget(maxSteps = 10000),
            )
            if (!result.ok) return null
            val known = result.result as? EvalResult.Known ?: return null
            val bool = known.value as? CapValue.Bool ?: return null
            if (!bool.value) return null
            val digest = VerifiedFact.digest(input)
            val capabilityRef = "${ref.id}@${ref.version}#${ref.contentHash}"
            val fact = Fact.verifiedForTest(
                id = "fact-${ref.id}-${ref.version}-$digest",
                proposition = ref.id,
                source = "execution:$capabilityRef",
                capabilityRef = capabilityRef,
                inputDigest = digest,
            )
            return VerifiedFact.issue(
                fact = fact,
                ref = ref,
                capabilityRef = capabilityRef,
                inputDigest = digest,
                input = input,
                executionId = "exec:$capabilityRef:$digest",
            )
        } finally {
            registry.close()
        }
    }
}
