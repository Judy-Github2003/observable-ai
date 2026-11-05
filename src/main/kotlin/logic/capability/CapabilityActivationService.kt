// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-2a 激活门禁：用追加式 activations 记录推导逻辑状态，不改 capabilities.status。
// 边界：这里“可信”是宿主进程内机制（登记表 + 记录核对），不是对恶意数据库管理员的强对抗安全承诺。
// 修改：DeepSeek Harness，2026-11-05，B0 M2c-1a 增加已撤销版本拒绝；撤销不恢复旧版本。
package logic.capability

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** verification_runs 的只读快照；字段与表一致，避免门禁直接拼 SQL 读裸值。 */
internal data class VerificationRunSnapshot(
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
    val createdAt: String,
)

internal data class ActivationSnapshot(
    val activationId: String,
    val contentHash: String,
    val verificationRunId: String,
)

internal data class DependencyPin(val id: String, val version: Int, val contentHash: String)

internal data class ActivationRow(
    val activationId: String,
    val capabilityId: String,
    val capabilityVersion: Int,
    val contentHash: String,
    val verificationRunId: String,
)

internal data class ActivationResult(
    val ok: Boolean,
    val code: String?,
    val activationId: String?,
    val alreadyActive: Boolean = false,
)

/**
 * 激活门禁：只有“当前定义可检查 + 通过的可信验证记录 + 全部传递依赖已激活”的能力版本才允许激活。
 * 激活是追加一条 activations 记录，不是把 candidate 改写成 active；旧 capability 行保持原样。
 */
internal class CapabilityActivationService(
    private val registry: SqliteCapabilityRegistry,
    private val suites: TrustedSuiteRegistry,
) {
    fun activate(capabilityId: String, capabilityVersion: Int, verificationRunId: String): ActivationResult {
        // 1) 当前定义必须仍可检查：body/依赖表/哈希任一被改过都不能激活。
        val inspected = registry.inspect(capabilityId, capabilityVersion)
        val definition = inspected.definition
        val contentHash = inspected.contentHash
        if (!inspected.ok || definition == null || contentHash == null) {
            return ActivationResult(false, inspected.code ?: "inspection_failed", null)
        }
        // 1b) 已撤销版本永久不可再激活；修正必须发布新版本，而不是恢复旧版本。
        if (registry.readRevocation(capabilityId, capabilityVersion) != null) {
            return ActivationResult(false, "revoked", null)
        }

        // 2) 验证记录必须存在，并精确绑定到当前 (id, version, contentHash)。
        val run = registry.readVerificationRun(verificationRunId)
            ?: return ActivationResult(false, "verification_not_found", null)
        if (run.capabilityId != capabilityId || run.capabilityVersion != capabilityVersion || run.contentHash != contentHash) {
            return ActivationResult(false, "verification_mismatch", null)
        }
        if (run.verdict != "pass") return ActivationResult(false, "verification_failed", null)

        // 3) 来源与套件身份必须能对上宿主登记，不能靠伪造库内 source 文本取得信任。
        val registered = suites.find(run.suiteId, run.suiteVersion)
            ?: return ActivationResult(false, "suite_not_registered", null)
        if (registered.suite.suiteHash != run.suiteHash) return ActivationResult(false, "suite_hash_mismatch", null)
        val source = VerificationSource.entries.firstOrNull { it.wireName == run.source }
            ?: return ActivationResult(false, "untrusted_source", null)
        if (registered.source != source) return ActivationResult(false, "source_mismatch", null)

        // 4) 传递依赖必须全部已激活且版本/哈希一致；任一层缺失都拒绝，不做“部分可用”。
        ActivationGate(registry).dependencyError(capabilityId, capabilityVersion)?.let {
            return ActivationResult(false, it, null)
        }

        // 5) 幂等：同一 (id, version) 已有激活记录且内容哈希一致，直接返回已有记录，不再追加事件。
        val existing = registry.readActivation(capabilityId, capabilityVersion)
        if (existing != null) {
            return if (existing.contentHash == contentHash) {
                ActivationResult(true, "already_active", existing.activationId, alreadyActive = true)
            } else {
                ActivationResult(false, "activation_conflict", null)
            }
        }

        // 6) 激活记录与 activated 事件同事务追加。
        val activationId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("activation_id", activationId)
            put("verification_run_id", verificationRunId)
            put("content_hash", contentHash)
            put("suite_id", run.suiteId)
            put("suite_version", run.suiteVersion)
            put("suite_hash", run.suiteHash)
            put("source", run.source)
            // 明确记录“逻辑激活”，避免读者误以为改写了 status 字段。
            put("logical_state", "active")
        }.toString()
        val written = registry.appendActivation(
            ActivationRow(activationId, capabilityId, capabilityVersion, contentHash, verificationRunId),
            payload,
        )
        return if (written.ok) {
            ActivationResult(true, null, activationId)
        } else {
            ActivationResult(false, written.code ?: "transaction_failed", null)
        }
    }
}
