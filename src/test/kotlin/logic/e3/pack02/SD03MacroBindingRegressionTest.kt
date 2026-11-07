// SD-03 engineering regression, synthetic fixture ONLY (not student-discovered evidence).
// Prepared for repository integration; not yet compiled in the project's Gradle environment.
package logic.e3.pack02

import logic.capability.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SD03MacroBindingRegressionTest {
    @Test
    fun `closed LGG macro receives caller-prefixed arguments and evaluates exact boolean`() {
        val library = C8TestBases.bases.library("FULL").definitions
        val prior = library.first { it.id == "c8v3.p" }
        val sharedCall = Expr.Call(
            prior.id, prior.version, prior.contentHash(),
            prior.inputs.keys.associateWith { Expr.Arg(it, CapType.Str) },
        )
        val left = Expr.And(
            sharedCall,
            Expr.Eq(Expr.Arg("d", CapType.Str), Expr.Const(CapValue.Str("1"))),
        )
        val right = Expr.And(
            sharedCall,
            Expr.Eq(Expr.Arg("c", CapType.Str), Expr.Const(CapValue.Str("2"))),
        )
        fun source(id: String, body: Expr) = SD03StudentSource(
            sourceId = "engineering-$id", runId = "SYNTHETIC_REGRESSION",
            body = body, canonical = CanonicalJson.expr(body),
            validationScope = "SYNTHETIC_ONLY_NOT_CHALLENGE_EVIDENCE",
            evidenceIds = emptyList(), challengeChecks = 0, challengeCounterexamples = 0,
        )
        val result = SD03StudentLoop.buildMacroAndCall(
            listOf(source("a", left), source("b", right)), library,
        )
        assertNotNull(result.pattern)
        assertTrue(!result.pattern!!.degenerate, "LGG should be non-degenerate")
        assertTrue(result.pattern!!.reconstructionOk)
        assertNotNull(result.macro, "closed macro must typecheck")
        assertTrue(result.macro!!.inputs.keys.containsAll(listOf("a", "b")), "shared free arguments must be captured")
        assertEquals(
            EvalResult.Known(CapValue.Bool(false)), result.macroCall,
            "caller values must use call_-prefixed keys; mere EvalResult.Known is insufficient",
        )
    }
}
