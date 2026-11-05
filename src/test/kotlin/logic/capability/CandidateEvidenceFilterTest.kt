// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M3-1b 验收测试 P01–P08；公开样本筛选、歧义、拒答与证据冲突。
package logic.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateEvidenceFilterTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val input = Expr.Arg("item", itemType)
    private val redBody = Expr.Eq(Expr.Field(input, "color"), Expr.Const(CapValue.Str("red")))
    private val circleBody = Expr.Eq(Expr.Field(input, "shape"), Expr.Const(CapValue.Str("circle")))
    private val inputs = mapOf("item" to itemType)

    private fun rec(color: String?, shape: String?): CapValue.Rec = CapValue.Rec(
        buildMap {
            if (color != null) put("color", CapValue.Str(color))
            if (shape != null) put("shape", CapValue.Str(shape))
        },
    )

    private fun example(color: String?, shape: String?, expected: Boolean): PublicExample =
        PublicExample(mapOf("item" to rec(color, shape)), CapValue.Bool(expected))

    private fun domain(): Map<String, List<CapValue>> = mapOf(
        "item" to listOf(
            rec("red", "circle"),
            rec("red", "square"),
            rec("blue", "circle"),
            rec("blue", "square"),
        ),
    )

    private fun compose(budget: ComposeBudget = ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000)): ComposeResult =
        CapabilityComposer().compose(
            ComposeRequest(
                inputs = inputs,
                output = CapType.Bool3,
                constants = listOf(
                    CapValue.Str("red"),
                    CapValue.Str("blue"),
                    CapValue.Str("circle"),
                    CapValue.Str("square"),
                ),
                budget = budget,
            ),
        )

    private fun filter(
        candidates: List<ComposedCandidate>,
        examples: List<PublicExample>,
        queryValues: Map<String, List<CapValue>> = domain(),
        searchExhausted: Boolean = false,
    ): EvidenceResult = CandidateEvidenceFilter(InMemoryResolver(emptyList()))
        .filter(inputs, candidates, examples, queryValues, searchExhausted = searchExhausted)

    private fun candidate(body: Expr): ComposedCandidate {
        val canonical = CanonicalJson.expr(body)
        return ComposedCandidate(
            body = body,
            canonical = canonical,
            astHash = sha256("$IR_SEMANTICS_VERSION\n$canonical"),
            nodeCount = countNodes(body),
            depth = exprDepth(body),
            dependencies = collectDependencies(body),
        )
    }

    @Test
    fun `P01 one positive example leaves multiple distinguishable candidates`() {
        val composed = compose()
        assertFalse(composed.reason == "candidate_limit", "测试预算不应先撞候选上限")
        val examples = listOf(example("red", "circle", true))
        val result = filter(composed.candidates, examples)
        assertEquals(EvidenceStatus.AMBIGUOUS, result.status)
        assertTrue(result.survivors.size > 1)
        assertTrue(result.distinguishingQueries.isNotEmpty(), "应给出可区分查询")
        assertTrue(result.distinguishingQueries.all { it.containsKey("item") })
    }

    @Test
    fun `P02 negative examples converge to the conjunction`() {
        val composed = compose()
        val examples = listOf(
            example("red", "circle", true),
            example("blue", "circle", false),
            example("red", "square", false),
            example("blue", "square", false),
        )
        val result = filter(composed.candidates, examples)
        assertEquals(EvidenceStatus.IDENTIFIED, result.status, "reason=${result.reason}")
        assertEquals(1, result.survivors.size)
        assertEquals(CanonicalJson.expr(Expr.And(redBody, circleBody)), result.survivors.single().canonical)
    }

    @Test
    fun `P03 unknown is not counted as false`() {
        val examples = listOf(example(null, "circle", false))
        val result = filter(listOf(candidate(redBody)), examples)
        assertEquals(EvidenceStatus.REFUSED, result.status)
        assertEquals("no_consistent_candidate", result.reason)
        val redEvaluation = result.evaluations.firstOrNull {
            it.candidate.canonical == CanonicalJson.expr(redBody)
        }
        assertTrue(redEvaluation != null, "应包含 color == red 候选的评估")
        assertEquals(1, redEvaluation!!.unknowns)
        assertEquals(0, redEvaluation.mismatches)
        assertFalse(redEvaluation.consistent)
    }

    @Test
    fun `P04 conflicting labels are rejected before execution`() {
        val composed = compose()
        val examples = listOf(
            example("red", "circle", true),
            example("red", "circle", false),
        )
        val result = filter(composed.candidates, examples)
        assertEquals(EvidenceStatus.REFUSED, result.status)
        assertEquals("conflicting_evidence", result.reason)
        assertTrue(result.evaluations.isEmpty())
    }

    @Test
    fun `P05 unknown attribute id is rejected`() {
        val composed = compose()
        val weird = PublicExample(
            mapOf("item" to CapValue.Rec(mapOf("color" to CapValue.Str("red"), "texture" to CapValue.Str("rough")))),
            CapValue.Bool(true),
        )
        val result = filter(composed.candidates, listOf(weird))
        assertEquals(EvidenceStatus.REFUSED, result.status)
        assertEquals("unknown_attribute", result.reason)
    }

    @Test
    fun `P06 rule beyond hypothesis space is refused not guessed`() {
        // 目标是最小多数函数 majority(R,C,S)，需要三层以上嵌套；maxDepth=3 的候选空间只能组合两个原子。
        val threeFields = CapType.Record(mapOf("color" to CapType.Str, "shape" to CapType.Str, "size" to CapType.Str))
        fun record(color: String, shape: String, size: String) = CapValue.Rec(
            mapOf("color" to CapValue.Str(color), "shape" to CapValue.Str(shape), "size" to CapValue.Str(size)),
        )
        val values = listOf("red", "blue")
        val shapes = listOf("circle", "square")
        val sizes = listOf("small", "large")
        val examples = ArrayList<PublicExample>()
        for (color in values) for (shape in shapes) for (size in sizes) {
            val votes = listOf(color == "red", shape == "circle", size == "small").count { it }
            examples += PublicExample(mapOf("item" to record(color, shape, size)), CapValue.Bool(votes >= 2))
        }
        val composed = CapabilityComposer().compose(
            ComposeRequest(
                inputs = mapOf("item" to threeFields),
                output = CapType.Bool3,
                constants = listOf(CapValue.Str("red"), CapValue.Str("circle"), CapValue.Str("small")),
                budget = ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 5000),
            ),
        )
        assertFalse(composed.reason == "candidate_limit")
        val result = CandidateEvidenceFilter(InMemoryResolver(emptyList()))
            .filter(mapOf("item" to threeFields), composed.candidates, examples)
        assertEquals(
            EvidenceStatus.REFUSED,
            result.status,
            "actual=${result.status} reason=${result.reason} survivors=${result.survivors.map { it.canonical }}",
        )
        assertEquals("no_consistent_candidate", result.reason)
        assertTrue(result.survivors.isEmpty())
    }

    @Test
    fun `P07 truncated search is reported as budget exhaustion`() {
        val composed = compose(budget = ComposeBudget(maxDepth = 2, maxNodes = 32, maxCandidates = 1))
        assertEquals("candidate_limit", composed.reason)
        val result = filter(composed.candidates, listOf(example("red", "circle", true)), searchExhausted = true)
        assertEquals(EvidenceStatus.REFUSED, result.status)
        assertEquals("search_budget_exhausted", result.reason)
    }

    @Test
    fun `P08 equivalent syntax is one class and filtering is deterministic`() {
        val conjunction = Expr.And(redBody, circleBody)
        val doubleNegation = Expr.Not(Expr.Not(conjunction))
        val candidates = listOf(candidate(conjunction), candidate(doubleNegation))
        val examples = listOf(
            example("red", "circle", true),
            example("blue", "circle", false),
            example("red", "square", false),
            example("blue", "square", false),
        )
        val first = filter(candidates, examples)
        assertEquals(EvidenceStatus.IDENTIFIED, first.status)
        assertEquals(1, first.survivors.size)
        assertEquals(CanonicalJson.expr(conjunction), first.survivors.single().canonical)

        val second = filter(candidates, examples)
        assertEquals(first.status, second.status)
        assertEquals(first.survivors.map { it.astHash }, second.survivors.map { it.astHash })
        assertEquals(first.distinguishingQueries, second.distinguishingQueries)
    }
}
