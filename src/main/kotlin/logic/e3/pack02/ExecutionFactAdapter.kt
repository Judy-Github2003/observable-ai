// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-02 FIX-03 将真实激活能力执行结果受控转换为演绎事实。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Path

/**
 * 只有“合法激活引用 + Known(true)”才能生成 VERIFIED 事实。
 * false / Unknown / Error / 未激活 / 撤销 / 哈希不匹配都返回 null。
 */
object ExecutionFactAdapter {

    fun fromExecution(dbPath: Path, ref: KnowledgeRef, input: Map<String, String>): Fact? {
        val active = Pack02Knowledge.resolveActive(dbPath, listOf(ref)) ?: return null
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val definition = active.single()
            val result = CapabilityExecutionService(registry).executeActive(
                definition.id, definition.version,
                input.mapValues { EvalResult.Known(CapValue.Str(it.value)) },
                EvalBudget(maxSteps = 10000),
            )
            if (!result.ok) return null
            return fromResult(ref, input, result.result)
        } finally {
            registry.close()
        }
    }

    fun fromResult(ref: KnowledgeRef, input: Map<String, String>, result: EvalResult?): Fact? {
        val value = (result as? EvalResult.Known)?.value as? CapValue.Bool ?: return null
        if (!value.value) return null
        val digest = sha256(input.toSortedMap().entries.joinToString("|") { it.key + "=" + it.value })
        return Fact(
            id = "fact-${ref.id}-${ref.version}-$digest",
            proposition = "R",
            status = FactStatus.VERIFIED,
            source = "capability:${ref.id}@${ref.version}#${ref.contentHash}",
            capabilityRef = "${ref.id}@${ref.version}#${ref.contentHash}",
            inputDigest = digest,
        )
    }
}
