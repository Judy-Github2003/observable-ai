// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-1c 把验证结果与事件原子写入 SQLite；不激活能力、不改旧四表。
// 边界：通过只表示“该套件通过”，不代表开放世界正确；suite_hash 只证明套件内容一致，不证明测试集来源独立。
package logic.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** 一行 verification_runs 的写入参数；与表字段一一对应，避免位置参数写错。 */
internal data class VerificationRunRow(
    val runId: String,
    val capabilityId: String,
    val capabilityVersion: Int,
    val contentHash: String,
    val suiteId: String,
    val suiteVersion: Int,
    val suiteHash: String,
    val caseCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    val verdict: String,
    val source: String,
)

/**
 * 验证入库结果：
 * - recorded=true 表示 run + event 已同事务提交；
 * - recorded=false 时 code 说明是前置拒绝（套件未登记、库内能力损坏、trace 过大）还是事务失败；
 * - 无论是否入库，都返回内存 outcome 供调用方/报告使用。
 */
internal data class VerificationRecordResult(
    val recorded: Boolean,
    val code: String?,
    val runId: String?,
    val outcome: VerificationOutcome,
)

/** 用数据库 `inspect` 作为可信检查器：先校验库内 hash/依赖表，再把定义交给内存验证引擎。 */
internal class RegistryInspector(private val registry: SqliteCapabilityRegistry) : CapabilityInspector {
    override fun inspect(capabilityId: String, capabilityVersion: Int): Inspection {
        val result = registry.inspect(capabilityId, capabilityVersion)
        val definition = result.definition
        val contentHash = result.contentHash
        return if (result.ok && definition != null && contentHash != null) {
            Inspection.Ok(definition, contentHash)
        } else {
            Inspection.Unavailable(result.code ?: "inspection_failed")
        }
    }
}

/**
 * 验证服务：执行内存验证 → 追加 verification_runs + events。
 * 前置拒绝不留半条记录；已执行的运行（含 fail / suite_inadequate）都保留，历史不可覆盖。
 */
internal class CapabilityVerificationService(
    private val registry: SqliteCapabilityRegistry,
    private val suites: TrustedSuiteRegistry,
) {
    fun verify(
        capabilityId: String,
        capabilityVersion: Int,
        suiteId: String,
        suiteVersion: Int,
        budget: EvalBudget = EvalBudget(),
    ): VerificationRecordResult {
        val engine = TrustedVerificationEngine(registry, suites, RegistryInspector(registry))
        val outcome = engine.verify(capabilityId, capabilityVersion, suiteId, suiteVersion, budget)
        val contentHash = outcome.contentHash
        // 没有内容哈希说明 inspect 失败（库损坏/依赖断）；没有执行任何用例说明是套件前置拒绝。
        // 这两种都不写库，避免产生无法复核的“通过记录”。
        if (contentHash == null || outcome.caseCount == 0 || outcome.source == null) {
            return VerificationRecordResult(false, outcome.code, null, outcome)
        }
        val runId = UUID.randomUUID().toString()
        val payload = buildTrace(runId, outcome)
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            // 逐例证据放不进有大小限制的事件 JSON 时，宁可拒绝也不截断失败案例。
            return VerificationRecordResult(false, "verification_trace_too_large", null, outcome)
        }
        val row = VerificationRunRow(
            runId = runId,
            capabilityId = outcome.capabilityId,
            capabilityVersion = outcome.capabilityVersion,
            contentHash = contentHash,
            suiteId = outcome.suiteId,
            suiteVersion = outcome.suiteVersion,
            suiteHash = outcome.suiteHash,
            caseCount = outcome.caseCount,
            passedCount = outcome.passedCount,
            failedCount = outcome.failedCount,
            verdict = outcome.verdict.name.lowercase(),
            source = outcome.source.wireName,
        )
        val written = registry.appendVerificationRun(row, payload)
        return if (written.ok) {
            VerificationRecordResult(true, null, runId, outcome)
        } else {
            VerificationRecordResult(false, written.code ?: "transaction_failed", null, outcome)
        }
    }

    /** 事件 JSON：汇总 + 逐例 expected/actual/status，确保失败用例不被一句“通过”覆盖。 */
    private fun buildTrace(runId: String, outcome: VerificationOutcome): String = buildJsonObject {
        put("run_id", runId)
        put("capability_id", outcome.capabilityId)
        put("version", outcome.capabilityVersion)
        put("content_hash", outcome.contentHash!!)
        put("suite_id", outcome.suiteId)
        put("suite_version", outcome.suiteVersion)
        put("suite_hash", outcome.suiteHash)
        put("source", outcome.source!!.wireName)
        put("verdict", outcome.verdict.name.lowercase())
        if (outcome.code == null) put("code", JsonNull) else put("code", outcome.code)
        put("case_count", outcome.caseCount)
        put("passed_count", outcome.passedCount)
        put("failed_count", outcome.failedCount)
        put("definite_count", outcome.definiteCount)
        put("true_count", outcome.trueCount)
        put("false_count", outcome.falseCount)
        put("cases", buildJsonArray {
            outcome.cases.forEach { case ->
                addJsonObject {
                    put("case_id", case.caseId)
                    put("status", case.status.name)
                    put("counted_as_definite", case.countedAsDefinite)
                    put("expected", Json.parseToJsonElement(encodeExpected(case.expected)))
                    put("actual", Json.parseToJsonElement(encodeExpected(case.actual)))
                }
            }
        })
    }.toString()
}
