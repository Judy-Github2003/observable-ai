// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-2a/2b 共用的依赖激活检查；激活门禁与正式执行入口必须用同一套传递依赖核对。
package logic.capability

/**
 * 依赖健康检查：沿 dependencies 表逐层确认每个传递依赖都有激活记录、版本与哈希一致、当前仍可 inspect。
 * 只读，不写激活记录；visited 防止共享子依赖被重复检查，深度上限防止病态深图。
 */
internal class ActivationGate(private val registry: SqliteCapabilityRegistry) {

    companion object {
        const val MAX_DEPENDENCY_DEPTH = 32
    }

    fun dependencyError(
        capabilityId: String,
        capabilityVersion: Int,
        visited: MutableSet<Pair<String, Int>> = mutableSetOf(),
        depth: Int = 0,
    ): String? {
        if (depth > MAX_DEPENDENCY_DEPTH) return "dependency_depth_limit"
        if (!visited.add(capabilityId to capabilityVersion)) return null
        for (dependency in registry.recordedDependencies(capabilityId, capabilityVersion)) {
            val activation = registry.readActivation(dependency.id, dependency.version)
                ?: return "dependency_not_activated"
            if (activation.contentHash != dependency.contentHash) return "dependency_hash_mismatch"
            val inspected = registry.inspect(dependency.id, dependency.version)
            if (!inspected.ok) return inspected.code ?: "dependency_invalid"
            if (inspected.contentHash != dependency.contentHash) return "dependency_hash_mismatch"
            dependencyError(dependency.id, dependency.version, visited, depth + 1)?.let { return it }
        }
        return null
    }
}
