// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：PACK-02 C4/C8 最小知识组合与跨任务复用；复用 B0，不修改冻结代码。
package logic.e3.pack02

import logic.capability.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

data class Pack02Sample(val id: String, val values: Map<String, String>, val label: Boolean)
data class Pack02Trace(
    val id: String,
    val generated: Int,
    val filterStatus: String,
    val consistent: Int,
    val selectedBody: Expr?,
    val selectedCanonical: String?,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val active: Boolean,
    val definition: CapabilityDefinition?,
    val contentHash: String?,
    val verificationRunId: String?,
    val usesIds: List<String>,
    val failureCode: String?,
)

object Pack02Knowledge {

    fun learn(
        id: String,
        inputNames: List<String>,
        training: List<Pack02Sample>,
        heldout: List<Pack02Sample>,
        library: List<CapabilityDefinition>,
        dbPath: Path,
        maxDepth: Int = 4,
        maxCandidates: Int = 4000,
    ): Pack02Trace {
        val compose = compose(inputNames, training, library, maxDepth, maxCandidates)
        val evidence = filter(inputNames, compose.candidates, training, library)
        val consistent = evidence.evaluations.filter { it.consistent }.map { it.candidate }
        if (evidence.status != EvidenceStatus.IDENTIFIED) {
            return failed(id, compose.generated, evidence.status.name, consistent.size, "filter_not_identified")
        }
        val selected = evidence.survivors.firstOrNull()
            ?: return failed(id, compose.generated, evidence.status.name, consistent.size, "no_survivor")
        val correct = heldout.count { sample ->
            predict(selected.body, sample.values.filterKeys { it in inputNames }, library) == sample.label
        }
        if (correct != heldout.size) {
            return Pack02Trace(
                id, compose.generated, evidence.status.name, consistent.size, selected.body, selected.canonical,
                correct, heldout.size, false, null, null, null, idsIn(selected.body),
                "heldout_verification_failed",
            )
        }
        val definition = CapabilityDefinition(
            id, 1, inputNames.associateWith { CapType.Str }, CapType.Bool3, selected.body,
        )
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val provenance = buildJsonObject {
                put("source", "composer_bounded_search")
                put("attribution", "student_composed")
                put("library", library.joinToString(",") { it.id + "@" + it.version })
                put("selected_canonical", selected.canonical)
            }.toString()
            val proposed = registry.propose(definition, provenance)
            if (!proposed.ok) {
                return Pack02Trace(
                    id, compose.generated, evidence.status.name, consistent.size, selected.body, selected.canonical,
                    correct, heldout.size, false, null, null, null, idsIn(selected.body),
                    proposed.code ?: "proposal_failed",
                )
            }
            val cases = heldout.map { sample ->
                VerificationCase(
                    sample.id,
                    sample.values.filterKeys { it in inputNames }.mapValues { CapValue.Str(it.value) },
                    EvalResult.Known(CapValue.Bool(sample.label)),
                )
            }
            val suite = TrustedVerificationSuite.create("$id.heldout", 1, cases)
            val suites = TrustedSuiteRegistry()
            suites.register(suite)
            val verification = CapabilityVerificationService(registry, suites)
                .verify(definition.id, definition.version, suite.suiteId, suite.suiteVersion)
            if (!verification.recorded || verification.outcome.verdict.name != "PASS") {
                return Pack02Trace(
                    id, compose.generated, evidence.status.name, consistent.size, selected.body, selected.canonical,
                    correct, heldout.size, false, definition, proposed.contentHash, verification.runId,
                    idsIn(selected.body), "verification_failed:${verification.recorded}:${verification.outcome.caseCount}:${verification.outcome.passedCount}:${verification.outcome.definiteCount}:${verification.outcome.trueCount}:${verification.outcome.falseCount}:${verification.code}",
                )
            }
            val activation = CapabilityActivationService(registry, suites)
                .activate(definition.id, definition.version, verification.runId!!)
            return Pack02Trace(
                id, compose.generated, evidence.status.name, consistent.size, selected.body, selected.canonical,
                correct, heldout.size, activation.ok, if (activation.ok) definition else null,
                proposed.contentHash, verification.runId, idsIn(selected.body),
                if (activation.ok) null else activation.code ?: "activation_failed",
            )
        } finally {
            registry.close()
        }
    }

    fun reuse(
        id: String,
        inputNames: List<String>,
        training: List<Pack02Sample>,
        heldout: List<Pack02Sample>,
        library: List<CapabilityDefinition>,
        maxDepth: Int = 4,
        maxCandidates: Int = 4000,
        preferredId: String? = null,
    ): Pack02Trace {
        val compose = compose(inputNames, training, library, maxDepth, maxCandidates)
        val evidence = filter(inputNames, compose.candidates, training, library)
        val consistent = evidence.evaluations.filter { it.consistent }.map { it.candidate }
        if (evidence.status == EvidenceStatus.REFUSED) {
            return failed(id, compose.generated, evidence.status.name, consistent.size, "filter_refused")
        }
        val selected = if (preferredId != null) {
            consistent.firstOrNull { preferredId in idsIn(it.body) } ?: evidence.survivors.firstOrNull { preferredId in idsIn(it.body) }
        } else if (library.isEmpty()) evidence.survivors.firstOrNull()
        else consistent.firstOrNull { idsIn(it.body).isNotEmpty() } ?: evidence.survivors.firstOrNull()
        if (selected == null) return failed(id, compose.generated, evidence.status.name, consistent.size, "no_candidate")
        val correct = heldout.count { sample ->
            predict(selected.body, sample.values.filterKeys { it in inputNames }, library) == sample.label
        }
        return Pack02Trace(
            id, compose.generated, evidence.status.name, consistent.size, selected.body, selected.canonical,
            correct, heldout.size, false, null, null, null, idsIn(selected.body),
            when {
                evidence.status == EvidenceStatus.AMBIGUOUS -> "ambiguous_selection"
                correct != heldout.size -> "heldout_mismatch"
                else -> null
            },
        )
    }

    fun predict(body: Expr, values: Map<String, String>, library: List<CapabilityDefinition>): Boolean? {
        val definition = CapabilityDefinition(
            "pack02.eval", 1, values.keys.associateWith { CapType.Str }, CapType.Bool3, body,
        )
        val result = CapabilityInterpreter(InMemoryResolver(library)).execute(
            definition,
            values.mapValues { EvalResult.Known(CapValue.Str(it.value)) },
            EvalBudget(maxSteps = 10000),
        )
        return (result as? EvalResult.Known)?.value?.let { (it as? CapValue.Bool)?.value }
    }

    fun idsIn(body: Expr): List<String> {
        val ids = LinkedHashSet<String>()
        fun walk(expr: Expr) {
            when (expr) {
                is Expr.Call -> {
                    ids += expr.id
                    expr.args.values.forEach(::walk)
                }
                is Expr.And -> { walk(expr.left); walk(expr.right) }
                is Expr.Or -> { walk(expr.left); walk(expr.right) }
                is Expr.Not -> walk(expr.value)
                is Expr.Eq -> { walk(expr.left); walk(expr.right) }
                is Expr.If -> { walk(expr.condition); walk(expr.whenTrue); walk(expr.whenFalse) }
                is Expr.Let -> { walk(expr.value); walk(expr.body) }
                is Expr.Field -> walk(expr.record)
                is Expr.Arg, is Expr.Const, is Expr.Ref -> Unit
            }
        }
        walk(body)
        return ids.toList()
    }

    private fun compose(
        inputNames: List<String>,
        training: List<Pack02Sample>,
        library: List<CapabilityDefinition>,
        maxDepth: Int,
        maxCandidates: Int,
    ): ComposeResult = CapabilityComposer().compose(
        ComposeRequest(
            inputs = inputNames.associateWith { CapType.Str },
            output = CapType.Bool3,
            constants = training.flatMap { it.values.values }.distinct().sorted().map { CapValue.Str(it) },
            library = library,
            budget = ComposeBudget(maxDepth = maxDepth, maxNodes = 40, maxCandidates = maxCandidates),
        ),
    )

    private fun filter(
        inputNames: List<String>,
        candidates: List<ComposedCandidate>,
        training: List<Pack02Sample>,
        library: List<CapabilityDefinition>,
    ): EvidenceResult {
        val examples = training.map { sample ->
            PublicExample(
                sample.values.filterKeys { it in inputNames }.mapValues { CapValue.Str(it.value) },
                CapValue.Bool(sample.label),
            )
        }
        val values = training.flatMap { it.values.filterKeys { k -> k in inputNames }.values }.distinct().sorted()
        val domain = inputNames.associateWith { values.map { CapValue.Str(it) } }
        return CandidateEvidenceFilter(InMemoryResolver(library)).filter(
            inputNames.associateWith { CapType.Str },
            candidates,
            examples,
            domain,
            EvalBudget(maxSteps = 10000),
        )
    }

    private fun failed(id: String, generated: Int, status: String, consistent: Int, code: String) =
        Pack02Trace(id, generated, status, consistent, null, null, 0, 0, false, null, null, null, emptyList(), code)
}
