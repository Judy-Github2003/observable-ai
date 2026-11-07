package logic.e3.pack02

import logic.capability.*
import java.security.MessageDigest

/** TRAIN-only adapter. No target/world/truth, query controller or heldout in its API. */
data class E1RepoProposal(
    val result: E1Result,
    val candidateBody: Expr?,
    val candidateCanonical: String?,
    val typecheckOk: Boolean,
    val realTrainCorrect: Int,
    val realTrainKnown: Int,
    val atomPreviewInterpreterCalls: Long,
    val realTrainInterpreterCalls: Long,
    val sourceAtomCount: Int,
    val trainDigest: String,
    val traceDigest: String,
)

object SD03E1RepoEvidenceAdapter {
    private fun digest(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun buildAndPropose(
        training: List<StudyCase>,
        library: List<CapabilityDefinition>,
        options: E1Config = E1Config(),
    ): E1RepoProposal {
        require(training.isNotEmpty())
        val inputs = C8Domain.attributes
        val source = ArrayList<Pair<String, Expr>>()
        for (field in inputs) for (value in C8Domain.values) {
            val expr = Expr.Eq(Expr.Arg(field, CapType.Str), Expr.Const(CapValue.Str(value)))
            source += CanonicalJson.expr(expr) to expr
        }
        for (definition in library) {
            if (!definition.inputs.keys.all { it in inputs }) continue
            if (!definition.inputs.values.all { it == CapType.Str }) continue
            val expr = Expr.Call(definition.id, definition.version, definition.contentHash(),
                definition.inputs.keys.associateWith { Expr.Arg(it, CapType.Str) })
            source += CanonicalJson.expr(expr) to expr
        }
        val catalog = source.distinctBy { it.first }.sortedBy { it.first }
        val expressions = catalog.toMap()
        val examples = training.map { E1Example(it.id, it.values.toMap(), it.label) }
        val atoms = catalog.mapIndexed { i, (canonical, expr) ->
            E1Atom(canonical,
                training.associate { case -> case.id to Pack02Knowledge.predict(expr, case.values, library) },
                "TRAIN_ATOM_PREDICTION:${i + 1}")
        }
        val result = SD03EvidenceCompositionE1.propose(examples, atoms, options)
        fun translate(t: E1Program): Expr = when (t) {
            is E1Program.Constant -> Expr.Const(CapValue.Bool(t.value))
            is E1Program.Branch -> Expr.If(expressions.getValue(t.atomId),
                translate(t.whenTrue), translate(t.whenFalse))
        }
        val body = result.program?.let(::translate)
        val definition = body?.let {
            CapabilityDefinition("sd03.e1.proposal", 1, inputs.associateWith { CapType.Str }, CapType.Bool3, it)
        }
        val typecheck = definition?.let { CapabilityTypeChecker(InMemoryResolver(library)).check(it).ok } ?: false
        // Independent real executor checks every TRAIN example. The pure tree scorer
        // cannot substitute for the capability interpreter in scientific reporting.
        val actual = if (body != null && typecheck) training.map { sample ->
            Pack02Knowledge.predict(body, sample.values, library)
        } else emptyList()
        val correct = actual.zip(training).count { (predicted, sample) -> predicted == sample.label }
        val canonical = body?.let { CanonicalJson.expr(it) }
        val trainDigest = digest(examples.joinToString("|") { e ->
            e.id + ":" + e.label + ":" + e.fields.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        })
        val traceDigest = digest(buildString {
            append(trainDigest).append('|').append(options).append('|').append(result.status)
            for (step in result.decisionEvidence) {
                append('|').append(step.path).append(':').append(step.atomId).append(':')
                append(step.supportingTrainingIds.joinToString(","))
                append(":").append(step.trueTrainingIds.joinToString(","))
            }
            append('|').append(canonical)
        })
        return E1RepoProposal(result,body,canonical,typecheck,correct,actual.count { it != null },
            atoms.size.toLong()*training.size,actual.size.toLong(), atoms.size,trainDigest,traceDigest)
    }
}
