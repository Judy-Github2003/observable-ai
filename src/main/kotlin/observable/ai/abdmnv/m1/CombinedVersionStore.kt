// 修复轮（DeepSeek Harness, 2026-11-09）：版本存储。
// 修复点：
//  A/B/C  所有入口（propose/get/activate/revoke/supersede）都要求 ref.hash == 内容哈希，伪造 hash 一律拒绝；
//  D      依赖 pinned hash 进入内容哈希；
//  F      propose 拒绝缺失依赖；
//  E      checkGraph 拒绝环；executable 对环返回 false（防御性）；
//  H      PROPOSED 只算“候选可执行”，ACTIVE_WITH_SCOPE 才算“可用”。
package observable.ai.abdmnv.m1

enum class CombinedVersionStatus { PROPOSED, ACTIVE_WITH_SCOPE, SUPERSEDED, REVOKED }

class CombinedStoreRejection(val code: String, message: String = code) : IllegalArgumentException(message)

class CombinedConceptVersionStore {
    private val concepts = LinkedHashMap<String, Concept>()
    private val status = mutableMapOf<String, CombinedVersionStatus>()
    private val order = mutableListOf<String>()

    private fun key(ref: ConceptRef): String = "${ref.id}@${ref.version}"
    private fun stored(ref: ConceptRef): Concept? = concepts[key(ref)]

    fun fullContentHash(c: Concept): String = CombinedConceptHashing.contentHash(c)

    // ------------------------------------------------------------------ 写入

    /** 抛出 CombinedStoreRejection；返回落库内容哈希。 */
    fun propose(c: Concept): String {
        validateContentHash(c)
        val batchResult = checkGraph(listOf(c))
        if (batchResult != "OK") throw CombinedStoreRejection(batchResult)
        insert(c)
        return c.ref.hash
    }

    fun tryPropose(c: Concept): String = try {
        propose(c)
        "OK"
    } catch (e: CombinedStoreRejection) {
        e.code
    }

    /** 批量：先整体校验（含批内互相依赖与环），再原子写入。 */
    fun proposeBatch(batch: List<Concept>): String {
        val byKey = batch.associateBy { key(it.ref) }
        if (byKey.size != batch.size) return "DUPLICATE_IN_BATCH"
        for (c in batch) {
            try {
                validateContentHash(c)
            } catch (e: CombinedStoreRejection) {
                return e.code
            }
        }
        val graph = checkGraph(batch)
        if (graph != "OK") return graph
        for (c in batch) {
            val existing = concepts[key(c.ref)]
            if (existing != null && fullContentHash(existing) != fullContentHash(c)) return "VERSION_IMMUTABLE_VIOLATION"
        }
        batch.forEach(::insert)
        return "OK"
    }

    private fun insert(c: Concept) {
        val k = key(c.ref)
        val existing = concepts[k]
        if (existing != null) {
            if (fullContentHash(existing) != fullContentHash(c)) throw CombinedStoreRejection("VERSION_IMMUTABLE_VIOLATION")
            return
        }
        concepts[k] = c
        status[k] = CombinedVersionStatus.PROPOSED
        order += k
    }

    private fun validateContentHash(c: Concept) {
        if (c.ref.version <= 0) throw CombinedStoreRejection("INVALID_VERSION")
        if (c.ref.hash.isBlank()) throw CombinedStoreRejection("HASH_MISMATCH")
        val expected = CombinedConceptHashing.contentHash(c)
        if (c.ref.hash != expected) {
            throw CombinedStoreRejection("HASH_MISMATCH: expected $expected, got ${c.ref.hash}")
        }
    }

    // ------------------------------------------------------------------ 图校验

    /** 结构校验：依赖必须存在（本批或库内）、pinned hash 必须与目标 ref.hash 一致、禁止环。 */
    fun checkGraph(batch: List<Concept>): String {
        val merged = LinkedHashMap<String, Concept>()
        for (k in order) concepts[k]?.let { merged[k] = it }
        batch.forEach { merged[key(it.ref)] = it }
        for (c in batch) {
            for (d in c.dependencies) {
                val target = merged[key(d)] ?: return "MISSING_DEPENDENCY:${d.id}@${d.version}"
                if (target.ref.hash != d.hash) return "DEPENDENCY_HASH_MISMATCH:${d.id}@${d.version}"
                scopeRule(c, target)?.let { return it }
            }
        }
        return if (hasCycle(batch, merged)) "CYCLE_DETECTED" else "OK"
    }

    /** GATE-03：局部概念只能依赖同 world 的局部概念或 global；global 只能依赖 global。 */
    private fun scopeRule(child: Concept, dep: Concept): String? {
        val cs = child.worldScope
        val ds = dep.worldScope
        return when {
            cs == null && ds != null -> "CROSS_WORLD_DEPENDENCY:global->local(${ds})"
            cs != null && ds != null && cs != ds -> "CROSS_WORLD_DEPENDENCY:$cs->$ds"
            else -> null
        }
    }

    private fun hasCycle(nodes: List<Concept>, merged: Map<String, Concept>): Boolean {
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visit(k: String): Boolean {
            if (k in done) return false
            if (!visiting.add(k)) return true
            val c = merged[k] ?: return false
            for (d in c.dependencies) {
                if (visit(key(d))) return true
            }
            visiting.remove(k)
            done.add(k)
            return false
        }
        val roots = (nodes.map { key(it.ref) } + merged.keys).distinct()
        return roots.any { visit(it) }
    }

    // ------------------------------------------------------------------ 读取与状态

    /** 只有 hash 与内容都匹配才返回；伪造 hash 得到 null。 */
    fun get(ref: ConceptRef): Concept? {
        val c = stored(ref) ?: return null
        if (c.ref.hash != ref.hash) return null
        if (CombinedConceptHashing.contentHash(c) != ref.hash) return null
        return c
    }

    fun statusOf(ref: ConceptRef): CombinedVersionStatus? = stored(ref)?.let { status[key(ref)] }

    fun activate(ref: ConceptRef): String {
        val c = stored(ref) ?: return "UNKNOWN_CONCEPT"
        requireHash(c, ref)?.let { return it }
        when (status[key(ref)]) {
            CombinedVersionStatus.REVOKED -> return "VERSION_REVOKED"
            CombinedVersionStatus.SUPERSEDED -> return "VERSION_SUPERSEDED"
            CombinedVersionStatus.ACTIVE_WITH_SCOPE -> return "OK"
            else -> Unit
        }
        // GATE-01：依赖必须已激活，否则激活请求被拒绝。
        if (!dependenciesActive(c, mutableSetOf())) return "DEPENDENCY_NOT_ACTIVE"
        status[key(ref)] = CombinedVersionStatus.ACTIVE_WITH_SCOPE
        return "OK"
    }

    fun revoke(ref: ConceptRef, reason: String): String {
        val c = stored(ref) ?: return "UNKNOWN_CONCEPT"
        requireHash(c, ref)?.let { return it }
        status[key(ref)] = CombinedVersionStatus.REVOKED
        return "OK"
    }

    fun supersede(old: ConceptRef, next: Concept): String {
        val c = stored(old) ?: return "UNKNOWN_CONCEPT"
        requireHash(c, old)?.let { return it }
        if (next.ref.id != old.id) return "SUPERSEDE_ID_MISMATCH"
        if (next.ref.version != old.version + 1) return "SUPERSEDE_VERSION_GAP"
        return try {
            propose(next)
            status[key(old)] = CombinedVersionStatus.SUPERSEDED
            "OK"
        } catch (e: CombinedStoreRejection) {
            e.code
        }
    }

    private fun requireHash(storedConcept: Concept, supplied: ConceptRef): String? {
        if (storedConcept.ref.hash != supplied.hash) return "HASH_MISMATCH"
        if (CombinedConceptHashing.contentHash(storedConcept) != supplied.hash) return "HASH_MISMATCH"
        return null
    }

    fun dependents(ref: ConceptRef): List<ConceptRef> =
        order.mapNotNull { concepts[it] }
            .filter { c -> c.dependencies.any { it.id == ref.id && it.version == ref.version } }
            .map { it.ref }

    /** 可用（已激活）且依赖全部可用、无环。 */
    fun executable(ref: ConceptRef): Boolean {
        val c = get(ref) ?: return false
        if (status[key(ref)] != CombinedVersionStatus.ACTIVE_WITH_SCOPE) return false
        return depsHealthy(c, mutableSetOf())
    }

    /** 候选可执行：PROPOSED 或 ACTIVE，依赖存在且无环（用于激活前验证）。 */
    fun candidateExecutable(ref: ConceptRef): Boolean {
        val c = get(ref) ?: return false
        val s = status[key(ref)] ?: return false
        if (s != CombinedVersionStatus.PROPOSED && s != CombinedVersionStatus.ACTIVE_WITH_SCOPE) return false
        return depsHealthy(c, mutableSetOf())
    }

    private fun depsHealthy(c: Concept, visiting: MutableSet<String>): Boolean {
        val k = key(c.ref)
        if (!visiting.add(k)) return false // 环
        for (d in c.dependencies) {
            val dep = stored(d) ?: return false
            if (dep.ref.hash != d.hash) return false
            // GATE-01：依赖必须已激活（PROPOSED/REVOKED/SUPERSEDED 都不算健康）。
            if (status[key(d)] != CombinedVersionStatus.ACTIVE_WITH_SCOPE) return false
            if (!depsHealthy(dep, visiting)) return false
        }
        visiting.remove(k)
        return true
    }

    /** 激活前置：依赖全部存在、hash 匹配、已激活、无环。 */
    private fun dependenciesActive(c: Concept, visiting: MutableSet<String>): Boolean = depsHealthy(c, visiting)

    fun revokeDependents(ref: ConceptRef, reason: String): List<ConceptRef> {
        // GATE-02：起始 ref 必须与库内 hash 一致，伪造 ref 不得触发级联撤销。
        if (get(ref) == null) return emptyList()
        val out = mutableListOf<ConceptRef>()
        val queue = ArrayDeque(dependents(ref))
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            if (out.any { it.id == d.id && it.version == d.version }) continue
            revoke(d, reason)
            out += d
            queue.addAll(dependents(d))
        }
        return out
    }


    /**
     * Store-backed enumeration for learning/retrieval.
     * `inspected` charges every stored version considered, including inactive or out-of-scope entries.
     * Only hash-valid, active, executable concepts in the requested scope (or global scope) are returned.
     */
    data class ActiveConceptScan(val inspected: Int, val concepts: List<Concept>)

    fun scanActiveConcepts(worldScope: String): ActiveConceptScan {
        val inspected = order.size
        val found = order.mapNotNull { k ->
            val c = concepts[k] ?: return@mapNotNull null
            if (status[k] != CombinedVersionStatus.ACTIVE_WITH_SCOPE) return@mapNotNull null
            if (c.worldScope != null && c.worldScope != worldScope) return@mapNotNull null
            if (!executable(c.ref)) return@mapNotNull null
            c
        }
        return ActiveConceptScan(inspected, found)
    }

    /** Snapshot for audit/reporting only; callers must still use get/executable before execution. */
    fun storedConceptCount(): Int = order.size

    fun diagnose(ref: ConceptRef): String {
        val c = stored(ref) ?: return "UNKNOWN_CONCEPT"
        if (c.ref.hash != ref.hash || CombinedConceptHashing.contentHash(c) != ref.hash) return "HASH_MISMATCH"
        return when (status[key(ref)]) {
            CombinedVersionStatus.REVOKED -> "REVOKED"
            CombinedVersionStatus.SUPERSEDED -> "SUPERSEDED"
            CombinedVersionStatus.PROPOSED -> "PROPOSED_CANDIDATE_ONLY"
            CombinedVersionStatus.ACTIVE_WITH_SCOPE -> if (executable(ref)) "ACTIVE" else "DEPENDENCY_UNHEALTHY"
            null -> "UNKNOWN_CONCEPT"
        }
    }
}
