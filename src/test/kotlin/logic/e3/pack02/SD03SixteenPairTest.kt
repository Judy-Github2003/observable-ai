// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-03 16-pair：真实调用 SD02AntiUnify.generalize 16 次，逐对记录。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SD03SixteenPairTest {
    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

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
    fun `all sixteen real pairs actually invoke generalize`() {
        val w1 = MRXWorld("pair-a", MRXCategory.DEPTH, "a=1 and d=1",
            { m -> m["a"] == "1" && m["d"] == "1" })
        val w2 = MRXWorld("pair-b", MRXCategory.DEPTH, "b=3 and c=3",
            { m -> m["b"] == "3" && m["c"] == "3" })
        val a = pool(w1, "pair-a")
        val b = pool(w2, "pair-b")
        assertEquals(4, a.size, "pre-registered A pool must be 4")
        assertEquals(4, b.size, "pre-registered B pool must be 4")
        val dir = Path.of("docs/research/sd03-16pair-2026-11-07")
        Files.createDirectories(dir)
        val sourceLines = ArrayList<String>()
        for ((tag, src) in (a.map { "A" to it } + b.map { "B" to it })) {
            sourceLines += """{"type":"source","tag":"$tag","id":"${esc(src.sourceId)}","run":"${esc(src.runId)}","sha256":"${sha(src.canonical)}","scope":"${src.validationScope}","checks":${src.challengeChecks},"counterexamples":${src.challengeCounterexamples},"ast":"${esc(src.body.toString())}"}"""
        }
        Files.write(dir.resolve("SOURCES-16PAIR.jsonl"), sourceLines)

        val pairLines = ArrayList<String>()
        var attempts = 0
        var valid = 0
        for ((ia, x) in a.withIndex()) for ((ib, y) in b.withIndex()) {
            attempts++
            val pairId = "A$ia-B$ib"
            val diag = SD03StudentLoop.diagnose(x.body, y.body)
            val verifiedX = SD02VerifiedSource(
                x.sourceId, 1, sha(x.canonical), x.body,
                C8Domain.attributes.associateWith { CapType.Str }, x.validationScope, x.evidenceIds,
            )
            val verifiedY = SD02VerifiedSource(
                y.sourceId, 1, sha(y.canonical), y.body,
                C8Domain.attributes.associateWith { CapType.Str }, y.validationScope, y.evidenceIds,
            )
            val result = try {
                SD02AntiUnify.generalize(listOf(verifiedX, verifiedY))
            } catch (e: Exception) {
                pairLines += """{"type":"lgg_attempt","pairId":"$pairId","generalizeInvoked":false,"error":"${esc(e.message ?: e::class.simpleName ?: "unknown")}"}"""
                continue
            }
            val macro = if (!result.degenerate) {
                CapabilityDefinition(
                    "sd03.16pair.macro", 1,
                    result.holes.associate { it.id to it.expectedType }, CapType.Bool3, result.pattern,
                )
            } else null
            val typecheck = macro?.let {
                CapabilityTypeChecker(InMemoryResolver(listOf(it))).check(it).ok
            } ?: false
            val reconLeft = if (result.degenerate) false else
                SD02AntiUnify.reconstruct(result.pattern, result.substitutionsBySource[x.sourceId].orEmpty()) == x.body
            val reconRight = if (result.degenerate) false else
                SD02AntiUnify.reconstruct(result.pattern, result.substitutionsBySource[y.sourceId].orEmpty()) == y.body
            if (!result.degenerate && result.reconstructionOk && reconLeft && reconRight && typecheck) valid++
            pairLines += """{"type":"lgg_attempt","pairId":"$pairId","generalizeInvoked":true,"sourceA":"${sha(x.canonical)}","sourceB":"${sha(y.canonical)}","diagnostic":{"category":"${diag.category}","path":"${esc(diag.path)}"},"degenerate":${result.degenerate},"holes":${result.holes.size},"reconstructionOk":${result.reconstructionOk},"reconLeft":$reconLeft,"reconRight":$reconRight,"rejectReason":"${esc(result.rejectReason ?: "")}","pattern":"${esc(result.pattern.toString())}","typecheck":$typecheck}"""
        }
        Files.write(dir.resolve("PAIR-LGG.jsonl"), pairLines)
        println("pairs=$attempts valid=$valid")
        assertEquals(16, attempts)
        assertEquals(16, pairLines.size)
        assertTrue(pairLines.all { it.contains("\"generalizeInvoked\":true") })
    }
}
