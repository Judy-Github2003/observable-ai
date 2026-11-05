// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-M1.1a 挑战入口测试；只验证入口状态，不执行预测/查询。
package logic.e3

import logic.capability.CapValue
import logic.capability.Expr
import logic.e3.evaluator.E3WorldGenerator
import logic.e3.evaluator.WorldFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E3ChallengeSessionTest {

    private fun boundedResult(): A9BaselineResult {
        val world = E3WorldGenerator(101L).generate(WorldFamily.SOLVABLE, 0)
        return E3A9BaselineRunner().run(
            A9BaselineId.B0_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
        )
    }

    @Test
    fun `solvable bounded result enters pending challenge state`() {
        val result = boundedResult()
        assertEquals(A9DiagnosticClaim.BOUNDED_IDENTIFIED, result.diagnosticClaim)
        val entry = E3ChallengeSession.open(result)
        assertTrue(entry.accepted)
        val pending = entry.pending!!
        assertEquals(result.searchRecord, pending.snapshot)
        assertEquals(result.candidateBodies.single(), pending.candidate.body)
        assertEquals(result.candidateCanonicals.single(), pending.candidate.canonical)
        assertEquals(ChallengeTrustStatus.FIELD_CONSISTENT_ONLY, pending.trustStatus)
        assertTrue(pending.notes.contains("entry_only_no_prediction_no_oracle_call"))
    }

    @Test
    fun `undetermined result is rejected`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val result = E3A9BaselineRunner().run(
            A9BaselineId.B0_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
        )
        val entry = E3ChallengeSession.open(result)
        assertFalse(entry.accepted)
        assertEquals("not_bounded_identified", entry.rejectionCode)
    }

    @Test
    fun `collision result is rejected`() {
        val world = E3WorldGenerator(103L).generate(WorldFamily.LATENT_ALIASING, 0)
        val result = E3A9BaselineRunner().run(
            A9BaselineId.COLLISION_ONLY, world.public.schema, world.public.observations,
            PublicConstants(world.public.schema.fields.values.flatten().distinct(), "schema_domains"),
            world.public.queryPool.map { it.visible },
        )
        val entry = E3ChallengeSession.open(result)
        assertFalse(entry.accepted)
        assertEquals("not_bounded_identified", entry.rejectionCode)
    }

    @Test
    fun `missing candidate or snapshot is rejected`() {
        val result = boundedResult()
        val noCandidate = E3ChallengeSession.open(
            result.copy(candidateBodies = emptyList(), candidateCanonicals = emptyList()),
        )
        assertFalse(noCandidate.accepted)
        assertEquals("multiple_selected_candidates", noCandidate.rejectionCode)

        val noSnapshot = E3ChallengeSession.open(result.copy(searchRecord = null))
        assertFalse(noSnapshot.accepted)
        assertEquals("missing_search_record", noSnapshot.rejectionCode)
    }

    @Test
    fun `forged or inconsistent candidate canonical is rejected`() {
        val result = boundedResult()
        val badHash = E3ChallengeSession.open(result.copy(candidateCanonicals = listOf("forged")))
        assertFalse(badHash.accepted)
        assertEquals("candidate_canonical_mismatch", badHash.rejectionCode)

        val foreignBody = Expr.Const(CapValue.Bool(false))
        val foreign = E3ChallengeSession.open(
            result.copy(
                candidateBodies = listOf(foreignBody),
                candidateCanonicals = listOf(logic.capability.CanonicalJson.astHash(foreignBody)),
            ),
        )
        assertFalse(foreign.accepted)
        assertEquals("candidate_not_in_snapshot", foreign.rejectionCode)
    }

    @Test
    fun `scope evidence and budget inconsistencies are rejected`() {
        val result = boundedResult()
        val snapshot = result.searchRecord!!
        val badScope = E3ChallengeSession.open(
            result.copy(boundedScope = EvidenceScope("other", "other")),
        )
        assertFalse(badScope.accepted)
        assertEquals("scope_mismatch", badScope.rejectionCode)

        val badEvidence = E3ChallengeSession.open(result.copy(evidenceIds = listOf("not-in-snapshot")))
        assertFalse(badEvidence.accepted)
        assertEquals("evidence_not_in_snapshot", badEvidence.rejectionCode)

        val badBudget = E3ChallengeSession.open(
            result.copy(searchRecord = snapshot.copy(budget = DiagnosticBudget(0, 0, 0, 0))),
        )
        assertFalse(badBudget.accepted)
        assertEquals("invalid_budget", badBudget.rejectionCode)
    }

    @Test
    fun `query domain mismatch is rejected`() {
        val result = boundedResult()
        val tamperedOuter = result.copy(queryDomain = result.queryDomain + mapOf("x" to "other"))
        assertEquals("query_domain_mismatch", E3ChallengeSession.open(tamperedOuter).rejectionCode)

        val tamperedSnapshot = result.copy(
            searchRecord = result.searchRecord!!.copy(queryDomain = result.searchRecord.queryDomain + mapOf("x" to "other")),
        )
        assertEquals("query_domain_mismatch", E3ChallengeSession.open(tamperedSnapshot).rejectionCode)
    }

    @Test
    fun `tampered snapshot candidate AST or inconsistent candidate set is rejected`() {
        val result = boundedResult()
        val snapshot = result.searchRecord!!
        val first = snapshot.consistentCandidates.first()
        val tamperedAst = snapshot.copy(
            consistentCandidates = listOf(first.copy(body = Expr.Const(CapValue.Bool(!(first.body is Expr.Const)))) ) +
                snapshot.consistentCandidates.drop(1),
        )
        assertEquals("snapshot_candidate_hash_mismatch", E3ChallengeSession.open(result.copy(searchRecord = tamperedAst)).rejectionCode)

        val foreignBody = Expr.Const(CapValue.Bool(false))
        val foreign = A9FrozenCandidate(foreignBody, logic.capability.CanonicalJson.astHash(foreignBody))
        val inconsistent = snapshot.copy(consistentCandidates = snapshot.consistentCandidates + foreign)
        assertEquals("snapshot_candidate_not_in_composed", E3ChallengeSession.open(result.copy(searchRecord = inconsistent)).rejectionCode)

        val selected = result.candidateCanonicals.single()
        val missingComposed = snapshot.copy(composedCandidateHashes = snapshot.composedCandidateHashes - selected)
        assertEquals("snapshot_candidate_not_in_composed", E3ChallengeSession.open(result.copy(searchRecord = missingComposed)).rejectionCode)
    }

    @Test
    fun `pending state does not query labels or modify knowledge`() {
        val result = boundedResult()
        val entry = E3ChallengeSession.open(result)
        val pending = entry.pending!!
        assertEquals(result.evidenceIds, pending.evidenceIds)
        assertEquals(result.searchRecord!!.publicEvidenceIds, pending.snapshot.publicEvidenceIds)
        assertEquals(result.searchRecord, pending.snapshot)
        assertEquals(result.candidateBodies, listOf(pending.candidate.body))
        assertNull(entry.rejectionCode)
        assertFalse(pending.notes.any { it.contains("label") && it.contains("received") })
    }
}
