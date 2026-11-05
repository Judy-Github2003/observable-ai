// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A1 允许的探测动作契约；E3-A 只允许公开观测，不包含新传感器或新谓词。
package logic.e3

/**
 * E3-A 只允许三类动作：
 * - 重复请求同一对象/固定上下文的标签；
 * - 从公开候选池请求一条新观察；
 * - 不动作，保留不确定性。
 *
 * 禁止 SELECT_NEW_SENSOR / 新增字段 / 创建概念；这些属于 E3-B/C。
 */
sealed interface ProbeRequest {
    data class RepeatLabel(val objectId: String) : ProbeRequest
    data class RequestPublicInstance(val poolId: String) : ProbeRequest
    data object NoAction : ProbeRequest
}
