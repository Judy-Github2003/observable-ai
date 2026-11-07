// SD-03 Root Cause: bounded, examiner-side diagnostic experiment.
// Prepared for repository integration; not yet compiled in the project's Gradle environment.
// This file is a TEST, not part of the student. The oracle truth never enters MRX2BSpace.
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SD03RootCauseMatrixTest {
    private data class World(
        val id: String,
        val lhs: String,
        val rhs: String,
        val target: (Map<String, String>) -> Boolean,
    )

    private data class Arm(
        val name: String,
        val strategy: MRX2BStrategy,
        val atomLimit: Int,
    )

    private data class AtomProbe(
        val expr: Expr,
        val family: String,
        val canonical: String,
        val predictions: List<Boolean?>,
    )

    private fun eq(name: String, value: String = "1"): Expr =
        Expr.Eq(Expr.Arg(name, CapType.Str), Expr.Const(CapValue.Str(value)))

    private fun gold(world: World): Expr = Expr.And(eq(world.lhs), eq(world.rhs))

    private fun probeAtoms(
        definitions: List<CapabilityDefinition>,
        training: List<StudyCase>,
    ): List<AtomProbe> {
        val atoms = ArrayList<Pair<Expr, String>>()
        for (name in C8Domain.attributes) for (value in C8Domain.values) {
            atoms += eq(name, value) to "eq:$name"
        }
        for (definition in definitions) {
            if (definition.inputs.keys.all { it in C8Domain.attributes }) {
                atoms += Expr.Call(
                    definition.id, definition.version, definition.contentHash(),
                    definition.inputs.keys.associateWith { Expr.Arg(it, CapType.Str) },
                ) to "call:${definition.id}"
            }
        }
        return atoms.map { (expr, family) ->
            AtomProbe(
                expr, family, CanonicalJson.expr(expr),
                training.map { Pack02Knowledge.predict(expr, it.values, definitions) },
            )
        }
    }

    // Matches MRX2BSpace's inverted-label accuracy, including its treatment of Unknown.
    private fun accuracy(predictions: List<Boolean?>, labels: List<Boolean>): Double {
        val known = predictions.mapIndexedNotNull { i, p -> p?.let { i to it } }
        if (known.isEmpty()) return 0.0
        val correct = known.count { (index, prediction) -> prediction == labels[index] }
        return maxOf(correct, known.size - correct).toDouble() / known.size
    }

    private fun rankAtoms(
        atoms: List<AtomProbe>,
        labels: List<Boolean>,
        strategy: MRX2BStrategy,
        pairGains: List<MRX2BPairGain>,
    ): Pair<List<AtomProbe>, Map<String, Double>> {
        val gains = HashMap<String, Double>()
        for (pair in pairGains) {
            gains[pair.left] = maxOf(gains[pair.left] ?: 0.0, pair.gainOverSingle)
            gains[pair.right] = maxOf(gains[pair.right] ?: 0.0, pair.gainOverSingle)
        }
        val byAccuracy = compareByDescending<AtomProbe> { accuracy(it.predictions, labels) }
            .thenBy { it.canonical }
        val ordered = when (strategy) {
            MRX2BStrategy.BASELINE -> atoms.sortedWith(byAccuracy)
            MRX2BStrategy.PAIRWISE -> atoms.sortedWith(
                compareByDescending<AtomProbe> {
                    (gains[it.canonical] ?: 0.0) + accuracy(it.predictions, labels)
                }.thenBy { it.canonical },
            )
            MRX2BStrategy.DIVERSITY -> {
                val groups = atoms.groupBy { it.family }.mapValues { (_, group) ->
                    group.sortedWith(byAccuracy)
                }
                val ranked = ArrayList<AtomProbe>()
                var round = 0
                while (ranked.size < atoms.size && groups.values.any { round < it.size }) {
                    for ((_, group) in groups.toSortedMap()) {
                        if (round < group.size) ranked += group[round]
                    }
                    round++
                }
                ranked
            }
        }
        return ordered to gains
    }

    private fun jLine(type: String, block: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject { put("type", type); block() }.toString()

    @Test
    fun `frozen worlds isolate atom truncation candidate coverage and oracle`() {
        val seed = 1449
        val inputs = C8Domain.all().shuffled(Random(seed))
        val trainInputs = inputs.take(40)
        val challenge = inputs.drop(40).take(20)
        val heldout = inputs.drop(60)
        assertEquals(81, inputs.size)
        assertEquals(21, heldout.size)
        assertEquals(81, inputs.map { C8Domain.key(it) }.toSet().size)

        val worlds = listOf(
            World("A", "a", "d") { m -> m["a"] == "1" && m["d"] == "1" },
            World("B", "b", "c") { m -> m["b"] == "1" && m["c"] == "1" },
        )
        val arms = listOf(
            Arm("PAIRWISE_10", MRX2BStrategy.PAIRWISE, 10),
            Arm("PAIRWISE_12", MRX2BStrategy.PAIRWISE, 12),
            Arm("DIVERSITY_10", MRX2BStrategy.DIVERSITY, 10),
            Arm("BASELINE_10", MRX2BStrategy.BASELINE, 10),
        )
        val library = C8TestBases.bases.library("FULL").definitions
        val output = ArrayList<String>()
        output += jLine("environment") {
            put("seed", seed)
            put("train", trainInputs.size)
            put("challenge", challenge.size)
            put("heldout", heldout.size)
            put("maxCandidates", 30_000)
            put("consistentLimit", 2_000)
            put("library", "FULL")
        }

        for (world in worlds) {
            val training = trainInputs.mapIndexed { i, input ->
                StudyCase("sd03-cause-${world.id}-$i", input, world.target(input))
            }
            val labels = training.map { it.label }
            output += jLine("world_split") {
                put("world", world.id)
                put("trainTrue", labels.count { it })
                put("trainFalse", labels.count { !it })
                put("challengeTrueExaminerOnly", challenge.count(world.target))
                put("heldoutTrueExaminerOnly", heldout.count(world.target))
            }
            assertTrue(labels.any { it } && labels.any { !it })
            assertTrue(challenge.any(world.target) && challenge.any { !world.target(it) })
            assertTrue(heldout.any(world.target) && heldout.any { !world.target(it) })

            // H3: fixed TRAIN evidence that distinguishes candidate theories;
            // logged for examiner review, never passed as hints to the student.
            for (i in training.indices) for (j in i + 1 until training.size) {
                val x = training[i]
                val y = training[j]
                val sameOtherCoordinates = C8Domain.attributes
                    .filter { it != world.rhs }
                    .all { x.values[it] == y.values[it] }
                if (sameOtherCoordinates && x.values[world.rhs] != y.values[world.rhs]
                    && x.label != y.label) {
                    output += jLine("distinguishing_train_pair") {
                        put("world", world.id)
                        put("leftKey", C8Domain.key(x.values))
                        put("rightKey", C8Domain.key(y.values))
                        put("leftLabel", x.label)
                        put("rightLabel", y.label)
                        put("differingAttribute", world.rhs)
                    }
                }
            }

            // Examiner-only gold: constructed AFTER the student's generation interface;
            // it is never admitted to the student, the atom ranking, or source selection.
            val gold = gold(world)
            val goldCanonical = CanonicalJson.expr(gold)
            val reverseCanonical = CanonicalJson.expr(Expr.And(eq(world.rhs), eq(world.lhs)))
            val goldDef = CapabilityDefinition(
                "__sd03_gold_${world.id}", 1,
                C8Domain.attributes.associateWith { CapType.Str }, CapType.Bool3, gold,
            )
            val goldTypecheck = CapabilityTypeChecker(InMemoryResolver(library)).check(goldDef).ok
            val goldTrainCorrect = training.count {
                Pack02Knowledge.predict(gold, it.values, library) == it.label
            }
            val goldChallengeCorrect = challenge.count {
                Pack02Knowledge.predict(gold, it, library) == world.target(it)
            }
            val goldHeldoutCorrect = heldout.count {
                Pack02Knowledge.predict(gold, it, library) == world.target(it)
            }
            val goldMeter = MRX2Cost()
            val goldLedger = MRX2Ledger(
                challenge, StudyQueryController(challenge, challenge.size, world.target),
                challenge.size, goldMeter,
            )
            val goldStatus = goldLedger.validate(
                goldCanonical, { input -> Pack02Knowledge.predict(gold, input, library) },
                "EXAMINER_GOLD_${world.id}", challenge.size, goldMeter,
            )
            output += jLine("examiner_gold") {
                put("world", world.id)
                put("typecheck", goldTypecheck)
                put("trainCorrect", goldTrainCorrect)
                put("challengeCorrect", goldChallengeCorrect)
                put("heldoutCorrect", goldHeldoutCorrect)
                put("oracleStatus", goldStatus.status.name)
                put("oracleNew", goldLedger.newQueries())
            }
            assertTrue(goldTypecheck)
            assertEquals(training.size, goldTrainCorrect)
            assertEquals(challenge.size, goldChallengeCorrect)
            assertEquals(heldout.size, goldHeldoutCorrect)
            assertEquals(MRX2ValidationStatus.PASS_ON_CHALLENGE, goldStatus.status)

            // B/10 is a calibration world; four A arms are the causal comparisons.
            val selectedArms = if (world.id == "A") arms else arms.take(1)
            for (arm in selectedArms) {
                val space = MRX2BSpace(
                    C8Domain.attributes, library, training, arm.strategy,
                    atomLimit = arm.atomLimit,
                    maxCandidates = 30_000,
                    consistentLimit = 2_000,
                ).generate()
                val (ranking, gains) = rankAtoms(
                    probeAtoms(library, training), labels, arm.strategy, space.trace.pairGains,
                )
                // This must match the production selection, or the probe is INVALID.
                assertEquals(
                    space.trace.atoms, ranking.take(arm.atomLimit).map { it.canonical },
                    "ranking reconstruction diverged: ${world.id}/${arm.name}",
                )

                ranking.forEachIndexed { index, atom ->
                    output += jLine("atom_rank") {
                        put("world", world.id)
                        put("arm", arm.name)
                        put("rank", index + 1)
                        put("family", atom.family)
                        put("canonical", atom.canonical)
                        put("accuracy", accuracy(atom.predictions, labels))
                        put("pairGain", gains[atom.canonical] ?: 0.0)
                        put("combinedScore", accuracy(atom.predictions, labels) + (gains[atom.canonical] ?: 0.0))
                        put("selected", index < arm.atomLimit)
                        put("unknownPredictions", atom.predictions.count { it == null })
                    }
                }

                val selectedCanonical = space.trace.atoms.toSet()
                val necessaryLhs = CanonicalJson.expr(eq(world.lhs))
                val necessaryRhs = CanonicalJson.expr(eq(world.rhs))
                val generated = space.trace.candidates(MRX2BStage.GENERATED)
                    .mapTo(HashSet()) { it.canonical }
                val consistent = space.trace.candidates(MRX2BStage.TRAIN_CONSISTENT)
                    .mapTo(HashSet()) { it.canonical }
                val retained = space.trace.candidates(MRX2BStage.RETAINED)
                val retainedCanonicals = retained.mapTo(HashSet()) { it.canonical }
                val goldPresentGenerated = goldCanonical in generated || reverseCanonical in generated
                val goldPresentConsistent = goldCanonical in consistent || reverseCanonical in consistent
                val goldPresentRetained = goldCanonical in retainedCanonicals || reverseCanonical in retainedCanonicals

                val meter = MRX2Cost()
                val ledger = MRX2Ledger(
                    challenge, StudyQueryController(challenge, challenge.size, world.target),
                    challenge.size, meter,
                )
                val statusCounts = LinkedHashMap<String, Int>()
                var checked = 0
                var passes = 0
                for (candidate in retained) {
                    if (passes >= 4) break
                    checked++
                    val outcome = ledger.validate(
                        candidate.canonical,
                        { input -> Pack02Knowledge.predict(candidate.body, input, library) },
                        "ROOT_CAUSE_${world.id}_${arm.name}", challenge.size, meter,
                    )
                    statusCounts[outcome.status.name] = (statusCounts[outcome.status.name] ?: 0) + 1
                    if (outcome.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) passes++
                }
                output += jLine("arm_summary") {
                    put("world", world.id)
                    put("arm", arm.name)
                    put("generated", space.generated)
                    put("trainConsistent", space.consistent)
                    put("retained", space.retained)
                    put("trainPruned", space.trace.prunes.count { it.stage == "TRAIN_CONSISTENT" })
                    put("retainedPruned", space.trace.prunes.count { it.stage == "RETAINED" })
                    put("candidateCapPossiblyHit", space.generated >= 30_000)
                    put("lhsAtomRetained", necessaryLhs in selectedCanonical)
                    put("rhsAtomRetained", necessaryRhs in selectedCanonical)
                    put("goldCandidateGenerated", goldPresentGenerated)
                    put("goldCandidateTrainConsistent", goldPresentConsistent)
                    put("goldCandidateRetained", goldPresentRetained)
                    put("validationCandidatesTried", checked)
                    put("challengePassSources", passes)
                    put("oracleNew", ledger.newQueries())
                    put("oracleReuseUses", meter.oracleReuseUses)
                    put("validationInterpreterCalls", meter.interpreterCalls)
                    put("generationWallMillis", space.wallMillis)
                    put("totalGenerationInterpreterCalls", "NOT_INSTRUMENTED")
                    put("statuses", buildJsonObject {
                        statusCounts.forEach { (status, count) -> put(status, count) }
                    })
                }
            }
        }
        val dir = Path.of("build", "reports", "sd03-root-cause")
        Files.createDirectories(dir)
        val report = dir.resolve("ROOT-CAUSE-MATRIX.jsonl")
        Files.write(report, output)
        println("SD03 root-cause report: $report; events=${output.size}")
    }
}
