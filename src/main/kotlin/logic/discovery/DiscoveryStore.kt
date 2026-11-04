// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 状态持久化；按 world_id 分目录、原子替换 state.json，事件追加到 events.jsonl。
package logic.discovery

import kotlinx.serialization.json.JsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal class DiscoveryStore(stateDir: Path) {
    private val root: Path = stateDir.toAbsolutePath().normalize()
    private val worlds: Path = root.resolve("worlds")
    private val lock = Any()

    fun stateFile(worldId: String): Path = worlds.resolve(sha256(worldId).take(32)).resolve("state.json")

    fun load(worldId: String): LearningState? {
        val file = stateFile(worldId)
        if (!Files.isRegularFile(file)) return null
        val text = Files.readString(file, Charsets.UTF_8)
        val obj = discoveryJson.parseToJsonElement(text).requireObject("state")
        return LearningState.fromJson(obj)
    }

    /** 先写临时文件再原子替换；中途失败不会留下“半条规则”。 */
    fun save(state: LearningState) {
        val file = stateFile(state.worldId)
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "state-", ".tmp")
        try {
            Files.writeString(temp, state.toJson().toString(), Charsets.UTF_8)
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** 显式重置：把旧 state 移到同目录归档文件，保留历史，不直接删除。 */
    fun archive(worldId: String): String? {
        val file = stateFile(worldId)
        if (!Files.isRegularFile(file)) return null
        val target = file.resolveSibling("state.archive-${System.currentTimeMillis()}.json")
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING)
        return target.fileName.toString()
    }

    fun appendEvent(event: JsonObject) {
        synchronized(lock) {
            Files.createDirectories(root)
            Files.writeString(
                root.resolve("events.jsonl"),
                event.toString() + "\n",
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    fun eventsPath(): Path = root.resolve("events.jsonl")
}
