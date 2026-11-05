// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M3-1a 验收测试 N01–N09；确定性与类型安全优先，不评估公开样本（留给 M3-1b）。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityComposerTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val redBody = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))
    private val red = CapabilityDefinition("core.is_red", 1, mapOf("item" to itemType), CapType.Bool3, redBody)

    private fun request(
        constants: List<CapValue> = listOf(CapValue.Str("red"), CapValue.Str("circle")),
        library: List<CapabilityDefinition> = emptyList(),
        budget: ComposeBudget = ComposeBudget(),
    ) = ComposeRequest(
        inputs = mapOf("item" to itemType),
        output = CapType.Bool3,
        constants = constants,
        library = library,
        budget = budget,
    )

    private fun containsCall(body: Expr): Boolean {
        var found = false
        fun walk(expr: Expr) {
            if (found) return
            when (expr) {
                is Expr.Call -> found = true
                is Expr.Field -> walk(expr.record)
                is Expr.Eq -> { walk(expr.left); walk(expr.right) }
                is Expr.And -> { walk(expr.left); walk(expr.right) }
                is Expr.Or -> { walk(expr.left); walk(expr.right) }
                is Expr.Not -> walk(expr.value)
                is Expr.If -> { walk(expr.condition); walk(expr.whenTrue); walk(expr.whenFalse) }
                is Expr.Let -> { walk(expr.value); walk(expr.body) }
                is Expr.Arg, is Expr.Const, is Expr.Ref -> Unit
            }
        }
        walk(body)
        return found
    }

    @Test
    fun `N01 builds field equality and boolean combinations from public values`() {
        val result = CapabilityComposer().compose(request())
        assertTrue(result.candidates.isNotEmpty())
        val expected = CanonicalJson.expr(redBody)
        assertTrue(result.candidates.any { it.canonical == expected }, "应生成 color == red 候选")
        val conjunction = CanonicalJson.expr(
            Expr.And(redBody, Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle")))),
        )
        assertTrue(result.candidates.any { it.canonical == conjunction }, "应生成 AND 组合候选")
        assertTrue(result.candidates.none { containsCall(it.body) }, "库为空时不应出现 call")
    }

    @Test
    fun `N02 same request yields same order and hashes`() {
        val first = CapabilityComposer().compose(request())
        val second = CapabilityComposer().compose(request())
        assertEquals(first.candidates.map { it.astHash }, second.candidates.map { it.astHash })
        assertEquals(first.generated, second.generated)
        assertEquals(first.deduplicated, second.deduplicated)
    }

    @Test
    fun `N03 every candidate is type correct and within node budget`() {
        val budget = ComposeBudget(maxDepth = 3, maxNodes = 12, maxCandidates = 200)
        val result = CapabilityComposer().compose(request(budget = budget))
        val checker = CapabilityTypeChecker(InMemoryResolver(emptyList()))
        for (candidate in result.candidates) {
            assertTrue(candidate.nodeCount <= budget.maxNodes, "节点数超限: ${candidate.canonical}")
            val definition = CapabilityDefinition("__check", 1, mapOf("item" to itemType), CapType.Bool3, candidate.body)
            assertTrue(checker.check(definition).ok, "候选未通过类型检查: ${candidate.canonical}")
        }
    }

    @Test
    fun `N04 candidates are unique by canonical hash`() {
        val result = CapabilityComposer().compose(request())
        assertEquals(result.candidates.size, result.candidates.map { it.astHash }.toSet().size)
    }

    @Test
    fun `N05 candidate limit is reported`() {
        val result = CapabilityComposer().compose(request(budget = ComposeBudget(maxCandidates = 1)))
        assertEquals(1, result.candidates.size)
        assertTrue(result.exhausted)
        assertEquals("candidate_limit", result.reason)
    }

    @Test
    fun `N06 node limit discards oversized combinations without failing`() {
        val result = CapabilityComposer().compose(
            request(budget = ComposeBudget(maxDepth = 3, maxNodes = 3, maxCandidates = 100)),
        )
        assertTrue(result.nodeLimitSkipped > 0)
        assertTrue(result.candidates.all { it.nodeCount <= 3 })
    }

    @Test
    fun `N07 activated capability becomes a pinned call candidate`() {
        val result = CapabilityComposer().compose(request(constants = emptyList(), library = listOf(red)))
        val callCandidate = result.candidates.firstOrNull { containsCall(it.body) }
        assertTrue(callCandidate != null, "应生成对已激活能力的 call 候选")
        assertEquals(
            listOf(DependencyPin("core.is_red", 1, red.contentHash())),
            callCandidate!!.dependencies,
        )
    }

    @Test
    fun `N08 different versions are pinned independently`() {
        val v2 = CapabilityDefinition(
            "core.is_red", 2, mapOf("item" to itemType), CapType.Bool3,
            Expr.And(redBody, Expr.Const(CapValue.Bool(true))),
        )
        val hashes = mapOf(("core.is_red" to 1) to red.contentHash(), ("core.is_red" to 2) to v2.contentHash())
        val result = CapabilityComposer().compose(request(constants = emptyList(), library = listOf(red, v2)))
        val callCandidates = result.candidates.filter { containsCall(it.body) }
        assertTrue(callCandidates.isNotEmpty())
        for (candidate in callCandidates) {
            for (dependency in candidate.dependencies) {
                assertEquals(hashes[dependency.id to dependency.version], dependency.contentHash)
            }
        }
        assertTrue(result.candidates.any { c -> c.dependencies.any { it.version == 1 } })
        assertTrue(result.candidates.any { c -> c.dependencies.any { it.version == 2 } })
    }

    @Test
    fun `N09 revoked capability is excluded from the active library`() {
        val path: Path = Files.createTempDirectory("b0-composer").resolve("capabilities.db")
        val suites = TrustedSuiteRegistry()
        val cases = listOf(
            VerificationCase("c1", mapOf("item" to CapValue.Rec(mapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("circle")))), EvalResult.Known(CapValue.Bool(true))),
            VerificationCase("c2", mapOf("item" to CapValue.Rec(mapOf("color" to CapValue.Str("red"), "shape" to CapValue.Str("square")))), EvalResult.Known(CapValue.Bool(true))),
            VerificationCase("c3", mapOf("item" to CapValue.Rec(mapOf("color" to CapValue.Str("blue"), "shape" to CapValue.Str("circle")))), EvalResult.Known(CapValue.Bool(false))),
            VerificationCase("c4", mapOf("item" to CapValue.Rec(mapOf("color" to CapValue.Str("green"), "shape" to CapValue.Str("square")))), EvalResult.Known(CapValue.Bool(false))),
        )
        suites.register(TrustedVerificationSuite.create("suite.colors", 1, cases))
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(red).ok)
            val verification = CapabilityVerificationService(registry, suites).verify("core.is_red", 1, "suite.colors", 1)
            assertTrue(verification.recorded)
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, verification.runId!!).ok)
            assertEquals(listOf("core.is_red"), registry.activeCapabilities().map { it.id })

            assertTrue(CapabilityRevocationService(registry).revoke("core.is_red", 1, "incorrect_behavior", "evidence:1").ok)
            assertTrue(registry.activeCapabilities().isEmpty())

            val result = CapabilityComposer().compose(
                request(constants = emptyList(), library = registry.activeCapabilities()),
            )
            assertTrue(result.candidates.none { containsCall(it.body) }, "撤销能力不得进入候选")
            assertFalse(result.candidates.any { c -> c.dependencies.isNotEmpty() })
        }
    }
}
