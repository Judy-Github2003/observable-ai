// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A5/A6 只读适配器；公开输入校验、真实 B0 轨迹，不读取评测端 truth/family。
package logic.e3

import logic.capability.*

data class DiagnosticBudget(
    /** B0 的“组合轮数”，不是 AST 嵌套深度。 */
    val maxComposeRounds: Int = 3,
    val maxNodes: Int = 32,
    val maxCandidates: Int = 3000,
    val maxSteps: Int = 10000,
)

data class PublicConstants(val values: List<String>, val source: String)

data class SearchTrace(
    val maxComposeRounds: Int,
    val maxNodes: Int,
    val maxCandidates: Int,
    val maxSteps: Int,
    val generated: Int,
    val exhausted: Boolean,
    val reason: String?,
    val candidateCount: Int,
    /** 仅用于说明组合轮数与 AST 深度不同，不代表搜索边界。 */
    val observedAstMaxDepth: Int,
)

data class FilterTrace(
    val status: String,
    val reason: String?,
    val survivorCount: Int,
    val consistentCount: Int,
    val queryCount: Int,
    /** 来自真实 Filter 的 EvidenceResult.domainSize，不是传入列表长度。 */
    val domainSize: Int,
)

enum class DiagnosticSignalStatus {
    INVALID_INPUT,
    NO_COMPLETE_OBSERVATIONS,
    OBSERVATION_COLLISION,
    IDENTIFIED_WITHIN_BOUNDED_SPACE,
    AMBIGUOUS_WITHIN_BOUNDED_SPACE,
    REFUSED,
    NO_CANDIDATE,
    TRAINING_ONLY_CONSISTENCY,
}

data class DiagnosticSignal(
    val status: DiagnosticSignalStatus,
    val usableObservationIds: List<String>,
    val excludedUnknownValueIds: List<String>,
    val excludedUnlabeledIds: List<String>,
    val collisionReport: CollisionReport,
    val search: SearchTrace?,
    val filter: FilterTrace?,
    val boundedScope: EvidenceScope?,
    val notes: List<String>,
    val constantsSource: String? = null,
    val composedCandidateHashes: List<String> = emptyList(),
    val survivorHashes: List<String> = emptyList(),
    val consistentCandidateHashes: List<String> = emptyList(),
    /** 仅供 A6 计算行为等价类；不写入面向学生的 JSON。 */
    val consistentCandidateBodies: List<Expr> = emptyList(),
    val queryDomainProvided: Boolean = false,
    val suggestedQueryValues: List<Map<String, String>> = emptyList(),
)

class E3B0DiagnosticAdapter {

    fun diagnose(
        schema: VisibleSchema,
        observations: List<Observation>,
        constants: PublicConstants = PublicConstants(emptyList(), "schema_domains"),
        queryDomain: List<Map<String, String>> = emptyList(),
        budget: DiagnosticBudget = DiagnosticBudget(),
        meter: E3UsageMeter? = null,
    ): DiagnosticSignal {
        val validation = validateObservations(schema, observations)
        val collision = ObservableCollisionDetector.detect(schema, observations)
        val inputErrors = ArrayList<ContractError>()
        inputErrors += validation.errors
        inputErrors += validateBudget(budget)
        inputErrors += validateConstants(schema, constants)
        inputErrors += validateQueryDomain(schema, queryDomain)
        if (inputErrors.isNotEmpty()) {
            return emptySignal(
                DiagnosticSignalStatus.INVALID_INPUT, collision, inputErrors.map { "invalid:${it.code}" },
                constants.source,
            )
        }

        // 有完整 Known 向量上的标签冲突时，搜索不能解决逻辑矛盾；保留 A4 证据并停止。
        if (collision.findings.isNotEmpty()) {
            return emptySignal(
                DiagnosticSignalStatus.OBSERVATION_COLLISION, collision,
                listOf("observable_collision_present; composer_search_skipped_to_avoid_pretending_to_resolve_it"),
                constants.source,
            )
        }

        val usable = observations.filter { obs ->
            obs.label != null && obs.visible.values.all { it is ObservedValue.Known }
        }
        if (usable.isEmpty()) {
            return emptySignal(
                DiagnosticSignalStatus.NO_COMPLETE_OBSERVATIONS, collision,
                listOf("no_complete_known_labeled_observations"), constants.source,
            )
        }

        val type = CapType.Record(schema.fieldNames().associateWith { CapType.Str })
        val inputs = mapOf("x" to type)
        val values = constants.values.ifEmpty { schema.fields.values.flatten().distinct() }
        val capConstants = values.distinct().map { CapValue.Str(it) }
        val examples = usable.map { obs ->
            PublicExample(
                mapOf("x" to CapValue.Rec(obs.visible.mapValues { (_, v) -> CapValue.Str((v as ObservedValue.Known).value) })),
                CapValue.Bool(obs.label!!),
            )
        }
        val domainValues = if (queryDomain.isNotEmpty()) {
            queryDomain.map { point ->
                mapOf("x" to CapValue.Rec(schema.fieldNames().associateWith { CapValue.Str(point.getValue(it)) }))
            }
        } else {
            usable.map { obs ->
                mapOf("x" to CapValue.Rec(obs.visible.mapValues { (_, v) -> CapValue.Str((v as ObservedValue.Known).value) }))
            }
        }

        val composed = CapabilityComposer().compose(
            ComposeRequest(inputs, CapType.Bool3, capConstants, emptyList(), ComposeBudget(
                maxDepth = budget.maxComposeRounds,
                maxNodes = budget.maxNodes,
                maxCandidates = budget.maxCandidates,
            )),
        )
        meter?.recordCompose(composed.generated)
        val evidence = CandidateEvidenceFilter(InMemoryResolver(emptyList())).filter(
            inputs,
            composed.candidates,
            examples,
            mapOf("x" to domainValues.map { it.getValue("x") }),
            EvalBudget(maxSteps = budget.maxSteps),
            composed.reason == "candidate_limit",
        )

        meter?.recordFilter(
            estimatedCandidateCount = composed.candidates.size,
            exampleCount = examples.size,
            observedCandidateCount = evidence.evaluations.size,
            observedDomainEvaluations = evidence.survivors.size * evidence.domainSize,
        )
        val consistent = evidence.evaluations.filter { it.consistent }.map { it.candidate }
        val baseStatus = when (evidence.status) {
            EvidenceStatus.IDENTIFIED -> DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE
            EvidenceStatus.AMBIGUOUS -> DiagnosticSignalStatus.AMBIGUOUS_WITHIN_BOUNDED_SPACE
            EvidenceStatus.REFUSED -> if (composed.candidates.isEmpty()) DiagnosticSignalStatus.NO_CANDIDATE else DiagnosticSignalStatus.REFUSED
        }
        val status = if (queryDomain.isEmpty() && baseStatus == DiagnosticSignalStatus.IDENTIFIED_WITHIN_BOUNDED_SPACE) {
            DiagnosticSignalStatus.TRAINING_ONLY_CONSISTENCY
        } else {
            baseStatus
        }
        val notes = ArrayList<String>()
        if (composed.reason == "depth_limit") {
            notes += "compose_round_limit_observed; this does NOT prove the target rule is outside the hypothesis space"
        }
        if (composed.reason == "candidate_limit") {
            notes += "candidate_limit_observed; search truncated before exhaustion"
        }
        notes += "identified_scope_is_bounded_to_current_hypothesis_and_query_domain"
        if (queryDomain.isEmpty()) notes += "query_domain_empty_training_only; no independent query-domain identification"
        if (evidence.domainSize == 0) notes += "filter_domain_empty_or_unavailable; not a complete domain verification"
        if (collision.excludedUnknownValueIds.isNotEmpty()) notes += "unknown_values_excluded_from_examples"
        if (collision.excludedUnlabeledIds.isNotEmpty()) notes += "unlabeled_observations_excluded"

        return DiagnosticSignal(
            status = status,
            usableObservationIds = usable.map { it.observationId },
            excludedUnknownValueIds = collision.excludedUnknownValueIds,
            excludedUnlabeledIds = collision.excludedUnlabeledIds,
            collisionReport = collision,
            search = SearchTrace(
                maxComposeRounds = budget.maxComposeRounds,
                maxNodes = budget.maxNodes,
                maxCandidates = budget.maxCandidates,
                maxSteps = budget.maxSteps,
                generated = composed.generated,
                exhausted = composed.exhausted,
                reason = composed.reason,
                candidateCount = composed.candidates.size,
                observedAstMaxDepth = composed.candidates.maxOfOrNull { it.depth } ?: 0,
            ),
            filter = FilterTrace(
                status = evidence.status.name,
                reason = evidence.reason,
                survivorCount = evidence.survivors.size,
                consistentCount = consistent.size,
                queryCount = evidence.distinguishingQueries.size,
                domainSize = evidence.domainSize,
            ),
            boundedScope = EvidenceScope(
                hypothesisSpace = "typed IR composeRounds<=${budget.maxComposeRounds}, nodes<=${budget.maxNodes}, library=[]",
                observationDomain = "${usable.size} complete Known labeled observations",
                queryDomain = if (queryDomain.isEmpty()) "training observations only; no independent query domain" else "${evidence.domainSize} evaluated public query points",
            ),
            notes = notes,
            constantsSource = constants.source,
            composedCandidateHashes = composed.candidates.map { it.astHash },
            survivorHashes = evidence.survivors.map { it.astHash },
            consistentCandidateHashes = consistent.map { it.astHash },
            consistentCandidateBodies = consistent.map { it.body },
            queryDomainProvided = queryDomain.isNotEmpty(),
            suggestedQueryValues = evidence.distinguishingQueries.mapNotNull { point ->
                val rec = point["x"] as? CapValue.Rec ?: return@mapNotNull null
                if (rec.fields.values.any { it !is CapValue.Str }) return@mapNotNull null
                rec.fields.mapValues { (_, v) -> (v as CapValue.Str).value }
            },
        )
    }

    private fun emptySignal(
        status: DiagnosticSignalStatus,
        collision: CollisionReport,
        notes: List<String>,
        constantsSource: String,
    ) = DiagnosticSignal(
        status = status,
        usableObservationIds = emptyList(),
        excludedUnknownValueIds = collision.excludedUnknownValueIds,
        excludedUnlabeledIds = collision.excludedUnlabeledIds,
        collisionReport = collision,
        search = null,
        filter = null,
        boundedScope = null,
        notes = notes,
        constantsSource = constantsSource,
    )

    private fun validateBudget(budget: DiagnosticBudget): List<ContractError> {
        val errors = ArrayList<ContractError>()
        if (budget.maxComposeRounds < 1) errors += ContractError("invalid_budget", "maxComposeRounds < 1")
        if (budget.maxNodes < 1) errors += ContractError("invalid_budget", "maxNodes < 1")
        if (budget.maxCandidates < 1) errors += ContractError("invalid_budget", "maxCandidates < 1")
        if (budget.maxSteps < 1) errors += ContractError("invalid_budget", "maxSteps < 1")
        return errors
    }

    private fun validateConstants(schema: VisibleSchema, constants: PublicConstants): List<ContractError> {
        val errors = ArrayList<ContractError>()
        if (constants.source.isBlank()) errors += ContractError("blank_constants_source", "constants source is blank")
        val allowed = schema.fields.values.flatten().toSet()
        for (value in constants.values) {
            if (value !in allowed) errors += ContractError("constant_not_in_public_domain", "constant $value is not in schema domain")
        }
        return errors
    }

    private fun validateQueryDomain(schema: VisibleSchema, domain: List<Map<String, String>>): List<ContractError> {
        val errors = ArrayList<ContractError>()
        if (domain.size > 256) {
            errors += ContractError("query_domain_too_large", "B0 public query domain is capped at 256 points; got ${domain.size}")
        }
        val seen = HashSet<List<Pair<String, String>>>()
        for ((index, point) in domain.withIndex()) {
            val missing = schema.fieldNames().filter { it !in point }
            val extra = point.keys.filter { it !in schema.fields.keys }
            if (missing.isNotEmpty()) errors += ContractError("query_missing_field", "query[$index] missing ${missing.joinToString()}")
            if (extra.isNotEmpty()) errors += ContractError("query_unknown_field", "query[$index] extra ${extra.joinToString()}")
            for (field in schema.fieldNames()) {
                val value = point[field] ?: continue
                if (value !in schema.fields.getValue(field)) {
                    errors += ContractError("query_invalid_value", "query[$index].$field=$value outside domain", field = field)
                }
            }
            val key = schema.fieldNames().map { it to (point[it] ?: "") }
            if (!seen.add(key)) errors += ContractError("duplicate_query_point", "query[$index] duplicates an earlier point")
        }
        return errors
    }
}
