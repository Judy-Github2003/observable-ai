// 本文件原始实现：Codex，2026-11-04。
// 本次中文注释与可读步骤日志：DeepSeek Harness 补充，2026-11-04。
// 本轮补充代码内部中文注释：DeepSeek Harness，2026-11-04；未改 schema、SQL 或行为。
// 局部注释审计：Codex，2026-11-04；澄清来源标识与数据库并发边界，避免把元数据误当教师能力。
package logic

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/** 从 SQLite 读出的原始教师回复及其来源；文本仍为未核验。 */
data class RememberedAnswer(
    // 原始问题文本，用于展示和追溯；查询键仍是数据库中的 question_key。
    val question: String,
    // 教师当时的原始回复，可能包含多行；这里不重新解释或核验它。
    val text: String,
    // 来源运行框架，例如 deepseek-harness 或测试用 offline-teacher。
    val runtime: String,
    // 来源模型提供方；没有提供方信息时，这一列的值为 null。
    val provider: String?,
    // 配置时使用的模型名；未知或未配置时为 null。
    val model: String?,
    // 仅保存教师会话的来源标识，不表示把教师整段对话或内部能力存入学生。
    val teacherSessionId: String?,
    // 最初保存这条回复的 run_id，命中时继续带回给调用方。
    val sourceRunId: String,
    // 原始保存时间（ISO 字符串），便于判断记录新旧。
    val createdAt: String,
)

/**
 * V1 的精确记忆存储：只按原始问题匹配，不生成概念、语义向量或推理结论。
 * 数据库 schema、匹配键和未核验/撤销语义保持原实现不变。
 */
class AnswerMemory(private val path: Path) {
    init {
        // 初始化时创建父目录，避免首次运行因为 runs/ 不存在而失败。
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection().use { db ->
            // 先读 schema 版本：0 表示新库，1 表示当前库；更高版本不应被旧代码误读。
            val version = db.createStatement().use { s ->
                s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
            }
            require(version in 0..1) { "不支持的记忆数据库版本。" }
            // 建表与写版本号放在同一事务里，避免只建了一半就留下不一致的库。
            db.autoCommit = false
            db.createStatement().use { s ->
                // 表字段按用途分三类：
                // 1) 匹配与原文：question_key 是精确查询键，raw_question 保留展示用原文；
                // 2) 回复与状态：response 保存教师原文，kind/verification_status 标明类型和未核验/撤销；
                // 3) 来源与规范化：runtime/provider/model/session/run/created_at 用于追溯，
                //    normalizer_version 固定为 identity-v1，防止未来匹配规则升级后误用旧记录。
                s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS answers (
                        question_key TEXT PRIMARY KEY COLLATE BINARY,
                        raw_question TEXT NOT NULL,
                        response TEXT NOT NULL,
                        source_runtime TEXT NOT NULL,
                        source_provider TEXT,
                        configured_model TEXT,
                        teacher_session_id TEXT,
                        source_run_id TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        normalizer_version TEXT NOT NULL DEFAULT 'identity-v1',
                        kind TEXT NOT NULL DEFAULT 'raw_teacher_answer',
                        verification_status TEXT NOT NULL DEFAULT 'unverified'
                            CHECK (verification_status IN ('unverified', 'revoked'))
                    )
                """.trimIndent())
                // 建表成功后才把版本推进到 1，并与前面的事务一起提交。
                s.execute("PRAGMA user_version = 1")
            }
            db.commit()
        }
    }

    /** 每次操作后关闭连接，避免长连接持有状态；数据库并发冲突仍按 SQLException 处理。 */
    private fun connection() = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    /**
     * 精确查询未撤销的原始问题记录；只有 identity-v1 且 unverified 的记录可复用。
     * 返回 null 表示 miss，调用方据此决定是否询问教师。
     */
    fun find(question: String): RememberedAnswer? = connection().use { db ->
        // SQL 只保留占位符 ?，不把用户问题拼进 SQL 文本；这样既避免注入，也保证按原始字符串精确匹配。
        db.prepareStatement("SELECT * FROM answers WHERE question_key = ? AND verification_status = 'unverified' AND normalizer_version = 'identity-v1'").use { query ->
            // 第 1 个参数绑定原始问题；BINARY 主键比较不会 trim、忽略大小写或做同义改写。
            query.setString(1, question)
            query.executeQuery().use { row ->
                // row.next() 为 false 就是真正的 miss；命中时按列名映射成来源完整的数据对象。
                if (!row.next()) null else RememberedAnswer(
                    row.getString("raw_question"), row.getString("response"), row.getString("source_runtime"),
                    row.getString("source_provider"), row.getString("configured_model"),
                    row.getString("teacher_session_id"), row.getString("source_run_id"), row.getString("created_at"),
                )
            }
        }
    }

    /**
     * 保存教师原始回复；INSERT OR REPLACE 保持“同一原始问题只保留最新回复”的既有行为。
     * 事务提交后返回，调用方才能记录“保存提交完成”。
     */
    fun save(question: String, reply: TeacherReply, teacher: Teacher, runId: String) = connection().use { db ->
        // 关闭自动提交：只有参数全部绑定并执行成功才 commit，失败时不会留下半条记录。
        db.autoCommit = false
        // 占位符顺序与下面 listOf 的顺序一一对应，避免把问题、回复、来源等值写串列。
        db.prepareStatement("""
            INSERT OR REPLACE INTO answers
                (question_key, raw_question, response, source_runtime, source_provider, configured_model,
                 teacher_session_id, source_run_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { insert ->
            // 前两项都是原始问题：第一项是精确键，第二项是展示用原文；其余依次是回复和来源。
            listOf(question, question, reply.text, teacher.runtime, teacher.provider, teacher.model,
                reply.sessionId, runId, Instant.now().toString()).forEachIndexed { index, value ->
                // JDBC 参数下标从 1 开始；forEachIndexed 从 0 开始，所以统一加 1。
                insert.setString(index + 1, value)
            }
            insert.executeUpdate()
        }
        // commit 返回后才算保存完成；调用方在返回后记录 memory_saved 才是准确顺序。
        db.commit()
    }

    /**
     * 撤销某个原始问题的记忆：只改 verification_status，不删除行。
     * 返回 true 表示本次确实有记录被撤销。
     */
    fun revoke(question: String): Boolean = connection().use { db ->
        // 只更新仍为 unverified 的行，重复撤销不会重复计数，也不会破坏原始来源信息。
        db.prepareStatement("UPDATE answers SET verification_status = 'revoked' WHERE question_key = ? AND verification_status <> 'revoked'").use { update ->
            // 用参数绑定原始问题，避免撤销时误伤其他相似问题。
            update.setString(1, question)
            // executeUpdate 返回受影响行数；大于 0 才说明本次真的撤销了记录。
            update.executeUpdate() > 0
        }
    }
}
