// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M4-1c 验收测试 U01–U04；原位重写、有限域等价、净收益接受/拒绝。
package logic.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtreeRewritingTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val arg = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(arg, "color"), Expr.Const(CapValue.Str("red")))
    private val circle = Expr.Eq(Expr.Field(arg, "shape"), Expr.Const(CapValue.Str("circle")))
    private val square = Expr.Eq(Expr.Field(arg, "shape"), Expr.Const(CapValue.Str("square")))
    private val condition = Expr.And(red, circle)

    private fun rec(color: String?, shape: String?): CapValue.Rec = CapValue.Rec(
        buildMap {
            if (color != null) put("color", CapValue.Str(color))
            if (shape != null) put("shape", CapValue.Str(shape))
        },
    )

    private fun point(color: String?, shape: String?): Map<String, CapValue> = mapOf("item" to rec(color, shape))

    private fun domain(): List<Map<String, CapValue>> = listOf(
        point("red", "circle"),
        point("red", "square"),
        point("blue", "circle"),
        point("blue", "square"),
    )

    private fun task(id: String, body: Expr): TaskProgram =
        TaskProgram(id, CapabilityDefinition("task.$id", 1, mapOf("item" to itemType), CapType.Bool3, body))

    private fun candidateFor(tasks: List<TaskProgram>, body: Expr): ExtractHelperCandidate =
        SubtreeExtractor(InMemoryResolver(emptyList())).extract(tasks)
            .candidates.single { it.bodyHash == CanonicalJson.astHash(body) }

    private fun containsCall(expr: Expr, helperId: String, helperHash: String): Boolean {
        var found = false
        fun visit(node: Expr) {
            if (found) return
            when (node) {
                is Expr.Call -> {
                    if (node.id == helperId && node.bodyHash == helperHash) found = true
                    node.args.values.forEach(::visit)
                }
                is Expr.Field -> visit(node.record)
                is Expr.Eq -> { visit(node.left); visit(node.right) }
                is Expr.And -> { visit(node.left); visit(node.right) }
                is Expr.Or -> { visit(node.left); visit(node.right) }
                is Expr.Not -> visit(node.value)
                is Expr.If -> { visit(node.condition); visit(node.whenTrue); visit(node.whenFalse) }
                is Expr.Let -> { visit(node.value); visit(node.body) }
                is Expr.Arg, is Expr.Const, is Expr.Ref -> Unit
            }
        }
        visit(expr)
        return found
    }

    @Test
    fun `U01 profitable rewrite is accepted with net cost reduction`() {
        val tasks = listOf(
            task("t1", Expr.And(condition, square)),
            task("t2", Expr.Or(condition, square)),
            task("t3", Expr.Not(condition)),
        )
        val candidate = candidateFor(tasks, condition)
        val outcome = SubtreeRewriter().rewrite(tasks, candidate, domain())
        assertTrue(outcome.accepted, "reason=${outcome.reason}")
        assertEquals(null, outcome.reason)
        assertEquals(3, outcome.tasks.count { it.replacedCount == 1 })
        assertTrue(outcome.costAfter < outcome.costBefore)
        assertTrue(outcome.equivalence.verified)
        assertEquals(3 * domain().size, outcome.equivalence.checked)
        assertTrue(
            outcome.tasks.all {
                it.rewritten.version == it.original.version + 1 &&
                    containsCall(it.rewritten.body, candidate.helperId, candidate.helper.contentHash())
            },
        )
    }

    @Test
    fun `U02 unprofitable rewrite is rejected instead of auto promoted`() {
        val tasks = listOf(
            task("a", Expr.And(red, circle)),
            task("b", Expr.Or(red, square)),
        )
        val candidate = candidateFor(tasks, red)
        val outcome = SubtreeRewriter().rewrite(tasks, candidate, domain())
        assertFalse(outcome.accepted)
        assertEquals("no_net_benefit", outcome.reason)
        assertTrue(outcome.equivalence.verified, "行为等价应仍然成立")
        assertTrue(outcome.costAfter >= outcome.costBefore)
    }

    @Test
    fun `U03 unknown and error behavior are compared exactly`() {
        val tasks = listOf(
            task("t1", Expr.And(condition, square)),
            task("t2", Expr.Or(condition, square)),
            task("t3", Expr.Not(condition)),
        )
        val candidate = candidateFor(tasks, condition)
        val extended = domain() + listOf(
            point(null, "circle"), // 缺 color → Unknown
            mapOf("item" to CapValue.Str("bad")), // 类型不符 → Error
        )
        val outcome = SubtreeRewriter().rewrite(tasks, candidate, extended, DomainKind.BOUNDED)
        assertTrue(outcome.equivalence.verified)
        assertTrue(outcome.equivalence.bounded)
        assertEquals(3 * extended.size, outcome.equivalence.checked)
        assertTrue(outcome.equivalence.mismatches.isEmpty())
    }

    @Test
    fun `U04 tasks without shared structure produce no candidate`() {
        val tasks = listOf(
            task("a", Expr.And(red, square)),
            task("b", Expr.Or(circle, Expr.Const(CapValue.Bool(true)))),
        )
        val result = SubtreeExtractor(InMemoryResolver(emptyList())).extract(tasks)
        assertTrue(result.candidates.isEmpty(), "没有公共子树时不应提出 helper")
    }

    @Test
    fun `U05 published rewrite follows activation gate and revocation`() {
        val tasks = listOf(
            task("t1", Expr.And(condition, Expr.Const(CapValue.Bool(true)))),
            task("t2", Expr.Or(condition, square)),
            task("t3", Expr.Not(condition)),
        )
        val candidate = candidateFor(tasks, condition)
        val outcome = SubtreeRewriter().rewrite(tasks, candidate, domain())
        assertTrue(outcome.accepted, "reason=${outcome.reason}")

        fun casesFor(definition: CapabilityDefinition): List<VerificationCase> {
            // 重写版本会调用 helper，评估预期值时必须能解析它；否则预期会变成 missing_dependency。
            val resolver = InMemoryResolver(listOf(candidate.helper))
            return domain().mapIndexed { index, point ->
                val actual = CapabilityInterpreter(resolver)
                    .execute(definition, point.mapValues { (_, value) -> EvalResult.Known(value) })
                VerificationCase("c$index", point, actual)
            }
        }

        val suites = TrustedSuiteRegistry()
        suites.register(TrustedVerificationSuite.create("suite.helper", 1, casesFor(candidate.helper)))
        for (rewritten in outcome.tasks) {
            suites.register(
                TrustedVerificationSuite.create("suite.${rewritten.taskId}", 1, casesFor(rewritten.rewritten)),
            )
        }

        val path = java.nio.file.Files.createTempDirectory("b0-rewrite").resolve("capabilities.db")
        SqliteCapabilityRegistry(path).use { registry ->
            // 先发布原程序（version 1），再发布 helper 与重写版本（version 2），旧版本不被覆盖。
            for (original in tasks) assertTrue(registry.propose(original.definition).ok)
            val helperProposal = registry.propose(candidate.helper)
            assertTrue(helperProposal.ok)
            val helperVerification = CapabilityVerificationService(registry, suites)
                .verify(candidate.helperId, 1, "suite.helper", 1)
            assertTrue(helperVerification.recorded)
            assertTrue(
                CapabilityActivationService(registry, suites)
                    .activate(candidate.helperId, 1, helperVerification.runId!!).ok,
            )
            for (rewritten in outcome.tasks) {
                assertTrue(registry.propose(rewritten.rewritten).ok)
                val verification = CapabilityVerificationService(registry, suites)
                    .verify(rewritten.rewritten.id, rewritten.rewritten.version, "suite.${rewritten.taskId}", 1)
                assertTrue(verification.recorded)
                assertTrue(
                    CapabilityActivationService(registry, suites)
                        .activate(rewritten.rewritten.id, rewritten.rewritten.version, verification.runId!!).ok,
                )
            }
            val hit = CapabilityExecutionService(registry).executeActive(
                "task.t1", 2, point("red", "circle").mapValues { (_, value) -> EvalResult.Known(value) },
            )
            assertTrue(hit.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), hit.result)
        }

        SqliteCapabilityRegistry(path).use { registry ->
            // 重启后重写版本仍可执行。
            val restarted = CapabilityExecutionService(registry).executeActive(
                "task.t1", 2, point("red", "circle").mapValues { (_, value) -> EvalResult.Known(value) },
            )
            assertTrue(restarted.ok)
            // 撤销 helper 后，下游重写版本必须失效，而不是静默复用旧激活。
            assertTrue(CapabilityRevocationService(registry).revoke(candidate.helperId, 1, "incorrect_behavior", "evidence:1").ok)
            val blocked = CapabilityExecutionService(registry).executeActive(
                "task.t1", 2, point("red", "circle").mapValues { (_, value) -> EvalResult.Known(value) },
            )
            assertFalse(blocked.ok)
            assertEquals("dependency_revoked", blocked.code)
            // 旧版本原程序保留，仍可 inspect。
            assertTrue(registry.inspect("task.t1", 1).ok)
        }
    }
}
