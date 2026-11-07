// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-03 续研 Gate 1：真实来源 AST 取证与首次不一致路径/类别。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

class SD03ForensicTest {
    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun pool(world: MRXWorld, runId: String): List<SD03StudentSource> {
        val training = C8Domain.all().take(40)
        val challenge = C8Domain.all().drop(40)
        val cases = training.mapIndexed { i, m -> StudyCase("$runId-$i", m, world.truth(m)) }
        val full = C8TestBases.bases.library("FULL")
        return SD03StudentLoop.findSources(
            runId, C8Domain.attributes, full.definitions, cases,
            StudyQueryController(challenge, challenge.size, world.truth), challenge, limit = 4,
        )
    }

    @Test
    fun `forensic AST mismatch for real source pairs`() {
        val w1 = MRXWorld("sd03c-a", MRXCategory.DEPTH, "a=1 and d=1",
            { m -> m["a"] == "1" && m["d"] == "1" })
        val w2 = MRXWorld("sd03c-b", MRXCategory.DEPTH, "b=3 and c=3",
            { m -> m["b"] == "3" && m["c"] == "3" })
        val a = pool(w1, "forensic-a")
        val b = pool(w2, "forensic-b")
        println("poolA=${a.size} poolB=${b.size}")
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        val dir = Path.of("docs/research/sd03-continuation-2026-11-07")
        Files.createDirectories(dir)
        val lines = ArrayList<String>()
        var pairs = 0
        for (x in a) for (y in b) {
            pairs++
            val d = SD03StudentLoop.diagnose(x.body, y.body)
            println("pair ${x.sourceId.take(10)}/${y.sourceId.take(10)} category=${d.category} path=${d.path} types=${d.leftType}/${d.rightType}")
            lines += """{"type":"antiunify_attempt","lhs":"${sha(x.canonical)}","rhs":"${sha(y.canonical)}","category":"${d.category}","path":"${d.path}","leftType":"${d.leftType}","rightType":"${d.rightType}"}"""
        }
        lines += """{"type":"source_pool","condition":"A","count":${a.size}}"""
        lines += """{"type":"source_pool","condition":"B","count":${b.size}}"""
        lines += """{"type":"pair_count","pairs":$pairs}"""
        Files.write(dir.resolve("FORENSIC-SD03.jsonl"), lines)
        assertTrue(pairs > 0)
    }
}
