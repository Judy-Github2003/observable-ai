// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M4-1a 验收测试 L01–L06；验收 M2a 正文共享、冻结成本模型、损坏库拒绝。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryCostTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val redBody = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))

    private fun db(): Path = Files.createTempDirectory("b0-cost").resolve("capabilities.db")

    private fun def(body: Expr = redBody, id: String = "core.is_red"): CapabilityDefinition =
        CapabilityDefinition(id, 1, mapOf("item" to itemType), CapType.Bool3, body)

    private fun count(path: Path, table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows -> rows.next(); rows.getInt(1) }
            }
        }

    @Test
    fun `L01 frozen weights produce stable exact costs`() {
        // 手算：Arg=1, Field=2+1=3, Const=1, Eq=2+3+1=6。
        assertEquals(6L, CapabilityCost.program(redBody))
        // 签名：1 + output(1) + (1 + Record{String,String}=3) = 6。
        assertEquals(6L, CapabilityCost.signature(mapOf("item" to itemType), CapType.Bool3))
        assertEquals(3L, CapabilityCost.type(itemType))

        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def()).ok)
            val result = L1LibraryAudit(registry).audit()
            assertTrue(result is L1AuditResult.Ok)
            val evidence = (result as L1AuditResult.Ok).evidence
            assertEquals(1, evidence.capabilityCount)
            assertEquals(1, evidence.uniqueBodyCount)
            assertEquals(0, evidence.sharedBodyCount)
            assertEquals(6L, evidence.bodyCost)
            assertEquals(6L, evidence.signatureCost)
            assertEquals(1L, evidence.interfaceCost)
            assertEquals(13L, evidence.totalCost)
            assertEquals(CanonicalJson.expr(redBody).toByteArray(Charsets.UTF_8).size.toLong(), evidence.uniqueBodyBytes)
        }
    }

    @Test
    fun `L02 identical bodies are stored once and costed once`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def(redBody, "core.is_red")).ok)
            assertTrue(registry.propose(def(redBody, "core.is_red_copy")).ok)
            assertEquals(1, count(path, "bodies"), "同一 body_hash 只能有一行正文")
            assertEquals(2, count(path, "capabilities"))

            val evidence = (L1LibraryAudit(registry).audit() as L1AuditResult.Ok).evidence
            assertEquals(2, evidence.capabilityCount)
            assertEquals(1, evidence.uniqueBodyCount)
            assertEquals(1, evidence.sharedBodyCount)
            assertEquals(listOf("core.is_red@1", "core.is_red_copy@1"), evidence.bodies.single().capabilityIds)
            assertEquals(6L, evidence.bodyCost)
            assertEquals(12L, evidence.signatureCost)
            assertEquals(2L, evidence.interfaceCost)
            assertEquals(20L, evidence.totalCost)
        }
    }

    @Test
    fun `L03 call cost includes base and every argument expression`() {
        val oneArg = Expr.Call("core.is_red", 1, "0".repeat(64), mapOf("item" to input))
        val twoArgs = Expr.Call("core.both", 1, "0".repeat(64), mapOf("left" to input, "right" to input))
        assertEquals(4L + CapabilityCost.program(input), CapabilityCost.program(oneArg))
        assertEquals(5L + 2 * CapabilityCost.program(input), CapabilityCost.program(twoArgs))
        assertTrue(CapabilityCost.program(twoArgs) > CapabilityCost.program(oneArg))
    }

    @Test
    fun `L04 corrupted body makes the audit fail instead of returning partial cost`() {
        val path = db()
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def()).ok)
            DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
                connection.createStatement().use {
                    it.executeUpdate("""UPDATE bodies SET canonical_ir_json='{"op":"const","type":"Bool3","value":"false"}'""")
                }
            }
            val result = L1LibraryAudit(registry).audit()
            assertTrue(result is L1AuditResult.Failed)
            assertEquals("body_hash_mismatch", (result as L1AuditResult.Failed).code)
        }
    }

    @Test
    fun `L05 cost is stable across restart`() {
        val path = db()
        val first: Long
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(def()).ok)
            first = (L1LibraryAudit(registry).audit() as L1AuditResult.Ok).evidence.totalCost
        }
        SqliteCapabilityRegistry(path).use { registry ->
            assertEquals(first, (L1LibraryAudit(registry).audit() as L1AuditResult.Ok).evidence.totalCost)
        }
    }

    @Test
    fun `L06 richer signature costs more`() {
        val small = CapabilityCost.signature(mapOf("item" to itemType), CapType.Bool3)
        val rich = CapType.Record(mapOf("color" to CapType.Str, "shape" to CapType.Str, "size" to CapType.Str))
        val large = CapabilityCost.signature(mapOf("item" to rich), CapType.Bool3)
        assertTrue(large > small)
    }
}
