// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-2b 验收测试 Y01–Y11；正式入口必须每次核对激活与传递依赖，区分 Known/Unknown/Error。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapabilityExecutionServiceTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))

    private fun db(): Path = Files.createTempDirectory("b0-execute").resolve("capabilities.db")

    private fun def(body: Expr, id: String = "core.is_red"): CapabilityDefinition =
        CapabilityDefinition(id, 1, mapOf("item" to itemType), CapType.Bool3, body)

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

    private fun record(registry: SqliteCapabilityRegistry, suites: TrustedSuiteRegistry, def: CapabilityDefinition, suiteId: String): String {
        val result = CapabilityVerificationService(registry, suites).verify(def.id, def.version, suiteId, 1)
        assertTrue(result.recorded, "verification must be recorded: ${result.code}")
        return result.runId!!
    }

    @Test
    fun `Y01 candidate or verified only is rejected by the formal entry`() {
        val path = db()
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            val base = def(red)
            assertTrue(registry.propose(base).ok)
            val service = CapabilityExecutionService(registry)
            val candidate = service.executeActive("core.is_red", 1, args("red"))
            assertFalse(candidate.ok)
            assertEquals("not_active", candidate.code)

            val verification = CapabilityVerificationService(registry, suites).verify("core.is_red", 1, "suite.colors", 1)
            assertTrue(verification.recorded)
            val verifiedOnly = service.executeActive("core.is_red", 1, args("red"))
            assertFalse(verifiedOnly.ok)
            assertEquals("not_active", verifiedOnly.code)

            // 内部开发接口仍能解析定义；这是开发/验证接口，不是正式应用路由。
            assertNotNull(registry.resolve("core.is_red", 1))
        }
    }

    @Test
    fun `Y02 activated capability executes with known results`() {
        val path = db()
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            val base = def(red)
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId).ok)

            val service = CapabilityExecutionService(registry)
            val hit = service.executeActive("core.is_red", 1, args("red"))
            assertTrue(hit.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), hit.result)
            assertEquals("known", hit.resultKind)

            val miss = service.executeActive("core.is_red", 1, args("blue"))
            assertTrue(miss.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(false)), miss.result)
        }
    }

    @Test
    fun `Y03 body change after activation blocks execution`() {
        val path = db()
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            val base = def(red)
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId).ok)
            exec(path, """UPDATE bodies SET canonical_ir_json='{"op":"const","type":"Bool3","value":"false"}'""")

            val result = CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red"))
            assertFalse(result.ok)
            assertEquals("body_hash_mismatch", result.code)
        }
    }

    @Test
    fun `Y04 dependency activation is checked at execution time`() {
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
            val callerRun = record(registry, suites, caller, "suite.round")
            // 直接插入调用方的激活记录，绕过激活服务，专门测试执行期依赖门禁：
            // 调用方“已激活”，但它依赖的 core.is_red 还没有激活记录。
            exec(
                path,
                "INSERT INTO activations(activation_id,capability_id,version,content_hash,verification_run_id,created_at) " +
                    "VALUES('manual-caller','demo.red_round',1,'${caller.contentHash()}','$callerRun','2026-11-04T00:00:00Z')",
            )

            val service = CapabilityExecutionService(registry)
            val result = service.executeActive("demo.red_round", 1, args("red"))
            assertFalse(result.ok)
            assertEquals("dependency_not_activated", result.code)

            // 补齐依赖激活后，同一次执行请求应放行，说明拒绝原因确实来自依赖门禁。
            val helperRun = record(registry, suites, helper, "suite.red")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, helperRun).ok)
            assertTrue(service.executeActive("demo.red_round", 1, args("red")).ok)
        }
    }

    @Test
    fun `Y05 transitive dependency corruption blocks the caller`() {
        val path = db()
        val helper = def(red, "core.is_red")
        val caller = CapabilityDefinition(
            "demo.red_round", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.Call("core.is_red", 1, helper.contentHash(), mapOf("item" to input)),
        )
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(helper).ok)
            assertTrue(registry.propose(caller).ok)
            val helperRun = record(registry, suites, helper, "suite.colors")
            val callerRun = record(registry, suites, caller, "suite.colors")
            val activation = CapabilityActivationService(registry, suites)
            assertTrue(activation.activate("core.is_red", 1, helperRun).ok)
            assertTrue(activation.activate("demo.red_round", 1, callerRun).ok)

            val service = CapabilityExecutionService(registry)
            assertTrue(service.executeActive("demo.red_round", 1, args("red")).ok)

            exec(path, """UPDATE bodies SET canonical_ir_json='{"op":"const","type":"Bool3","value":"false"}'""")
            val blocked = service.executeActive("demo.red_round", 1, args("red"))
            assertFalse(blocked.ok)
            assertEquals("body_hash_mismatch", blocked.code)
        }
    }

    @Test
    fun `Y06 activation survives restart and still enforces the gate`() {
        val path = db()
        val suites = suitesWith("suite.colors" to redCases())
        val base = def(red)
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId).ok)
        }
        SqliteCapabilityRegistry(path).use { registry ->
            val afterRestart = CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red"))
            assertTrue(afterRestart.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), afterRestart.result)
        }
        SqliteCapabilityRegistry(path).use { registry ->
            // 重启后未激活的另一个版本仍应被拒，不能因为库里有验证记录就放行。
            val other = CapabilityDefinition("core.is_red", 2, mapOf("item" to itemType), CapType.Bool3, red)
            assertEquals("not_found", CapabilityExecutionService(registry).executeActive("core.is_red", 2, args("red")).code)
            assertTrue(registry.propose(other).ok)
            assertEquals("not_active", CapabilityExecutionService(registry).executeActive("core.is_red", 2, args("red")).code)
        }
    }

    @Test
    fun `Y07 tampered activation record is rejected`() {
        val path = db()
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            val base = def(red)
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId).ok)
            exec(path, "UPDATE activations SET content_hash='${"0".repeat(64)}'")
            val result = CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red"))
            assertFalse(result.ok)
            assertEquals("activation_mismatch", result.code)
        }
    }

    @Test
    fun `Y08 tampered verification verdict blocks execution`() {
        val path = db()
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            val base = def(red)
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId).ok)
            exec(path, "UPDATE verification_runs SET verdict='fail'")
            val result = CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red"))
            assertFalse(result.ok)
            assertEquals("verification_failed", result.code)
        }
    }

    @Test
    fun `Y09 unsupported semantics version is rejected`() {
        val path = db()
        val suites = suitesWith("suite.colors" to redCases())
        SqliteCapabilityRegistry(path).use { registry ->
            val base = def(red)
            assertTrue(registry.propose(base).ok)
            val runId = record(registry, suites, base, "suite.colors")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, runId).ok)
            exec(path, "UPDATE capabilities SET semantics_version='cap-ir-v999'")
            val result = CapabilityExecutionService(registry).executeActive("core.is_red", 1, args("red"))
            assertFalse(result.ok)
            assertEquals("unsupported_ir_semantics_version", result.code)
        }
    }

    @Test
    fun `Y10 unknown and error are reported as results not successes`() {
        val path = db()
        val compound = CapabilityDefinition(
            "core.red_round", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.And(
                Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red"))),
                Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle"))),
            ),
        )
        val suites = suitesWith("suite.round" to roundCases())
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(compound).ok)
            val runId = record(registry, suites, compound, "suite.round")
            assertTrue(CapabilityActivationService(registry, suites).activate("core.red_round", 1, runId).ok)
            val service = CapabilityExecutionService(registry)

            // 预算耗尽：门禁通过（确实执行了），但结果是 Error，不是成功预测。
            val limited = service.executeActive("core.red_round", 1, args("red"), EvalBudget(maxSteps = 1))
            assertTrue(limited.ok)
            assertEquals("error", limited.resultKind)
            assertEquals(EvalResult.Error("resource_limit"), limited.result)

            // 缺字段：结果是 Unknown，也不能当 false。
            val unknown = service.executeActive("core.red_round", 1, args(null))
            assertTrue(unknown.ok)
            assertEquals("unknown", unknown.resultKind)
            assertTrue(unknown.result is EvalResult.Unknown)

            // 输入类型不匹配：仍是执行结果 Error，调用方必须区分。
            val badInput = service.executeActive("core.red_round", 1, mapOf("item" to EvalResult.Known(CapValue.Str("x"))))
            assertTrue(badInput.ok)
            assertEquals("error", badInput.resultKind)
        }
    }

    @Test
    fun `Y11 three level chain survives restart and blocks on inner corruption`() {
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
            val activation = CapabilityActivationService(registry, suites)
            for (definition in listOf(c, b, a)) {
                assertTrue(activation.activate(definition.id, 1, record(registry, suites, definition, "suite.colors")).ok)
            }
        }
        SqliteCapabilityRegistry(path).use { registry ->
            val service = CapabilityExecutionService(registry)
            val afterRestart = service.executeActive("chain.a", 1, args("red"))
            assertTrue(afterRestart.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), afterRestart.result)

            exec(path, "UPDATE bodies SET canonical_ir_json='{\"op\":\"const\",\"type\":\"Bool3\",\"value\":\"false\"}' WHERE body_hash='${CanonicalJson.astHash(red)}'")
            val blocked = service.executeActive("chain.a", 1, args("red"))
            assertFalse(blocked.ok)
            assertEquals("body_hash_mismatch", blocked.code)
        }
        assertEquals(3, count(path, "activations"))
    }
}
