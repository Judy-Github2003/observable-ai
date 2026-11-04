// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：依据学前第一课课程约定新增独立 SQLite 对象记忆，见 experiments/lessons/preschool-001/lesson.md。
// 本文件只保存显式给出的对象 ID、名称和来源；不包含语义推断、别名、分类或世界真理。
// Codex 局部审计修正，2026-11-04：建表前严格验证专用库归属，拒绝异库和残缺元数据，避免污染旧文件。
package logic

import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.Locale

/** 本课固定课程标识；对象知识本身仍只能来自材料或“教”命令，不能由这个常量推断。 */
const val PRESCHOOL_LESSON_ID = "preschool-001"

/**
 * 对象记忆中一条已保存的“对象 ID → 名称”记录。
 *
 * objectId 与 name 是两个独立字段：ID 用于身份比较，名称只是本轮显式保存的标签。
 * 同名记录允许多条，所以不能用 name 代替 objectId，也不能据此认为两条记录是同一个对象。
 */
data class NamedObject(
    // 对象 ID；按原始字符串精确保存和匹配。
    val objectId: String,
    // 该对象当前保存的名称；同名不代表同一对象。
    val name: String,
    // 来源字符串：材料来源或显式教学来源，用于追溯而不是宣称世界真理。
    val source: String,
    // 写入这条当前记录时的运行标识，便于回到产生它的那次课程操作。
    val sourceRunId: String,
    // 这条当前记录版本的保存时间（ISO 字符串）。
    val recordedAt: String,
    // 记录状态：given_in_training_world 或 explicit_teaching。
    val status: String,
)

/** 一次材料导入的提交结果；只有事务提交成功后才会构造并返回它。 */
data class ObjectImportReport(
    // 已校验的课程标识，当前只接受 preschool-001。
    val lessonId: String,
    // 材料中声明的来源，原样保存到每条对象记录。
    val source: String,
    // 本次实际写入/更新的对象记录，顺序与材料 objects 数组一致。
    val objects: List<NamedObject>,
)

/**
 * 第一课专用的对象记忆仓库。
 *
 * 它使用调用方显式传入的新数据库路径，不读取 AnswerMemory 的 answers 表，
 * 也不迁移、覆盖或复用旧的问答缓存。数据库只保存对象 ID、名称、来源和状态。
 */
class ObjectNames(private val path: Path) {
    companion object {
        // 数据库内的 schema 标识；打开已有对象库时先核对它，避免误读不同布局。
        const val SCHEMA_VERSION = "object-names-v1"
        // 训练世界中由研究者给出的来源状态，不表示“世界真理”。
        const val STATUS_GIVEN_IN_TRAINING_WORLD = "given_in_training_world"
        // 通过“教”命令显式教授后的来源状态。
        const val STATUS_EXPLICIT_TEACHING = "explicit_teaching"
        // 显式教学没有材料文件，因此使用固定来源字符串标明是谁给出的知识。
        const val SOURCE_EXPLICIT_TEACHING = "researcher_explicit_teaching"

        // 八列顺序与 saveAll 中的绑定下标严格对应：ID、名称、来源、运行、时间、状态、课程、schema。
        private val UPSERT_SQL = """
            INSERT INTO object_names
                (object_id, name, source, source_run_id, recorded_at, status, lesson_id, schema_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(object_id) DO UPDATE SET
                name = excluded.name,
                source = excluded.source,
                source_run_id = excluded.source_run_id,
                recorded_at = excluded.recorded_at,
                status = excluded.status,
                lesson_id = excluded.lesson_id,
                schema_version = excluded.schema_version
        """.trimIndent()
    }

    init {
        // 只创建数据库文件的父目录；不会去创建或读取 runs/memory.db 等旧库。
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection().use { db ->
            // 先只读检查已有表：如果目标是 AnswerMemory 的 answers 库，必须在任何建表/写入前拒绝。
            val existingTables = tableNames(db)
            require("answers" !in existingTables) {
                "目标文件是已有问答记忆数据库（含 answers 表），拒绝写入对象记忆，以免污染旧库。"
            }
            // Codex 审计：只有空用户表库能初始化；非空库必须完整属于本课，不能补建残缺表或混入业务库。
            if (existingTables.isNotEmpty()) {
                require(existingTables == setOf("object_names", "object_memory_meta")) {
                    "目标数据库不是完整的本课对象库，拒绝接管陌生或残缺的数据库。"
                }
                require(readMeta(db, "schema_version") == SCHEMA_VERSION) {
                    "对象记忆数据库的 schema 标识缺失或不匹配，拒绝打开。"
                }
                require(readMeta(db, "lesson_id") == PRESCHOOL_LESSON_ID) {
                    "对象记忆数据库的课程标识缺失或不匹配，拒绝打开。"
                }
            }

            // 建表、建索引和写元数据放在同一事务里；失败则回滚，避免留下半套 schema。
            db.autoCommit = false
            try {
                db.createStatement().use { statement ->
                    // object_id 是主键：身份比较只看这个精确字符串，名称不能替代身份。
                    // name 允许重复：不同对象可以同名，因此名称不能替代 ID 作为身份。
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS object_names (
                            object_id TEXT COLLATE BINARY PRIMARY KEY,
                            name TEXT COLLATE BINARY NOT NULL,
                            source TEXT NOT NULL,
                            source_run_id TEXT NOT NULL,
                            recorded_at TEXT NOT NULL,
                            status TEXT NOT NULL
                                CHECK (status IN ('given_in_training_world', 'explicit_teaching')),
                            lesson_id TEXT NOT NULL,
                            schema_version TEXT NOT NULL
                        )
                    """.trimIndent())
                    // 元数据表保存整库的 schema 与课程标识，不参与对象匹配。
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS object_memory_meta (
                            key TEXT COLLATE BINARY PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                    """.trimIndent())
                    // 反向查询按名称精确过滤；索引只是加速，不改变 BINARY 精确比较语义。
                    statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_object_names_name ON object_names(name)"
                    )
                }
                // 新库没有 schema 元数据时补写；已有正确元数据时保留原值。
                if (readMeta(db, "schema_version") == null) {
                    insertMeta(db, "schema_version", SCHEMA_VERSION)
                }
                if (readMeta(db, "lesson_id") == null) {
                    insertMeta(db, "lesson_id", PRESCHOOL_LESSON_ID)
                }
                // 只有上面的建表和元数据都成功才提交。
                db.commit()
            } catch (error: Exception) {
                // 初始化失败时尽量回滚；回滚本身失败也不能覆盖最初的异常。
                runCatching { db.rollback() }
                throw error
            }
        }
    }

    /** 每次操作独立打开连接；本地 SQLite 的数据库错误继续按 SQLException 抛给上层。 */
    private fun connection() = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    /** Codex 审计：忽略 SQLite 内表，统一表名大小写；只识别库归属，不改动对象 ID 或名称的精确匹配。 */
    private fun tableNames(db: Connection): Set<String> {
        val names = mutableSetOf<String>()
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { row ->
                while (row.next()) {
                    val name = row.getString("name").lowercase(Locale.ROOT)
                    if (!name.startsWith("sqlite_")) names += name
                }
            }
        }
        return names
    }

    /** 读取对象库元数据；键用占位符绑定，避免把内部标识拼进 SQL 文本。 */
    private fun readMeta(db: Connection, key: String): String? =
        db.prepareStatement("SELECT value FROM object_memory_meta WHERE key = ?").use { query ->
            query.setString(1, key)
            query.executeQuery().use { row ->
                if (row.next()) row.getString("value") else null
            }
        }

    /** 写入元数据键值；调用前已确认键不存在，仍使用参数绑定保持统一。 */
    private fun insertMeta(db: Connection, key: String, value: String) {
        db.prepareStatement("INSERT INTO object_memory_meta (key, value) VALUES (?, ?)").use { insert ->
            insert.setString(1, key)
            insert.setString(2, value)
            insert.executeUpdate()
        }
    }

    /**
     * 从材料文件批量导入对象。
     *
     * 顺序很重要：先完整读取、解析并校验全部字段、lesson_id 和重复 ID，
     * 再开启一次事务写入所有记录。任何解析或校验失败都不会触碰数据库，因此不会半写入。
     */
    fun importMaterial(materialPath: Path, runId: String): ObjectImportReport {
        // runId 会写进每行的 source_run_id；空值会让来源无法追溯。
        require(runId.isNotBlank()) { "导入缺少来源 run_id。" }
        // 路径缺失、无权限或目录等读取问题都视为材料参数错误，交给 Main 按参数错误处理。
        val text = try {
            Files.readString(materialPath)
        } catch (error: IOException) {
            throw IllegalArgumentException("无法读取材料文件，请检查路径和权限。", error)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("无法读取材料文件，请检查访问权限。", error)
        }

        // 整个 JSON 必须是一个对象；解析失败或根节点类型不对都视为材料格式错误，不进入数据库事务。
        val rootElement = try {
            Json.parseToJsonElement(text)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("材料文件不是合法的 JSON。", error)
        }
        val root = rootElement as? JsonObject
            ?: throw IllegalArgumentException("材料根节点必须是 JSON 对象。")

        // lesson_id 是课程边界：材料只能导入本课，防止把其他课程对象混进本库。
        val lessonId = root["lesson_id"].requiredMaterialString("lesson_id")
        require(lessonId == PRESCHOOL_LESSON_ID) {
            "材料 lesson_id 必须是 $PRESCHOOL_LESSON_ID。"
        }
        // source 原样保存；这里不把它升格为对现实世界的断言。
        val source = root["source"].requiredMaterialString("source")

        // objects 必须是非空数组；每个元素必须有非空字符串 id 和 name。
        val objectArray = root["objects"] as? JsonArray
            ?: throw IllegalArgumentException("材料字段 objects 必须是数组。")
        require(objectArray.isNotEmpty()) { "材料 objects 不能为空。" }
        val parsed = objectArray.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("材料 objects[$index] 必须是对象。")
            // 分别校验 ID 和名称，保留用户给出的原始字符串，不 trim、不猜测、不改写。
            MaterialObject(
                id = item["id"].requiredMaterialString("objects[$index].id"),
                name = item["name"].requiredMaterialString("objects[$index].name"),
            )
        }

        // 重复 ID 会让“一个 ID 对应一条当前记录”的身份语义含糊，因此在写入前整体拒绝。
        val duplicateIds = parsed.groupingBy { it.id }.eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "材料包含重复对象ID：${duplicateIds.joinToString("、")}"
        }

        // 同一次批量导入共用一个时间戳，明确这批记录来自同一次材料载入。
        val recordedAt = Instant.now().toString()
        // 材料导入的状态固定为 given_in_training_world；它只描述来源，不宣称世界真理。
        val records = parsed.map { item ->
            NamedObject(
                objectId = item.id,
                name = item.name,
                source = source,
                sourceRunId = runId,
                recordedAt = recordedAt,
                status = STATUS_GIVEN_IN_TRAINING_WORLD,
            )
        }
        // 只有 saveAll 返回（事务已提交）后才构造成功报告。
        saveAll(records)
        return ObjectImportReport(lessonId, source, records)
    }

    /**
     * 显式教授一个对象名称。
     *
     * 对象 ID 已存在时允许更新名称和本次来源元数据，但不保留被覆盖的旧名称历史；
     * 只有 upsert 事务提交成功后才返回新记录，调用方随后才能记录“成功”。
     */
    fun teach(objectId: String, name: String, runId: String): NamedObject {
        // 空 ID、空名称或空 run_id 无法形成可追溯记录；只做空白检查，不修改原字符串。
        require(objectId.isNotBlank()) { "对象ID不能为空。" }
        require(name.isNotBlank()) { "名称不能为空。" }
        require(runId.isNotBlank()) { "显式教学缺少来源 run_id。" }
        val record = NamedObject(
            objectId = objectId,
            name = name,
            source = SOURCE_EXPLICIT_TEACHING,
            sourceRunId = runId,
            recordedAt = Instant.now().toString(),
            status = STATUS_EXPLICIT_TEACHING,
        )
        // teach 也复用同一事务写入逻辑；提交失败会回滚并原样抛出。
        saveAll(listOf(record))
        return record
    }

    /** 按对象 ID 精确查询；未命中返回 null，由调用方输出 object_not_observed，而不是猜名称。 */
    fun findById(objectId: String): NamedObject? {
        // 空 ID 是参数错误，不能当作一次正常 miss。
        require(objectId.isNotBlank()) { "对象ID不能为空。" }
        return connection().use { db ->
            db.prepareStatement("""
                SELECT object_id, name, source, source_run_id, recorded_at, status
                FROM object_names
                WHERE object_id = ?
            """.trimIndent()).use { query ->
                // 绑定原始 ID；BINARY 主键比较不会 trim、忽略大小写或做别名匹配。
                query.setString(1, objectId)
                query.executeQuery().use { row ->
                    if (row.next()) readObject(row) else null
                }
            }
        }
    }

    /**
     * 按名称反向查询所有同名对象。
     *
     * 一个名称允许多个 ID，所以返回列表而不是单个对象；ORDER BY object_id 提供稳定顺序。
     */
    fun findByName(name: String): List<NamedObject> {
        // 空名称属于参数错误；查询本身保持原始字符串精确匹配。
        require(name.isNotBlank()) { "名称不能为空。" }
        return connection().use { db ->
            db.prepareStatement("""
                SELECT object_id, name, source, source_run_id, recorded_at, status
                FROM object_names
                WHERE name = ?
                ORDER BY object_id COLLATE BINARY
            """.trimIndent()).use { query ->
                // 精确绑定查询名称，不允许模糊匹配或语义别名。
                query.setString(1, name)
                query.executeQuery().use { row ->
                    val matches = mutableListOf<NamedObject>()
                    while (row.next()) matches += readObject(row)
                    matches
                }
            }
        }
    }

    /**
     * 在执行批量导入或单条教学时统一写入。
     *
     * 一次调用最多一个事务：任何一行绑定或 executeUpdate 失败都回滚整批，
     * 不会出现“前面的对象已保存、后面的对象失败”的半写入状态。
     */
    private fun saveAll(records: List<NamedObject>) {
        if (records.isEmpty()) return
        connection().use { db ->
            // 关闭自动提交后，只有全部 upsert 成功才 commit。
            db.autoCommit = false
            try {
                db.prepareStatement(UPSERT_SQL).use { upsert ->
                    records.forEach { record ->
                        // 下标 1..8 与 UPSERT_SQL 的列顺序一一对应；全部按原始字符串绑定。
                        upsert.setString(1, record.objectId)
                        upsert.setString(2, record.name)
                        upsert.setString(3, record.source)
                        upsert.setString(4, record.sourceRunId)
                        upsert.setString(5, record.recordedAt)
                        upsert.setString(6, record.status)
                        upsert.setString(7, PRESCHOOL_LESSON_ID)
                        upsert.setString(8, SCHEMA_VERSION)
                        upsert.executeUpdate()
                    }
                }
                // 提交完成后才向调用方表示操作成功。
                db.commit()
            } catch (error: Exception) {
                // 数据库约束、磁盘或并发错误都回滚；原始异常继续抛给上层，不吞成 unknown。
                runCatching { db.rollback() }
                throw error
            }
        }
    }

    /** 把结果集当前行映射为对象记录；列名与建表字段保持一致。 */
    private fun readObject(row: ResultSet) = NamedObject(
        objectId = row.getString("object_id"),
        name = row.getString("name"),
        source = row.getString("source"),
        sourceRunId = row.getString("source_run_id"),
        recordedAt = row.getString("recorded_at"),
        status = row.getString("status"),
    )

    /** 材料字段校验：必须是非空字符串；检查空白但不改写原值。 */
    private fun JsonElement?.requiredMaterialString(field: String): String {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException("材料字段 $field 必须是字符串。")
        require(primitive.isString) { "材料字段 $field 必须是字符串。" }
        val value = primitive.content
        require(value.isNotBlank()) { "材料字段 $field 不能为空。" }
        return value
    }

    /** 材料解析过程中的临时结构；分开保存 ID 和名称，避免把二者混为一谈。 */
    private data class MaterialObject(
        val id: String,
        val name: String,
    )
}
