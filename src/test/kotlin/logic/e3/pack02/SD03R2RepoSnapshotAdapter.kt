package logic.e3.pack02

import logic.capability.*
import java.security.MessageDigest

/**
 * Developer-facing adapter to REAL MRX2BSpace data. No target/truth/examiner
 * callback appears in the adapter. This code is intended to be integrated and
 * compiled in the original repository; locally only the pure kernel was compiled.
 *
 * Alternative rankings are computed from PUBLIC TRAIN atom predictions, not by
 * reading CHALLENGE outcomes. All preview interpretation work is reportable cost.
 */
data class R2SnapshotBuild(
    val snapshot: R2Snapshot,
    val previewInterpreterCalls: Int,
    val atomCount: Int,
    val baselineGenerated: Int,
)

object SD03R2RepoSnapshotAdapter {
    private data class AtomRecord(
        val expr: Expr,
        val canonical: String,
        val family: String,
        val predictions: List<Boolean?>,
    )
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun build(
        runToken: String,
        training: List<StudyCase>,
        library: List<CapabilityDefinition>,
        base: MRX2BSpaceResult,
    ): R2SnapshotBuild {
        require(runToken.isNotBlank())
        require(training.isNotEmpty() && training.map { it.id }.distinct().size == training.size)
        val atoms = ArrayList<Pair<Expr, String>>()
        for (field in C8Domain.attributes) for (value in C8Domain.values) {
            atoms += Expr.Eq(Expr.Arg(field, CapType.Str), Expr.Const(CapValue.Str(value))) to "eq:$field"
        }
        for (definition in library) if (definition.inputs.keys.all { it in C8Domain.attributes }) {
            atoms += Expr.Call(definition.id, definition.version, definition.contentHash(),
                definition.inputs.keys.associateWith { Expr.Arg(it, CapType.Str) }) to "call:${definition.id}"
        }
        val atomRecords = atoms.map { (expr, family) ->
            AtomRecord(expr, CanonicalJson.expr(expr), family,
                training.map { case -> Pack02Knowledge.predict(expr, case.values, library) })
        }.distinctBy { it.canonical }
        val labels = training.map { it.label }
        fun accuracy(pred: List<Boolean?>): Double {
            val known = pred.mapIndexedNotNull { i, v -> v?.let { i to it } }
            if (known.isEmpty()) return 0.0
            val correct = known.count { labels[it.first] == it.second }
            return maxOf(correct, known.size - correct).toDouble() / known.size
        }
        val gain = HashMap<String, Double>()
        for (p in base.trace.pairGains) {
            gain[p.left] = maxOf(gain[p.left] ?: 0.0, p.gainOverSingle)
            gain[p.right] = maxOf(gain[p.right] ?: 0.0, p.gainOverSingle)
        }
        val byAccuracy = compareByDescending<AtomRecord> { accuracy(it.predictions) }.thenBy { it.canonical }
        val pairwise = atomRecords.sortedWith(
            compareByDescending<AtomRecord> { accuracy(it.predictions) + (gain[it.canonical] ?: 0.0) }
                .thenBy { it.canonical },
        )
        require(pairwise.take(10).map { it.canonical } == base.trace.atoms) {
            "R2 atom preview ranking differs from actual MRX2BSpace; do not run attribution on fabricated ranking"
        }
        val baseline = atomRecords.sortedWith(byAccuracy)
        val groups = atomRecords.groupBy { it.family }.mapValues { (_, vs) -> vs.sortedWith(byAccuracy) }.toSortedMap()
        val diversity = ArrayList<AtomRecord>()
        var round = 0
        while (diversity.size < atomRecords.size && groups.values.any { round < it.size }) {
            for ((_, group) in groups) if (round < group.size) diversity += group[round]
            round++
        }
        val idByCanonical = atomRecords.associate { it.canonical to "atom:${hash(it.canonical)}" }
        val ranked = pairwise.mapIndexed { i, it ->
            R2Atom(
                id = idByCanonical.getValue(it.canonical), rank = i + 1,
                retained = i < 10,
                predictions = training.indices.associate { j -> training[j].id to it.predictions[j] },
                evidenceId = "trace:$runToken:atom-rank:${i + 1}",
            )
        }
        fun alternative(action: R2Action, a: List<AtomRecord>, limit: Int) =
            R2Alternative(action, a.take(limit).map { idByCanonical.getValue(it.canonical) },
                "preview:$runToken:${action.name}")
        val snapshot = R2Snapshot(
            id = "r2:$runToken",
            observations = training.map { R2Observation(it.id, it.values.toMap(), it.label) },
            atoms = ranked,
            lifecycle = R2Lifecycle(base.generated, base.consistent, base.retained,
                base.generated >= 30_000, "trace:$runToken:lifecycle"),
            alternatives = listOf(
                alternative(R2Action.DIVERSITY_10, diversity, 10),
                alternative(R2Action.BASELINE_10, baseline, 10),
                alternative(R2Action.PAIRWISE_12, pairwise, 12),
            ),
            allowedActions = R2Action.entries.toSet(),
        )
        return R2SnapshotBuild(snapshot, atomRecords.size * training.size, atomRecords.size, base.generated)
    }
}
