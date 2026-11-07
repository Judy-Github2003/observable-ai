// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-03 blind attribution 真实接入：12 个盲任务×3 arms，学生侧无 truth/category/heldout。
package logic.e3.pack02

import logic.capability.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SD03BlindAttributionIntegrationTest {
    private data class Probe(val expr: Expr, val canonical: String, val family: String, val predictions: List<Boolean?>)
    private data class Result(val snapshot: BABlindSnapshot, val decision: BADecision, val initialPasses: Int, val finalPasses: Int, val generated: Int, val consistent: Int, val retained: Int, val capHit: Boolean)

    private fun eq(name: String, value: String = "1") = Expr.Eq(Expr.Arg(name, CapType.Str), Expr.Const(CapValue.Str(value)))

    private fun probes(definitions: List<CapabilityDefinition>, training: List<StudyCase>): List<Probe> {
        val atoms = ArrayList<Pair<Expr, String>>()
        for (name in C8Domain.attributes) for (value in C8Domain.values) atoms += eq(name, value) to "eq:$name"
        for (d in definitions) if (d.inputs.keys.all { it in C8Domain.attributes }) {
            atoms += Expr.Call(d.id, d.version, d.contentHash(), d.inputs.keys.associateWith { Expr.Arg(it, CapType.Str) }) to "call:${d.id}"
        }
        return atoms.map { (e, f) -> Probe(e, CanonicalJson.expr(e), f, training.map { Pack02Knowledge.predict(e, it.values, definitions) }) }
    }

    private fun accuracy(p: List<Boolean?>, labels: List<Boolean>): Double {
        val known = p.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (known.isEmpty()) return 0.0
        val correct = known.count { (i, v) -> v == labels[i] }
        return maxOf(correct, known.size - correct).toDouble() / known.size
    }

    private fun rank(atoms: List<Probe>, labels: List<Boolean>, strategy: MRX2BStrategy, gains: List<MRX2BPairGain>): List<Probe> {
        val gain = HashMap<String, Double>()
        for (p in gains) {
            gain[p.left] = maxOf(gain[p.left] ?: 0.0, p.gainOverSingle)
            gain[p.right] = maxOf(gain[p.right] ?: 0.0, p.gainOverSingle)
        }
        val byAcc = compareByDescending<Probe> { accuracy(it.predictions, labels) }.thenBy { it.canonical }
        return when (strategy) {
            MRX2BStrategy.BASELINE -> atoms.sortedWith(byAcc)
            MRX2BStrategy.PAIRWISE -> atoms.sortedWith(compareByDescending<Probe> { accuracy(it.predictions, labels) + (gain[it.canonical] ?: 0.0) }.thenBy { it.canonical })
            MRX2BStrategy.DIVERSITY -> {
                val groups = atoms.groupBy { it.family }.mapValues { (_, g) -> g.sortedWith(byAcc) }
                val out = ArrayList<Probe>(); var round = 0
                while (out.size < atoms.size && groups.values.any { round < it.size }) {
                    for ((_, g) in groups.toSortedMap()) if (round < g.size) out += g[round]
                    round++
                }
                out
            }
        }
    }

    private fun deps(e: Expr): Set<String> = when (e) {
        is Expr.Arg -> setOf(e.name)
        is Expr.Const -> emptySet()
        is Expr.Field -> deps(e.record)
        is Expr.Eq -> deps(e.left) + deps(e.right)
        is Expr.And -> deps(e.left) + deps(e.right)
        is Expr.Or -> deps(e.left) + deps(e.right)
        is Expr.Not -> deps(e.value)
        is Expr.If -> deps(e.condition) + deps(e.whenTrue) + deps(e.whenFalse)
        is Expr.Let -> deps(e.value) + (deps(e.body) - e.name)
        is Expr.Ref -> emptySet()
        is Expr.Call -> e.args.values.flatMap { deps(it) }.toSet()
    }

    private fun run(world: MRXWorld, seed: Int, library: List<CapabilityDefinition>): Result {
        val inputs = C8Domain.all().shuffled(Random(seed.toLong()))
        val training = inputs.take(40).mapIndexed { i, m -> StudyCase("b-$i", m, world.truth(m)) }
        val challenge = inputs.drop(40).take(20)
        val labels = training.map { it.label }
        val base = MRX2BSpace(C8Domain.attributes, library, training, MRX2BStrategy.PAIRWISE, atomLimit = 10).generate()
        val ordered = rank(probes(library, training), labels, MRX2BStrategy.PAIRWISE, base.trace.pairGains)
        assertEquals(base.trace.atoms, ordered.take(10).map { it.canonical }, "ranking mismatch for blind task")
        val selected = ordered.take(10)
        val observations = training.mapIndexed { i, c -> BAObservation("train-$i", c.values, c.label) }
        val atoms = selected.mapIndexed { i, p ->
            BARankedAtom(p.canonical, i + 1, true, deps(p.expr), BADependencyQuality.COMPLETE_OVERAPPROX, "atom-${i + 1}")
        }
        val snapshot = BABlindSnapshot(
            "blind-${world.id.hashCode().toUInt().toString(16)}", observations, atoms,
            BACandidateLifecycle(base.generated, base.consistent, base.retained, base.generated >= 30_000, "lifecycle"),
            setOf(BAAction.KEEP, BAAction.DIVERSITY_10, BAAction.BASELINE_10, BAAction.PAIRWISE_12, BAAction.ABSTAIN),
        )
        val decision = SD03BlindAttributor.decide(snapshot)
        fun passes(space: MRX2BSpaceResult): Int {
            val meter = MRX2Cost()
            val ledger = MRX2Ledger(challenge, StudyQueryController(challenge, 20, world.truth), 20, meter)
            var count = 0
            for (c in space.trace.candidates(MRX2BStage.RETAINED)) {
                if (ledger.validate(c.canonical, { i -> Pack02Knowledge.predict(c.body, i, library) }, "BLIND", 20, meter).status == MRX2ValidationStatus.PASS_ON_CHALLENGE) count++
                if (count >= 4) break
            }
            return count
        }
        val initialPasses = passes(base)
        val action = decision.action
        val finalSpace = when (action) {
            BAAction.DIVERSITY_10 -> MRX2BSpace(C8Domain.attributes, library, training, MRX2BStrategy.DIVERSITY, atomLimit = 10).generate()
            BAAction.BASELINE_10 -> MRX2BSpace(C8Domain.attributes, library, training, MRX2BStrategy.BASELINE, atomLimit = 10).generate()
            BAAction.PAIRWISE_12 -> MRX2BSpace(C8Domain.attributes, library, training, MRX2BStrategy.PAIRWISE, atomLimit = 12).generate()
            else -> base
        }
        return Result(snapshot, decision, initialPasses, if (action == BAAction.KEEP || action == BAAction.ABSTAIN) initialPasses else passes(finalSpace), base.generated, base.consistent, base.retained, base.generated >= 30_000)
    }

    @Test
    fun `blind 12 tasks three arms`() {
        val wanted = setOf(MRXCategory.EXPRESSIBLE, MRXCategory.DEPTH, MRXCategory.IF_STRUCTURE)
        val worlds = MRXWorlds.baseline.filter { it.category in wanted }.groupBy { it.category }.flatMap { (_, list) -> list.sortedBy { it.id }.take(2) }
        assertEquals(6, worlds.size, "need two worlds per category")
        val library = C8TestBases.bases.library("FULL").definitions
        val lines = ArrayList<String>()
        var rows = 0
        for (w in worlds) for (seed in listOf(20261107, 20261108)) {
            val base = run(w, seed, library)
            // CONTROL keeps initial; GENERIC is fixed DIVERSITY; ATTRIBUTION uses the blind decision.
            val control = base.initialPasses
            val genericSpace = MRX2BSpace(C8Domain.attributes, library,
                C8Domain.all().shuffled(Random(seed.toLong())).take(40).mapIndexed { i, m -> StudyCase("g-$i", m, w.truth(m)) },
                MRX2BStrategy.DIVERSITY, atomLimit = 10).generate()
            val meter = MRX2Cost()
            val challenge = C8Domain.all().shuffled(Random(seed.toLong())).drop(40).take(20)
            val ledger = MRX2Ledger(challenge, StudyQueryController(challenge, 20, w.truth), 20, meter)
            var generic = 0
            for (c in genericSpace.trace.candidates(MRX2BStage.RETAINED)) {
                if (ledger.validate(c.canonical, { i -> Pack02Knowledge.predict(c.body, i, library) }, "GENERIC", 20, meter).status == MRX2ValidationStatus.PASS_ON_CHALLENGE) generic++
                if (generic >= 4) break
            }
            val taskHash = w.id.hashCode().toUInt().toString(16)
            for ((arm, value, action) in listOf(
                Triple("CONTROL", control, "KEEP"),
                Triple("GENERIC", generic, "DIVERSITY_10"),
                Triple("ATTRIBUTION", base.finalPasses, base.decision.action.name),
            )) {
                lines += """{"type":"blind_run","taskHash":"$taskHash","seed":$seed,"arm":"$arm","action":"$action","initialPasses":${base.initialPasses},"finalPasses":$value,"generated":${base.generated},"consistent":${base.consistent},"retained":${base.retained},"capHit":${base.capHit},"claims":${base.decision.claims.size}}"""
                rows++
            }
            println("task=$taskHash seed=$seed A0=$control generic=$generic attr=${base.finalPasses} action=${base.decision.action} claims=${base.decision.claims.map { "${it.cause}:${it.status}" }}")
        }
        val dir = Path.of("docs/research/sd03-blind-attribution-2026-11-07")
        Files.createDirectories(dir)
        Files.write(dir.resolve("TRACE-BLIND.jsonl"), lines)
        assertEquals(36, rows)
        assertTrue(lines.any { it.contains("\"arm\":\"ATTRIBUTION\"") })
    }
}
