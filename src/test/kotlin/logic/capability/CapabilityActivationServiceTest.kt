// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-2a 验收测试 X01–X09；激活只追加记录，不改 capabilities.status。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityActivationServiceTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))

    private fun db(): Path = Files.createTempDirectory("b0-activation").resolve("capabilities.db")

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

    private fun redCases(): List<VerificationCase> = listOf(
        case("r1", "red", true, "circle"),
        case("r2", "red", true, "square"),
        case("r3", "blue", false, "circle"),
        case("r4", "green", false, "square"),
    )

    private fun blueCases(): List<VerificationCase> = listOf(
        case("b1", "blue", true, "circle"),
        case("b2", "blue", true, "square"),
        case("b3", "red", false, "circle"),
        case("b4", "green", false, "square"),
    )

    private fun roundCases(): List<VerificationCase> = listOf(
        case("q1", "red", true, "circle"),
        case("q2", "red", false, "square"),
        case("q3", "blue", false, "circle"),
        case("q4", "green", false, "square"),
    )

    private fun suitesWith(vararg entries: Pair<String, List<VerificationCase>>): TrustedSuiteRegistry {
        val registry = TrustedSuiteRegistry()
        entries.forEach { (id, cases) -> registry.register(TrustedVerificationSuite.create(id, 1, cases)) }
        return registry
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
                        while (result.next()) add(columns.associateWith { result.getString(it) ?: "" })
                    }
                }
            }
        }

    private fun record(registry: SqliteCapabilityRegistry, suites: TrustedSuiteRegistry, def: CapabilityDefinition, suiteId: String): String {
        val result = CapabilityVerificationService(registry, suites).verify(def.id, def.version, suiteId, 1)
        assertTrue(result.recorded, "verification must be recorded: ${result.code}")
        return result.runId!!
    }

    @Test
    fun `X01 pass verification activates by appending a record only`() {
        val path = db()
        val base = def(red)
        val suites = suitesWith("suite.colors" to redCases())
        var activationId: String? = null
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            val activation = CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId)
            assertTrue(activation.ok)
            assertEquals(false, activation.alreadyActive)
            activationId = activation.activationId
        }
        assertEquals(1, count(path, "activations"))
        assertEquals(3, count(path, "events"))
        val activationRow = rows(path, "SELECT * FROM activations").single()
        assertEquals(activationId, activationRow.getValue("activation_id"))
        assertEquals(base.contentHash(), activationRow.getValue("content_hash"))
        // 关键：旧 capabilities.status 仍是 candidate，激活不是改状态字段。
        assertEquals("candidate", rows(path, "SELECT status FROM capabilities").single().getValue("status"))
        val payload = rows(path, "SELECT payload_json FROM events WHERE kind='activated'").single().getValue("payload_json")
        assertTrue(payload.contains("\"logical_state\":\"active\""))
        assertTrue(payload.contains("\"activation_id\":\"$activationId\""))
    }

    @Test
    fun `X02 direct activation without verification is rejected`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def(red)).ok)
            val result = CapabilityActivationService(registry, suitesWith("suite.colors" to redCases()))
                .activate("core.is_red", 1, "run.does.not.exist")
            assertFalse(result.ok)
            assertEquals("verification_not_found", result.code)
        }
        assertEquals(0, count(path, "activations"))
    }

    @Test
    fun `X03 failed verification cannot activate`() {
        val path = db()
        val base = def(red)
        val wrongCases = redCases().toMutableList().also { it[2] = case("r3", "blue", true) }
        val suites = suitesWith("suite.colors" to wrongCases)
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val verification = CapabilityVerificationService(registry, suites).verify("core.is_red", 1, "suite.colors", 1)
            assertTrue(verification.recorded)
            assertEquals(VerificationVerdict.FAIL, verification.outcome.verdict)
            val result = CapabilityActivationService(registry, suites).activate("core.is_red", 1, verification.runId!!)
            assertFalse(result.ok)
            assertEquals("verification_failed", result.code)
        }
        assertEquals(0, count(path, "activations"))
    }

    @Test
    fun `X04 run id must bind to the same capability version and hash`() {
        val path = db()
        val redDef = def(red, "core.is_red")
        val blueDef = def(Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("blue"))), "core.is_blue")
        val suites = suitesWith("suite.red" to redCases(), "suite.blue" to blueCases())
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(redDef).ok)
            assertTrue(registry.propose(blueDef).ok)
            val redRun = record(registry, suites, redDef, "suite.red")
            val blueRun = record(registry, suites, blueDef, "suite.blue")
            val service = CapabilityActivationService(registry, suites)

            // 用另一能力的通过记录来激活本能力。
            val crossed = service.activate("core.is_red", 1, blueRun)
            assertFalse(crossed.ok)
            assertEquals("verification_mismatch", crossed.code)

            // 篡改记录里的 content_hash 后也不能激活。
            exec(path, "UPDATE verification_runs SET content_hash='${"0".repeat(64)}' WHERE run_id='$redRun'")
            val tampered = service.activate("core.is_red", 1, redRun)
            assertFalse(tampered.ok)
            assertEquals("verification_mismatch", tampered.code)
        }
        assertEquals(0, count(path, "activations"))
    }

    @Test
    fun `X05 dependency must be activated before the caller`() {
        val path = db()
        val helper = def(red, "core.is_red")
        val caller = CapabilityDefinition(
            "demo.red_round", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.And(Expr.Call("core.is_red", 1, helper.contentHash(), mapOf("item" to input)),
                Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle")))),
        )
        val suites = suitesWith("suite.red" to redCases(), "suite.round" to roundCases())
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(helper).ok)
            assertTrue(registry.propose(caller).ok)
            val helperRun = record(registry, suites, helper, "suite.red")
            val callerRun = record(registry, suites, caller, "suite.round")
            val service = CapabilityActivationService(registry, suites)

            val blocked = service.activate("demo.red_round", 1, callerRun)
            assertFalse(blocked.ok)
            assertEquals("dependency_not_activated", blocked.code)

            assertTrue(service.activate("core.is_red", 1, helperRun).ok)
            assertTrue(service.activate("demo.red_round", 1, callerRun).ok)
        }
        assertEquals(2, count(path, "activations"))
    }

    @Test
    fun `X06 event failure rolls back activation`() {
        val path = db()
        val base = def(red)
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            exec(path, "CREATE TRIGGER fail_activated BEFORE INSERT ON events WHEN NEW.kind='activated' BEGIN SELECT RAISE(ABORT,'forced'); END")
            val result = CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId)
            assertFalse(result.ok)
            assertEquals("transaction_failed", result.code)
        }
        assertEquals(0, count(path, "activations"))
    }

    @Test
    fun `X07 repeated activation is idempotent and writes nothing new`() {
        val path = db()
        val base = def(red)
        val suites = suitesWith("suite.colors" to redCases())
        var firstId: String? = null
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            val service = CapabilityActivationService(registry, suites)
            val first = service.activate("core.is_red", 1, runId)
            assertTrue(first.ok)
            firstId = first.activationId
            val second = service.activate("core.is_red", 1, runId)
            assertTrue(second.ok)
            assertTrue(second.alreadyActive)
            assertEquals("already_active", second.code)
            assertEquals(firstId, second.activationId)
        }
        assertEquals(1, count(path, "activations"))
        assertEquals(1, count(path, "events", "WHERE kind='activated'"))
    }

    @Test
    fun `X08 three level chain activates innermost first`() {
        val path = db()
        val c = def(red, "chain.c")
        val b = CapabilityDefinition("chain.b", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.Call("chain.c", 1, c.contentHash(), mapOf("item" to input)))
        val a = CapabilityDefinition("chain.a", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.Call("chain.b", 1, b.contentHash(), mapOf("item" to input)))
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(c).ok)
            assertTrue(registry.propose(b).ok)
            assertTrue(registry.propose(a).ok)
            val cRun = record(registry, suites, c, "suite.colors")
            val bRun = record(registry, suites, b, "suite.colors")
            val aRun = record(registry, suites, a, "suite.colors")
            val service = CapabilityActivationService(registry, suites)

            val blocked = service.activate("chain.a", 1, aRun)
            assertFalse(blocked.ok)
            assertEquals("dependency_not_activated", blocked.code)

            assertTrue(service.activate("chain.c", 1, cRun).ok)
            assertTrue(service.activate("chain.b", 1, bRun).ok)
            assertTrue(service.activate("chain.a", 1, aRun).ok)
        }
        assertEquals(3, count(path, "activations"))
    }

    @Test
    fun `X09 forged source or unregistered suite is rejected`() {
        val path = db()
        val base = def(red)
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            exec(path, "UPDATE verification_runs SET source='implementer_run' WHERE run_id='$runId'")
            val forged = CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId)
            assertFalse(forged.ok)
            assertEquals("source_mismatch", forged.code)
        }
        assertEquals(0, count(path, "activations"))

        val ghostPath = db()
        val ghostSuites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(ghostPath).use { registry ->
            assertTrue(registry.propose(base).ok)
            exec(
                ghostPath,
                "INSERT INTO verification_runs(run_id,capability_id,version,content_hash,suite_id,suite_version,suite_hash,case_count,passed_count,failed_count,verdict,source,created_at) " +
                    "VALUES('ghost-run','core.is_red',1,'${base.contentHash()}','suite.ghost',1,'${"0".repeat(64)}',4,4,0,'pass','host_fixture','2026-11-04T00:00:00Z')",
            )
            val result = CapabilityActivationService(registry, ghostSuites).activate("core.is_red", 1, "ghost-run")
            assertFalse(result.ok)
            assertEquals("suite_not_registered", result.code)
        }
        assertEquals(0, count(ghostPath, "activations"))
    }
}
