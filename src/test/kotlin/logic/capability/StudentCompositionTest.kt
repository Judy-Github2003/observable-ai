// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M3-1c 验收测试 C01–C07；两个新世界、留出集、归因、退化与未验证拒绝。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudentCompositionTest {

    private val itemType = CapType.Record(mapOf("shape" to CapType.Str, "color" to CapType.Str))
    private val deviceType = CapType.Record(mapOf("power" to CapType.Str, "port" to CapType.Str))
    private val accessType = CapType.Record(mapOf("role" to CapType.Str, "level" to CapType.Int64))

    private fun db(): Path = Files.createTempDirectory("b0-composition").resolve("capabilities.db")

    private fun item(color: String, shape: String) =
        CapValue.Rec(mapOf("color" to CapValue.Str(color), "shape" to CapValue.Str(shape)))

    private fun device(power: String, port: String) =
        CapValue.Rec(mapOf("power" to CapValue.Str(power), "port" to CapValue.Str(port)))

    private fun access(role: String, level: Long) =
        CapValue.Rec(mapOf("role" to CapValue.Str(role), "level" to CapValue.I64(level)))

    private fun example(type: CapType, value: CapValue, expected: Boolean): PublicExample =
        PublicExample(mapOf("item" to value), CapValue.Bool(expected))

    private fun deviceCases(): List<PublicExample> = listOf(
        example(deviceType, device("usb", "c"), true),
        example(deviceType, device("usb", "a"), false),
        example(deviceType, device("dc", "c"), false),
        example(deviceType, device("dc", "a"), false),
    )

    private fun deviceDomain(): Map<String, List<CapValue>> = mapOf(
        "item" to listOf(
            device("usb", "c"), device("usb", "a"), device("dc", "c"), device("dc", "a"),
        ),
    )

    private fun deviceSuiteCases(): List<PublicExample> = listOf(
        example(deviceType, device("usb", "c"), true),
        example(deviceType, device("usb", "a"), false),
        example(deviceType, device("dc", "c"), false),
        example(deviceType, device("dc", "a"), false),
    )

    private fun accessCases(): List<PublicExample> = listOf(
        example(accessType, access("admin", 3), true),
        example(accessType, access("admin", 1), false),
        example(accessType, access("guest", 3), false),
        example(accessType, access("guest", 1), false),
    )

    private fun accessDomain(): Map<String, List<CapValue>> = mapOf(
        "item" to listOf(access("admin", 3), access("admin", 1), access("guest", 3), access("guest", 1)),
    )

    private fun accessSuiteCases(): List<PublicExample> = listOf(
        example(accessType, access("admin", 3), true),
        example(accessType, access("admin", 1), false),
        example(accessType, access("guest", 3), false),
        example(accessType, access("guest", 1), false),
    )

    private fun registerSuite(suites: TrustedSuiteRegistry, suiteId: String, examples: List<PublicExample>) {
        val cases = examples.mapIndexed { index, example ->
            VerificationCase("c$index", example.inputs, EvalResult.Known(example.expected))
        }
        suites.register(TrustedVerificationSuite.create(suiteId, 1, cases))
    }

    private fun provenance(path: Path, capabilityId: String): String =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.prepareStatement("SELECT provenance_json FROM capabilities WHERE capability_id=?").use { statement ->
                statement.setString(1, capabilityId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "" }
            }
        }

    private fun execute(registry: SqliteCapabilityRegistry, capabilityId: String, value: CapValue): EvalResult =
        CapabilityExecutionService(registry).executeActive(
            capabilityId, 1, mapOf("item" to EvalResult.Known(value)),
        ).result ?: EvalResult.Error("not_executed")

    @Test
    fun `C01 device world composes verifies activates and passes held out`() {
        val path = db()
        val suites = TrustedSuiteRegistry()
        registerSuite(suites, "suite.device", deviceSuiteCases())
        val constants = listOf(CapValue.Str("usb"), CapValue.Str("dc"), CapValue.Str("c"), CapValue.Str("a"))
        SqliteCapabilityRegistry(path).use { registry ->
            val service = StudentCompositionService(registry, suites)
            val proposed = service.composeAndPropose(
                "m3.device", 1, mapOf("item" to deviceType), CapType.Bool3,
                constants, deviceCases(), deviceDomain(),
                ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000),
            )
            assertEquals(
                CompositionStage.PROPOSED, proposed.stage,
                "code=${proposed.code} search=${proposed.searchCandidates} evals=${proposed.evidence?.evaluations?.size} " +
                    "maxMatches=${proposed.evidence?.evaluations?.maxOfOrNull { it.matches }} " +
                    "top=${proposed.evidence?.evaluations?.maxByOrNull { it.matches }?.candidate?.canonical}",
            )
            assertTrue(proposed.searchCandidates > 0)
            val expected = CanonicalJson.expr(
                Expr.And(
                    Expr.Eq(Expr.Field(Expr.Arg("item", deviceType), "port"), Expr.Const(CapValue.Str("c"))),
                    Expr.Eq(Expr.Field(Expr.Arg("item", deviceType), "power"), Expr.Const(CapValue.Str("usb"))),
                ),
            )
            assertEquals(expected, proposed.candidate!!.canonical)

            val activated = service.verifyAndActivate("m3.device", 1, "suite.device", 1)
            assertEquals(CompositionStage.ACTIVATED, activated.stage, "code=${activated.code}")
            assertEquals(EvalResult.Known(CapValue.Bool(true)), execute(registry, "m3.device", device("usb", "c")))
            assertEquals(EvalResult.Known(CapValue.Bool(false)), execute(registry, "m3.device", device("usb", "a")))

            // 留出集只在激活后评估，未参与组合与筛选。
            val heldOut = HeldOutEvaluator(registry).evaluate(
                "m3.device", 1,
                listOf(
                    example(deviceType, device("usb", "b"), false),
                    example(deviceType, device("solar", "c"), false),
                ),
            )
            assertEquals(2, heldOut.matches)
            assertEquals(0, heldOut.mismatches + heldOut.unknowns + heldOut.errors)
        }
        assertTrue(provenance(path, "m3.device").contains("\"acquisition_method\":\"student_composed\""))
    }

    @Test
    fun `C02 access world reuses the same primitives for a different rule`() {
        val path = db()
        val suites = TrustedSuiteRegistry()
        registerSuite(suites, "suite.access", accessSuiteCases())
        val constants = listOf(CapValue.Str("admin"), CapValue.Str("guest"), CapValue.I64(3), CapValue.I64(1))
        SqliteCapabilityRegistry(path).use { registry ->
            val service = StudentCompositionService(registry, suites)
            val proposed = service.composeAndPropose(
                "m3.access", 1, mapOf("item" to accessType), CapType.Bool3,
                constants, accessCases(), accessDomain(),
                ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000),
            )
            assertEquals(CompositionStage.PROPOSED, proposed.stage, "code=${proposed.code}")
            val expected = CanonicalJson.expr(
                Expr.And(
                    Expr.Eq(Expr.Field(Expr.Arg("item", accessType), "level"), Expr.Const(CapValue.I64(3))),
                    Expr.Eq(Expr.Field(Expr.Arg("item", accessType), "role"), Expr.Const(CapValue.Str("admin"))),
                ),
            )
            assertEquals(expected, proposed.candidate!!.canonical)
            assertEquals(CompositionStage.ACTIVATED, service.verifyAndActivate("m3.access", 1, "suite.access", 1).stage)
            assertEquals(EvalResult.Known(CapValue.Bool(true)), execute(registry, "m3.access", access("admin", 3)))
            assertEquals(EvalResult.Known(CapValue.Bool(false)), execute(registry, "m3.access", access("guest", 3)))
        }
    }

    @Test
    fun `C03 removing a key library capability degrades the search observably`() {
        val path = db()
        val suites = TrustedSuiteRegistry()
        val redCases = listOf(
            example(itemType, item("red", "circle"), true),
            example(itemType, item("red", "square"), true),
            example(itemType, item("blue", "circle"), false),
            example(itemType, item("green", "square"), false),
        )
        registerSuite(suites, "suite.red", redCases)
        val helper = CapabilityDefinition(
            "core.is_red", 1, mapOf("item" to itemType), CapType.Bool3,
            Expr.Eq(Expr.Field(Expr.Arg("item", itemType), "color"), Expr.Const(CapValue.Str("red"))),
        )
        val targetCases = listOf(
            example(itemType, item("red", "circle"), true),
            example(itemType, item("blue", "circle"), false),
            example(itemType, item("red", "square"), false),
            example(itemType, item("blue", "square"), false),
        )
        val targetDomain = mapOf(
            "item" to listOf(item("red", "circle"), item("red", "square"), item("blue", "circle"), item("blue", "square")),
        )
        val constants = listOf(CapValue.Str("circle"), CapValue.Str("square"))
        SqliteCapabilityRegistry(path).use { registry ->
            assertTrue(registry.propose(helper).ok)
            val helperRun = CapabilityVerificationService(registry, suites).verify("core.is_red", 1, "suite.red", 1)
            assertTrue(helperRun.recorded)
            assertTrue(CapabilityActivationService(registry, suites).activate("core.is_red", 1, helperRun.runId!!).ok)

            val service = StudentCompositionService(registry, suites)
            val withLibrary = service.composeAndPropose(
                "m3.helper.target", 1, mapOf("item" to itemType), CapType.Bool3,
                constants, targetCases, targetDomain,
                ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000),
            )
            assertEquals(
                CompositionStage.PROPOSED, withLibrary.stage,
                "code=${withLibrary.code} search=${withLibrary.searchCandidates} " +
                    "evals=${withLibrary.evidence?.evaluations?.size} " +
                    "summary=${withLibrary.evidence?.evaluations?.joinToString(";") { "${it.matches}/${it.unknowns}/${it.errors}" }} " +
                    "near=${withLibrary.evidence?.evaluations?.filter { it.matches == 3 }?.map { it.candidate.canonical }}",
            )
            assertEquals(1, withLibrary.candidate!!.dependencies.size)
            assertEquals("core.is_red", withLibrary.candidate!!.dependencies.single().id)

            assertTrue(CapabilityRevocationService(registry).revoke("core.is_red", 1, "incorrect_behavior", "evidence:1").ok)
            val withoutLibrary = service.composeAndPropose(
                "m3.helper.target2", 1, mapOf("item" to itemType), CapType.Bool3,
                constants, targetCases, targetDomain,
                ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000),
            )
            assertEquals(CompositionStage.REFUSED, withoutLibrary.stage)
            assertEquals("no_consistent_candidate", withoutLibrary.code)
        }
    }

    @Test
    fun `C04 unverified candidate cannot be activated`() {
        val path = db()
        val suites = TrustedSuiteRegistry()
        val constants = listOf(CapValue.Str("usb"), CapValue.Str("dc"), CapValue.Str("c"), CapValue.Str("a"))
        SqliteCapabilityRegistry(path).use { registry ->
            val service = StudentCompositionService(registry, suites)
            assertTrue(
                service.composeAndPropose(
                    "m3.unverified", 1, mapOf("item" to deviceType), CapType.Bool3,
                    constants, deviceCases(), deviceDomain(),
                    ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000),
                ).stage == CompositionStage.PROPOSED,
            )
            val activation = CapabilityActivationService(registry, suites).activate("m3.unverified", 1, "run.not.exists")
            assertFalse(activation.ok)
            assertEquals("verification_not_found", activation.code)
            val execution = CapabilityExecutionService(registry).executeActive(
                "m3.unverified", 1, mapOf("item" to EvalResult.Known(device("usb", "c"))),
            )
            assertFalse(execution.ok)
            assertEquals("not_active", execution.code)
        }
    }

    @Test
    fun `C05 insufficient evidence stays ambiguous and registers nothing`() {
        val path = db()
        val suites = TrustedSuiteRegistry()
        val constants = listOf(CapValue.Str("usb"), CapValue.Str("dc"), CapValue.Str("c"), CapValue.Str("a"))
        SqliteCapabilityRegistry(path).use { registry ->
            val service = StudentCompositionService(registry, suites)
            val result = service.composeAndPropose(
                "m3.ambiguous", 1, mapOf("item" to deviceType), CapType.Bool3,
                constants, listOf(example(deviceType, device("usb", "c"), true)), deviceDomain(),
                ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000),
            )
            assertEquals(CompositionStage.AMBIGUOUS, result.stage)
            assertTrue(result.evidence!!.distinguishingQueries.isNotEmpty())
            assertEquals("not_found", registry.inspect("m3.ambiguous", 1).code)
        }
    }

    @Test
    fun `C06 out of hypothesis space is refused and nothing is registered`() {
        val path = db()
        val suites = TrustedSuiteRegistry()
        val threeFields = CapType.Record(mapOf("color" to CapType.Str, "shape" to CapType.Str, "size" to CapType.Str))
        fun record(color: String, shape: String, size: String) = CapValue.Rec(
            mapOf("color" to CapValue.Str(color), "shape" to CapValue.Str(shape), "size" to CapValue.Str(size)),
        )
        val examples = ArrayList<PublicExample>()
        for (color in listOf("red", "blue")) for (shape in listOf("circle", "square")) for (size in listOf("small", "large")) {
            val votes = listOf(color == "red", shape == "circle", size == "small").count { it }
            examples += example(threeFields, record(color, shape, size), votes >= 2)
        }
        SqliteCapabilityRegistry(path).use { registry ->
            val result = StudentCompositionService(registry, suites).composeAndPropose(
                "m3.majority", 1, mapOf("item" to threeFields), CapType.Bool3,
                listOf(CapValue.Str("red"), CapValue.Str("circle"), CapValue.Str("small")),
                examples,
                composeBudget = ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 5000),
            )
            assertEquals(CompositionStage.REFUSED, result.stage)
            assertEquals("no_consistent_candidate", result.code)
            assertEquals("not_found", registry.inspect("m3.majority", 1).code)
        }
    }

    @Test
    fun `C07 provenance records search size and zero teacher calls`() {
        val path = db()
        val suites = TrustedSuiteRegistry()
        val constants = listOf(CapValue.Str("usb"), CapValue.Str("dc"), CapValue.Str("c"), CapValue.Str("a"))
        SqliteCapabilityRegistry(path).use { registry ->
            val result = StudentCompositionService(registry, suites).composeAndPropose(
                "m3.provenance", 1, mapOf("item" to deviceType), CapType.Bool3,
                constants, deviceCases(), deviceDomain(),
                ComposeBudget(maxDepth = 3, maxNodes = 32, maxCandidates = 1000),
            )
            assertEquals(CompositionStage.PROPOSED, result.stage)
        }
        val json = provenance(path, "m3.provenance")
        assertTrue(json.contains("\"student_composed\""))
        assertTrue(json.contains("\"teacher_calls\":0"))
        assertTrue(json.contains("\"search_candidates\":"))
    }
}
