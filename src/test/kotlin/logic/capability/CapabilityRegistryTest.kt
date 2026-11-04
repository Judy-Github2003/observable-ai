// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2a 验收测试 R01–R10；只测注册表，不改 M1 语义。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapabilityRegistryTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))
    private val round = Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle")))
    private val itemValue = EvalResult.Known(CapValue.Rec(mapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("circle"))))

    private fun db(): Path = Files.createTempDirectory("b0-registry").resolve("capabilities.db")

    private fun def(body: Expr, id: String = "core.is_red", version: Int = 1, inputType: CapType = itemType) =
        CapabilityDefinition(id, version, mapOf("item" to inputType), CapType.Bool3, body)

    private fun count(path: Path, table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) }
            }
        }

    private fun call(def: CapabilityDefinition, id: String = def.id): Expr.Call =
        Expr.Call(id, def.version, def.contentHash(), mapOf("item" to input))

    @Test
    fun `R01 persists and resolves after reopen`() {
        val path = db()
        val base = def(red)
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
        }
        SqliteCapabilityRegistry(path).use { registry ->
            val inspected = registry.inspect("core.is_red", 1)
            assertTrue(inspected.ok)
            assertEquals(base.contentHash(), inspected.contentHash)
            assertEquals(CanonicalJson.astHash(red), inspected.bodyHash)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), CapabilityInterpreter(registry).execute(base, mapOf("item" to itemValue)))
        }
    }

    @Test
    fun `R02 immutable versions and idempotent same content`() {
        val path = db()
        val base = def(red)
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val again = registry.propose(base)
            assertTrue(again.ok)
            assertEquals("already_exists", again.code)
            val changed = registry.propose(def(Expr.And(red, round)))
            assertFalse(changed.ok)
            assertEquals("immutable_version_conflict", changed.code)
        }
        assertEquals(1, count(path, "capabilities"))
        assertEquals(1, count(path, "events"))
    }

    @Test
    fun `R03 dependency extraction and pinned call execution`() {
        val path = db()
        val helper = def(red, "core.is_red")
        val caller = CapabilityDefinition("demo.red_round", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.And(call(helper), round))
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(helper).ok)
            assertTrue(registry.propose(caller).ok)
            val inspected = registry.inspect("demo.red_round", 1)
            assertTrue(inspected.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), CapabilityInterpreter(registry).execute(inspected.definition!!, mapOf("item" to itemValue)))
        }
        assertEquals(1, count(path, "dependencies"))
    }

    @Test
    fun `R04 missing and mismatched dependencies leave no partial rows`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            val missing = registry.propose(CapabilityDefinition("demo.missing", 1, mapOf("item" to itemType), CapType.Bool3,
                Expr.Call("core.absent", 1, "0".repeat(64), mapOf("item" to input))))
            assertEquals("missing_dependency", missing.code)
            val helper = def(red)
            registry.propose(helper)
            val wrongHash = registry.propose(CapabilityDefinition("demo.wrong", 1, mapOf("item" to itemType), CapType.Bool3,
                Expr.Call("core.is_red", 1, "0".repeat(64), mapOf("item" to input))))
            assertEquals("dependency_hash_mismatch", wrongHash.code)
        }
        assertEquals(1, count(path, "capabilities"))
    }

    @Test
    fun `R05 tampering with body or content hash is rejected`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { it.propose(def(red)) }
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE bodies SET canonical_ir_json='{\"op\":\"const\",\"type\":\"Bool3\",\"value\":\"false\"}'") }
        }
        SqliteCapabilityRegistry(path).use { registry ->
            assertEquals("body_hash_mismatch", registry.inspect("core.is_red", 1).code)
            assertEquals(null, registry.resolve("core.is_red", 1))
        }

        val path2 = db()
        SqliteCapabilityRegistry(path2).use { it.propose(def(red)) }
        DriverManager.getConnection("jdbc:sqlite:${path2.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.executeUpdate("UPDATE capabilities SET content_hash='${"0".repeat(64)}'") }
        }
        SqliteCapabilityRegistry(path2).use { registry ->
            assertEquals("content_hash_mismatch", registry.inspect("core.is_red", 1).code)
        }
    }

    @Test
    fun `R06 transaction rollback leaves no rows`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use {
                    it.executeUpdate("CREATE TRIGGER fail_event BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT,'forced'); END")
                }
            }
            val result = registry.propose(def(red))
            assertFalse(result.ok)
            assertEquals("transaction_failed", result.code)
        }
        assertEquals(0, count(path, "capabilities"))
        assertEquals(0, count(path, "bodies"))
        assertEquals(0, count(path, "dependencies"))
        assertEquals(0, count(path, "events"))
    }

    @Test
    fun `R07 nested chain works and duplicate dependencies deduplicate`() {
        val path = db()
        val base = def(red, "chain.base")
        val mid = CapabilityDefinition("chain.mid", 1, mapOf("item" to itemType), CapType.Bool3, Expr.And(call(base), round))
        val top = CapabilityDefinition("chain.top", 1, mapOf("item" to itemType), CapType.Bool3, Expr.And(call(mid), call(mid)))
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            assertTrue(registry.propose(mid).ok)
            assertTrue(registry.propose(top).ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), CapabilityInterpreter(registry).execute(top, mapOf("item" to itemValue)))
        }
        assertEquals(1, count(path, "dependencies WHERE from_id='chain.top'"))
    }

    @Test
    fun `R08 oversized inputs are rejected without crashing`() {
        val path = db()
        val manyFields = (0 until 257).associate { "f$it" to CapType.Str }
        SqliteCapabilityRegistry(path).use { registry ->
            assertEquals("record_field_limit", registry.propose(def(red, inputType = CapType.Record(manyFields))).code)
            var deep: Expr = red
            repeat(200) { deep = Expr.Not(deep) }
            assertEquals("ast_depth_limit", registry.propose(def(deep)).code)
        }
    }

    @Test
    fun `R10 bad path and unknown schema version fail explicitly`() {
        val dir = Files.createTempDirectory("b0-registry-dir")
        val openFailed = assertFailsWith<RegistryException> { SqliteCapabilityRegistry(dir) }
        assertEquals("registry_open_failed", openFailed.code)

        val path = db()
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }
        val badVersion = assertFailsWith<RegistryException> { SqliteCapabilityRegistry(path) }
        assertEquals("unsupported_schema_version", badVersion.code)
    }

    private fun exec(path: Path, sql: String) {
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.executeUpdate(sql) }
        }
    }

    @Test
    fun `X01 corrupted signature json is a controlled failure`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { it.propose(def(red)) }
        exec(path, "UPDATE capabilities SET inputs_json='not json'")
        SqliteCapabilityRegistry(path).use { registry ->
            val inspected = registry.inspect("core.is_red", 1)
            assertFalse(inspected.ok)
            assertEquals("invalid_stored_definition", inspected.code)
            assertEquals(null, registry.resolve("core.is_red", 1))
        }

        val path2 = db()
        SqliteCapabilityRegistry(path2).use { it.propose(def(red)) }
        exec(path2, """UPDATE capabilities SET output_json='"List"'""")
        SqliteCapabilityRegistry(path2).use { registry ->
            assertEquals("invalid_stored_definition", registry.inspect("core.is_red", 1).code)
        }
    }

    @Test
    fun `X02 repeated propose checks existing integrity`() {
        val path = db()
        val base = def(red)
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(base)
            exec(path, """UPDATE bodies SET canonical_ir_json='{"op":"const","type":"Bool3","value":"false"}'""")
            val corrupted = registry.propose(base)
            assertFalse(corrupted.ok, "损坏记录不能返回 already_exists")
        }
        assertEquals(1, count(path, "events"))
    }

    @Test
    fun `X02b idempotent propose keeps one event and one row`() {
        val path = db()
        val base = def(red)
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val again = registry.propose(base)
            assertEquals("already_exists", again.code)
        }
        assertEquals(1, count(path, "capabilities"))
        assertEquals(1, count(path, "bodies"))
        assertEquals(1, count(path, "events"))
    }

    @Test
    fun `X03 persisted semantics version is enforced`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { it.propose(def(red)) }
        exec(path, "UPDATE capabilities SET semantics_version='cap-ir-v999'")
        SqliteCapabilityRegistry(path).use { registry ->
            assertEquals("unsupported_ir_semantics_version", registry.inspect("core.is_red", 1).code)
            assertEquals(null, registry.resolve("core.is_red", 1))
        }
    }

    @Test
    fun `X04 oversized constants and provenance are rejected`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            val huge = Expr.Const(CapValue.Str("x".repeat(70_000)))
            assertEquals("definition_too_large", registry.propose(def(huge)).code)
            assertEquals("invalid_provenance", registry.propose(def(red), "not json").code)
            val bigProvenance = "{\"x\":\"" + "a".repeat(70_000) + "\"}"
            assertEquals("invalid_provenance", registry.propose(def(red), bigProvenance).code)
        }
        assertEquals(0, count(path, "capabilities"))
    }

    @Test
    fun `X04c corrupted existing body blocks new capability reusing it`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def(red, "core.is_red")).ok)
            exec(path, """UPDATE bodies SET canonical_ir_json='{"op":"const","type":"Bool3","value":"false"}'""")
            val reused = registry.propose(def(red, "core.is_red_copy"))
            assertFalse(reused.ok)
            assertEquals("body_corruption", reused.code)
        }
        assertEquals(1, count(path, "capabilities"))
    }
}
