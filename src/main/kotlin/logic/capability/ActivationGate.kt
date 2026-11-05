// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-2a/2b 共用的依赖激活检查；激活门禁与正式执行入口必须用同一套传递依赖核对。
// 修改：DeepSeek Harness，2026-11-05，B0 M2c-1b 增加撤销/验证健康检查与依赖路径，区分 dependency_revoked / dependency_invalid。
package logic.capability

/**
 * 依赖检查失败项：code 给机器判定，path 给出从根能力到失败依赖的完整链条，便于人读懂“为什么不可用”。
 * 例：chain.a@1 -> chain.b@1 -> chain.c@1。
 */
internal data class GateFailure(val code: String, val path: List<String>) {
    val detail: String get() = path.joinToString(" -> ")
}

/**
 * 依赖健康检查：沿 dependencies 表逐层确认每个传递依赖
 * 1) 未被撤销；2) 有激活记录且哈希一致；3) 引用的验证仍为 pass 且绑定同一版本/哈希；
 * 4) 先递归检查更深依赖，最后检查自身 inspect（body/依赖表/语义版本未损坏）。
 *
 * 先递归后自检是为了让路径指向真正的根因：若最内层 body 损坏，报告停在最内层，而不是笼统地报中间层无效。
 * 只读，不写任何记录；visited 防止共享子依赖被重复检查，深度上限防止病态深图。
 */
internal class ActivationGate(private val registry: SqliteCapabilityRegistry) {

    companion object {
        const val MAX_DEPENDENCY_DEPTH = 32
    }

    fun check(capabilityId: String, capabilityVersion: Int): GateFailure? =
        walk(capabilityId, capabilityVersion, mutableSetOf(), 0, emptyList())

    /**
     * 当根能力整体 inspect 失败时，定位到底是根自身损坏还是某个传递依赖损坏。
     * 先看直接依赖是否 inspect 失败，失败则继续向更深层找，直到找到叶子或自身即坏点；
     * 返回 null 表示依赖链自检未发现损坏（更可能是根自身或其它原因）。
     */
    fun locateDependencyFailure(capabilityId: String, capabilityVersion: Int): GateFailure? =
        locate(capabilityId, capabilityVersion, mutableSetOf(), 0, emptyList())

    private fun locate(
        capabilityId: String,
        capabilityVersion: Int,
        visited: MutableSet<Pair<String, Int>>,
        depth: Int,
        path: List<String>,
    ): GateFailure? {
        if (depth > MAX_DEPENDENCY_DEPTH) {
            return GateFailure("dependency_depth_limit", path + "$capabilityId@$capabilityVersion")
        }
        if (!visited.add(capabilityId to capabilityVersion)) return null
        for (dependency in registry.recordedDependencies(capabilityId, capabilityVersion)) {
            val dependencyPath = path + "${dependency.id}@${dependency.version}"
            val inspected = registry.inspect(dependency.id, dependency.version)
            if (!inspected.ok) {
                // 依赖自身 inspect 失败：先确认是不是更深层坏点，找不到才归因到这一层。
                return locate(dependency.id, dependency.version, visited, depth + 1, dependencyPath)
                    ?: GateFailure("dependency_invalid", dependencyPath)
            }
            if (inspected.contentHash != dependency.contentHash) {
                return GateFailure("dependency_hash_mismatch", dependencyPath)
            }
            locate(dependency.id, dependency.version, visited, depth + 1, dependencyPath)?.let { return it }
        }
        return null
    }

    private fun walk(
        capabilityId: String,
        capabilityVersion: Int,
        visited: MutableSet<Pair<String, Int>>,
        depth: Int,
        path: List<String>,
    ): GateFailure? {
        if (depth > MAX_DEPENDENCY_DEPTH) {
            return GateFailure("dependency_depth_limit", path + "$capabilityId@$capabilityVersion")
        }
        if (!visited.add(capabilityId to capabilityVersion)) return null
        for (dependency in registry.recordedDependencies(capabilityId, capabilityVersion)) {
            val dependencyPath = path + "${dependency.id}@${dependency.version}"
            // 撤销优先于其它状态：即使旧激活/旧验证记录还在，也不能继续当作可靠依赖。
            if (registry.readRevocation(dependency.id, dependency.version) != null) {
                return GateFailure("dependency_revoked", dependencyPath)
            }
            val activation = registry.readActivation(dependency.id, dependency.version)
                ?: return GateFailure("dependency_not_activated", dependencyPath)
            if (activation.contentHash != dependency.contentHash) {
                return GateFailure("dependency_hash_mismatch", dependencyPath)
            }
            val run = registry.readVerificationRun(activation.verificationRunId)
                ?: return GateFailure("dependency_invalid", dependencyPath)
            if (run.verdict != "pass" ||
                run.capabilityId != dependency.id ||
                run.capabilityVersion != dependency.version ||
                run.contentHash != dependency.contentHash
            ) {
                return GateFailure("dependency_invalid", dependencyPath)
            }
            // 先钻到最深层，确保错误路径指向真正失效的节点。
            walk(dependency.id, dependency.version, visited, depth + 1, dependencyPath)?.let { return it }
            val inspected = registry.inspect(dependency.id, dependency.version)
            if (!inspected.ok || inspected.contentHash != dependency.contentHash) {
                return GateFailure("dependency_invalid", dependencyPath)
            }
        }
        return null
    }
}
