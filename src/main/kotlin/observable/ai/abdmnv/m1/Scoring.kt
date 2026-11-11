package observable.ai.abdmnv.m1

/** Examiner-side public reference scorer: NOT a sealed independent examiner. */
enum class ScoreStatus {
    MATCH, PARTIAL, CONTRADICTION, TYPE_ERROR, EXEC_ERROR,
    NONTRIVIAL_FAIL, UNSCORABLE
}

data class Slot(val id: String, val kind: ConceptKind, val sort: Sort)

enum class TaskState { SUBMITTED, ABSTAIN, UNDETERMINED }

data class SlotResponse(
    val slotId: String,
    val state: TaskState,
    val concept: ConceptRef? = null,
    val reason: String? = null
)

data class SubmissionCheck(val accepted: Boolean, val code: String, val submitted: Int, val abstained: Int, val undetermined: Int)

object SubmissionGuard {
    fun verify(slots: List<Slot>, submissions: List<SlotResponse>): SubmissionCheck {
        if (submissions.size > slots.size) return SubmissionCheck(false, "SUBMISSION_OVERFLOW", 0, 0, 0)
        val slotIds = slots.map { it.id }.toSet()
        if (submissions.any { it.slotId !in slotIds }) return SubmissionCheck(false, "UNKNOWN_SLOT", 0, 0, 0)
        if (submissions.map { it.slotId }.distinct().size != submissions.size) {
            return SubmissionCheck(false, "SLOT_DUPLICATE", 0, 0, 0)
        }
        val refs = submissions.filter { it.state == TaskState.SUBMITTED }.mapNotNull { it.concept }
        if (refs.map { it.id }.distinct().size != refs.size) return SubmissionCheck(false, "CONCEPT_DUPLICATE", 0, 0, 0)
        if (submissions.any { (it.state == TaskState.SUBMITTED) != (it.concept != null) }) {
            return SubmissionCheck(false, "INVALID_SUBMISSION_STATE", 0, 0, 0)
        }
        val explicitAbstentions = submissions.count { it.state == TaskState.ABSTAIN }
        val undetermined = submissions.count { it.state == TaskState.UNDETERMINED }
        return SubmissionCheck(
            true, "ACCEPTED", refs.size,
            explicitAbstentions + (slots.size - submissions.size), undetermined
        )
    }
}

/** TargetSpec stays examiner-side. Never put it in StudentView or Kotlin learner input. */
data class TargetSpec(
    val slot: Slot,
    val inputs: List<List<String>>,
    val truth: List<Truth>,
) {
    init { require(inputs.size == truth.size) }
}

data class Score(
    val status: ScoreStatus,
    val r1TypeOk: Boolean,
    val r2NoContradiction: Boolean,
    val r3CoverageOk: Boolean,
    val r4NoError: Boolean,
    val r5Nontrivial: Boolean,
    val r6TargetDefined: Boolean,
    val coverage: Double,
    val correct: Int,
    val contradicted: Int,
    val errors: Int,
    val unknown: Int,
)

/** Deterministic, public, small scorer. Formal examiner must independently reproduce. */
class ReferenceScorer(private val coverageMinimum: Double = 0.9) {
    init { require(coverageMinimum in 0.0..1.0) }

    fun score(concept: Concept, target: TargetSpec, view: StudentView, catalog: ConceptCatalog): Score {
        val n = target.inputs.size
        if (n == 0 || target.truth.any { it == Truth.UNKNOWN || it == Truth.ERROR }) {
            return Score(ScoreStatus.UNSCORABLE, false, false, false, false, false, false, 0.0, 0, 0, 0, n)
        }
        val expectedKind = target.slot.kind
        val checked = M1TypeChecker(view, catalog).check(concept)
        val r1 = checked.ok && concept.kind == expectedKind && concept.output == target.slot.sort &&
            (concept.output == Sort.Bool) && target.inputs.all { it.size == concept.params.size }
        if (!r1) return Score(ScoreStatus.TYPE_ERROR, false, false, false, false, false, true, 0.0, 0, 0, 0, n)
        val evaluator = M1Evaluator(view, catalog)
        val actual = target.inputs.map { evaluator.evaluate(concept, it).truth() }
        val errors = actual.count { it == Truth.ERROR }
        val unknown = actual.count { it == Truth.UNKNOWN }
        val contradicted = actual.zip(target.truth).count { (a, b) ->
            a != Truth.UNKNOWN && a != Truth.ERROR && a != b
        }
        val correct = actual.zip(target.truth).count { (a, b) -> a == b }
        val coverage = (n - unknown - errors).toDouble() / n
        val r2 = contradicted == 0
        val r3 = coverage >= coverageMinimum
        val r4 = errors == 0
        val r5 = actual.contains(Truth.TRUE) && actual.contains(Truth.FALSE)
        val status = when {
            !r4 -> ScoreStatus.EXEC_ERROR
            !r2 -> ScoreStatus.CONTRADICTION
            !r5 -> ScoreStatus.NONTRIVIAL_FAIL
            !r3 -> ScoreStatus.PARTIAL
            else -> ScoreStatus.MATCH
        }
        return Score(status, true, r2, r3, r4, r5, true, coverage, correct, contradicted, errors, unknown)
    }
}

data class SubmissionCounts(
    val submitted: Int,
    val matched: Int,
    val contradicted: Int,
    val unscorable: Int,
    val abstained: Int,
    val undetermined: Int,
) {
    val precision: Double? get() = if (submitted == 0) null else matched.toDouble() / submitted
}

fun summarize(check: SubmissionCheck, scores: Map<String, Score>): SubmissionCounts = SubmissionCounts(
    submitted = check.submitted,
    matched = scores.values.count { it.status == ScoreStatus.MATCH },
    contradicted = scores.values.count { it.status == ScoreStatus.CONTRADICTION },
    unscorable = scores.values.count { it.status == ScoreStatus.UNSCORABLE },
    abstained = check.abstained,
    undetermined = check.undetermined,
)
