// 参考实现：云端 ChatGPT，2026-11-04；DSH（本会话）集成、测试、修复。
package logic.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CapabilityM1Test {
    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))
    private val round = Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle")))
    private fun def(body: Expr, id: String = "demo.pred") = CapabilityDefinition(id, 1, mapOf("item" to itemType), CapType.Bool3, body)
    private fun run(body: Expr, fields: Map<String, CapValue>): EvalResult {
        val c = def(body)
        return CapabilityInterpreter(InMemoryResolver()).execute(c, mapOf("item" to EvalResult.Known(CapValue.Rec(fields))))
    }

    @Test fun unknownIsNotFalseAndKleeneShortCircuits() {
        assertEquals(EvalResult.Unknown("unknown_operand"), run(red, emptyMap()))
        assertEquals(EvalResult.Known(CapValue.Bool(false)),
            run(Expr.And(Expr.Const(CapValue.Bool(false)), red), emptyMap()))
        assertEquals(EvalResult.Known(CapValue.Bool(false)),
            run(Expr.And(red, Expr.Const(CapValue.Bool(false))), emptyMap()))
        assertEquals(EvalResult.Known(CapValue.Bool(true)),
            run(Expr.Or(Expr.Const(CapValue.Bool(true)), red), emptyMap()))
        assertEquals(EvalResult.Unknown("condition_unknown"),
            run(Expr.If(red, Expr.Const(CapValue.Bool(true)), Expr.Const(CapValue.Bool(false))), emptyMap()))
    }

    @Test fun rejectsMismatchedTypesBeforeRunning() {
        val bad = Expr.Eq(Expr.Const(CapValue.Str("1")), Expr.Const(CapValue.I64(1L)))
        assertEquals("eq_type_mismatch", CapabilityTypeChecker(InMemoryResolver()).check(def(bad)).code)
        assertEquals(EvalResult.Error("eq_type_mismatch"), run(bad, emptyMap()))
    }

    @Test fun canonicalIsStableButDoesNotReorderLazyExpressions() {
        val typeA = CapType.Record(linkedMapOf("shape" to CapType.Str, "color" to CapType.Str))
        val typeB = CapType.Record(linkedMapOf("color" to CapType.Str, "shape" to CapType.Str))
        assertEquals(CanonicalJson.type(typeA), CanonicalJson.type(typeB))
        val text = CanonicalJson.expr(red)
        assertEquals(text, CapabilityJson.canonical(" \n " + text.replace(",", ", ") + " \n"))
        assertEquals(CanonicalJson.astHash(red), CanonicalJson.astHash(CapabilityJson.decode(text)))
        assertFalse(CanonicalJson.astHash(Expr.And(red, round)) == CanonicalJson.astHash(Expr.And(round, red)))
    }

    @Test fun bool3UnknownLiteralIsRejected() {
        assertEquals("invalid_bool3_literal", assertFailsWith<CapIrParseException> {
            CapabilityJson.decode("""{"op":"const","type":"Bool3","value":"unknown"}""")
        }.code)
    }

    @Test fun lockedCallWorksAndTamperingFails() {
        val helper = def(red, "core.is_red")
        val resolver = InMemoryResolver(listOf(helper))
        val call = Expr.Call("core.is_red", 1, helper.contentHash(), mapOf("item" to input))
        val parent = def(Expr.And(call, round), "demo.red_round")
        assertTrue(CapabilityTypeChecker(resolver).check(parent).ok)
        val value = CapabilityInterpreter(resolver).execute(parent, mapOf("item" to
            EvalResult.Known(CapValue.Rec(mapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("circle"))))))
        assertEquals(EvalResult.Known(CapValue.Bool(true)), value)
        assertEquals("dependency_hash_mismatch", CapabilityTypeChecker(resolver).check(
            parent.copy(body = Expr.Call("core.is_red", 1, "0".repeat(64), mapOf("item" to input)))
        ).code)
    }

    @Test fun threeDistinctProgramsUseSamePrimitiveSet() {
        val fields = mapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("square"))
        assertEquals(EvalResult.Known(CapValue.Bool(false)), run(Expr.And(red, round), fields))
        assertEquals(EvalResult.Known(CapValue.Bool(true)), run(Expr.Or(red, round), fields))
        assertEquals(EvalResult.Known(CapValue.Bool(true)), run(Expr.Not(Expr.And(red, round)), fields))
        val let = Expr.Let("r", red, Expr.And(Expr.Ref("r"), round))
        assertEquals(EvalResult.Known(CapValue.Bool(false)), run(let, fields))
    }

    @Test fun budgetAndErrorsAreNotUnknown() {
        assertEquals(EvalResult.Error("resource_limit"), CapabilityInterpreter(InMemoryResolver()).execute(
            def(Expr.And(red, round)),
            mapOf("item" to EvalResult.Known(CapValue.Rec(mapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("circle"))))),
            EvalBudget(maxSteps = 1)
        ))
    }
}
