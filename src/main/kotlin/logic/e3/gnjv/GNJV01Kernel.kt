package logic.e3.gnjv

/** GNJV-01 reference learner. Author: ChatGPT, NOT autonomously induced by the student.
 *  Deterministic, finite-hypothesis scientific pilot only; no hidden world or truth access.
 *  The causal hypothesis grammar is designer-supplied, not student-invented.
 */
enum class CausalVariable { U, X, Y }

data class GNJVObservation(val x: Boolean, val y: Boolean) {
    fun value(v: CausalVariable): Boolean = when (v) {
        CausalVariable.X -> x
        CausalVariable.Y -> y
        CausalVariable.U -> error("Hidden U is not observable")
    }
}

data class GNJVIntervention(val target: CausalVariable, val setTo: Boolean) {
    init { require(target != CausalVariable.U) { "Cannot intervene on hidden U" } }
    val key: String get() = "${target.name}=${if (setTo) 1 else 0}"
}

data class GNJVCausalHypothesis(
    val xFrom: CausalVariable,
    val yFrom: CausalVariable,
) {
    init {
        require(xFrom == CausalVariable.U || xFrom == CausalVariable.Y)
        require(yFrom == CausalVariable.U || yFrom == CausalVariable.X)
        require(!(xFrom == CausalVariable.Y && yFrom == CausalVariable.X)) { "Cyclic graph" }
    }

    /** Structural intervention semantics, not a dictionary overwrite.
     * In this pilot observational equality allows U to be inferred as baseline.x.
     * This assumption is explicit and must not be generalized to arbitrary SCMs.
     */
    fun predict(baseline: GNJVObservation, intervention: GNJVIntervention): GNJVObservation {
        require(baseline.x == baseline.y) { "Observational equality contract violated" }
        val u = baseline.x
        fun xFromUorY(y: Boolean): Boolean = if (xFrom == CausalVariable.U) u else y
        fun yFromUorX(x: Boolean): Boolean = if (yFrom == CausalVariable.U) u else x
        return when {
            intervention.target == CausalVariable.X -> {
                val x = intervention.setTo
                GNJVObservation(x, yFromUorX(x))
            }
            intervention.target == CausalVariable.Y -> {
                val y = intervention.setTo
                GNJVObservation(xFromUorY(y), y)
            }
            else -> error("Illegal intervention")
        }
    }

    fun structuralKey(): String = "X<-${xFrom.name}|Y<-${yFrom.name}"
}

object GNJV01HypothesisGrammar {
    /** Three candidate structures, public grammar; never expose target index to learner. */
    fun candidates(): List<GNJVCausalHypothesis> = listOf(
        GNJVCausalHypothesis(CausalVariable.U, CausalVariable.X), // X -> Y
        GNJVCausalHypothesis(CausalVariable.Y, CausalVariable.U), // Y -> X
        GNJVCausalHypothesis(CausalVariable.U, CausalVariable.U), // common U
    )
}

data class GNJVTrial(
    val initialObservation: GNJVObservation,
    val intervention: GNJVIntervention,
    val observedAfter: GNJVObservation,
)

enum class GNJVUpdateStatus { REFINED, CONFIRMATION, INCONSISTENT_EVIDENCE }
data class GNJVUpdate(val status: GNJVUpdateStatus, val survivors: Int, val eliminated: Int)

data class GNJVSelection(
    val intervention: GNJVIntervention?,
    val reason: String,
    val distinctPredictions: Int,
    val worstCaseSurvivors: Int,
)

class GNJV01Student(initial: List<GNJVCausalHypothesis> = GNJV01HypothesisGrammar.candidates()) {
    private var active = initial.distinct().also { require(it.isNotEmpty()) }
    private val evidence = mutableListOf<GNJVTrial>()
    private var contradictory = false

    fun hypotheses(): List<GNJVCausalHypothesis> = active.toList()
    fun history(): List<GNJVTrial> = evidence.toList()
    fun hasConflict(): Boolean = contradictory
    fun identified(): GNJVCausalHypothesis? = if (!contradictory && active.size == 1) active.single() else null

    fun legalActions(observation: GNJVObservation): List<GNJVIntervention> = listOf(
        GNJVIntervention(CausalVariable.X, !observation.x),
        GNJVIntervention(CausalVariable.Y, !observation.y),
    )

    /** Only publicly disclosed evidence, no oracle handle or world ID. */
    fun update(trial: GNJVTrial): GNJVUpdate {
        evidence += trial
        if (contradictory) return GNJVUpdate(GNJVUpdateStatus.INCONSISTENT_EVIDENCE, active.size, 0)
        val remaining = active.filter { h -> h.predict(trial.initialObservation, trial.intervention) == trial.observedAfter }
        if (remaining.isEmpty()) {
            contradictory = true // retain candidates and conflicting evidence for audit; never assert a winner
            return GNJVUpdate(GNJVUpdateStatus.INCONSISTENT_EVIDENCE, active.size, 0)
        }
        val eliminated = active.size - remaining.size
        active = remaining
        return GNJVUpdate(if (eliminated > 0) GNJVUpdateStatus.REFINED else GNJVUpdateStatus.CONFIRMATION,
            active.size, eliminated)
    }

    /** Minimax worst remaining behavior class, then deterministic stable tie-break. */
    fun choose(observation: GNJVObservation): GNJVSelection {
        if (contradictory) return GNJVSelection(null, "EVIDENCE_CONFLICT", 0, active.size)
        if (active.size == 1) return GNJVSelection(null, "ALREADY_IDENTIFIED", 1, 1)
        val choices = legalActions(observation).map { action ->
            val buckets = active.groupingBy { it.predict(observation, action) }.eachCount()
            Triple(action, buckets.size, buckets.values.maxOrNull() ?: 0)
        }
        val best = choices.sortedWith(compareBy<Triple<GNJVIntervention, Int, Int>> { it.third }
            .thenByDescending { it.second }.thenBy { it.first.target.name }).first()
        if (best.second <= 1) return GNJVSelection(null, "NO_DISCRIMINATIVE_INTERVENTION", best.second, best.third)
        return GNJVSelection(best.first, "DISAGREEMENT_MINIMAX", best.second, best.third)
    }

    /** Returns Unknown (null) when surviving graphs disagree on an unobserved intervention. */
    fun predict(observation: GNJVObservation, intervention: GNJVIntervention): GNJVObservation? {
        if (contradictory) return null
        return active.map { it.predict(observation, intervention) }.distinct().singleOrNull()
    }
}
