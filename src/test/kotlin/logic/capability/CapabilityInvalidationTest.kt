// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M2c-1b 验收测试 R01–R06；传递依赖撤销/损坏后调用方即使有旧激活记录也不可用，并给出依赖路径。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityInvalidationTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))

    private fun db(): Path = Files.createTempDirectory("b0-invalidation").resolve("capabilities.db")

    private fun def(body: Expr, id: String, version: Int = 1): CapabilityDefinition =
        CapabilityDefinition(id, version, mapOf("item" to itemType), CapType.Bool3, body)

    private fun call(target: CapabilityDefinition, version: Int = target.version): Expr =
        Expr.Call(target.id, version, target.contentHash(), mapOf("item" to input))

    private fun args(color: String): Map<String, EvalResult> =
        mapOf("item" to EvalResult.Known(CapValue.Rec(mapOf("color" to CapValue.Str(color), "shape" to CapValue.Str("circle")))))

    private fun case(id: String, color: String?, expected: Boolean, shape: String = "circle") =
        VerificationCase(id, mapOf("item" to CapValue.Rec(
            buildMap {
                if (color != null) put("color", CapValue.Str(color))
                put("shape", CapValue.Str(shape))
            },
        )), EvalResult.Known(CapValue.Bool(expected)))

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

    private fun record(registry: SqliteCapabilityRegistry, suites: TrustedSuiteRegistry, definition: CapabilityDefinition): String {
        val result = CapabilityVerificationService(registry, suites).verify(definition.id, definition.version, "suite.colors", 1)
        assertTrue(result.recorded, "verification must be recorded: ${result.code}")
        return result.runId!!
    }

    private fun activate(registry: SqliteCapabilityRegistry, suites: TrustedSuiteRegistry, definition: CapabilityDefinition): String {
        val runId = record(registry, suites, definition)
        val activation = CapabilityActivationService(registry, suites).activate(definition.id, definition.version, runId)
        assertTrue(activation.ok, "activation must succeed: ${activation.code}")
        return runId
    }

    private data class Chain(val c: CapabilityDefinition, val b: CapabilityDefinition, val a: CapabilityDefinition)

    /** 建立并激活 A→B→C 三层链；三层语义都等价于 isRed，因此共用一套 redCases。 */
    private fun activatedChain(registry: SqliteCapabilityRegistry, suites: TrustedSuiteRegistry): Chain {
        val c = def(red, "chain.c")
        val b = def(call(c), "chain.b")
        val a = def(call(b), "chain.a")
        assertTrue(registry.propose(c).ok)
        assertTrue(registry.propose(b).ok)
        assertTrue(registry.propose(a).ok)
        listOf(c, b, a).forEach { activate(registry, suites, it) }
        return Chain(c, b, a)
    }

    @Test
    fun `R01 revoking inner capability blocks all callers with dependency path`() {
        val path = db()
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            val chain = activatedChain(registry, suites)
            assertTrue(CapabilityExecutionService(registry).executeActive("chain.a", 1, args("red")).ok)

            assertTrue(CapabilityRevocationService(registry).revoke("chain.c", 1, "incorrect_behavior", "evidence:1").ok)
            val service = CapabilityExecutionService(registry)

            val blockedA = service.executeActive("chain.a", 1, args("red"))
            assertFalse(blockedA.ok)
            assertEquals("dependency_revoked", blockedA.code)
            assertEquals("chain.b@1 -> chain.c@1", blockedA.detail)

            val blockedB = service.executeActive("chain.b", 1, args("red"))
            assertFalse(blockedB.ok)
            assertEquals("dependency_revoked", blockedB.code)
            assertEquals("chain.c@1", blockedB.detail)

            val root = service.executeActive("chain.c", 1, args("red"))
            assertFalse(root.ok)
            assertEquals("revoked", root.code)

            // 旧激活与验证历史全部保留，inspect 仍可审计。
            assertEquals(3, count(path, "activations"))
            assertEquals(3, count(path, "verification_runs"))
            assertTrue(registry.inspect("chain.c", 1).ok)
        }
    }

    @Test
    fun `R02 new version of inner capability does not restore old callers`() {
        val path = db()
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            val chain = activatedChain(registry, suites)
            assertTrue(CapabilityRevocationService(registry).revoke("chain.c", 1, "incorrect_behavior", "evidence:1").ok)

            val c2 = def(Expr.And(red, Expr.Const(CapValue.Bool(true))), "chain.c", 2)
            assertTrue(registry.propose(c2).ok)
            activate(registry, suites, c2)

            val service = CapabilityExecutionService(registry)
            assertTrue(service.executeActive("chain.c", 2, args("red")).ok)
            assertEquals("dependency_revoked", service.executeActive("chain.a", 1, args("red")).code)
            assertEquals("dependency_revoked", service.executeActive("chain.b", 1, args("red")).code)
            // 旧版本依旧不可执行，不会被新版本“自动修复”。
            assertEquals("revoked", service.executeActive("chain.c", 1, args("red")).code)
        }
    }

    @Test
    fun `R03 new chain pinned to new version works independently`() {
        val path = db()
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            activatedChain(registry, suites)
            assertTrue(CapabilityRevocationService(registry).revoke("chain.c", 1, "incorrect_behavior", "evidence:1").ok)
            val c2 = def(Expr.And(red, Expr.Const(CapValue.Bool(true))), "chain.c", 2)
            assertTrue(registry.propose(c2).ok)
            activate(registry, suites, c2)

            val b2 = def(Expr.Call("chain.c", 2, c2.contentHash(), mapOf("item" to input)), "chain.b", 2)
            val a2 = def(Expr.Call("chain.b", 2, b2.contentHash(), mapOf("item" to input)), "chain.a", 2)
            assertTrue(registry.propose(b2).ok)
            assertTrue(registry.propose(a2).ok)
            activate(registry, suites, b2)
            activate(registry, suites, a2)

            val service = CapabilityExecutionService(registry)
            val fresh = service.executeActive("chain.a", 2, args("red"))
            assertTrue(fresh.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), fresh.result)
            assertEquals("dependency_revoked", service.executeActive("chain.a", 1, args("red")).code)
        }
    }

    @Test
    fun `R04 corrupted middle capability reports dependency_invalid at the right node`() {
        val path = db()
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            val chain = activatedChain(registry, suites)
            exec(path, "UPDATE bodies SET canonical_ir_json='{\"op\":\"const\",\"type\":\"Bool3\",\"value\":\"false\"}' WHERE body_hash='${CanonicalJson.astHash(chain.b.body)}'")
            val blocked = CapabilityExecutionService(registry).executeActive("chain.a", 1, args("red"))
            assertFalse(blocked.ok)
            assertEquals("dependency_invalid", blocked.code)
            assertEquals("chain.b@1", blocked.detail)
            // 被改坏的中间能力自身仍可 inspect 出错误，而不是静默通过。
            assertFalse(registry.inspect("chain.b", 1).ok)
        }
    }

    @Test
    fun `R05 activation refuses a caller whose dependency is revoked`() {
        val path = db()
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            val chain = activatedChain(registry, suites)
            assertTrue(CapabilityRevocationService(registry).revoke("chain.c", 1, "incorrect_behavior", "evidence:1").ok)
            val d = def(call(chain.c), "chain.d")
            assertTrue(registry.propose(d).ok)
            val runId = record(registry, suites, d)
            val activation = CapabilityActivationService(registry, suites).activate("chain.d", 1, runId)
            assertFalse(activation.ok)
            assertEquals("dependency_revoked", activation.code)
            assertEquals("chain.c@1", activation.detail)
        }
        assertEquals(3, count(path, "activations"))
    }

    @Test
    fun `R06 invalidation is stable across restart`() {
        val path = db()
        val suites = suites()
        SqliteCapabilityRegistry(path).use { registry ->
            activatedChain(registry, suites)
            assertTrue(CapabilityRevocationService(registry).revoke("chain.c", 1, "incorrect_behavior", "evidence:1").ok)
        }
        SqliteCapabilityRegistry(path).use { registry ->
            val blocked = CapabilityExecutionService(registry).executeActive("chain.a", 1, args("red"))
            assertFalse(blocked.ok)
            assertEquals("dependency_revoked", blocked.code)
            assertEquals("chain.b@1 -> chain.c@1", blocked.detail)
            assertEquals(3, count(path, "activations"))
            assertEquals(1, count(path, "revocations"))
            // 新版本仍可在重启后独立注册/验证/激活；旧链不恢复。
            val c2 = def(Expr.And(red, Expr.Const(CapValue.Bool(true))), "chain.c", 2)
            assertTrue(registry.propose(c2).ok)
            activate(registry, suites, c2)
            assertEquals("dependency_revoked", CapabilityExecutionService(registry).executeActive("chain.a", 1, args("red")).code)
        }
    }
}
