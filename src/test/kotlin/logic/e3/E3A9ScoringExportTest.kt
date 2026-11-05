// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 评分契约示例；证明有界有效性/隐藏错误/越权三者分离。
package logic.e3

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class E3A9ScoringExportTest {

    private fun result(claim: A9DiagnosticClaim, global: Boolean = false) = A9BaselineResult(
        A9BaselineId.B0_ONLY, claim, null, listOf("e1"), null,
        E3UsageSnapshot(1, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 1), emptyList(), claimedGlobal = global,
    )

    @Test
    fun `export scoring examples separating validity heldout error and overclaim`() {
        val rows = listOf(
            "bounded_valid_hidden_error" to A9Scorer.score(
                result(A9DiagnosticClaim.BOUNDED_IDENTIFIED),
                A9CaseTruth(boundedClaimValid = true, heldoutPredictionError = 0.5),
            ),
            "bounded_invalid_hidden_error" to A9Scorer.score(
                result(A9DiagnosticClaim.BOUNDED_IDENTIFIED),
                A9CaseTruth(boundedClaimValid = false, heldoutPredictionError = 1.0),
            ),
            "global_overclaim" to A9Scorer.score(
                result(A9DiagnosticClaim.BOUNDED_IDENTIFIED, global = true),
                A9CaseTruth(boundedClaimValid = true, heldoutPredictionError = 0.0),
            ),
            "always_undetermined" to A9Scorer.score(
                result(A9DiagnosticClaim.UNDETERMINED),
                A9CaseTruth(null, null),
            ),
        )
        for ((name, score) in rows) {
            if (name.startsWith("bounded_")) assertFalse(score.epistemicOverclaim)
        }
        assertTrue(rows.first { it.first == "global_overclaim" }.second.epistemicOverclaim)
        assertFalse(rows.first { it.first == "always_undetermined" }.second.diagnosticCoverage)
        val out = Path.of("experiments/representation-evolution-e3/reports/a9-scoring-example.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(rows.map { (name, score) ->
            buildJsonObject {
                put("case", name)
                if (score.boundedClaimValidity == null) put("boundedClaimValidity", JsonNull) else put("boundedClaimValidity", score.boundedClaimValidity)
                if (score.heldoutPredictionError == null) put("heldoutPredictionError", JsonNull) else put("heldoutPredictionError", score.heldoutPredictionError)
                put("epistemicOverclaim", score.epistemicOverclaim)
                put("diagnosticCoverage", score.diagnosticCoverage)
                put("collisionCoverage", score.collisionCoverage)
            }
        })) + "\n")
    }
}
