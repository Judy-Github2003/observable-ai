// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-02 typed anti-unification / 重建 / 宏调用契约。
package logic.e3.pack02

import logic.capability.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SD02AntiUnificationTest {
    private fun src(id: String, x: String, c: String, y: String, d: String) = SD02VerifiedSource(
        sourceId = id, version = 1, contentHash = "hash-$id",
        body = Expr.And(
            Expr.Eq(Expr.Arg(x, CapType.Str), Expr.Const(CapValue.Str(c))),
            Expr.Not(Expr.Eq(Expr.Arg(y, CapType.Str), Expr.Const(CapValue.Str(d)))),
        ),
        inputTypes = mapOf(x to CapType.Str, y to CapType.Str),
        validationScope = "CHALLENGE", evidenceIds = listOf("ev-$id"),
    )

    @Test
    fun `two distinct programs yield typed non-degenerate pattern`() {
        val p = SD02AntiUnify.generalize(listOf(src("s1", "a", "1", "d", "2"), src("s2", "b", "3", "c", "4")))
        println("pattern=${p.pattern} holes=${p.holes} reason=${p.rejectReason} recon=${p.reconstructionOk}")
        assertTrue(!p.degenerate)
        assertTrue(p.holes.size >= 2)
        assertTrue(p.reconstructionOk)
        assertTrue(p.substitutionsBySource.keys.containsAll(listOf("s1", "s2")))
    }

    @Test
    fun `identical source copies are rejected as degenerate`() {
        val a = src("s1", "a", "1", "d", "2")
        val p = SD02AntiUnify.generalize(listOf(a, a.copy(sourceId = "s1-copy")))
        println("identical degenerate=${p.degenerate} reason=${p.rejectReason}")
        assertTrue(p.degenerate)
        assertNotNull(p.rejectReason)
    }

    @Test
    fun `typed macro body passes typecheck and real Expr Call executes`() {
        val p = SD02AntiUnify.generalize(listOf(src("s1", "a", "1", "d", "2"), src("s2", "b", "3", "c", "4")))
        val inputs = p.holes.associate { it.id to it.expectedType }
        val macro = CapabilityDefinition("sd02.macro", 1, inputs, CapType.Bool3, p.pattern)
        val checker = CapabilityTypeChecker(InMemoryResolver(emptyList()))
        assertTrue(checker.check(macro).ok, "macro typecheck failed: ${checker.check(macro)}")
        val caller = CapabilityDefinition(
            "sd02.caller", 1,
            mapOf("a" to CapType.Str, "c1" to CapType.Str, "d" to CapType.Str, "c2" to CapType.Str),
            CapType.Bool3,
            Expr.Call(
                macro.id, macro.version, macro.contentHash(),
                mapOf(
                    p.holes[0].id to Expr.Arg("a", CapType.Str),
                    p.holes[1].id to Expr.Arg("c1", CapType.Str),
                    p.holes[2].id to Expr.Arg("d", CapType.Str),
                    p.holes[3].id to Expr.Arg("c2", CapType.Str),
                ),
            ),
        )
        val resolver = InMemoryResolver(listOf(macro))
        assertTrue(CapabilityTypeChecker(resolver).check(caller).ok)
        val result = CapabilityInterpreter(resolver).execute(
            caller,
            mapOf(
                "a" to EvalResult.Known(CapValue.Str("a")),
                "c1" to EvalResult.Known(CapValue.Str("1")),
                "d" to EvalResult.Known(CapValue.Str("d")),
                "c2" to EvalResult.Known(CapValue.Str("2")),
            ),
            EvalBudget(maxSteps = 1000),
        )
        println("macro call result=$result")
        assertTrue(result is EvalResult.Known)
    }
}
