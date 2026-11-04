// 本文件新增测试：DeepSeek Harness，2026-11-04。
// 创建原因：依据 experiments/lessons/preschool-001/lesson.md 补充离线测试，覆盖持久化、同名不同对象、未知、
// 显式教学、通用 ID、坏材料原子性、未知比较和拒绝旧 answers 库。
// 本文件只声明测试场景，不代表已经运行或通过；独立验证由后续步骤负责。
// Codex 局部审计补充，2026-11-04：扩展原第三个用例，确认异库、大小写旧表和残缺/错误元数据均在写入前被拒绝。
package logic

import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

/** 第一课行为：对象 ID/名称显式记忆、反向查询、身份比较、未知处理和专用库边界。 */
class LessonOneTest {
    // 每个测试独立临时目录；对象库、事件文件不会跨测试或触碰正式 runs 目录。
    @get:Rule val temp = TemporaryFolder()

    /**
     * 场景一：导入研究者给定的训练世界，重开对象库后仍能查询。
     * 同时验证 A/D 同名不同对象、A/A 同一对象、E 未知、显式教 E 后持久化，以及全程 0 教师调用。
     */
    @Test fun `imported names persist and explicit teaching adds a previously unknown object`() {
        val db = temp.root.toPath().resolve("object-names.db")
        val log = temp.root.toPath().resolve("lesson-events.jsonl")
        // 材料对象只在这里出现，不写进 LessonOne/ObjectNames 的固定逻辑。
        val material = materialFile(
            "training-world.json",
            listOf("A" to "苹果", "B" to "杯子", "C" to "球", "D" to "苹果"),
        )
        val events1 = Events(log, studentVersion = "PRESCHOOL-001")
        // 操作：导入完整材料；期望一次事务保存四条对象记录。
        val imported = json(
            LessonOne(ObjectNames(db), events1).execute(listOf("导入", material.toString())),
        )
        assertEquals("ok", imported.str("status"))
        assertEquals(PRESCHOOL_LESSON_ID, imported.str("lesson_id"))
        assertEquals(4, imported.int("saved_count"))
        assertEquals(listOf("A", "B", "C", "D"), imported.strings("object_ids"))
        assertEquals("researcher_given_training_world", imported.str("source"))
        assertEquals(events1.runId, imported.str("source_run_id"))
        assertEquals(0, imported.int("teacher_invocation_count"))

        // 重新打开数据库和 Events，模拟新一次运行；旧记录仍按原始 ID 和名称读出。
        val events2 = Events(log, studentVersion = "PRESCHOOL-001")
        val lesson = LessonOne(ObjectNames(db), events2)
        val a = json(lesson.execute(listOf("查", "A")))
        assertEquals("ok", a.str("status"))
        assertEquals("A", a.str("object_id"))
        assertEquals("苹果", a.str("name"))
        assertEquals("researcher_given_training_world", a.str("source"))
        assertEquals("given_in_training_world", a.str("object_status"))
        assertEquals(events1.runId, a.str("source_run_id"))
        assertEquals(0, a.int("teacher_invocation_count"))

        // 反向查询必须保留全部同名对象，并按 ID 稳定排序。
        val apples = json(lesson.execute(listOf("找", "苹果")))
        assertEquals("ok", apples.str("status"))
        assertEquals(listOf("A", "D"), apples.strings("object_ids"))
        assertEquals(2, apples.int("match_count"))

        // A 与 D 同名但不是同一个对象；A 与自身才是同一个对象。
        val aVersusD = json(lesson.execute(listOf("比较", "A", "D")))
        assertEquals("ok", aVersusD.str("status"))
        assertFalse(aVersusD.bool("same_object"))
        assertTrue(aVersusD.bool("same_name"))
        val aVersusA = json(lesson.execute(listOf("比较", "A", "A")))
        assertTrue(aVersusA.bool("same_object"))
        assertTrue(aVersusA.bool("same_name"))

        // E 尚未被材料或教学观察；查询和比较都只能返回 unknown，不能猜测名称。
        val eUnknown = json(lesson.execute(listOf("查", "E")))
        assertEquals("unknown", eUnknown.str("status"))
        assertEquals("object_not_observed", eUnknown.str("reason"))
        assertEquals("E", eUnknown.str("object_id"))
        assertFalse(eUnknown.containsKey("name"))
        val eVersusA = json(lesson.execute(listOf("比较", "E", "A")))
        assertEquals("unknown", eVersusA.str("status"))
        assertEquals(listOf("E"), eVersusA.strings("missing_object_ids"))
        // 未知 ID 与自己比较也不能被自动当成已认识的对象。
        val eVersusE = json(lesson.execute(listOf("比较", "E", "E")))
        assertEquals("unknown", eVersusE.str("status"))
        assertEquals(listOf("E"), eVersusE.strings("missing_object_ids"))
        assertFalse(eVersusE.containsKey("same_object"))

        // 操作：显式教 E 名称为“书”；成功后才应写入并返回新记录。
        val taught = json(lesson.execute(listOf("教", "E", "书")))
        assertEquals("ok", taught.str("status"))
        assertEquals("E", taught.str("object_id"))
        assertEquals("书", taught.str("name"))
        assertEquals("explicit_teaching", taught.str("object_status"))
        assertEquals("researcher_explicit_teaching", taught.str("source"))
        assertEquals(events2.runId, taught.str("source_run_id"))
        assertEquals(0, taught.int("teacher_invocation_count"))

        // 再次重开：E 的新名称和本次教学来源仍在，材料中的旧对象也没有丢失。
        val events3 = Events(log, studentVersion = "PRESCHOOL-001")
        val reopened = LessonOne(ObjectNames(db), events3)
        val e = json(reopened.execute(listOf("查", "E")))
        assertEquals("ok", e.str("status"))
        assertEquals("书", e.str("name"))
        assertEquals("explicit_teaching", e.str("object_status"))
        assertEquals(events2.runId, e.str("source_run_id"))
        val aStillKnown = json(reopened.execute(listOf("查", "A")))
        assertEquals("ok", aStillKnown.str("status"))
        assertEquals("苹果", aStillKnown.str("name"))
        assertEquals("given_in_training_world", aStillKnown.str("object_status"))
        assertEquals(events1.runId, aStillKnown.str("source_run_id"))

        // 本课所有 JSONL 事件都应写明教师调用为 0，且没有任何 teacher_request。
        val rows = Files.readAllLines(log).map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it["teacher_invocation_count"]?.jsonPrimitive?.int == 0 })
        assertTrue(rows.none { it["event_type"]?.jsonPrimitive?.content == "teacher_request" })
        // 导入输入归 harness，查询输入归 student，来源不误记为学生的自主观察。
        val importInputs = rows.filter {
            it["event_type"]?.jsonPrimitive?.content == "lesson_input" &&
                it["operation"]?.jsonPrimitive?.content == "导入"
        }
        assertTrue(importInputs.isNotEmpty())
        assertTrue(importInputs.all { it["actor"]?.jsonPrimitive?.content == "harness" })
        val lookupInputs = rows.filter {
            it["event_type"]?.jsonPrimitive?.content == "lesson_input" &&
                it["operation"]?.jsonPrimitive?.content == "查"
        }
        assertTrue(lookupInputs.isNotEmpty())
        assertTrue(lookupInputs.all { it["actor"]?.jsonPrimitive?.content == "student" })
    }

    /**
     * 场景二：用非 A-D、非苹果的 ID/名称验证通用性；
     * 重复 ID 的坏材料必须在事务前被拒，不能留下部分对象；未知比较仍为 unknown。
     */
    @Test fun `general ids keep bad imports atomic and unknown comparison stays unknown`() {
        val db = temp.root.toPath().resolve("general-objects.db")
        val log = temp.root.toPath().resolve("general-events.jsonl")
        val lesson = LessonOne(ObjectNames(db), Events(log, studentVersion = "PRESCHOOL-001"))

        // Codex 审计回归：缺少 ID 时只发生命令参数校验，不能把未调用的 findById 记成失败。
        assertFailsWith<IllegalArgumentException> { lesson.execute(listOf("查")) }
        val parameterFailure = Files.readAllLines(log).map { json(it) }
            .last { it["error_code"]?.jsonPrimitive?.content == "INVALID_ARGUMENT" }
        assertEquals("LessonOne.execute", parameterFailure.str("function"))

        // 尚未导入任何对象时，未知 ID 比较必须返回去重后的缺失列表。
        val beforeImport = json(lesson.execute(listOf("比较", "K9", "M2")))
        assertEquals("unknown", beforeImport.str("status"))
        assertEquals("object_not_observed", beforeImport.str("reason"))
        assertEquals(listOf("K9", "M2"), beforeImport.strings("missing_object_ids"))

        // 重复 ID 材料整体非法；第三个唯一对象也不能被提前写入。
        val badMaterial = materialFile(
            "bad-material.json",
            listOf("K9" to "灯", "K9" to "灯罩", "Z1" to "窗"),
        )
        assertFailsWith<IllegalArgumentException> {
            lesson.execute(listOf("导入", badMaterial.toString()))
        }
        assertEquals("unknown", json(lesson.execute(listOf("查", "K9"))).str("status"))
        assertEquals("unknown", json(lesson.execute(listOf("查", "Z1"))).str("status"))
        val noNamesYet = json(lesson.execute(listOf("找", "灯")))
        assertEquals("unknown", noNamesYet.str("status"))
        assertEquals("name_not_observed", noNamesYet.str("reason"))
        assertTrue(noNamesYet.strings("object_ids").isEmpty())

        // 合法材料使用全新 ID 和名称；同名“灯”对应两个不同对象，顺序按 ID 排序。
        val goodMaterial = materialFile(
            "general-material.json",
            listOf("K9" to "灯", "Q7" to "灯", "Z1" to "窗"),
        )
        val imported = json(lesson.execute(listOf("导入", goodMaterial.toString())))
        assertEquals("ok", imported.str("status"))
        assertEquals(3, imported.int("saved_count"))
        val lamps = json(lesson.execute(listOf("找", "灯")))
        assertEquals(listOf("K9", "Q7"), lamps.strings("object_ids"))
        assertEquals(2, lamps.int("match_count"))

        // 只认识 K9 而 M2 仍未知时，比较仍为 unknown，缺失列表只含 M2。
        val partiallyUnknown = json(lesson.execute(listOf("比较", "K9", "M2")))
        assertEquals("unknown", partiallyUnknown.str("status"))
        assertEquals(listOf("M2"), partiallyUnknown.strings("missing_object_ids"))
        val z1 = json(lesson.execute(listOf("查", "Z1")))
        assertEquals("ok", z1.str("status"))
        assertEquals("窗", z1.str("name"))
        assertEquals("given_in_training_world", z1.str("object_status"))
    }

    /** 场景三：只接纳空库或完整本课库；拒绝异库/残缺元数据时不改变数据库字节。 */
    @Test fun `object memory refuses foreign partial and incompatible databases`() {
        // Codex 审计：这些 SQL 只用于准备临时反例，不借助 ObjectNames 修补已损坏的库。
        fun prepare(db: Path, vararg statements: String) {
            DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
                connection.createStatement().use { statement ->
                    statements.forEach { statement.executeUpdate(it) }
                }
            }
        }

        fun assertRejectedWithoutWrites(db: Path) {
            val beforeTables = tableNames(db)
            val beforeBytes = Files.readAllBytes(db)
            assertFailsWith<IllegalArgumentException> { ObjectNames(db) }
            assertEquals(beforeTables, tableNames(db))
            // 不仅不能新增表，也不能偷偷补回 lesson_id 等元数据。
            assertContentEquals(beforeBytes, Files.readAllBytes(db))
        }

        val answersDb = temp.root.toPath().resolve("answers-memory.db")
        // 先创建真实 AnswerMemory 的 answers 表，模拟误把旧问答库传给本课。
        AnswerMemory(answersDb)
        assertTrue("answers" in tableNames(answersDb))
        assertRejectedWithoutWrites(answersDb)

        // SQLite 表名不区分 ASCII 大小写；Answers 仍是旧库，不能绕过守卫。
        val uppercaseAnswers = temp.root.toPath().resolve("uppercase-answers.db")
        prepare(uppercaseAnswers, "CREATE TABLE Answers (question_key TEXT PRIMARY KEY)")
        assertRejectedWithoutWrites(uppercaseAnswers)

        // 任意陌生业务库也不能被本课悄悄追加 schema，原有数据必须保持原样。
        val foreignDb = temp.root.toPath().resolve("foreign.db")
        prepare(foreignDb,
            "CREATE TABLE business_data (id INTEGER PRIMARY KEY, value TEXT)",
            "INSERT INTO business_data VALUES (1, 'keep')",
        )
        assertRejectedWithoutWrites(foreignDb)

        // 从完整本课库构造小反例：缺任一表、混入业务表、缺少或错写元数据都必须拒绝。
        val invalidChanges = listOf(
            "missing-meta-table" to "DROP TABLE object_memory_meta",
            "missing-object-table" to "DROP TABLE object_names",
            "mixed-foreign-table" to "CREATE TABLE business_data (id INTEGER)",
            "missing-schema" to "DELETE FROM object_memory_meta WHERE key = 'schema_version'",
            "wrong-schema" to "UPDATE object_memory_meta SET value = 'other-schema' WHERE key = 'schema_version'",
            "missing-lesson" to "DELETE FROM object_memory_meta WHERE key = 'lesson_id'",
            "wrong-lesson" to "UPDATE object_memory_meta SET value = 'another-lesson' WHERE key = 'lesson_id'",
        )
        for ((name, change) in invalidChanges) {
            val invalidDb = temp.root.toPath().resolve("$name.db")
            ObjectNames(invalidDb)
            prepare(invalidDb, change)
            assertRejectedWithoutWrites(invalidDb)
        }

        // 正例：完整本课库允许重开；表名大小写和 sqlite_sequence 内表不改变库的归属。
        val validDb = temp.root.toPath().resolve("valid-with-internal-table.db")
        ObjectNames(validDb).teach("X", "灯", "fixture-run")
        prepare(validDb,
            "ALTER TABLE object_names RENAME TO temporary_objects",
            "ALTER TABLE temporary_objects RENAME TO Object_Names",
            "ALTER TABLE object_memory_meta RENAME TO temporary_meta",
            "ALTER TABLE temporary_meta RENAME TO Object_Memory_Meta",
            "CREATE TABLE temporary_counter (id INTEGER PRIMARY KEY AUTOINCREMENT)",
            "DROP TABLE temporary_counter",
        )
        assertTrue("sqlite_sequence" in tableNames(validDb))
        assertEquals("灯", ObjectNames(validDb).findById("X")?.name)
    }

    /** 写入一份材料 JSON；对象 ID 和名称按传入原样写入，测试不 trim 或替换字符。 */
    private fun materialFile(
        fileName: String,
        objects: List<Pair<String, String>>,
        lessonId: String = PRESCHOOL_LESSON_ID,
        source: String = "researcher_given_training_world",
    ): Path {
        val path = temp.root.toPath().resolve(fileName)
        val content = buildJsonObject {
            put("lesson_id", lessonId)
            put("source", source)
            putJsonArray("objects") {
                objects.forEach { (id, name) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                    })
                }
            }
        }
        Files.writeString(path, content.toString())
        return path
    }

    /** 把 execute 返回的紧凑 JSON 解析为对象，便于逐字段断言。 */
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** 读取字符串字段；字段缺失时直接让测试失败。 */
    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    /** 读取整数字段；用于教师调用计数和保存数量。 */
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    /** 读取布尔字段；用于 same_object/same_name。 */
    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    /** 读取字符串数组；用于反向查询、缺失 ID 列表等稳定顺序断言。 */
    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }

    /** 列出 SQLite 用户表，验证拒绝旧库时没有产生任何本课 schema。 */
    private fun tableNames(db: Path): Set<String> {
        val names = mutableSetOf<String>()
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                    while (rows.next()) names += rows.getString("name")
                }
            }
        }
        return names
    }
}
