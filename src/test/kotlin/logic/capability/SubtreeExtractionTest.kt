// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M4-1b 验收测试 S01–S05；只提出候选，不改写任务程序，不误合并不同 AST 或捕获局部变量。
package logic.capability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtreeExtractionTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val arg = Expr.Arg("item", itemType)
    private val red = Expr.Eq(Expr.Field(arg, "color"), Expr.Const(CapValue.Str("red")))
    private val circle = Expr.Eq(Expr.Field(arg, "shape"), Expr.Const(CapValue.Str("circle")))
    private val square = Expr.Eq(Expr.Field(arg, "shape"), Expr.Const(CapValue.Str("square")))

    private fun task(id: String, body: Expr): TaskProgram =
        TaskProgram(id, CapabilityDefinition("task.$id", 1, mapOf("item" to itemType), CapType.Bool3, body))

    private fun extract(vararg tasks: TaskProgram): ExtractionResult =
        SubtreeExtractor(InMemoryResolver(emptyList())).extract(tasks.toList())

    private fun containsRef(root: Expr): Boolean {
        var found = false
        fun visit(expr: Expr) {
            if (found) return
            when (expr) {
                is Expr.Ref -> found = true
                is Expr.Field -> visit(expr.record)
                is Expr.Eq -> { visit(expr.left); visit(expr.right) }
                is Expr.And -> { visit(expr.left); visit(expr.right) }
                is Expr.Or -> { visit(expr.left); visit(expr.right) }
                is Expr.Not -> visit(expr.value)
                is Expr.If -> { visit(expr.condition); visit(expr.whenTrue); visit(expr.whenFalse) }
                is Expr.Let -> { visit(expr.value); visit(expr.body) }
                is Expr.Call -> expr.args.values.forEach(::visit)
                is Expr.Arg, is Expr.Const -> Unit
            }
        }
        visit(root)
        return found
    }

    @Test
    fun `S01 repeated isRed subtree becomes one helper candidate`() {
        val result = extract(
            task("t1", Expr.And(red, circle)),
            task("t2", Expr.Or(red, square)),
        )
        val candidate = result.candidates.single { it.bodyHash == CanonicalJson.astHash(red) }
        assertEquals(2, candidate.occurrences.size)
        assertEquals(setOf("t1", "t2"), candidate.occurrences.map { it.taskId }.toSet())
        assertEquals(4, candidate.nodeCount)
        assertEquals(mapOf("item" to itemType), candidate.helper.inputs)
        assertEquals(CapType.Bool3, candidate.helper.output)
        assertTrue(candidate.helperId.startsWith("helper."))
        assertEquals(CanonicalJson.expr(red), candidate.bodyCanonical)
        assertTrue(candidate.dependencies.isEmpty())
        assertEquals(setOf("item"), candidate.replacement.args.keys)
        assertEquals(candidate.helper.contentHash(), candidate.replacement.bodyHash)
        assertEquals(candidate.helperId, candidate.replacement.id)
    }

    @Test
    fun `S02 different ASTs are not merged just because they look alike`() {
        val result = extract(
            task("t1", Expr.And(red, circle)),
            task("t2", Expr.And(circle, red)),
        )
        val redCandidate = result.candidates.single { it.bodyHash == CanonicalJson.astHash(red) }
        val circleCandidate = result.candidates.single { it.bodyHash == CanonicalJson.astHash(circle) }
        assertEquals(2, redCandidate.occurrences.size)
        assertEquals(2, circleCandidate.occurrences.size)
        // 两个任务里的 AND 操作数顺序不同，canonical AST 不同，不能被当成同一候选。
        assertTrue(result.candidates.none { it.helper.body is Expr.And })
    }

    @Test
    fun `S03 let scoped subtrees never capture local variables`() {
        val scoped = Expr.Let("tmp", red, Expr.And(Expr.Ref("tmp"), circle))
        val result = extract(
            task("t1", scoped),
            task("t2", Expr.And(red, square)),
        )
        assertTrue(result.candidates.isNotEmpty())
        assertTrue(result.candidates.none { containsRef(it.helper.body) }, "候选不得含 Ref")
        val redCandidate = result.candidates.single { it.bodyHash == CanonicalJson.astHash(red) }
        assertEquals(2, redCandidate.occurrences.size)
    }

    @Test
    fun `S04 extraction order and hashes are deterministic`() {
        val tasks = listOf(
            task("t1", Expr.And(red, circle)),
            task("t2", Expr.Or(red, square)),
            task("t3", Expr.And(circle, square)),
        )
        val first = SubtreeExtractor(InMemoryResolver(emptyList())).extract(tasks)
        val second = SubtreeExtractor(InMemoryResolver(emptyList())).extract(tasks)
        assertEquals(first.candidates.map { it.bodyHash }, second.candidates.map { it.bodyHash })
        assertEquals(first.scannedTasks, second.scannedTasks)
    }

    @Test
    fun `S05 whole body is not proposed as its own helper`() {
        val body = Expr.And(red, circle)
        val result = extract(task("t1", body), task("t2", body))
        assertTrue(result.candidates.none { it.bodyCanonical == CanonicalJson.expr(body) })
        assertFalse(result.candidates.isEmpty(), "应仍能提出内部公共子树")
    }
}
