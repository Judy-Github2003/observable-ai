// 实现者：DeepSeek Harness，2026-11-09，ABDMNV-01 M1 切片。
// 证据与版本：复用 logic.e3.ObservedValue 的值语义，补 v2 要求的 schema_version / source_kind /
// independence / round / stable_round / nonce 字段；版本不可变、撤销传播、依赖失效。
package logic.e3.abdmnv

import logic.e3.ObservedValue

enum class AbdmnvSourceKind { EXAMINER_VERIFIED, STUDENT_REPORTED, DECLARED }
enum class AbdmnvIndependence { INDEPENDENT, SHARED_SOURCE, UNKNOWN }

data class AbdmnvEvidence(
    val id: String,
    val objectId: String,
    val attribute: String,
    val value: ObservedValue,
    val schemaVersion: Int,
    val sourceKind: AbdmnvSourceKind,
    val independence: AbdmnvIndependence,
    val round: Int,
    val stableRound: Int,
    val nonce: String? = null,
) {
    init {
        AbdmnvIds.requireId(id)
        AbdmnvIds.requireId(objectId)
        AbdmnvIds.requireId(attribute)
        require(schemaVersion >= 1) { "INVALID_SCHEMA_VERSION" }
        require(round >= 1 && stableRound >= 1) { "INVALID_ROUND" }
    }
}

sealed interface AbdmnvRead {
    data class Known(val value: String) : AbdmnvRead
    data class Unknown(val reason: String) : AbdmnvRead
    data class Error(val code: String) : AbdmnvRead
}

/** 证据账本：schema 缺失 → NOT_IN_SCHEMA；非 stable 轮 → TRANSIENT_ONLY；稳定值冲突 → SOURCE_CONFLICT。 */
class AbdmnvLedger {
    private val records = mutableListOf<AbdmnvEvidence>()

    fun record(e: AbdmnvEvidence): AbdmnvEvidence {
        records += e
        return e
    }

    fun all(): List<AbdmnvEvidence> = records.toList()

    fun read(objectId: String, attribute: String, schemaVersion: Int, stableOnly: Boolean = true): AbdmnvRead {
        val candidates = records.filter { it.objectId == objectId && it.attribute == attribute }
        if (candidates.isEmpty()) return AbdmnvRead.Unknown("NOT_MEASURED")
        val inSchema = candidates.filter { it.schemaVersion == schemaVersion }
        if (inSchema.isEmpty()) return AbdmnvRead.Unknown("NOT_IN_SCHEMA")
        val use = if (stableOnly) inSchema.filter { it.round == it.stableRound } else inSchema
        if (use.isEmpty()) return AbdmnvRead.Unknown("TRANSIENT_ONLY")
        val known = use.mapNotNull { (it.value as? ObservedValue.Known)?.value }
        if (known.isEmpty()) return AbdmnvRead.Unknown("UNKNOWN_VALUE")
        val distinct = known.distinct()
        if (distinct.size > 1) return AbdmnvRead.Unknown("SOURCE_CONFLICT")
        return AbdmnvRead.Known(distinct.single())
    }

    fun conflicts(): List<Pair<String, String>> {
        val grouped = records.filter { it.round == it.stableRound }
            .groupBy { it.objectId to it.attribute }
        return grouped.filter { (_, es) ->
            val known = es.mapNotNull { (it.value as? ObservedValue.Known)?.value }.distinct()
            known.size > 1
        }.keys.toList()
    }
}

enum class AbdmnvVersionStatus { PROPOSED, ACTIVE_WITH_SCOPE, SUPERSEDED, REVOKED }

/** 版本不可变；修订必须新版本；撤销传播到依赖者。 */
class AbdmnvConceptStore {
    private val concepts = LinkedHashMap<String, AbdmnvConcept>()
    private val status = mutableMapOf<String, AbdmnvVersionStatus>()
    private val ordered = mutableListOf<String>()

    private fun key(ref: AbdmnvVersionRef): String = ref.render()

    fun propose(c: AbdmnvConcept): String {
        val k = "${c.id}@${c.version}"
        val hash = AbdmnvCanonical.contentHash(c)
        val existing = concepts[k]
        if (existing != null) {
            if (AbdmnvCanonical.contentHash(existing) != hash) error("VERSION_IMMUTABLE_VIOLATION:$k")
            return hash
        }
        concepts[k] = c
        status[k] = AbdmnvVersionStatus.PROPOSED
        ordered += k
        return hash
    }

    fun get(ref: AbdmnvVersionRef): AbdmnvConcept? = concepts[key(ref)]

    fun hashOf(ref: AbdmnvVersionRef): String? = concepts[key(ref)]?.let { AbdmnvCanonical.contentHash(it) }

    fun statusOf(ref: AbdmnvVersionRef): AbdmnvVersionStatus? = status[key(ref)]

    fun activate(ref: AbdmnvVersionRef): String {
        val k = key(ref)
        if (concepts[k] == null) return "UNKNOWN_CONCEPT"
        if (status[k] == AbdmnvVersionStatus.REVOKED) return "VERSION_REVOKED"
        status[k] = AbdmnvVersionStatus.ACTIVE_WITH_SCOPE
        return "OK"
    }

    /** 修订：新版本必须 version = old.version + 1，旧版本标记 SUPERSEDED，内容不可变。 */
    fun supersede(old: AbdmnvVersionRef, new: AbdmnvConcept): String {
        val oldKey = key(old)
        if (concepts[oldKey] == null) return "UNKNOWN_CONCEPT"
        if (new.id != old.id) return "SUPERSEDE_ID_MISMATCH"
        if (new.version != old.version + 1) return "SUPERSEDE_VERSION_GAP"
        propose(new)
        status[oldKey] = AbdmnvVersionStatus.SUPERSEDED
        return "OK"
    }

    fun revoke(ref: AbdmnvVersionRef, reason: String): String {
        val k = key(ref)
        if (concepts[k] == null) return "UNKNOWN_CONCEPT"
        status[k] = AbdmnvVersionStatus.REVOKED
        return "OK"
    }

    fun dependents(ref: AbdmnvVersionRef): List<AbdmnvVersionRef> =
        ordered.mapNotNull { concepts[it] }
            .filter { c -> c.deps.any { it == ref } }
            .map { AbdmnvVersionRef(it.id, it.version) }

    /** 自撤销或任一依赖（递归）撤销时不可执行。 */
    fun executable(ref: AbdmnvVersionRef, seen: MutableSet<String> = mutableSetOf()): Boolean {
        val k = key(ref)
        if (!seen.add(k)) return true
        val c = concepts[k] ?: return false
        if (status[k] == AbdmnvVersionStatus.REVOKED) return false
        return c.deps.all { executable(it, seen) }
    }

    fun revokeDependents(ref: AbdmnvVersionRef, reason: String): List<AbdmnvVersionRef> {
        val out = mutableListOf<AbdmnvVersionRef>()
        val queue = ArrayDeque(dependents(ref))
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            if (out.contains(d)) continue
            revoke(d, reason)
            out += d
            queue.addAll(dependents(d))
        }
        return out
    }
}
