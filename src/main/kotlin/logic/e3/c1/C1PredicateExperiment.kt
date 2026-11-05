// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-C1 最小谓词学习与第二任务复用实验；只复用 B0，不修改 B0/A9。
package logic.e3.c1

import logic.capability.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

data class C1PredicateRef(val id: String, val version: Int, val contentHash: String)

data class C1PredicateSample(
    val sampleId: String,
    val a: String,
    val b: String,
    val label: Boolean,
)

data class C1ReuseSample(
    val sampleId: String,
    val a: String,
    val b: String,
    val c: String,
    val label: Boolean,
)

data class C1PredicateResult(
    val composeGenerated: Int,
    val composeReason: String?,
    val filterStatus: String,
    val consistentCount: Int,
    val ambiguous: Boolean,
    val selectedBody: Expr?,
    val selectedCanonical: String?,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val registered: Boolean,
    val activated: Boolean,
    val predicate: CapabilityDefinition?,
    val contentHash: String?,
    val verificationRunId: String?,
    val failureCode: String?,
)

data class C1ReuseResult(
    val group: String,
    val composeGenerated: Int,
    val composeReason: String?,
    val filterStatus: String,
    val consistentCount: Int,
    val selectedBody: Expr?,
    val usesPredicate: Boolean,
    val heldoutCorrect: Int,
    val heldoutTotal: Int,
    val selectionBasis: String,
    val failureCode: String?,
)

/**
 * 第一任务只使用公开样本；目标规则不进入本类。
 * 组合器/筛选器/解释器全部来自 B0；本类只做实验编排与证据记录。
 */
object C1Experiment {

    fun learnPredicate(
        training: List<C1PredicateSample>,
        heldout: List<C1PredicateSample>,
        dbPath: Path,
        maxDepth: Int = 4,
        maxCandidates: Int = 4000,
    ): C1PredicateResult {
        val compose = CapabilityComposer().compose(
            ComposeRequest(
                inputs = mapOf("a" to CapType.Str, "b" to CapType.Str),
                output = CapType.Bool3,
                constants = predicateConstants(training),
                budget = ComposeBudget(maxDepth = maxDepth, maxNodes = 32, maxCandidates = maxCandidates),
            ),
        )
        val examples = training.map { sample ->
            PublicExample(
                mapOf("a" to CapValue.Str(sample.a), "b" to CapValue.Str(sample.b)),
                CapValue.Bool(sample.label),
            )
        }
        val domain = mapOf(
            "a" to predicateConstants(training).filter { it is CapValue.Str }.map { it as CapValue.Str },
            "b" to predicateConstants(training).filter { it is CapValue.Str }.map { it as CapValue.Str },
        )
        val evidence = CandidateEvidenceFilter(InMemoryResolver(emptyList())).filter(
            mapOf("a" to CapType.Str, "b" to CapType.Str),
            compose.candidates,
            examples,
            domain,
            EvalBudget(maxSteps = 10000),
            compose.reason == "candidate_limit",
        )
        val consistent = evidence.evaluations.filter { it.consistent }.map { it.candidate }
        if (evidence.status == EvidenceStatus.REFUSED) {
            return C1PredicateResult(
                compose.generated, compose.reason, evidence.status.name, consistent.size, false,
                null, null, 0, heldout.size, false, false, null, null, null, "filter_refused",
            )
        }
        if (evidence.status == EvidenceStatus.AMBIGUOUS) {
            val candidate = consistent.firstOrNull()
            return C1PredicateResult(
                compose.generated, compose.reason, evidence.status.name, consistent.size, true,
                candidate?.body, candidate?.canonical, 0, heldout.size, false, false, null, null, null,
                "ambiguous_not_registered",
            )
        }
        val selected = evidence.survivors.firstOrNull()
        if (selected == null) {
            return C1PredicateResult(
                compose.generated, compose.reason, evidence.status.name, consistent.size, false,
                null, null, 0, heldout.size, false, false, null, null, null, "no_consistent_predicate",
            )
        }
        val heldoutCorrect = heldout.count { sample ->
            executePredicate(selected.body, sample.a, sample.b) == sample.label
        }
        val allHeldout = heldoutCorrect == heldout.size
        val definition = CapabilityDefinition(
            "c1.predicate", 1,
            mapOf("a" to CapType.Str, "b" to CapType.Str), CapType.Bool3, selected.body,
        )
        val registry = SqliteCapabilityRegistry(dbPath)
        try {
            val provenance = buildJsonObject {
                put("source", "composer_bounded_search")
                put("attribution", "student_composed")
                put("task", "C1-first")
                put("selected_canonical", selected.canonical)
                put("compose_reason", compose.reason ?: "exhausted")
                put("evidence_ids", training.joinToString(",") { it.sampleId })
            }.toString()
            val proposed = registry.propose(definition, provenance)
            if (!proposed.ok) {
                return C1PredicateResult(
                    compose.generated, compose.reason, evidence.status.name, consistent.size,
                    evidence.status == EvidenceStatus.AMBIGUOUS, selected.body, selected.canonical,
                    heldoutCorrect, heldout.size, false, false, null, null, null,
                    proposed.code ?: "proposal_failed",
                )
            }
            val suite = TrustedVerificationSuite.create(
                "c1.predicate.heldout", 1,
                heldout.map { sample ->
                    VerificationCase(
                        sample.sampleId,
                        mapOf("a" to CapValue.Str(sample.a), "b" to CapValue.Str(sample.b)),
                        EvalResult.Known(CapValue.Bool(sample.label)),
                    )
                },
            )
            val suites = TrustedSuiteRegistry()
            suites.register(suite)
            val verification = CapabilityVerificationService(registry, suites)
                .verify(definition.id, definition.version, suite.suiteId, suite.suiteVersion)
            if (!verification.recorded || verification.outcome.verdict.name != "PASS") {
                return C1PredicateResult(
                    compose.generated, compose.reason, evidence.status.name, consistent.size,
                    evidence.status == EvidenceStatus.AMBIGUOUS, selected.body, selected.canonical,
                    heldoutCorrect, heldout.size, true, false, definition, proposed.contentHash, null,
                    verification.code ?: "verification_failed",
                )
            }
            val activation = CapabilityActivationService(registry, suites)
                .activate(definition.id, definition.version, verification.runId!!)
            val active = activation.ok && allHeldout
            return C1PredicateResult(
                compose.generated, compose.reason, evidence.status.name, consistent.size,
                evidence.status == EvidenceStatus.AMBIGUOUS, selected.body, selected.canonical,
                heldoutCorrect, heldout.size, true, active, if (active) definition else null,
                proposed.contentHash, verification.runId,
                if (active) null else activation.code ?: "heldout_verification_failed",
            )
        } finally {
            registry.close()
        }
    }

    /**
     * 正式复用入口：P_REUSE 只从 SQLite 的 activeCapabilities() 快照读取 P；
     * NO_PREDICATE 强制空 library。外部 CapabilityDefinition 不能进入正式入口。
     */
    fun reuseSecondTask(
        group: String,
        training: List<C1ReuseSample>,
        heldout: List<C1ReuseSample>,
        dbPath: Path?,
        predicateRef: C1PredicateRef?,
        maxDepth: Int = 4,
        maxCandidates: Int = 4000,
    ): C1ReuseResult {
        if (group == "NO_PREDICATE") {
            if (predicateRef != null) return reuseRejected(group, heldout, "control_must_not_bind_predicate")
            return reuseWithLibrary(group, training, heldout, null, maxDepth, maxCandidates)
        }
        if (group != "P_REUSE") return reuseRejected(group, heldout, "unknown_group")
        if (dbPath == null) return reuseRejected(group, heldout, "missing_predicate_registry")
        if (predicateRef == null) return reuseRejected(group, heldout, "missing_predicate_ref")
        val active = try {
            val registry = SqliteCapabilityRegistry(dbPath)
            try {
                registry.activeCapabilities()
            } finally {
                registry.close()
            }
        } catch (_: Exception) {
            return reuseRejected(group, heldout, "active_snapshot_unavailable")
        }
        val predicate = active.firstOrNull {
            it.id == predicateRef.id && it.version == predicateRef.version &&
                it.contentHash() == predicateRef.contentHash
        } ?: return reuseRejected(group, heldout, "predicate_not_active_or_hash_mismatch")
        return reuseWithLibrary(group, training, heldout, predicate, maxDepth, maxCandidates)
    }

    private fun reuseRejected(group: String, heldout: List<C1ReuseSample>, code: String) =
        C1ReuseResult(
            group, 0, null, "NOT_EVALUATED", 0, null, false, 0, heldout.size, "none", code,
        )

    private fun reuseWithLibrary(
        group: String,
        training: List<C1ReuseSample>,
        heldout: List<C1ReuseSample>,
        predicate: CapabilityDefinition?,
        maxDepth: Int,
        maxCandidates: Int,
    ): C1ReuseResult {
        val constants = reuseConstants(training)
        val compose = CapabilityComposer().compose(
            ComposeRequest(
                inputs = mapOf("a" to CapType.Str, "b" to CapType.Str, "c" to CapType.Str),
                output = CapType.Bool3,
                constants = constants,
                library = listOfNotNull(predicate),
                budget = ComposeBudget(maxDepth = maxDepth, maxNodes = 16, maxCandidates = maxCandidates),
            ),
        )
        val examples = training.map { sample ->
            PublicExample(
                mapOf("a" to CapValue.Str(sample.a), "b" to CapValue.Str(sample.b), "c" to CapValue.Str(sample.c)),
                CapValue.Bool(sample.label),
            )
        }
        val domain = mapOf(
            "a" to constants.map { it as CapValue.Str },
            "b" to constants.map { it as CapValue.Str },
            "c" to constants.map { it as CapValue.Str },
        )
        val resolver = InMemoryResolver(listOfNotNull(predicate))
        val evidence = CandidateEvidenceFilter(resolver).filter(
            mapOf("a" to CapType.Str, "b" to CapType.Str, "c" to CapType.Str),
            compose.candidates,
            examples,
            domain,
            EvalBudget(maxSteps = 10000),
            compose.reason == "candidate_limit",
        )
        val consistent = evidence.evaluations.filter { it.consistent }.map { it.candidate }
        if (evidence.status == EvidenceStatus.REFUSED) {
            return C1ReuseResult(
                group, compose.generated, compose.reason, evidence.status.name, consistent.size,
                null, false, 0, heldout.size, "none", "filter_refused",
            )
        }
        val selected = if (predicate != null) {
            consistent.firstOrNull { bodyUsesPredicate(it.body, predicate) } ?: evidence.survivors.firstOrNull()
        } else {
            evidence.survivors.firstOrNull() ?: consistent.firstOrNull()
        }
        val used = selected?.let { bodyUsesPredicate(it.body, predicate) } ?: false
        val correct = if (selected == null) 0 else heldout.count { sample ->
            executeReuse(selected.body, sample.a, sample.b, sample.c, predicate) == sample.label
        }
        val basis = if (predicate != null && consistent.any { bodyUsesPredicate(it.body, predicate) }) {
            "first_consistent_candidate_using_predicate"
        } else {
            "first_survivor_or_consistent"
        }
        return C1ReuseResult(
            group, compose.generated, compose.reason, evidence.status.name, consistent.size,
            selected?.body, used, correct, heldout.size, basis,
            when {
                selected == null -> "no_consistent_candidate"
                evidence.status == EvidenceStatus.AMBIGUOUS -> "ambiguous_selection"
                !used && predicate != null -> "predicate_not_called"
                correct != heldout.size -> "heldout_mismatch"
                else -> null
            },
        )
    }

    fun executePredicate(body: Expr, a: String, b: String): Boolean? {
        val definition = CapabilityDefinition(
            "c1.eval", 1, mapOf("a" to CapType.Str, "b" to CapType.Str), CapType.Bool3, body,
        )
        val result = CapabilityInterpreter(InMemoryResolver(emptyList())).execute(
            definition,
            mapOf("a" to EvalResult.Known(CapValue.Str(a)), "b" to EvalResult.Known(CapValue.Str(b))),
            EvalBudget(maxSteps = 10000),
        )
        return (result as? EvalResult.Known)?.value?.let { (it as? CapValue.Bool)?.value }
    }

    fun executeReuse(body: Expr, a: String, b: String, c: String, predicate: CapabilityDefinition? = null): Boolean? {
        val resolver = InMemoryResolver(listOfNotNull(predicate))
        val definition = CapabilityDefinition(
            "c1.reuse.eval", 1,
            mapOf("a" to CapType.Str, "b" to CapType.Str, "c" to CapType.Str), CapType.Bool3, body,
        )
        val result = CapabilityInterpreter(resolver).execute(
            definition,
            mapOf(
                "a" to EvalResult.Known(CapValue.Str(a)),
                "b" to EvalResult.Known(CapValue.Str(b)),
                "c" to EvalResult.Known(CapValue.Str(c)),
            ),
            EvalBudget(maxSteps = 10000),
        )
        return (result as? EvalResult.Known)?.value?.let { (it as? CapValue.Bool)?.value }
    }

    fun bodyUsesPredicate(body: Expr, predicate: CapabilityDefinition?): Boolean {
        if (predicate == null) return false
        return when (body) {
            is Expr.Call -> (body.id == predicate.id && body.version == predicate.version &&
                body.bodyHash == predicate.contentHash()) || body.args.values.any { bodyUsesPredicate(it, predicate) }
            is Expr.And -> bodyUsesPredicate(body.left, predicate) || bodyUsesPredicate(body.right, predicate)
            is Expr.Or -> bodyUsesPredicate(body.left, predicate) || bodyUsesPredicate(body.right, predicate)
            is Expr.Not -> bodyUsesPredicate(body.value, predicate)
            is Expr.If -> bodyUsesPredicate(body.condition, predicate) || bodyUsesPredicate(body.whenTrue, predicate) ||
                bodyUsesPredicate(body.whenFalse, predicate)
            is Expr.Let -> bodyUsesPredicate(body.value, predicate) || bodyUsesPredicate(body.body, predicate)
            is Expr.Eq -> bodyUsesPredicate(body.left, predicate) || bodyUsesPredicate(body.right, predicate)
            is Expr.Field -> bodyUsesPredicate(body.record, predicate)
            is Expr.Arg, is Expr.Const, is Expr.Ref -> false
        }
    }

    private fun predicateConstants(samples: List<C1PredicateSample>): List<CapValue> =
        (samples.map { it.a } + samples.map { it.b }).distinct().sorted()
            .map { CapValue.Str(it) }

    private fun reuseConstants(samples: List<C1ReuseSample>): List<CapValue> =
        (samples.map { it.a } + samples.map { it.b }).distinct().sorted()
            .map { CapValue.Str(it) }
}
