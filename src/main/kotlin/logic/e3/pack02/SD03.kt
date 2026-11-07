// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-03 真实学生来源 -> typed anti-unification -> 可执行宏的最小闭环；
// 不接收 truth lambda，只通过 StudyQueryController/MRX2Ledger 获取 challenge 证据。
package logic.e3.pack02

import logic.capability.*

data class SD03StudentSource(
    val sourceId: String,
    val runId: String,
    val body: Expr,
    val canonical: String,
    val validationScope: String,
    val evidenceIds: List<String>,
    val challengeChecks: Int,
    val challengeCounterexamples: Int,
)

data class SD03Run(
    val sources: List<SD03StudentSource>,
    val pattern: SD02GeneralizedPattern?,
    val macro: CapabilityDefinition?,
    val macroCall: EvalResult?,
    val note: String,
)

data class SD03Diagnostic(
    val category: String,
    val path: String,
    val leftType: String,
    val rightType: String,
)

object SD03StudentLoop {
    fun findSource(
        runId: String,
        attributes: List<String>,
        definitions: List<CapabilityDefinition>,
        training: List<StudyCase>,
        challengeController: StudyQueryController,
        challengeInputs: List<Map<String, String>>,
        strategy: MRX2BStrategy = MRX2BStrategy.PAIRWISE,
    ): SD03StudentSource? = findSources(
        runId, attributes, definitions, training, challengeController, challengeInputs, strategy, limit = 1,
    ).firstOrNull()

    /** 有界来源池：最多返回 limit 个真实 challenge-pass 程序，供事后结构与诊断使用。 */
    fun findSources(
        runId: String,
        attributes: List<String>,
        definitions: List<CapabilityDefinition>,
        training: List<StudyCase>,
        challengeController: StudyQueryController,
        challengeInputs: List<Map<String, String>>,
        strategy: MRX2BStrategy = MRX2BStrategy.PAIRWISE,
        limit: Int = 4,
    ): List<SD03StudentSource> {
        val space = MRX2BSpace(attributes, definitions, training, strategy).generate()
        val cost = MRX2Cost()
        val ledger = MRX2Ledger(challengeInputs, challengeController, challengeInputs.size, cost)
        val out = ArrayList<SD03StudentSource>()
        for (candidate in space.trace.candidates(MRX2BStage.RETAINED)) {
            if (out.size >= limit) break
            val validation = ledger.validate(
                candidate.canonical,
                { input -> Pack02Knowledge.predict(candidate.body, input, definitions) },
                "SD03_CHALLENGE", challengeInputs.size, cost,
            )
            if (validation.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                out += SD03StudentSource(
                    sourceId = "$runId:${candidate.canonical.hashCode().toUInt().toString(16)}",
                    runId = runId,
                    body = candidate.body,
                    canonical = candidate.canonical,
                    validationScope = "CHALLENGE(${challengeInputs.size})",
                    evidenceIds = ledger.entries().map { "ledger:${it.inputKey}:${it.queryIndex}:${it.source}" },
                    challengeChecks = validation.checks,
                    challengeCounterexamples = validation.counterexamples,
                )
            }
        }
        return out
    }

    fun diagnose(a: Expr, b: Expr, path: String = "$"): SD03Diagnostic {
        if (a == b) return SD03Diagnostic("MATCH", path, a::class.simpleName ?: "?", b::class.simpleName ?: "?")
        if (a::class != b::class) {
            return SD03Diagnostic("ROOT_MISMATCH", path, a::class.simpleName ?: "?", b::class.simpleName ?: "?")
        }
        return when {
            a is Expr.Call && b is Expr.Call -> {
                when {
                    a.id != b.id || a.version != b.version || a.bodyHash != b.bodyHash ->
                        SD03Diagnostic("CALL_ID_OR_HASH_MISMATCH", "$path.call", "Call", "Call")
                    a.args.keys != b.args.keys ->
                        SD03Diagnostic("ARITY_MISMATCH", "$path.args", a.args.keys.toString(), b.args.keys.toString())
                    else -> a.args.keys.sorted().map { k -> diagnose(a.args.getValue(k), b.args.getValue(k), "$path.args['$k']") }
                        .firstOrNull { it.category != "MATCH" } ?: SD03Diagnostic("MATCH", path, "Call", "Call")
                }
            }
            a is Expr.Eq && b is Expr.Eq -> diagnose(a.left, b.left, "$path.left")
                .takeIf { it.category != "MATCH" } ?: diagnose(a.right, b.right, "$path.right")
            a is Expr.And && b is Expr.And -> diagnose(a.left, b.left, "$path.left")
                .takeIf { it.category != "MATCH" } ?: diagnose(a.right, b.right, "$path.right")
            a is Expr.Or && b is Expr.Or -> diagnose(a.left, b.left, "$path.left")
                .takeIf { it.category != "MATCH" } ?: diagnose(a.right, b.right, "$path.right")
            a is Expr.Not && b is Expr.Not -> diagnose(a.value, b.value, "$path.value")
            a is Expr.If && b is Expr.If -> diagnose(a.condition, b.condition, "$path.condition")
                .takeIf { it.category != "MATCH" } ?: diagnose(a.whenTrue, b.whenTrue, "$path.whenTrue")
                .takeIf { it.category != "MATCH" } ?: diagnose(a.whenFalse, b.whenFalse, "$path.whenFalse")
            a is Expr.Arg && b is Expr.Arg -> if (a.type != b.type) SD03Diagnostic("TYPE_MISMATCH", path, a.type.toString(), b.type.toString()) else SD03Diagnostic("ARG_MISMATCH", path, a.name, b.name)
            a is Expr.Const && b is Expr.Const -> SD03Diagnostic("CONSTANT_MISMATCH", path, "Const", "Const")
            else -> SD03Diagnostic("UNSUPPORTED_NODE", path, a::class.simpleName ?: "?", b::class.simpleName ?: "?")
        }
    }

    private fun sha256(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun defaultValue(type: CapType): CapValue = when (type) {
        CapType.Str -> CapValue.Str("x")
        CapType.Bool3 -> CapValue.Bool(true)
        CapType.Int64 -> CapValue.I64(0)
        is CapType.Record -> CapValue.Rec(type.fields.mapValues { defaultValue(it.value) })
    }

    fun buildMacroAndCall(
        sources: List<SD03StudentSource>,
        definitions: List<CapabilityDefinition>,
    ): SD03Run {
        if (sources.size != 2) return SD03Run(sources, null, null, null, "REQUIRES_EXACTLY_TWO_SOURCES")
        val verified = sources.map {
            SD02VerifiedSource(
                it.sourceId, 1, sha256(it.canonical),
                it.body, C8Domain.attributes.associateWith { CapType.Str }, it.validationScope, it.evidenceIds,
            )
        }
        val pattern = SD02AntiUnify.generalize(verified)
        if (pattern.degenerate) return SD03Run(sources, pattern, null, null, "DEGENERATE_PATTERN:${pattern.rejectReason}")
        val params = (pattern.holes + pattern.freeArgs).associate { it.id to it.expectedType }
        val macro = CapabilityDefinition(
            "sd03.macro", 1, params, CapType.Bool3, pattern.pattern,
        )
        val checker = CapabilityTypeChecker(InMemoryResolver(definitions + macro))
        if (!checker.check(macro).ok) return SD03Run(sources, pattern, macro, null, "MACRO_CLOSURE_OR_TYPE_FAIL")
        // 机制验证：真实 Expr.Call，但尚未走持久化注册/激活（G2 不由本函数声称）。
        val call = Expr.Call(
            macro.id, macro.version, macro.contentHash(),
            params.keys.associate { id -> id to Expr.Arg("call_$id", params.getValue(id)) },
        )
        val caller = CapabilityDefinition(
            "sd03.caller", 1,
            params.keys.associate { "call_$it" to params.getValue(it) },
            CapType.Bool3, call,
        )
        val resolver = InMemoryResolver(definitions + macro)
        val result = if (CapabilityTypeChecker(resolver).check(caller).ok) {
            val values = params.mapValues { (_, type) -> EvalResult.Known(defaultValue(type)) }
            CapabilityInterpreter(resolver).execute(caller, values, EvalBudget(maxSteps = 1000))
        } else null
        return SD03Run(sources, pattern, macro, result, "MECHANISM_CALL_ONLY_NOT_REGISTERED")
    }
}
