package observable.ai.abdmnv.m1

/** Minimal actual student learner. Generates hypotheses from *public* training observations.
 *
 *  修复轮改动（DeepSeek Harness, 2026-11-09）：
 *  - 概念 id 不再固定为 c_0001，而是由 worldId + 语义体派生，跨世界不再冲突；
 *  - ref.hash 改为 CombinedConceptHashing 的内容哈希（含 deps/params/scope），不再是 body-only 摘要。
 */
data class TrainingLabel(val objectId: String, val label: Boolean)

data class StudentInduction(
    val status: String,
    val concept: Concept?,
    val evaluatedCandidates: Int,
    val survivingCandidates: Int,
    val evidenceIds: List<String>,
)

/**
 * Intentionally limited to category induction from already exposed bool/enum attributes.
 * New predicates are runtime-generated; the grammar and search policy are implementer-authored.
 * This is NOT open-ended concept invention or a proof of autonomous representation evolution.
 */
class MinimalCategoryStudent(private val maxCandidates: Int = 64) {
    fun learn(view: StudentView, labels: List<TrainingLabel>, catalog: ConceptCatalog = PublicConceptCatalog()): StudentInduction {
        if (labels.isEmpty()) return StudentInduction("UNDETERMINED_NO_EVIDENCE", null, 0, 0, emptyList())
        if (labels.map { it.objectId }.toSet().size != labels.size) {
            return StudentInduction("UNDETERMINED_CONFLICTING_LABELS", null, 0, 0, emptyList())
        }
        if (labels.any { it.objectId !in view.knownObjects }) {
            return StudentInduction("UNDETERMINED_OUT_OF_SCOPE", null, 0, 0, emptyList())
        }
        val candidates = mutableListOf<Term>()
        for ((channel, sort) in view.attributeSorts.toSortedMap()) {
            val values = labels.mapNotNull { training ->
                val known = view.observations[training.objectId]?.values?.get(channel) as? ValueResult.Known
                known?.value
            }.distinctBy { it.toString() }.sortedBy { it.toString() }
            if (values.isEmpty()) continue
            val attr = Term.Attribute(channel, Term.Var("x"))
            when (sort) {
                Sort.Bool -> {
                    candidates += attr
                    candidates += Term.Not(attr)
                }
                is Sort.Enum, Sort.Int -> for (value in values) {
                    if (M1TypeChecker.sortOf(value) == sort) candidates += Term.Eq(attr, Term.Constant(value))
                }
                else -> Unit
            }
        }
        if (candidates.size > maxCandidates) return StudentInduction("SEARCH_TRUNCATED", null, maxCandidates, 0, emptyList())
        val survivors = mutableListOf<Term>()
        for (term in candidates) {
            val concept = conceptFor(term, view)
            val evaluator = M1Evaluator(view, catalog)
            val fits = labels.all { training ->
                val answer = evaluator.evaluate(concept, listOf(training.objectId)).truth()
                answer == if (training.label) Truth.TRUE else Truth.FALSE
            }
            if (fits) survivors += term
        }
        val ids = labels.mapNotNull { view.observations[it.objectId]?.id }.sorted()
        if (survivors.isEmpty()) return StudentInduction("NO_CANDIDATE_IN_BOUNDED_SPACE", null, candidates.size, 0, ids)
        if (survivors.size != 1) return StudentInduction("AMBIGUOUS", null, candidates.size, survivors.size, ids)
        return StudentInduction("STUDENT_COMPOSED_LIMITED", conceptFor(survivors.single(), view, ids), candidates.size, 1, ids)
    }

    /** id 由 world + 语义体派生；hash 由内容哈希给出（不含 id/version/evidence，因此对象重命名不影响）。 */
    private fun conceptFor(term: Term, view: StudentView, ids: List<String> = emptyList()): Concept {
        val semantic = stableTerm(term)
        val id = "c_" + CanonicalSExpr.sha256("id|${view.worldId}|$semantic").take(12)
        val draft = Concept(
            ref = ConceptRef(id, 1, ""),
            kind = ConceptKind.CATEGORY, output = Sort.Bool,
            params = listOf(Param("x", Sort.Object)), body = term,
            worldScope = view.worldId, evidenceIds = ids.toSet(),
        )
        return CombinedConceptHashing.seal(draft)
    }

    private fun stableTerm(term: Term): String = when (term) {
        is Term.Var -> "(var ${term.name})"
        is Term.Attribute -> "(attr ${term.channel} ${stableTerm(term.of)})"
        is Term.Not -> "(not ${stableTerm(term.child)})"
        is Term.Eq -> "(eq ${stableTerm(term.left)} ${stableTerm(term.right)})"
        is Term.Constant -> when (val v = term.value) {
            is TypedValue.Bool -> "(bool ${v.value})"
            is TypedValue.IntNumber -> "(int ${v.value})"
            is TypedValue.EnumSymbol -> "(enum ${v.enumId} ${v.value})"
            else -> "(unsupported)"
        }
        else -> "(unsupported)"
    }
}
