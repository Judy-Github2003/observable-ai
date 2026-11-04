// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 学习事件；记录可见的候选、证据、查询与晋升，不记录模型内部思考。
package logic.discovery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.*

internal class DiscoveryAudit(private val store: DiscoveryStore) {

    fun record(
        type: String,
        worldId: String,
        taskId: String?,
        traceId: String,
        candidateSetHash: String,
        evidenceIds: List<String> = emptyList(),
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) {
        val event = buildJsonObject {
            put("event", type)
            put("world_id", worldId)
            put("task_id", taskId ?: "")
            put("trace_id", traceId)
            put("timestamp", nowIso())
            put("candidate_set_hash", candidateSetHash)
            putJsonArray("evidence_ids") { evidenceIds.forEach { add(it) } }
            put("source", "student")
            put("algorithm_version", ALGORITHM_VERSION)
            extra()
        }
        store.appendEvent(event)
    }

    fun disable(): Unit = Unit
}
