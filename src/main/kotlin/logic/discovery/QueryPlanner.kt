// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 主动查询；决策必须在拿到标签前完成，且只依据候选预测分布。
package logic.discovery

internal data class QueryChoice(
    val objectId: String,
    val features: Map<String, String>,
    val score: Int,
    val groups: Map<String, List<String>>,
)

internal object QueryPlanner {

    /**
     * 对每个未询问对象，计算各候选的三值预测，按“能分出多少组”打分。
     * 组数越多，越能淘汰候选；同分按对象 ID 升序，保证确定性。
     * 返回 null 表示查询池里没有对象能减少歧义——此时只能拒答。
     */
    fun choose(candidates: List<Rule>, state: LearningState): QueryChoice? {
        if (candidates.size <= 1) return null
        val observed = state.observations.map { it.objectId }.toSet()
        var best: QueryChoice? = null

        for (objectId in state.pool.sorted()) {
            if (objectId in observed) continue
            val features = state.objects[objectId] ?: continue
            val groups = LinkedHashMap<String, MutableList<String>>()
            for (candidate in candidates) {
                val outcome = RuleInterpreter.evaluate(candidate, features).wire()
                groups.getOrPut(outcome) { mutableListOf() } += candidate.hash()
            }
            val score = groups.size
            if (score <= 1) continue
            if (best == null || score > best.score || (score == best.score && objectId < best.objectId)) {
                best = QueryChoice(
                    objectId = objectId,
                    features = features,
                    score = score,
                    groups = groups.mapValues { (_, hashes) -> hashes.toList() },
                )
            }
        }
        return best
    }
}
