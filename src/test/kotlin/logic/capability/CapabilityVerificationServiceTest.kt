// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-1c 验收测试 W01–W09；验证记录与事件同事务，失败运行保留。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityVerificationServiceTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))

    private fun db(): Path = Files.createTempDirectory("b0-verify").resolve("capabilities.db")

    private fun def(body: Expr, id: String = "core.is_red"): CapabilityDefinition =
        CapabilityDefinition(id, 1, mapOf("item" to itemType), CapType.Bool3, body)

    private fun item(color: String?, shape: String = "circle"): CapValue.Rec {
        val fields = linkedMapOf<String, CapValue>()
        if (color != null) fields["color"] = CapValue.Str(color)
        fields["shape"] = CapValue.Str(shape)
        return CapValue.Rec(fields)
    }

    private fun case(id: String, color: String?, expected: Boolean, shape: String = "circle") =
        VerificationCase(id, mapOf("item" to item(color, shape)), EvalResult.Known(CapValue.Bool(expected)))

    private fun fourCases(): List<VerificationCase> = listOf(
        case("c1", "red", true, "circle"),
        case("c2", "red", true, "square"),
        case("c3", "blue", false, "circle"),
        case("c4", "green", false, "square"),
    )

    private fun suitesWith(cases: List<VerificationCase>, source: VerificationSource = VerificationSource.HOST_FIXTURE): TrustedSuiteRegistry =
        TrustedSuiteRegistry().also {
            it.register(TrustedVerificationSuite.create("suite.colors", 1, cases), source)
        }

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
                        while (result.next()) {
                            add(columns.associateWith { result.getString(it) ?: "" })
                        }
                    }
                }
            }
        }

    private fun verifiedPayload(path: Path): String =
        rows(path, "SELECT payload_json FROM events WHERE kind='verified'").single().getValue("payload_json")

    @Test
    fun `W01 passing run and event are persisted together`() {
        val path = db()
        val base = def(red)
        var runId: String? = null
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val result = CapabilityVerificationService(registry, suitesWith(fourCases()))
                .verify("core.is_red", 1, "suite.colors", 1)
            assertTrue(result.recorded)
            assertNull(result.code)
            assertEquals(VerificationVerdict.PASS, result.outcome.verdict)
            runId = result.runId
        }
        assertEquals(1, count(path, "verification_runs"))
        assertEquals(2, count(path, "events"))
        val run = rows(path, "SELECT * FROM verification_runs").single()
        assertEquals(runId, run.getValue("run_id"))
        assertEquals("core.is_red", run.getValue("capability_id"))
        assertEquals("1", run.getValue("version"))
        assertEquals(base.contentHash(), run.getValue("content_hash"))
        assertEquals("suite.colors", run.getValue("suite_id"))
        assertEquals("1", run.getValue("suite_version"))
        assertEquals(TrustedVerificationSuite.create("suite.colors", 1, fourCases()).suiteHash, run.getValue("suite_hash"))
        assertEquals("4", run.getValue("case_count"))
        assertEquals("4", run.getValue("passed_count"))
        assertEquals("0", run.getValue("failed_count"))
        assertEquals("pass", run.getValue("verdict"))
        assertEquals("host_fixture", run.getValue("source"))
        val payload = verifiedPayload(path)
        assertEquals(4, Regex("\"case_id\"").findAll(payload).count())
        assertTrue(payload.contains("\"status\":\"PASS\""))
        assertTrue(payload.contains("\"run_id\":\"$runId\""))
    }

    @Test
    fun `W02 failing run is kept with per case evidence`() {
        val path = db()
        val cases = fourCases().toMutableList()
        cases[2] = case("c3", "blue", true)
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(def(red))
            val result = CapabilityVerificationService(registry, suitesWith(cases))
                .verify("core.is_red", 1, "suite.colors", 1)
            assertTrue(result.recorded)
            assertEquals(VerificationVerdict.FAIL, result.outcome.verdict)
            assertEquals("case_failed", result.outcome.code)
        }
        val run = rows(path, "SELECT verdict,passed_count,failed_count FROM verification_runs").single()
        assertEquals("fail", run.getValue("verdict"))
        assertEquals("3", run.getValue("passed_count"))
        assertEquals("1", run.getValue("failed_count"))
        assertTrue(verifiedPayload(path).contains("\"status\":\"WRONG_VALUE\""))
    }

    @Test
    fun `W03 suite inadequate is persisted as fail not pass`() {
        val path = db()
        val alwaysTrue = def(Expr.Const(CapValue.Bool(true)), "core.always_true")
        val cases = listOf(case("c1", "red", true), case("c2", "blue", true), case("c3", "green", true), case("c4", "black", true))
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(alwaysTrue)
            val result = CapabilityVerificationService(registry, suitesWith(cases))
                .verify("core.always_true", 1, "suite.colors", 1)
            assertTrue(result.recorded)
            assertEquals("suite_inadequate", result.outcome.code)
        }
        val run = rows(path, "SELECT verdict,passed_count,failed_count FROM verification_runs").single()
        assertEquals("fail", run.getValue("verdict"))
        assertEquals("4", run.getValue("passed_count"))
        assertEquals("0", run.getValue("failed_count"))
    }

    @Test
    fun `W04 event failure rolls back the run row`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(def(red))
            exec(path, "CREATE TRIGGER fail_verified BEFORE INSERT ON events WHEN NEW.kind='verified' BEGIN SELECT RAISE(ABORT,'forced'); END")
            val result = CapabilityVerificationService(registry, suitesWith(fourCases()))
                .verify("core.is_red", 1, "suite.colors", 1)
            assertFalse(result.recorded)
            assertEquals("transaction_failed", result.code)
        }
        assertEquals(0, count(path, "verification_runs"))
        assertEquals(1, count(path, "events"))
    }

    @Test
    fun `W05 tampered body cannot produce a pass record`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(def(red))
            exec(path, """UPDATE bodies SET canonical_ir_json='{"op":"const","type":"Bool3","value":"false"}'""")
            val result = CapabilityVerificationService(registry, suitesWith(fourCases()))
                .verify("core.is_red", 1, "suite.colors", 1)
            assertFalse(result.recorded)
            assertEquals("body_hash_mismatch", result.code)
        }
        assertEquals(0, count(path, "verification_runs"))
        assertEquals(1, count(path, "events"))
    }

    @Test
    fun `W06 each run gets a fresh id and history is append only`() {
        val path = db()
        val runIds = mutableListOf<String>()
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(def(red))
            val service = CapabilityVerificationService(registry, suitesWith(fourCases()))
            repeat(2) {
                val result = service.verify("core.is_red", 1, "suite.colors", 1)
                assertTrue(result.recorded)
                runIds += result.runId!!
            }
        }
        assertEquals(2, count(path, "verification_runs"))
        assertEquals(3, count(path, "events"))
        assertNotEquals(runIds[0], runIds[1])
    }

    @Test
    fun `W07 unregistered suite leaves no row`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(def(red))
            val result = CapabilityVerificationService(registry, TrustedSuiteRegistry())
                .verify("core.is_red", 1, "suite.absent", 1)
            assertFalse(result.recorded)
            assertEquals("unknown_suite", result.code)
        }
        assertEquals(0, count(path, "verification_runs"))
        assertEquals(1, count(path, "events"))
    }

    @Test
    fun `W08 broken dependency blocks new verification record`() {
        val path = db()
        val helper = def(red, "core.is_red")
        val caller = CapabilityDefinition(
            "demo.red_round", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.And(
                Expr.Call("core.is_red", 1, helper.contentHash(), mapOf("item" to input)),
                Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle"))),
            ),
        )
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(helper).ok)
            assertTrue(registry.propose(caller).ok)
            val service = CapabilityVerificationService(registry, suitesWith(fourCases()))
            assertTrue(service.verify("demo.red_round", 1, "suite.colors", 1).recorded)
            exec(path, """UPDATE bodies SET canonical_ir_json='{"op":"const","type":"Bool3","value":"false"}' WHERE body_hash='${CanonicalJson.astHash(red)}'""")
            val broken = service.verify("demo.red_round", 1, "suite.colors", 1)
            assertFalse(broken.recorded)
            assertEquals("body_hash_mismatch", broken.code)
        }
        assertEquals(1, count(path, "verification_runs"))
    }

    @Test
    fun `W09 source is copied from host registration`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            registry.propose(def(red))
            val result = CapabilityVerificationService(
                registry,
                suitesWith(fourCases(), VerificationSource.IMPLEMENTER_RUN),
            ).verify("core.is_red", 1, "suite.colors", 1)
            assertTrue(result.recorded)
        }
        assertEquals("implementer_run", rows(path, "SELECT source FROM verification_runs").single().getValue("source"))
        assertTrue(verifiedPayload(path).contains("\"source\":\"implementer_run\""))
    }
}
