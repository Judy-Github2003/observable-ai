// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-02D 在 W4/独立 XOR 上做 G1–G5 考官归因；主动查询使用独立 QUERY/CHALLENGE 渠道。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class MRX2DG1G5Test {
    @Test
    fun `W4 and XOR G1 to G5`() {
        val full = C8TestBases.bases.library("FULL")
        val cases = listOf(
            "mrx-depth-w4" to MRXWorlds.baseline.first { it.id == "mrx-depth-w4" },
            "mrx2c-xor-a-d" to MRXWorld(
                "mrx2c-xor-a-d", MRXCategory.DEPTH, "a1 XOR d2",
                { m -> (m["a"] == "1") != (m["d"] == "2") },
            ),
        )
        val records = ArrayList<JsonObject>()
        for ((id, world) in cases) {
            val split = MRXWorlds.splitFor(world, 1449)
            val training = split.train.mapIndexed { i, m -> StudyCase("g-$i", m, world.truth(m)) }
            val space = MRX2BSpace(C8Domain.attributes, full.definitions, training, MRX2BStrategy.PAIRWISE).generate()
            val retained = space.trace.candidates(MRX2BStage.RETAINED)
            val audit = MRX2BAuditor.auditAfterExam(
                space.trace, C8Domain.all(), split.query + split.challenge, world.truth,
                full.definitions, null, queryBudget = 16,
            )
            val cost = MRX2Cost()
            val queryLedger = MRX2Ledger(split.query, StudyQueryController(split.query, 16, world.truth), 16, cost)
            val challengeLedger = MRX2Ledger(split.challenge, StudyQueryController(split.challenge, 8, world.truth), 8, cost)
            val identifier = MRX2DActiveIdentifier(
                listOf(
                    MRX2DChannel("QUERY", split.query, queryLedger, 16),
                    MRX2DChannel("CHALLENGE", split.challenge, challengeLedger, 8),
                ),
                { c, input -> Pack02Knowledge.predict(c.body, input, full.definitions) },
                cost,
            )
            val result = identifier.identify(retained)
            println("COST $id new=${cost.oracleNewQueries} reuse=${cost.oracleReuseUses} gen=${cost.generatedCandidates} calls=${cost.interpreterCalls}")
            val finalAudit = MRX2BAuditor.auditAfterExam(
                space.trace, C8Domain.all(), split.query + split.challenge, world.truth,
                full.definitions, result.selected, queryBudget = 16,
            )
            val held = result.selected?.let { c ->
                split.heldout.count { input -> Pack02Knowledge.predict(c.body, input, full.definitions) == world.truth(input) }
            } ?: 0
            println("$id retained=${retained.size} status=${result.status} classes=${result.initialClasses}->${result.remainingClasses} " +
                "replay=${result.replayEliminated} new=${result.newQueries} held=$held/27 g0=${audit.g0} g1=${audit.g1GeneratedCorrect} " +
                "g2=${audit.g2RetainedCorrect} g3=${audit.g3} g4=${finalAudit.g4SelectedCorrect}")
            records += buildJsonObject {
                put("world", id)
                put("seed", split.seed)
                put("retained", retained.size)
                put("status", result.status)
                put("initialClasses", result.initialClasses)
                put("remainingClasses", result.remainingClasses)
                put("replayEliminated", result.replayEliminated)
                put("newQueries", result.newQueries)
                put("heldoutCorrect", held)
                put("heldoutTotal", 27)
                put("g0", audit.g0)
                put("g1", audit.g1GeneratedCorrect)
                put("g2", audit.g2RetainedCorrect)
                put("g3", audit.g3)
                put("g4", finalAudit.g4SelectedCorrect)
                put("g5", if (result.status.contains("PROVISIONAL") || result.status.contains("UNKNOWN")) "DECLARED_INSUFFICIENT" else "NO_FALSE_ACTIVATION_CLAIM")
                put("observations", buildJsonArray {
                    result.observations.forEach { o ->
                        add(buildJsonObject {
                            put("channel", o.channel); put("input", o.inputKey); put("label", o.label)
                            put("queryIndex", o.queryIndex); put("replayed", o.replayed); put("eliminated", o.eliminated)
                        })
                    }
                })
            }
        }
        val root = buildJsonObject {
            put("experiment", "MRX-02D")
            put("phase", "G1_G5_W4_XOR")
            put("records", buildJsonArray { records.forEach { add(it) } })
        }
        val out = Path.of("experiments/representation-evolution-e3/reports/mrx-02d-g1-g5.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root))
        assertTrue(Files.size(out) > 500)
        assertTrue(records.any { it["g1"]!!.jsonPrimitive.boolean })
    }
}
