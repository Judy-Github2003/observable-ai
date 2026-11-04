// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-1a 验收测试 M01–M05；只验证 v1→v2 迁移与回滚，不写验证器、不激活能力。
package logic.capability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilitySchemaMigrationTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val itemValue = EvalResult.Known(
        CapValue.Rec(mapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("circle"))),
    )

    private fun db(): Path = Files.createTempDirectory("b0-migration").resolve("capabilities.db")

    private fun connect(path: Path) = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    private fun userVersion(path: Path): Int =
        connect(path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows -> rows.next(); rows.getInt(1) }
            }
        }

    private fun tableExists(path: Path, name: String): Boolean =
        connect(path).use { connection ->
            connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rows -> rows.next() }
            }
        }

    private fun tables(path: Path): Set<String> =
        connect(path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    /** 把整表按主键顺序拼成一个字符串快照，用于断言迁移前后旧行逐字节不变。 */
    private fun snapshot(path: Path, sql: String): String =
        connect(path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val columns = rows.metaData.columnCount
                    buildString {
                        while (rows.next()) {
                            for (index in 1..columns) append(rows.getString(index)).append('|')
                            append('\n')
                        }
                    }
                }
            }
        }

    private fun typesJson(types: Map<String, CapType>): String = buildJsonObject {
        types.toSortedMap().forEach { (name, type) -> put(name, Json.parseToJsonElement(CanonicalJson.type(type))) }
    }.toString()

    private data class V1Fixture(val helper: CapabilityDefinition, val caller: CapabilityDefinition)

    /**
     * 手写一个真实 v1 库：旧四表 + 两条能力（含 pinned Call 依赖）+ 依赖行 + 两条 proposed 事件。
     * 不能用当前注册表来建，否则打开时就已自动迁移到 v2，无法覆盖 v1→v2 路径。
     */
    private fun writeV1Fixture(path: Path): V1Fixture {
        val helper = CapabilityDefinition(
            "core.is_red", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red"))),
        )
        val caller = CapabilityDefinition(
            "demo.red_round", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.And(
                Expr.Call("core.is_red", 1, helper.contentHash(), mapOf("item" to input)),
                Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle"))),
            ),
        )
        connect(path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE capabilities (
                        capability_id TEXT NOT NULL,
                        version INTEGER NOT NULL CHECK(version > 0),
                        content_hash TEXT NOT NULL,
                        semantics_version TEXT NOT NULL,
                        inputs_json TEXT NOT NULL,
                        output_json TEXT NOT NULL,
                        body_hash TEXT NOT NULL,
                        provenance_json TEXT NOT NULL,
                        status TEXT NOT NULL CHECK(status = 'candidate'),
                        created_at TEXT NOT NULL,
                        PRIMARY KEY (capability_id, version)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE bodies (
                        body_hash TEXT PRIMARY KEY,
                        canonical_ir_json TEXT NOT NULL,
                        byte_size INTEGER NOT NULL CHECK(byte_size >= 0)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE dependencies (
                        from_id TEXT NOT NULL,
                        from_version INTEGER NOT NULL,
                        to_id TEXT NOT NULL,
                        to_version INTEGER NOT NULL,
                        to_content_hash TEXT NOT NULL,
                        PRIMARY KEY(from_id, from_version, to_id, to_version),
                        FOREIGN KEY (from_id, from_version) REFERENCES capabilities(capability_id, version),
                        FOREIGN KEY (to_id, to_version) REFERENCES capabilities(capability_id, version)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE events (
                        event_seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind TEXT NOT NULL,
                        capability_id TEXT NOT NULL,
                        version INTEGER NOT NULL,
                        payload_json TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version = 1")
            }
            for (definition in listOf(helper, caller)) {
                val canonical = CanonicalJson.expr(definition.body)
                connection.prepareStatement("INSERT INTO bodies(body_hash,canonical_ir_json,byte_size) VALUES(?,?,?)").use { statement ->
                    statement.setString(1, CanonicalJson.astHash(definition.body))
                    statement.setString(2, canonical)
                    statement.setInt(3, canonical.toByteArray(Charsets.UTF_8).size)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO capabilities(capability_id,version,content_hash,semantics_version,inputs_json,output_json,body_hash,provenance_json,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                ).use { statement ->
                    statement.setString(1, definition.id)
                    statement.setInt(2, definition.version)
                    statement.setString(3, definition.contentHash())
                    statement.setString(4, IR_SEMANTICS_VERSION)
                    statement.setString(5, typesJson(definition.inputs))
                    statement.setString(6, CanonicalJson.type(definition.output))
                    statement.setString(7, CanonicalJson.astHash(definition.body))
                    statement.setString(8, "{}")
                    statement.setString(9, "candidate")
                    statement.setString(10, "2026-11-04T00:00:00Z")
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                "INSERT INTO dependencies(from_id,from_version,to_id,to_version,to_content_hash) VALUES(?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, caller.id)
                statement.setInt(2, caller.version)
                statement.setString(3, helper.id)
                statement.setInt(4, helper.version)
                statement.setString(5, helper.contentHash())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO events(kind,capability_id,version,payload_json,created_at) VALUES(?,?,?,?,?)",
            ).use { statement ->
                for (definition in listOf(helper, caller)) {
                    statement.setString(1, "proposed")
                    statement.setString(2, definition.id)
                    statement.setInt(3, definition.version)
                    statement.setString(4, "{}")
                    statement.setString(5, "2026-11-04T00:00:00Z")
                    statement.executeUpdate()
                }
            }
        }
        return V1Fixture(helper, caller)
    }

    private val oldTableSnapshots = listOf(
        "SELECT capability_id,version,content_hash,semantics_version,inputs_json,output_json,body_hash,provenance_json,status,created_at FROM capabilities ORDER BY capability_id,version",
        "SELECT body_hash,canonical_ir_json,byte_size FROM bodies ORDER BY body_hash",
        "SELECT from_id,from_version,to_id,to_version,to_content_hash FROM dependencies ORDER BY from_id,from_version,to_id,to_version",
        "SELECT event_seq,kind,capability_id,version,payload_json,created_at FROM events ORDER BY event_seq",
    )

    @Test
    fun `M01 v1 fixture migrates to v2 without touching old rows`() {
        val path = db()
        val fixture = writeV1Fixture(path)
        assertEquals(1, userVersion(path))
        val before = oldTableSnapshots.map { snapshot(path, it) }
        assertFalse(tableExists(path, "verification_runs"))

        SqliteCapabilityRegistry(path).use { registry ->
            assertEquals(2, userVersion(path))
            assertTrue(registry.inspect("core.is_red", 1).ok)
            assertTrue(registry.inspect("demo.red_round", 1).ok)
            val caller = registry.inspect("demo.red_round", 1)
            assertEquals(fixture.caller.contentHash(), caller.contentHash)
            assertEquals(
                EvalResult.Known(CapValue.Bool(true)),
                CapabilityInterpreter(registry).execute(caller.definition!!, mapOf("item" to itemValue)),
            )
        }

        assertEquals(before, oldTableSnapshots.map { snapshot(path, it) })
        assertTrue(tableExists(path, "verification_runs"))
        assertTrue(tableExists(path, "activations"))
        assertEquals(0, snapshot(path, "SELECT * FROM verification_runs").length)
        assertEquals(0, snapshot(path, "SELECT * FROM activations").length)
        assertEquals(2, userVersion(path))
    }

    @Test
    fun `M02 empty database initializes directly to v2`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertEquals(2, userVersion(path))
            assertEquals(
                setOf("activations", "bodies", "capabilities", "dependencies", "events", "verification_runs"),
                tables(path),
            )
            assertTrue(registry.inspect("core.absent", 1).code == "not_found")
        }
        assertEquals(2, userVersion(path))
    }

    @Test
    fun `M03 repeated reopen stays on v2 and keeps data`() {
        val path = db()
        val helper = CapabilityDefinition(
            "core.is_red", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red"))),
        )
        SqliteCapabilityRegistry(path).use { registry -> assertTrue(registry.propose(helper).ok) }
        repeat(3) {
            SqliteCapabilityRegistry(path).use { registry -> assertTrue(registry.inspect("core.is_red", 1).ok) }
        }
        assertEquals(2, userVersion(path))
        assertEquals(1, snapshot(path, "SELECT capability_id FROM capabilities").lineSequence().count { it.isNotBlank() })
        assertEquals(0, snapshot(path, "SELECT * FROM verification_runs").length)
    }

    @Test
    fun `M04 failed migration rolls back tables and version`() {
        val path = db()
        writeV1Fixture(path)
        val before = oldTableSnapshots.map { snapshot(path, it) }
        // 制造第二次 CREATE 失败：activations 已存在，而 verification_runs 尚不存在。
        connect(path).use { connection ->
            connection.createStatement().use {
                it.executeUpdate("CREATE TABLE activations (placeholder TEXT)")
            }
        }

        val failed = assertFailsWith<RegistryException> { SqliteCapabilityRegistry(path) }
        assertEquals("schema_migration_failed", failed.code)

        assertEquals(1, userVersion(path), "迁移失败后版本号必须回退到 1")
        assertFalse(tableExists(path, "verification_runs"), "第一次 CREATE 必须随事务回滚")
        assertTrue(tableExists(path, "activations"), "迁移前已存在的冲突表不属于事务，应保留")
        assertEquals(before, oldTableSnapshots.map { snapshot(path, it) })

        // 冲突表移除后应能正常补迁，说明失败没有破坏数据库。
        connect(path).use { connection ->
            connection.createStatement().use { it.executeUpdate("DROP TABLE activations") }
        }
        SqliteCapabilityRegistry(path).use { registry ->
            assertEquals(2, userVersion(path))
            assertTrue(registry.inspect("demo.red_round", 1).ok)
            assertEquals(0, snapshot(path, "SELECT * FROM activations").length)
        }
    }

    @Test
    fun `M05 unknown schema versions are rejected without migration`() {
        val path = db()
        connect(path).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 3") }
        }
        val failed = assertFailsWith<RegistryException> { SqliteCapabilityRegistry(path) }
        assertEquals("unsupported_schema_version", failed.code)
        assertEquals(3, userVersion(path))
        assertFalse(tableExists(path, "capabilities"))
    }
}
