// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M2c-1a 追加式撤销记录；旧程序体、旧验证、旧激活历史全部保留，不原地改写。
// 边界：本步只处理“根能力被撤销后不可执行”；传递依赖失效与恢复语义在 M2c-1b。
package logic.capability

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** 撤销原因/证据引用文本上限，避免把长文档或敏感内容塞进数据库。 */
const val MAX_REVOCATION_TEXT_BYTES = 4096

internal data class RevocationSnapshot(
    val revokeId: String,
    val capabilityId: String,
    val capabilityVersion: Int,
    val contentHash: String,
    val reasonCode: String,
    val evidenceRef: String,
    val createdAt: String,
)

internal data class RevocationRow(
    val revokeId: String,
    val capabilityId: String,
    val capabilityVersion: Int,
    val contentHash: String,
    val reasonCode: String,
    val evidenceRef: String,
)

/**
 * 撤销结果：
 * - ok=true 且 alreadyRevoked=false：本次新增撤销记录；
 * - ok=true 且 alreadyRevoked=true：该版本已撤销且内容哈希一致，幂等返回原记录；
 * - ok=false：前置拒绝（不存在、原因/证据缺失、哈希异常）或事务失败。
 */
internal data class RevocationResult(
    val ok: Boolean,
    val code: String?,
    val revokeId: String?,
    val alreadyRevoked: Boolean = false,
)

/**
 * 撤销服务：
 * 1) 原因码与证据引用不能为空（“缺理由的撤销”不写库）；
 * 2) 能力版本必须存在且当前可 inspect；
 * 3) 同版本重复撤销按内容哈希幂等，不追加第二条记录/事件；
 * 4) 撤销记录与 revoked 事件同事务追加。
 *
 * 撤销不提供“原地恢复”：修正应发布新版本、重新验证、重新激活。
 */
internal class CapabilityRevocationService(private val registry: SqliteCapabilityRegistry) {

    fun revoke(
        capabilityId: String,
        capabilityVersion: Int,
        reasonCode: String,
        evidenceRef: String,
    ): RevocationResult {
        if (reasonCode.isBlank() || reasonCode.toByteArray(Charsets.UTF_8).size > MAX_REVOCATION_TEXT_BYTES) {
            return RevocationResult(false, "invalid_revocation_reason", null)
        }
        if (evidenceRef.isBlank() || evidenceRef.toByteArray(Charsets.UTF_8).size > MAX_REVOCATION_TEXT_BYTES) {
            return RevocationResult(false, "invalid_evidence_ref", null)
        }
        val inspected = registry.inspect(capabilityId, capabilityVersion)
        val contentHash = inspected.contentHash
        if (!inspected.ok || contentHash == null) {
            return RevocationResult(false, inspected.code ?: "inspection_failed", null)
        }
        // 幂等规则固定：同 (id, version) 已撤销且哈希一致 → 返回原记录；哈希不同说明库被改写 → 拒绝。
        val existing = registry.readRevocation(capabilityId, capabilityVersion)
        if (existing != null) {
            return if (existing.contentHash == contentHash) {
                RevocationResult(true, "already_revoked", existing.revokeId, alreadyRevoked = true)
            } else {
                RevocationResult(false, "revocation_conflict", null)
            }
        }
        val revokeId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("revoke_id", revokeId)
            put("content_hash", contentHash)
            put("reason_code", reasonCode)
            put("evidence_ref", evidenceRef)
        }.toString()
        val written = registry.appendRevocation(
            RevocationRow(revokeId, capabilityId, capabilityVersion, contentHash, reasonCode, evidenceRef),
            payload,
        )
        return if (written.ok) {
            RevocationResult(true, null, revokeId)
        } else {
            RevocationResult(false, written.code ?: "transaction_failed", null)
        }
    }
}
