// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：实现学前第二课“颜色与简单分组”的通用属性记忆，见 experiments/lessons/preschool-002/lesson.md。
// 属性名是数据参数，可复用于后续“大小”“形状”；对象身份、名称和属性分别保存，互不替代。
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

/** 第二课固定课程标识；教材事实仍只来自材料或显式教学，不由该常量推断。 */
const val PRESCHOOL_LESSON_TWO_ID = "preschool-002"

/**
 * 第二课中一个已保存的对象身份与名称。
 *
 * objectId 是身份键，name 只是名称标签；两者分开保存，避免同名对象被合并。
 */
data class LessonObject(
    // 对象 ID；按原始字符串精确保存和匹配。
    val objectId: String,
    // 该对象当前保存的名称；同名不代表同一对象。
    val name: String,
    // 这条当前记录的来源，用于追溯给定材料或显式教学。
    val source: String,
    // 写入这条记录的运行标识。
    val sourceRunId: String,
    // 当前记录版本的保存时间（ISO 字符串）。
    val recordedAt: String,
    // 来源状态：given_in_training_world 或 explicit_teaching。
    val status: String,
)

/**
 * 一条“对象 ID＋属性名＋属性值”记录。
 *
 * 属性名和值都使用原始字符串精确匹配；缺失表示没有记录，不表示属性为假或不存在。
 */
data class ObjectAttribute(
    // 属性所属对象的 ID；属性不能单独替代对象身份。
    val objectId: String,
    // 属性名称，例如“颜色”；由材料或教学给出，不写死在代码中。
    val attributeName: String,
    // 该属性当前保存的值，例如“红色”；不做语义别名校验。
    val attributeValue: String,
    // 这条属性记录的来源。
    val source: String,
    // 写入这条属性记录的运行标识。
    val sourceRunId: String,
    // 当前属性记录版本的保存时间（ISO 字符串）。
    val recordedAt: String,
    // 来源状态：given_in_training_world 或 explicit_teaching。
    val status: String,
)

/** 按属性值反查时，把属性记录与所属对象的名称一起返回，便于直接展示分组结果。 */
data class AttributeMatch(
    // 命中的对象 ID。
    val objectId: String,
    // 命中的对象当前名称；名称只用于展示，不参与属性匹配。
    val name: String,
    // 命中的属性记录。
    val attribute: ObjectAttribute,
)

/** 一次材料导入的提交结果；只有对象表和属性表都提交成功后才构造并返回。 */
data class AttributeImportReport(
    // 已校验的课程标识，当前只接受 preschool-002。
    val lessonId: String,
    // 材料声明的来源，原样写入对象和属性记录。
    val source: String,
    // 本次写入/更新的对象，顺序与材料 objects 数组一致。
    val objects: List<LessonObject>,
    // 本次写入/更新的属性，顺序与材料中各对象的 attributes 字段一致。
    val attributes: List<ObjectAttribute>,
)

/**
 * 第二课专用的对象与属性仓库。
 *
 * 数据库只保存显式给定的对象名称和属性，不读取第一课库、问答库或教师回复；
 * 每次操作独立打开 SQLite 连接，写入使用事务，查询保持原始字符串精确匹配。
 */
class AttributeMemory(private val path: Path) {
    companion object {
        // 本库 schema 标识；重开时必须匹配，避免误读第一课或其他业务库。
        const val SCHEMA_VERSION = "lesson-attributes-v1"
        // 培训世界给定材料的来源状态。
        const val STATUS_GIVEN_IN_TRAINING_WORLD = "given_in_training_world"
        // 通过“教”命令显式补充属性后的来源状态。
        const val STATUS_EXPLICIT_TEACHING = "explicit_teaching"
        // 显式教学没有材料文件，使用固定来源字符串标明知识由研究者给出。
        const val SOURCE_EXPLICIT_TEACHING = "researcher_explicit_teaching"

        // 本课库必须包含的用户表；初始化前用它判断是否接管已有库。
        private val REQUIRED_TABLES = setOf("lesson_objects", "lesson_attributes", "lesson_meta")

        // 对象 upsert：object_id 是主键，名称和来源可随显式教学更新，但不改变身份。
        private val UPSERT_OBJECT_SQL = """
            INSERT INTO lesson_objects
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

        // 属性 upsert：同一对象同一属性只保留一条当前值；不同属性名互不影响。
        private val UPSERT_ATTRIBUTE_SQL = """
            INSERT INTO lesson_attributes
                (object_id, attribute_name, attribute_value, source, source_run_id, recorded_at, status, lesson_id, schema_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(object_id, attribute_name) DO UPDATE SET
                attribute_value = excluded.attribute_value,
                source = excluded.source,
                source_run_id = excluded.source_run_id,
                recorded_at = excluded.recorded_at,
                status = excluded.status,
                lesson_id = excluded.lesson_id,
                schema_version = excluded.schema_version
        """.trimIndent()
    }

    init {
        // 只创建本课数据库的父目录，不触碰 runs/memory.db 或第一课目录。
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        connection().use { db ->
            // 建表前先只读检查：误传旧问答库或陌生库时必须在任何写入前拒绝。
            val existingTables = tableNames(db)
            require("answers" !in existingTables) {
                "目标文件是已有问答记忆数据库（含 answers 表），拒绝写入第二课属性记忆。"
            }
            if (existingTables.isNotEmpty()) {
                require(existingTables == REQUIRED_TABLES) {
                    "目标数据库不是完整的第二课属性库，拒绝接管陌生或残缺的数据库。"
                }
                require(readMeta(db, "schema_version") == SCHEMA_VERSION) {
                    "第二课属性库的 schema 标识缺失或不匹配，拒绝打开。"
                }
                require(readMeta(db, "lesson_id") == PRESCHOOL_LESSON_TWO_ID) {
                    "第二课属性库的课程标识缺失或不匹配，拒绝打开。"
                }
            }

            // 建表、索引和元数据放在同一事务；失败回滚，避免留下半套 schema。
            db.autoCommit = false
            try {
                db.createStatement().use { statement ->
                    // 对象表只保存身份与名称；属性表用 (object_id, attribute_name) 关联。
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS lesson_objects (
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
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS lesson_attributes (
                            object_id TEXT COLLATE BINARY NOT NULL,
                            attribute_name TEXT COLLATE BINARY NOT NULL,
                            attribute_value TEXT COLLATE BINARY NOT NULL,
                            source TEXT NOT NULL,
                            source_run_id TEXT NOT NULL,
                            recorded_at TEXT NOT NULL,
                            status TEXT NOT NULL
                                CHECK (status IN ('given_in_training_world', 'explicit_teaching')),
                            lesson_id TEXT NOT NULL,
                            schema_version TEXT NOT NULL,
                            PRIMARY KEY (object_id, attribute_name)
                        )
                    """.trimIndent())
                    // 元数据表只保存整库标识，不参与对象或属性匹配。
                    statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS lesson_meta (
                            key TEXT COLLATE BINARY PRIMARY KEY,
                            value TEXT NOT NULL
                        )
                    """.trimIndent())
                    // 按属性值反查是第二课的核心路径；索引不改变 BINARY 精确比较语义。
                    statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_lesson_attributes_value " +
                            "ON lesson_attributes(attribute_name, attribute_value)"
                    )
                }
                if (readMeta(db, "schema_version") == null) {
                    insertMeta(db, "schema_version", SCHEMA_VERSION)
                }
                if (readMeta(db, "lesson_id") == null) {
                    insertMeta(db, "lesson_id", PRESCHOOL_LESSON_TWO_ID)
                }
                db.commit()
            } catch (error: Exception) {
                runCatching { db.rollback() }
                throw error
            }
        }
    }

    /** 每次操作独立连接；SQLite 错误按 SQLException 继续抛给上层。 */
    private fun connection() = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    /** 列出 SQLite 用户表并统一小写，仅用于库归属判断，不改变对象或属性的精确匹配。 */
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

    /** 读取库元数据；键用占位符绑定。 */
    private fun readMeta(db: Connection, key: String): String? =
        db.prepareStatement("SELECT value FROM lesson_meta WHERE key = ?").use { query ->
            query.setString(1, key)
            query.executeQuery().use { row ->
                if (row.next()) row.getString("value") else null
            }
        }

    /** 写入库元数据；调用前已确认键不存在。 */
    private fun insertMeta(db: Connection, key: String, value: String) {
        db.prepareStatement("INSERT INTO lesson_meta (key, value) VALUES (?, ?)").use { insert ->
            insert.setString(1, key)
            insert.setString(2, value)
            insert.executeUpdate()
        }
    }

    /**
     * 从材料文件导入对象和属性。
     *
     * 先完整读取、解析并校验全部对象、属性、课程标识和重复 ID，再开启一次事务写入；
     * 任何校验失败都不会触碰数据库，避免半写入。
     */
    fun importMaterial(materialPath: Path, runId: String): AttributeImportReport {
        require(runId.isNotBlank()) { "导入缺少来源 run_id。" }
        // 文件读取问题统一转为参数错误，由命令入口记录为材料错误而不是数据库错误。
        val text = try {
            Files.readString(materialPath)
        } catch (error: IOException) {
            throw IllegalArgumentException("无法读取材料文件，请检查路径和权限。", error)
        } catch (error: SecurityException) {
            throw IllegalArgumentException("无法读取材料文件，请检查访问权限。", error)
        }

        val rootElement = try {
            Json.parseToJsonElement(text)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("材料文件不是合法的 JSON。", error)
        }
        val root = rootElement as? JsonObject
            ?: throw IllegalArgumentException("材料根节点必须是 JSON 对象。")

        // 课程标识防止把第一课或其他课程的材料导入本库。
        val lessonId = root["lesson_id"].requiredMaterialString("lesson_id")
        require(lessonId == PRESCHOOL_LESSON_TWO_ID) {
            "材料 lesson_id 必须是 $PRESCHOOL_LESSON_TWO_ID。"
        }
        val source = root["source"].requiredMaterialString("source")

        val objectArray = root["objects"] as? JsonArray
            ?: throw IllegalArgumentException("材料字段 objects 必须是数组。")
        require(objectArray.isNotEmpty()) { "材料 objects 不能为空。" }
        val parsed = objectArray.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw IllegalArgumentException("材料 objects[$index] 必须是对象。")
            val id = item["id"].requiredMaterialString("objects[$index].id")
            val name = item["name"].requiredMaterialString("objects[$index].name")
            // attributes 可省略，表示该对象当前没有任何属性记录；缺失颜色不会自动补默认值。
            val attributes = when (val rawAttributes = item["attributes"]) {
                null -> emptyMap()
                is JsonObject -> rawAttributes.entries.associate { (attributeName, rawValue) ->
                    require(attributeName.isNotBlank()) {
                        "材料 objects[$index] 的属性名不能为空。"
                    }
                    val value = rawValue.requiredMaterialString(
                        "objects[$index].attributes[$attributeName]",
                    )
                    attributeName to value
                }
                else -> throw IllegalArgumentException(
                    "材料字段 objects[$index].attributes 必须是对象。",
                )
            }
            MaterialObject(id, name, attributes)
        }

        // 重复 ID 会让“一个对象只有一条身份记录”的语义含糊，写入前整体拒绝。
        val duplicateIds = parsed.groupingBy { it.id }.eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "材料包含重复对象ID：${duplicateIds.joinToString("、")}"
        }

        // 同一次导入共用一个时间戳，表明这些对象和属性来自同一次材料载入。
        val recordedAt = Instant.now().toString()
        val objects = parsed.map { item ->
            LessonObject(
                objectId = item.id,
                name = item.name,
                source = source,
                sourceRunId = runId,
                recordedAt = recordedAt,
                status = STATUS_GIVEN_IN_TRAINING_WORLD,
            )
        }
        // 属性按“对象 ID＋属性名”展开；材料里没写的属性不会产生行。
        val attributes = parsed.flatMap { item ->
            item.attributes.map { (attributeName, attributeValue) ->
                ObjectAttribute(
                    objectId = item.id,
                    attributeName = attributeName,
                    attributeValue = attributeValue,
                    source = source,
                    sourceRunId = runId,
                    recordedAt = recordedAt,
                    status = STATUS_GIVEN_IN_TRAINING_WORLD,
                )
            }
        }

        // 只有事务提交成功后才构造报告；失败时对象和属性都不会留下。
        saveAll(objects, attributes)
        return AttributeImportReport(lessonId, source, objects, attributes)
    }

    /**
     * 显式教授一个对象的某个属性值。
     *
     * 对象必须已在材料或此前的对象记录中存在；属性只附着在已知对象上，
     * 不从属性教学反推或创建新的对象身份。检查与 upsert 在同一事务内完成。
     */
    fun teachAttribute(
        objectId: String,
        attributeName: String,
        attributeValue: String,
        runId: String,
    ): ObjectAttribute {
        require(objectId.isNotBlank()) { "对象ID不能为空。" }
        require(attributeName.isNotBlank()) { "属性名不能为空。" }
        require(attributeValue.isNotBlank()) { "属性值不能为空。" }
        require(runId.isNotBlank()) { "显式教学缺少来源 run_id。" }
        val record = ObjectAttribute(
            objectId = objectId,
            attributeName = attributeName,
            attributeValue = attributeValue,
            source = SOURCE_EXPLICIT_TEACHING,
            sourceRunId = runId,
            recordedAt = Instant.now().toString(),
            status = STATUS_EXPLICIT_TEACHING,
        )
        connection().use { db ->
            db.autoCommit = false
            try {
                require(objectExists(db, objectId)) {
                    "对象ID $objectId 未在对象记忆中出现，不能单独保存属性。"
                }
                upsertAttributes(db, listOf(record))
                db.commit()
            } catch (error: Exception) {
                // 校验失败、磁盘或并发错误都回滚，原始异常继续上抛，不吞成 unknown。
                runCatching { db.rollback() }
                throw error
            }
        }
        return record
    }

    /** 按对象 ID 精确查询身份与名称；未命中返回 null，由上层输出 unknown。 */
    fun findObject(objectId: String): LessonObject? {
        require(objectId.isNotBlank()) { "对象ID不能为空。" }
        return connection().use { db -> findObject(db, objectId) }
    }

    /** 按对象 ID 与属性名精确读取一条属性；对象存在但属性缺失时返回 null。 */
    fun findAttribute(objectId: String, attributeName: String): ObjectAttribute? {
        require(objectId.isNotBlank()) { "对象ID不能为空。" }
        require(attributeName.isNotBlank()) { "属性名不能为空。" }
        return connection().use { db ->
            db.prepareStatement("""
                SELECT object_id, attribute_name, attribute_value, source, source_run_id, recorded_at, status
                FROM lesson_attributes
                WHERE object_id = ? AND attribute_name = ?
            """.trimIndent()).use { query ->
                // 两个条件都绑定原始字符串；BINARY 比较不做 trim、大小写或别名处理。
                query.setString(1, objectId)
                query.setString(2, attributeName)
                query.executeQuery().use { row ->
                    if (row.next()) readAttribute(row) else null
                }
            }
        }
    }

    /**
     * 按属性名和属性值反查所有对象。
     *
     * 连接对象表以带回名称；结果按 object_id 排序，保证同一次分组顺序稳定。
     * 空结果只表示没有已记录的匹配属性，不表示现实中没有这样的对象。
     */
    fun findByAttributeValue(attributeName: String, attributeValue: String): List<AttributeMatch> {
        require(attributeName.isNotBlank()) { "属性名不能为空。" }
        require(attributeValue.isNotBlank()) { "属性值不能为空。" }
        return connection().use { db ->
            db.prepareStatement("""
                SELECT o.name AS object_name,
                       a.object_id, a.attribute_name, a.attribute_value,
                       a.source, a.source_run_id, a.recorded_at, a.status
                FROM lesson_attributes a
                JOIN lesson_objects o ON o.object_id = a.object_id
                WHERE a.attribute_name = ? AND a.attribute_value = ?
                ORDER BY a.object_id COLLATE BINARY
            """.trimIndent()).use { query ->
                // 属性名和值都来自命令参数，精确匹配；不扩展到同义词或近似颜色。
                query.setString(1, attributeName)
                query.setString(2, attributeValue)
                query.executeQuery().use { row ->
                    val matches = mutableListOf<AttributeMatch>()
                    while (row.next()) {
                        matches += AttributeMatch(
                            objectId = row.getString("object_id"),
                            name = row.getString("object_name"),
                            attribute = readAttribute(row),
                        )
                    }
                    matches
                }
            }
        }
    }

    /** 在给定连接上按 ID 读取对象，供查询与教学事务复用。 */
    private fun findObject(db: Connection, objectId: String): LessonObject? =
        db.prepareStatement("""
            SELECT object_id, name, source, source_run_id, recorded_at, status
            FROM lesson_objects
            WHERE object_id = ?
        """.trimIndent()).use { query ->
            query.setString(1, objectId)
            query.executeQuery().use { row ->
                if (row.next()) readObject(row) else null
            }
        }

    /** 教学前确认对象身份存在；属性不能脱离对象单独创建。 */
    private fun objectExists(db: Connection, objectId: String): Boolean =
        db.prepareStatement("SELECT 1 FROM lesson_objects WHERE object_id = ?").use { query ->
            query.setString(1, objectId)
            query.executeQuery().use { row -> row.next() }
        }

    /**
     * 材料导入的统一事务：全部对象和属性成功才提交。
     *
     * 只做 upsert，不删除材料中未出现的旧属性；因此重新导入初始材料不会抹掉
     * 此前显式教过的颜色，也不会把缺失属性误写成否定。要重做未知阶段请换新库。
     */
    private fun saveAll(objects: List<LessonObject>, attributes: List<ObjectAttribute>) {
        if (objects.isEmpty() && attributes.isEmpty()) return
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement(UPSERT_OBJECT_SQL).use { upsert ->
                    objects.forEach { record ->
                        // 下标 1..8 与 UPSERT_OBJECT_SQL 列顺序严格对应。
                        upsert.setString(1, record.objectId)
                        upsert.setString(2, record.name)
                        upsert.setString(3, record.source)
                        upsert.setString(4, record.sourceRunId)
                        upsert.setString(5, record.recordedAt)
                        upsert.setString(6, record.status)
                        upsert.setString(7, PRESCHOOL_LESSON_TWO_ID)
                        upsert.setString(8, SCHEMA_VERSION)
                        upsert.executeUpdate()
                    }
                }
                upsertAttributes(db, attributes)
                // 对象和属性都写完后才提交，避免只导入对象或只导入部分属性。
                db.commit()
            } catch (error: Exception) {
                runCatching { db.rollback() }
                throw error
            }
        }
    }

    /** 在给定连接上批量 upsert 属性；调用方负责事务提交或回滚。 */
    private fun upsertAttributes(db: Connection, attributes: List<ObjectAttribute>) {
        if (attributes.isEmpty()) return
        db.prepareStatement(UPSERT_ATTRIBUTE_SQL).use { upsert ->
            attributes.forEach { record ->
                // 下标 1..9 与 UPSERT_ATTRIBUTE_SQL 列顺序严格对应。
                upsert.setString(1, record.objectId)
                upsert.setString(2, record.attributeName)
                upsert.setString(3, record.attributeValue)
                upsert.setString(4, record.source)
                upsert.setString(5, record.sourceRunId)
                upsert.setString(6, record.recordedAt)
                upsert.setString(7, record.status)
                upsert.setString(8, PRESCHOOL_LESSON_TWO_ID)
                upsert.setString(9, SCHEMA_VERSION)
                upsert.executeUpdate()
            }
        }
    }

    /** 把结果集当前行映射为对象记录；列名与建表字段一致。 */
    private fun readObject(row: ResultSet) = LessonObject(
        objectId = row.getString("object_id"),
        name = row.getString("name"),
        source = row.getString("source"),
        sourceRunId = row.getString("source_run_id"),
        recordedAt = row.getString("recorded_at"),
        status = row.getString("status"),
    )

    /** 把结果集当前行映射为属性记录；对象名在反查 SQL 中另用 object_name 别名读取。 */
    private fun readAttribute(row: ResultSet) = ObjectAttribute(
        objectId = row.getString("object_id"),
        attributeName = row.getString("attribute_name"),
        attributeValue = row.getString("attribute_value"),
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

    /** 材料解析临时结构：对象名称与属性映射分开，属性可缺失。 */
    private data class MaterialObject(
        val id: String,
        val name: String,
        val attributes: Map<String, String>,
    )
}
