// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：补齐 B0 M1 验收清单 M1-T01–T17 中参考测试未覆盖的边界；参考骨架由 ChatGPT 提供。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityM1ChecklistTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))
    private val round = Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle")))

    private fun def(body: Expr, id: String = "check.pred") =
        CapabilityDefinition(id, 1, mapOf("item" to itemType), CapType.Bool3, body)

    private fun checker() = CapabilityTypeChecker(InMemoryResolver())

    @Test
    fun `M1-T03 missing field stays unknown and never false`() {
        val result = CapabilityInterpreter(InMemoryResolver()).execute(
            def(red), mapOf("item" to EvalResult.Known(CapValue.Rec(emptyMap()))),
        )
        assertEquals(EvalResult.Unknown("unknown_operand"), result)
        assertFalse(result is EvalResult.Known)
    }

    @Test
    fun `M1-T05 T06 not unknown and if unknown do not evaluate branches`() {
        val unknownNot = CapabilityInterpreter(InMemoryResolver()).execute(
            def(Expr.Not(red)), mapOf("item" to EvalResult.Known(CapValue.Rec(emptyMap()))),
        )
        assertEquals(EvalResult.Unknown("unknown_operand"), unknownNot)

        // 条件为 unknown 时分支不得执行；否则 4 步预算会先耗尽成 resource_limit。
        val expensive = Expr.And(red, round)
        val body = Expr.If(red, expensive, expensive)
        val result = CapabilityInterpreter(InMemoryResolver()).execute(
            def(body), mapOf("item" to EvalResult.Known(CapValue.Rec(emptyMap()))), EvalBudget(maxSteps = 6),
        )
        assertEquals(EvalResult.Unknown("condition_unknown"), result)
    }

    @Test
    fun `M1-T07 boolean operator rejects non bool operand`() {
        assertEquals("expected_bool3", checker().check(def(Expr.And(Expr.Const(CapValue.Str("x")), red))).code)
    }

    @Test
    fun `M1-T08 int64 rejects number json non canonical and out of range`() {
        assertEquals("expected_string", assertFailsWith<CapIrParseException> {
            CapabilityJson.decode("""{"op":"const","type":"Int64","value":5}""")
        }.code)
        assertEquals("invalid_int64", assertFailsWith<CapIrParseException> {
            CapabilityJson.decode("""{"op":"const","type":"Int64","value":"07"}""")
        }.code)
        assertEquals("int64_out_of_range", assertFailsWith<CapIrParseException> {
            CapabilityJson.decode("""{"op":"const","type":"Int64","value":"99999999999999999999"}""")
        }.code)
        val ok = CapabilityJson.decode("""{"op":"const","type":"Int64","value":"-7"}""")
        assertEquals(CanonicalJson.expr(ok), CanonicalJson.expr(Expr.Const(CapValue.I64(-7L))))
    }

    @Test
    fun `M1-T09 call args key order and whitespace do not change canonical`() {
        val helper = def(red, "core.is_red")
        val a1 = """{"op":"call","id":"core.is_red","version":1,"body_hash":"${helper.contentHash()}","args":{"item":${CanonicalJson.expr(input)}}}"""
        val a2 = """{"op":"call","id":"core.is_red","version":1,"body_hash":"${helper.contentHash()}","args":{
            "item":${CanonicalJson.expr(input)}
        }}"""
        val e1 = CapabilityJson.decode(a1)
        val e2 = CapabilityJson.decode(a2)
        assertEquals(CanonicalJson.expr(e1), CanonicalJson.expr(e2))
        assertEquals(CanonicalJson.astHash(e1), CanonicalJson.astHash(e2))
    }

    @Test
    fun `M1-T11 unbound ref and duplicate binding are rejected`() {
        assertEquals("unbound_ref", checker().check(def(Expr.Ref("x"))).code)
        assertEquals("invalid_binding", checker().check(def(Expr.Let("item", red, Expr.Const(CapValue.Bool(true))))).code)
    }

    @Test
    fun `M1-T13 missing dependency and depth limits are explicit`() {
        val missing = Expr.Call("nope", 1, "0".repeat(64), mapOf("item" to input))
        assertEquals("missing_dependency", checker().check(def(missing)).code)

        var nested: Expr = red
        repeat(40) { nested = Expr.Not(nested) }
        assertEquals("ast_depth_limit", checker().check(def(nested)).code)

        var previous = def(red, "chain-0")
        val chain = mutableListOf(previous)
        for (index in 1..17) {
            val call = Expr.Call(previous.id, previous.version, previous.contentHash(), mapOf("item" to input))
            previous = CapabilityDefinition("chain-$index", 1, mapOf("item" to itemType), CapType.Bool3, call)
            chain += previous
        }
        assertEquals("call_depth_limit", CapabilityTypeChecker(InMemoryResolver(chain)).check(previous).code)
    }

    @Test
    fun `M1-T14 call budget is resource limit not unknown`() {
        val helper = def(red, "core.is_red")
        val resolver = InMemoryResolver(listOf(helper))
        val call = Expr.Call("core.is_red", 1, helper.contentHash(), mapOf("item" to input))
        val result = CapabilityInterpreter(resolver).execute(
            def(call), mapOf("item" to EvalResult.Known(CapValue.Rec(mapOf("color" to CapValue.Str("red"))))),
            EvalBudget(maxSteps = 100, maxCallDepth = 1),
        )
        assertEquals(EvalResult.Error("resource_limit"), result)
    }

    @Test
    fun `M1-T10 operand order is preserved in hash`() {
        assertFalse(CanonicalJson.astHash(Expr.And(red, round)) == CanonicalJson.astHash(Expr.And(round, red)))
        assertFalse(CanonicalJson.astHash(Expr.Or(red, round)) == CanonicalJson.astHash(Expr.Or(round, red)))
        assertFalse(CanonicalJson.astHash(Expr.Eq(red, round)) == CanonicalJson.astHash(Expr.Eq(round, red)))
    }

    @Test
    fun `M1-T15 type check rejects before execution`() {
        val bad = Expr.Eq(Expr.Const(CapValue.Str("1")), Expr.Const(CapValue.I64(1L)))
        val result = CapabilityInterpreter(InMemoryResolver()).execute(
            def(bad), mapOf("item" to EvalResult.Known(CapValue.Rec(emptyMap()))),
        )
        assertEquals(EvalResult.Error("eq_type_mismatch"), result)
    }

    @Test
    fun `invalid callee is rejected without throwing`() {
        val bad = CapabilityDefinition(
            id = "bad", version = 1, inputs = emptyMap(),
            output = CapType.Bool3,
            body = Expr.Const(CapValue.Rec(emptyMap())),
        )
        val caller = CapabilityDefinition(
            id = "caller", version = 1, inputs = emptyMap(),
            output = CapType.Bool3,
            body = Expr.Call("bad", 1, "0".repeat(64), emptyMap()),
        )
        val result = CapabilityTypeChecker(InMemoryResolver(listOf(bad))).check(caller)
        assertFalse(result.ok)
        assertEquals("invalid_dependency_definition", result.code)
    }

    @Test
    fun `record input allows missing fields but rejects wrong or extra fields`() {
        val resolver = InMemoryResolver()
        val interpreter = CapabilityInterpreter(resolver)
        val partial = interpreter.execute(
            def(red), mapOf("item" to EvalResult.Known(CapValue.Rec(emptyMap()))),
        )
        assertTrue(partial is EvalResult.Unknown, "缺字段必须是 Unknown，不能是 false")

        val wrongType = interpreter.execute(
            def(red), mapOf("item" to EvalResult.Known(CapValue.Rec(mapOf("color" to CapValue.I64(3))))),
        )
        assertEquals(EvalResult.Error("input_type_mismatch"), wrongType)

        val extraField = interpreter.execute(
            def(red), mapOf("item" to EvalResult.Known(CapValue.Rec(mapOf("other" to CapValue.Str("x"))))),
        )
        assertEquals(EvalResult.Error("input_type_mismatch"), extraField)
    }

    @Test
    fun `input limits reject oversized json record and node count`() {
        val huge = """{"op":"const","type":"String","value":"${"x".repeat(1_100_000)}"}"""
        assertEquals("json_too_large", assertFailsWith<CapIrParseException> { CapabilityJson.decode(huge) }.code)

        val fields = (0 until 257).joinToString(",") { """"f$it":"String"""" }
        val wide = """{"op":"arg","name":"x","type":{"kind":"Record","fields":{$fields}}}"""
        assertEquals("record_field_limit", assertFailsWith<CapIrParseException> { CapabilityJson.decode(wide) }.code)

        var level: List<Expr> = List(8192) { Expr.Const(CapValue.Bool(true)) }
        while (level.size > 1) level = level.chunked(2).map { if (it.size == 2) Expr.And(it[0], it[1]) else it[0] }
        assertEquals("ast_node_limit", checker().check(def(level[0])).code)
    }

    @Test
    fun `example json files roundtrip to canonical form`() {
        val base = Path.of("experiments/capability-foundation-b0/examples")
        for (name in listOf("single_cond.json", "double_and.json", "pinned_call.json")) {
            val text = Files.readString(base.resolve(name), Charsets.UTF_8).trim()
            val canonical = CapabilityJson.canonical(text)
            // 例子文件是缩进 JSON；roundtrip 验证“解析→规范化→再解析”稳定，而不是要求文本逐字相等。
            assertEquals(canonical, CapabilityJson.canonical(canonical), "roundtrip mismatch: $name")
        }
    }
}

