// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-1b 验收测试 V01–V16；只测内存验证语义，不写库、不激活。
package logic.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrustedVerificationTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val itemArg = Expr.Arg("item", itemType)
    private val isRed = CapabilityDefinition(
        "core.is_red", 1, mapOf("item" to itemType), CapType.Bool3,
        Expr.Eq(Expr.Field(itemArg, "color"), Expr.Const(CapValue.Str("red"))),
    )
    private val alwaysTrue = CapabilityDefinition(
        "core.always_true", 1, mapOf("item" to itemType), CapType.Bool3,
        Expr.Const(CapValue.Bool(true)),
    )

    private fun item(color: String?, shape: String = "circle"): CapValue.Rec {
        val fields = linkedMapOf<String, CapValue>()
        if (color != null) fields["color"] = CapValue.Str(color)
        fields["shape"] = CapValue.Str(shape)
        return CapValue.Rec(fields)
    }

    private fun args(color: String?, shape: String = "circle"): Map<String, CapValue> = mapOf("item" to item(color, shape))

    private fun known(value: Boolean): EvalResult = EvalResult.Known(CapValue.Bool(value))

    private fun case(id: String, color: String?, expected: Boolean, shape: String = "circle") =
        VerificationCase(id, args(color, shape), known(expected))

    private fun fourDefinite(): List<VerificationCase> = listOf(
        case("c1", "red", true, "circle"),
        case("c2", "red", true, "square"),
        case("c3", "blue", false, "circle"),
        case("c4", "green", false, "square"),
    )

    private fun verify(
        def: CapabilityDefinition,
        cases: List<VerificationCase>,
        source: VerificationSource = VerificationSource.HOST_FIXTURE,
        budget: EvalBudget = EvalBudget(),
        extra: List<CapabilityDefinition> = emptyList(),
        suiteId: String = "suite.test",
    ): VerificationOutcome {
        val resolver = InMemoryResolver(listOf(def) + extra)
        val registry = TrustedSuiteRegistry()
        registry.register(TrustedVerificationSuite.create(suiteId, 1, cases), source)
        return TrustedVerificationEngine(resolver, registry).verify(def.id, def.version, suiteId, 1, budget)
    }

    @Test
    fun `V01 four independent definite cases pass`() {
        val outcome = verify(isRed, fourDefinite())
        assertTrue(outcome.ok)
        assertEquals(VerificationVerdict.PASS, outcome.verdict)
        assertNull(outcome.code)
        assertEquals(4, outcome.caseCount)
        assertEquals(4, outcome.passedCount)
        assertEquals(0, outcome.failedCount)
        assertEquals(4, outcome.definiteCount)
        assertEquals(2, outcome.trueCount)
        assertEquals(2, outcome.falseCount)
        assertEquals(VerificationSource.HOST_FIXTURE, outcome.source)
        assertEquals(isRed.contentHash(), outcome.contentHash)
        assertEquals(64, outcome.suiteHash.length)
    }

    @Test
    fun `V02 all true cases are inadequate even when all pass`() {
        val cases = listOf(
            case("c1", "red", true),
            case("c2", "blue", true),
            case("c3", "green", true),
            case("c4", "black", true),
        )
        val outcome = verify(alwaysTrue, cases)
        assertEquals(VerificationVerdict.FAIL, outcome.verdict)
        assertEquals("suite_inadequate", outcome.code)
        assertEquals(4, outcome.passedCount)
        assertEquals(4, outcome.trueCount)
        assertEquals(0, outcome.falseCount)
    }

    @Test
    fun `V03 fewer than four definite cases are inadequate`() {
        val cases = listOf(case("c1", "red", true), case("c2", "red", true, "square"), case("c3", "blue", false))
        val outcome = verify(isRed, cases)
        assertEquals("suite_inadequate", outcome.code)
        assertEquals(3, outcome.definiteCount)
        assertEquals(0, outcome.failedCount)
    }

    @Test
    fun `V04 missing field is Unknown and never counts as false`() {
        val cases = fourDefinite() + VerificationCase("c5", args(null), known(false))
        val outcome = verify(isRed, cases)
        assertEquals("case_failed", outcome.code)
        assertEquals(1, outcome.failedCount)
        val missing = outcome.cases.first { it.caseId == "c5" }
        assertEquals(VerificationCaseStatus.UNKNOWN_MISMATCH, missing.status)
        assertTrue(missing.actual is EvalResult.Unknown)
        assertEquals(false, missing.countedAsDefinite)
    }

    @Test
    fun `V05 expected Unknown matches but never counts toward coverage`() {
        val withUnknown = fourDefinite() + VerificationCase("c5", args(null), EvalResult.Unknown("missing_attribute"))
        val mixed = verify(isRed, withUnknown)
        assertTrue(mixed.ok)
        assertEquals(5, mixed.passedCount)
        assertEquals(4, mixed.definiteCount)

        val onlyUnknown = (1..4).map { index ->
            VerificationCase("u$index", args(null, "shape$index"), EvalResult.Unknown("missing_attribute"))
        }
        val inadequate = verify(isRed, onlyUnknown)
        assertEquals("suite_inadequate", inadequate.code)
        assertEquals(4, inadequate.passedCount)
        assertEquals(0, inadequate.definiteCount)
    }

    @Test
    fun `V06 actual Error always fails and budget is enforced`() {
        val compound = CapabilityDefinition(
            "core.red_round", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.And(
                Expr.Eq(Expr.Field(itemArg, "color"), Expr.Const(CapValue.Str("red"))),
                Expr.Eq(Expr.Field(itemArg, "shape"), Expr.Const(CapValue.Str("circle"))),
            ),
        )
        val outcome = verify(compound, fourDefinite(), budget = EvalBudget(maxSteps = 1))
        assertEquals("case_failed", outcome.code)
        assertEquals(4, outcome.failedCount)
        outcome.cases.forEach { case ->
            assertEquals(VerificationCaseStatus.ACTUAL_ERROR, case.status)
            assertEquals(EvalResult.Error("resource_limit"), case.actual)
        }
    }

    @Test
    fun `V07 wrong expected value fails the run`() {
        val cases = fourDefinite().toMutableList()
        cases[2] = case("c3", "blue", true)
        val outcome = verify(isRed, cases)
        assertEquals("case_failed", outcome.code)
        assertEquals(1, outcome.failedCount)
        assertEquals(VerificationCaseStatus.WRONG_VALUE, outcome.cases.first { it.caseId == "c3" }.status)
    }

    @Test
    fun `V08 duplicate case ids are rejected`() {
        val cases = listOf(case("dup", "red", true), case("dup", "blue", false))
        val outcome = verify(isRed, cases)
        assertEquals("duplicate_case_id", outcome.code)
        assertEquals(0, outcome.caseCount)
    }

    @Test
    fun `V09 repeated identical inputs do not inflate coverage`() {
        val cases = (1..4).map { index -> case("c$index", "red", true) }
        val outcome = verify(isRed, cases)
        assertEquals("suite_inadequate", outcome.code)
        assertEquals(4, outcome.passedCount)
        assertEquals(1, outcome.definiteCount)
        assertEquals(1, outcome.trueCount)
    }

    @Test
    fun `V10 suite hash ignores map insertion order`() {
        val first = TrustedVerificationSuite.create(
            "suite.hash", 1,
            listOf(
                VerificationCase("c1", mapOf("item" to CapValue.Rec(linkedMapOf("shape" to CapValue.Str("circle"), "color" to CapValue.Str("red")))), known(true)),
            ),
        )
        val second = TrustedVerificationSuite.create(
            "suite.hash", 1,
            listOf(
                VerificationCase("c1", mapOf("item" to CapValue.Rec(linkedMapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("circle")))), known(true)),
            ),
        )
        assertEquals(first.suiteHash, second.suiteHash)
    }

    @Test
    fun `V11 suite hash changes with case order or expected value`() {
        val base = TrustedVerificationSuite.create("suite.hash", 1, fourDefinite())
        val reordered = TrustedVerificationSuite.create("suite.hash", 1, fourDefinite().reversed())
        val changed = TrustedVerificationSuite.create(
            "suite.hash", 1,
            fourDefinite().mapIndexed { index, case -> if (index == 0) case.copy(expected = known(false)) else case },
        )
        assertEquals(64, base.suiteHash.length)
        assertTrue(base.suiteHash != reordered.suiteHash, "用例顺序影响报告编号，哈希必须变化")
        assertTrue(base.suiteHash != changed.suiteHash, "改一个预期值哈希必须变化")
    }

    @Test
    fun `V12 source comes from host registration and unknown suite is rejected`() {
        val resolver = InMemoryResolver(listOf(isRed))
        val registry = TrustedSuiteRegistry()
        registry.register(TrustedVerificationSuite.create("suite.src", 1, fourDefinite()), VerificationSource.IMPLEMENTER_RUN)
        val engine = TrustedVerificationEngine(resolver, registry)
        val outcome = engine.verify("core.is_red", 1, "suite.src", 1)
        assertEquals(VerificationSource.IMPLEMENTER_RUN, outcome.source)

        val missing = engine.verify("core.is_red", 1, "suite.absent", 1)
        assertEquals("unknown_suite", missing.code)
        assertNull(missing.source)
        assertNull(missing.contentHash)
    }

    @Test
    fun `V13 non Bool3 output is unsupported`() {
        val stringOut = CapabilityDefinition(
            "core.name", 1, mapOf("item" to itemType), CapType.Str, Expr.Const(CapValue.Str("x")),
        )
        val outcome = verify(stringOut, listOf(VerificationCase("c1", args("red"), EvalResult.Known(CapValue.Str("x")))))
        assertEquals("unsupported_output_type", outcome.code)
        assertEquals(0, outcome.caseCount)
    }

    @Test
    fun `V14 empty suite is rejected`() {
        val outcome = verify(isRed, emptyList())
        assertEquals("suite_empty", outcome.code)
        assertEquals(0, outcome.caseCount)
    }

    @Test
    fun `V15 same suite id and version cannot change content`() {
        val registry = TrustedSuiteRegistry()
        registry.register(TrustedVerificationSuite.create("suite.immutable", 1, fourDefinite()))
        val changed = TrustedVerificationSuite.create(
            "suite.immutable", 1,
            fourDefinite().mapIndexed { index, case -> if (index == 0) case.copy(expected = known(false)) else case },
        )
        val error = assertFailsWith<IllegalArgumentException> { registry.register(changed) }
        assertEquals("suite_version_conflict", error.message)
        // 同内容重复登记是幂等的。
        registry.register(TrustedVerificationSuite.create("suite.immutable", 1, fourDefinite()))
    }

    @Test
    fun `V16 missing dependency and invalid expectation fail without executing`() {
        val missing = CapabilityDefinition(
            "core.needs_missing", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.Call("core.absent", 1, "0".repeat(64), mapOf("item" to itemArg)),
        )
        assertEquals("missing_dependency", verify(missing, fourDefinite()).code)

        val invalid = verify(
            isRed,
            fourDefinite() + VerificationCase("bad", args("red"), EvalResult.Error("boom")),
        )
        assertEquals("invalid_expectation", invalid.code)
        assertEquals(VerificationCaseStatus.INVALID_EXPECTATION, invalid.cases.first { it.caseId == "bad" }.status)
    }
}
