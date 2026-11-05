// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M2c-1a 验收测试 Z01–Z08；撤销只追加记录，旧能力/验证/激活历史不被改写。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityRevocationServiceTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))

    private fun db(): Path = Files.createTempDirectory("b0-revocation").resolve("capabilities.db")

    private fun def(body: Expr, id: String = "core.is_red", version: Int = 1): CapabilityDefinition =
        CapabilityDefinition(id, version, mapOf("item" to itemType), CapType.Bool3, body)

    private fun item(color: String?, shape: String = "circle"): CapValue.Rec {
        val fields = linkedMapOf<String, CapValue>()
        if (color != null) fields["color"] = CapValue.Str(color)
        fields["shape"] = CapValue.Str(shape)
        return CapValue.Rec(fields)
    }

    private fun args(color: String?, shape: String = "circle"): Map<String, EvalResult> =
        mapOf("item" to EvalResult.Known(item(color, shape)))

    private fun case(id: String, color: String?, expected: Boolean, shape: String = "circle") =
        VerificationCase(id, mapOf("item" to item(color, shape)), EvalResult.Known(CapValue.Bool(expected)))

    private fun redCases(): List<VerificationCase> = listOf(
        case("r1", "red", true, "circle"),
        case("r2", "red", true, "square"),
        case("r3", "blue", false, "circle"),
        case("r4", "green", false, "square"),
    )

    private fun suites(): TrustedSuiteRegistry =
        TrustedSuiteRegistry().also { it.register(TrustedVerificationSuite.create("suite.colors", 1, redCases())) }

    private fun connect(path: Path) = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")

    private fun exec(path: Path, sql: String) {
        connect(path).use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    private fun count(path: Path, table: String, where: String = ""): Int =
        connect(path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table $where").use { rows -> rows.next(); rows.getInt(1) }
            }
        }

    private fun rows(path: Path, sql: String): List<Map<String, String>> =
        connect(path).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    val columns = (1..result.metaData.columnCount).map { result.metaData.getColumnName(it) }
                    buildList {
                        while (result.next()) add(columns.associateWith { result.getString(it) ?: "" })
                    }
                }
            }
        }

    private fun snapshot(path: Path, sql: String): String =
        rows(path, sql).joinToString("\n") { row -> row.entries.joinToString("|") { "${it.key}=${it.value}" } }

    private fun verifiedAndActivated(registry: SqliteCapabilityRegistry, suites: TrustedSuiteRegistry, definition: CapabilityDefinition): String {
        val verification = CapabilityVerificationService(registry, suites).verify(definition.id, definition.version, "suite.colors", 1)
        assertTrue(verification.recorded, "verification must be recorded: ${verification.code}")
        val activation = CapabilityActivationService(registry, suites).activate(definition.id, definition.version, verification.runId!!)
        assertTrue(activation.ok, "activation must succeed: ${activation.code}")
        return verification.runId
    }

    @Test
    fun `Z01 revocation is appended and survives restart`() {
        val path = db()
        val base = def(red)
        val suites = suites()
        var revokeId: String? = null
        var runId: String? = null
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            runId = verifiedAndActivated(registry, suites, base)
            val result = CapabilityRevocationService(registry).revoke("core.is_red", 1, "incorrect_behavior", "evidence:W05")
            assertTrue(result.ok)
            assertNull(result.code)
            assertFalse(result.alreadyRevoked)
            revokeId = result.revokeId
        }
        assertEquals(1, count(path, "revocations"))
        assertEquals(4, count(path, "events"))
        val row = rows(path, "SELECT * FROM revocations").single()
        assertEquals(revokeId, row.getValue("revoke_id"))
        assertEquals("core.is_red", row.getValue("capability_id"))
        assertEquals("1", row.getValue("version"))
        assertEquals(base.contentHash(), row.getValue("content_hash"))
        assertEquals("incorrect_behavior", row.getValue("reason_code"))
        assertEquals("evidence:W05", row.getValue("evidence_ref"))
        val payload = rows(path, "SELECT payload_json FROM events WHERE kind='revoked'").single().getValue("payload_json")
        assertTrue(payload.contains("\"reason_code\":\"incorrect_behavior\""))
        assertTrue(payload.contains("\"revoke_id\":\"$revokeId\""))

        SqliteCapabilityRegistry(path).use { registry ->
            // 历史审计仍可读；撤销不删除能力或验证记录。
            assertTrue(registry.inspect("core.is_red", 1).ok)
            assertEquals(1, count(path, "verification_runs"))
            assertEquals(1, count(path, "activations"))
            assertEquals("candidate", rows(path, "SELECT status FROM capabilities").single().getValue("status"))
            assertEquals(runId, rows(path, "SELECT verification_run_id FROM activations").single().getValue("verification_run_id"))
        }
    }

    @Test
    fun `Z02 revoking missing capability leaves no row`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            val result = CapabilityRevocationService(registry).revoke("core.absent", 1, "incorrect_behavior", "evidence:x")
            assertFalse(result.ok)
            assertEquals("not_found", result.code)
        }
        assertEquals(0, count(path, "revocations"))
    }

    @Test
    fun `Z03 missing reason or evidence is rejected`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def(red)).ok)
            val service = CapabilityRevocationService(registry)
            assertEquals("invalid_revocation_reason", service.revoke("core.is_red", 1, "  ", "evidence:x").code)
            assertEquals("invalid_evidence_ref", service.revoke("core.is_red", 1, "incorrect_behavior", "").code)
            assertEquals(
                "invalid_revocation_reason",
                service.revoke("core.is_red", 1, "x".repeat(MAX_REVOCATION_TEXT_BYTES + 1), "evidence:x").code,
            )
        }
        assertEquals(0, count(path, "revocations"))
    }

    @Test
    fun `Z04 repeated revocation is idempotent and keeps one record`() {
        val path = db()
        val base = def(red)
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val service = CapabilityRevocationService(registry)
            val first = service.revoke("core.is_red", 1, "incorrect_behavior", "evidence:1")
            assertTrue(first.ok)
            val second = service.revoke("core.is_red", 1, "incorrect_behavior", "evidence:1")
            assertTrue(second.ok)
            assertTrue(second.alreadyRevoked)
            assertEquals("already_revoked", second.code)
            assertEquals(first.revokeId, second.revokeId)
        }
        assertEquals(1, count(path, "revocations"))
        assertEquals(1, count(path, "events", "WHERE kind='revoked'"))
    }

    @Test
    fun `Z05 event failure rolls back the revocation`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def(red)).ok)
            exec(path, "CREATE TRIGGER fail_revoked BEFORE INSERT ON events WHEN NEW.kind='revoked' BEGIN SELECT RAISE(ABORT,'forced'); END")
            val result = CapabilityRevocationService(registry).revoke("core.is_red", 1, "incorrect_behavior", "evidence:1")
            assertFalse(result.ok)
            assertEquals("transaction_failed", result.code)
        }
        assertEquals(0, count(path, "revocations"))
    }

    @Test
    fun `Z06 revoked capability cannot be activated or executed but stays auditable`() {
        val path = db()
        val base = def(red)
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val runId = verifiedAndActivated(registry, suites, base)
            assertTrue(CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red")).ok)
            assertTrue(CapabilityRevocationService(registry).revoke("core.is_red", 1, "incorrect_behavior", "evidence:1").ok)

            val reactivation = CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId)
            assertFalse(reactivation.ok)
            assertEquals("revoked", reactivation.code)

            val execution = CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red"))
            assertFalse(execution.ok)
            assertEquals("revoked", execution.code)

            // 历史审计入口不受影响。
            assertTrue(registry.inspect("core.is_red", 1).ok)
            assertEquals(1, count(path, "activations"))
        }
    }

    @Test
    fun `Z07 new version can be verified activated and executed while old one stays revoked`() {
        val path = db()
        val v1 = def(red, "core.is_red", 1)
        val v2 = def(Expr.And(red, Expr.Const(CapValue.Bool(true))), "core.is_red", 2)
        assertTrue(v1.contentHash() != v2.contentHash())
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(v1).ok)
            assertTrue(registry.propose(v2).ok)
            verifiedAndActivated(registry, suites, v1)
            assertTrue(CapabilityRevocationService(registry).revoke("core.is_red", 1, "incorrect_behavior", "evidence:1").ok)

            verifiedAndActivated(registry, suites, v2)
            val newRun = CapabilityExecutionService(registry).executeActive("core.is_red", 2, args("red"))
            assertTrue(newRun.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), newRun.result)

            // 旧版本仍然被撤销，不会因为新版本出现而恢复。
            assertEquals("revoked", CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red")).code)
            assertTrue(registry.inspect("core.is_red", 1).ok)
        }
    }

    @Test
    fun `Z08 revocation leaves ast hashes and prior evidence untouched`() {
        val path = db()
        val base = def(red)
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            verifiedAndActivated(registry, suites, base)
        }
        val beforeCapabilities = snapshot(path, "SELECT * FROM capabilities ORDER BY capability_id,version")
        val beforeBodies = snapshot(path, "SELECT * FROM bodies ORDER BY body_hash")
        val beforeRuns = snapshot(path, "SELECT * FROM verification_runs ORDER BY run_id")
        val beforeActivations = snapshot(path, "SELECT * FROM activations ORDER BY activation_id")

        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(CapabilityRevocationService(registry).revoke("core.is_red", 1, "incorrect_behavior", "evidence:1").ok)
        }
        assertEquals(beforeCapabilities, snapshot(path, "SELECT * FROM capabilities ORDER BY capability_id,version"))
        assertEquals(beforeBodies, snapshot(path, "SELECT * FROM bodies ORDER BY body_hash"))
        assertEquals(beforeRuns, snapshot(path, "SELECT * FROM verification_runs ORDER BY run_id"))
        assertEquals(beforeActivations, snapshot(path, "SELECT * FROM activations ORDER BY activation_id"))
    }
}
