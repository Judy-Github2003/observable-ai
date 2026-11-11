// 修复轮（DeepSeek Harness, 2026-11-09）：证据账本。
// 修复点 G：read/conflicts 一律按 worldId 过滤，跨 world 同名对象不再互相污染；
// 来源策略：requireVerified=true 时只接受 EXAMINER_VERIFIED，否则 UNVERIFIED_SOURCE。
package observable.ai.abdmnv.m1

class CombinedEvidenceLedger {
    private val records = mutableListOf<Evidence>()

    fun record(e: Evidence): String {
        if (records.any { it.id == e.id }) return "DUPLICATE_EVIDENCE_ID"
        records += e
        return "OK"
    }

    fun all(): List<Evidence> = records.toList()

    fun objectsIn(worldId: String): List<String> =
        records.filter { it.worldId == worldId }.map { it.objectId }.distinct().sorted()

    fun read(
        worldId: String,
        objectId: String,
        channel: String,
        schemaVersion: Int,
        round: Int? = null,
        requireVerified: Boolean = false,
    ): ValueResult {
        val candidates = records.filter {
            it.worldId == worldId && it.objectId == objectId && channel in it.values
        }
        if (candidates.isEmpty()) return ValueResult.Unknown("NOT_MEASURED")
        val inSchema = candidates.filter { it.schemaVersion == schemaVersion }
        if (inSchema.isEmpty()) return ValueResult.Unknown("NOT_IN_SCHEMA")
        val use = if (round == null) inSchema else inSchema.filter { it.round == round }
        if (use.isEmpty()) return ValueResult.Unknown("TRANSIENT_ONLY")
        // GATE-05：EXAMINER_VERIFIED 必须由可信网关签署（signedByExaminer），调用者自填字符串不算。
        val trusted = if (requireVerified) {
            use.filter { it.sourceKind == "EXAMINER_VERIFIED" && it.signedByExaminer }
        } else use
        if (trusted.isEmpty()) return ValueResult.Unknown("UNVERIFIED_SOURCE")
        val known = trusted.mapNotNull { (it.values[channel] as? ValueResult.Known)?.value }
        if (known.isEmpty()) return ValueResult.Unknown("UNKNOWN_VALUE")
        val distinct = known.distinctBy { stableValue(it) }
        if (distinct.size > 1) return ValueResult.Unknown("SOURCE_CONFLICT")
        return ValueResult.Known(distinct.single())
    }

    fun conflicts(worldId: String, round: Int? = null): List<Pair<String, String>> =
        records.filter { it.worldId == worldId && (round == null || it.round == round) }
            .flatMap { e -> e.values.keys.map { ch -> Triple(e.objectId, ch, e.values[ch]) } }
            .groupBy { it.first to it.second }
            .filter { (_, entries) ->
                entries.mapNotNull { (it.third as? ValueResult.Known)?.value }
                    .distinctBy { stableValue(it) }.size > 1
            }
            .keys.toList()

    private fun stableValue(v: TypedValue): String = when (v) {
        is TypedValue.Bool -> "b:${v.value}"
        is TypedValue.IntNumber -> "i:${v.value}"
        is TypedValue.EnumSymbol -> "e:${v.enumId}:${v.value}"
        is TypedValue.ObjectId -> "o:${v.id}"
        is TypedValue.SetValue -> "s:" + v.values.map { stableValue(it) }.sorted().joinToString(",")
    }
}
