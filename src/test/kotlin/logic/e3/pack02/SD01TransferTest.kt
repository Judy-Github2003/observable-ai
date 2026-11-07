// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-01 迁移/禁用消融与真实 trace；封闭程序实例不应冒充可复用宏。
package logic.e3.pack02

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SD01TransferTest {
    private fun cases(split: MRXSplit, world: MRXWorld, prefix: String) =
        split.train.mapIndexed { i, m -> StudyCase("$prefix-$i", m, world.truth(m)) }

    @Test
    fun `closed discovered instance is evaluated on transfer and disabled ablation`() {
        val w4 = MRXWorlds.baseline.first { it.id == "mrx-depth-w4" }
        val xor = MRXWorld(
            "sd01-transfer-xor", MRXCategory.DEPTH, "independent attributes",
            { m -> (m["a"] == "1") != (m["d"] == "2") },
        )
        val full = C8TestBases.bases.library("FULL")
        val w4Split = MRXWorlds.splitFor(w4, 1449)
        val xorSplit = MRXWorlds.splitFor(xor, 1449)
        val discovered = SD01Discovery.discover(
            C8Domain.attributes, full.definitions, cases(w4Split, w4, "d"),
            w4Split.query, w4Split.challenge, w4.truth,
        )
        val baseline = SD01Discovery.discover(
            C8Domain.attributes, full.definitions, cases(w4Split, w4, "b"),
            w4Split.query, w4Split.challenge, w4.truth, MRX2BStrategy.BASELINE,
        )
        val structure = discovered.structure
        assertNotNull(structure)
        assertEquals("PROGRAM_INSTANCE", structure.kind)
        assertTrue(structure.parameterTypes.isEmpty())

        val xorCases = cases(xorSplit, xor, "t")
        val (checks, correct) = SD01Discovery.transfer(structure, xorCases, full.definitions)
        val (disabledChecks, disabledCorrect) = SD01Discovery.transfer(null, xorCases, full.definitions)
        assertEquals(xorCases.size, checks)
        assertEquals(0, disabledChecks)
        assertEquals(0, disabledCorrect)

        val trace = Path.of("docs/research/sd-01-2026-11-07", "TRACE-SD01.jsonl")
        Files.createDirectories(trace.parent)
        val lines = ArrayList<String>()
        lines += """{"type":"run","condition":"STRUCTURE_DISCOVERY","generated":${discovered.generated},"retained":${discovered.retained},"structureKind":"${structure.kind}","structureId":"${structure.structureId}","note":"${discovered.note}"}"""
        lines += """{"type":"run","condition":"BASE_SEARCH","generated":${baseline.generated},"retained":${baseline.retained},"structureKind":"${baseline.structure?.kind ?: "NONE"}","note":"same bounded generator; no new macro"}"""
        lines += """{"type":"transfer","from":"W4","to":"XOR","checks":$checks,"correct":$correct,"disabledChecks":$disabledChecks,"disabledCorrect":$disabledCorrect}"""
        discovered.candidates.forEach { c ->
            lines += """{"type":"candidate","id":"${c.candidateId.hashCode()}","status":"${c.status}","checks":${c.challengeChecks},"counterexamples":${c.challengeCounterexamples},"scope":"${c.validationScope}","kind":"${c.structureKind}"}"""
        }
        Files.write(trace, lines)
        println("discovered=${discovered.generated}/${discovered.retained} structure=${structure.structureId} " +
            "transfer=$correct/$checks disabled=$disabledCorrect/$disabledChecks baseline=${baseline.structure != null}")
        assertTrue(Files.exists(trace))
    }
}
