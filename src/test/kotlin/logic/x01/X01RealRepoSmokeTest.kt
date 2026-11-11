// TEMPLATE FOR THE REAL REPOSITORY, NOT YET RUN AGAINST THE REAL B0 SQLITE STACK.
// After vendoring/importing the M1 source into this same Kotlin Gradle module,
// place this in src/test/kotlin/logic/x01/X01RealRepoSmokeTest.kt.
// Requires actual logic.capability internals (not scaffold-only/B0ContractStubs.kt).
package logic.x01

import logic.capability.*
import observable.ai.abdmnv.m1.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class X01RealRepoSmokeTest {
    @Test
    fun realStudentConceptIsVerifiedActivatedExecutedAndRevoked() {
        val ledger = CombinedEvidenceLedger()
        val world = "x01_smoke_world"
        val colors = listOf("red", "blue", "green", "yellow")
        val evidence = colors.mapIndexed { index, color ->
            Evidence("x01_ev_$index", world, "obj_$index", 1, 1,
                "x01_public", "PUBLIC_QUERY", "INDEPENDENT",
                mapOf("color" to ValueResult.Known(TypedValue.EnumSymbol(color, "palette"))))
        }
        evidence.forEach { assertEquals("OK", ledger.record(it)) }
        val view = StudentView(world, 1, evidence.map { it.objectId },
            mapOf("color" to Sort.Enum("palette")), emptySet(), evidence.associateBy { it.objectId })
        val learned = MinimalCategoryStudent().learn(view, colors.indices.map { i ->
            TrainingLabel("obj_$i", colors[i] == "red")
        })
        assertEquals("STUDENT_COMPOSED_LIMITED", learned.status)
        val concept = learned.concept ?: error("student didn't induce concept")
        val m1Store = CombinedConceptVersionStore()
        assertEquals(concept.ref.hash, m1Store.propose(concept))
        assertEquals("OK", m1Store.activate(concept.ref))
        val bridge = X01ConceptBridge(m1Store, ledger)
        val candidate = bridge.compile(concept.ref, view)
        assertTrue(candidate.provenanceJson.contains("UNVERIFIED_BY_B0"))

        // The verifier's expected values are provided by the HOST test fixture,
        // independently of the student's concept's AST and predictions.
        val suite = TrustedVerificationSuite.create("x01.red.fixture", 1,
            colors.mapIndexed { index, color ->
                val b0Args = bridge.materialize(candidate, view, "obj_$index")
                val record = ((b0Args.getValue("item") as EvalResult.Known).value as CapValue.Rec)
                VerificationCase("x01_case_$index", mapOf("item" to record),
                    EvalResult.Known(CapValue.Bool(color == "red")))
            })
        // Same-host developer smoke: do NOT mark as independent examiner.
        val suites = TrustedSuiteRegistry().also { it.register(suite, VerificationSource.IMPLEMENTER_RUN) }
        val db = Files.createTempDirectory("x01-real-b0").resolve("capabilities.db")
        SqliteCapabilityRegistry(db).use { registry ->
            val proposed = registry.propose(candidate.definition, candidate.provenanceJson)
            assertTrue(proposed.ok, "propose refused: ${proposed.code}")
            // G6 证据：provenance 必须真的落库，且能读到 M1 源 hash 与证据 ID，而不是只存在于内存对象里。
            val storedProvenance = java.sql.DriverManager
                .getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
                    connection.prepareStatement(
                        "SELECT provenance_json FROM capabilities WHERE capability_id = ? AND version = ?"
                    ).use { statement ->
                        statement.setString(1, candidate.definition.id)
                        statement.setInt(2, 1)
                        statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                    }
                }
            assertTrue(storedProvenance != null, "provenance not persisted")
            assertTrue(storedProvenance!!.contains(concept.ref.hash), "source hash missing from stored provenance")
            assertTrue(storedProvenance.contains("x01_ev_0"), "evidence id missing from stored provenance")
            assertTrue(storedProvenance.contains("UNVERIFIED_BY_B0"), "trust marker missing from stored provenance")
            val pre = CapabilityExecutionService(registry).executeActive(
                candidate.definition.id, 1, bridge.materialize(candidate, view, "obj_0"))
            assertFalse(pre.ok)
            assertEquals("not_active", pre.code)
            val verify = CapabilityVerificationService(registry, suites).verify(candidate.definition.id, 1,
                suite.suiteId, suite.suiteVersion)
            assertTrue(verify.recorded, "verify not recorded: ${verify.code}")
            assertEquals(VerificationVerdict.PASS, verify.outcome.verdict)
            // 证据不得升级：开发烟测只能以 IMPLEMENTER_RUN 登记，绝不能冒充 HOST_FIXTURE/独立考官。
            assertEquals(VerificationSource.IMPLEMENTER_RUN, verify.outcome.source)
            val activation = CapabilityActivationService(registry, suites).activate(
                candidate.definition.id, 1, verify.runId!!)
            assertTrue(activation.ok, "activation refused: ${activation.code}")
            val hit = CapabilityExecutionService(registry).executeActive(candidate.definition.id, 1,
                bridge.materialize(candidate, view, "obj_0"))
            assertTrue(hit.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), hit.result)
            val miss = CapabilityExecutionService(registry).executeActive(candidate.definition.id, 1,
                bridge.materialize(candidate, view, "obj_1"))
            assertTrue(miss.ok)
            assertEquals(EvalResult.Known(CapValue.Bool(false)), miss.result)
            val revoked = CapabilityRevocationService(registry).revoke(candidate.definition.id, 1,
                "SOURCE_REVOKED", concept.evidenceIds.first())
            assertTrue(revoked.ok)
            val blocked = CapabilityExecutionService(registry).executeActive(candidate.definition.id, 1,
                bridge.materialize(candidate, view, "obj_0"))
            assertFalse(blocked.ok)
            assertEquals("revoked", blocked.code)
        }
    }

    @Test
    fun cannotActivateCandidateJustBecauseM1EvidenceClaimsSignature() {
        val ledger = CombinedEvidenceLedger()
        val world = "x01_spoof_world"
        val evidence = listOf("red", "blue").mapIndexed { i, color ->
            Evidence("spoof_$i", world, "o_$i", 1, 1, "attacker", "EXAMINER_VERIFIED", "INDEPENDENT",
                mapOf("color" to ValueResult.Known(TypedValue.EnumSymbol(color, "palette"))), signedByExaminer = true)
        }
        evidence.forEach { assertEquals("OK", ledger.record(it)) }
        val view = StudentView(world, 1, evidence.map { it.objectId },
            mapOf("color" to Sort.Enum("palette")), emptySet(), evidence.associateBy { it.objectId })
        val draft = Concept(ConceptRef("spoof_concept", 1, ""), ConceptKind.CATEGORY, Sort.Bool,
            listOf(Param("x", Sort.Object)),
            Term.Eq(Term.Attribute("color", Term.Var("x")), Term.Constant(TypedValue.EnumSymbol("red", "palette"))),
            worldScope = world, evidenceIds = evidence.map { it.id }.toSet())
        val concept = CombinedConceptHashing.seal(draft)
        val store = CombinedConceptVersionStore()
        store.propose(concept); assertEquals("OK", store.activate(concept.ref))
        val candidate = X01ConceptBridge(store, ledger).compile(concept.ref, view)
        assertTrue(candidate.provenanceJson.contains("signed_by_examiner"))
        val db = Files.createTempDirectory("x01-spoof-b0").resolve("capabilities.db")
        SqliteCapabilityRegistry(db).use { registry ->
            assertTrue(registry.propose(candidate.definition, candidate.provenanceJson).ok)
            // No TrustedSuiteRegistry run exists. A string flag in M1 cannot create it.
            val activation = CapabilityActivationService(registry, TrustedSuiteRegistry()).activate(
                candidate.definition.id, 1, "forged_run")
            assertFalse(activation.ok)
            assertEquals("verification_not_found", activation.code)
        }
    }
}


