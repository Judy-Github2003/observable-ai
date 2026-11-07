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

object SD03StudentLoop {
    fun findSource(
        runId: String,
        attributes: List<String>,
        definitions: List<CapabilityDefinition>,
        training: List<StudyCase>,
        challengeController: StudyQueryController,
        challengeInputs: List<Map<String, String>>,
        strategy: MRX2BStrategy = MRX2BStrategy.PAIRWISE,
    ): SD03StudentSource? {
        val space = MRX2BSpace(attributes, definitions, training, strategy).generate()
        val cost = MRX2Cost()
        val ledger = MRX2Ledger(challengeInputs, challengeController, challengeInputs.size, cost)
        for (candidate in space.trace.candidates(MRX2BStage.RETAINED)) {
            val validation = ledger.validate(
                candidate.canonical,
                { input -> Pack02Knowledge.predict(candidate.body, input, definitions) },
                "SD03_CHALLENGE", challengeInputs.size, cost,
            )
            if (validation.status == MRX2ValidationStatus.PASS_ON_CHALLENGE) {
                return SD03StudentSource(
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
        return null
    }

    fun buildMacroAndCall(
        sources: List<SD03StudentSource>,
        definitions: List<CapabilityDefinition>,
    ): SD03Run {
        if (sources.size != 2) return SD03Run(sources, null, null, null, "REQUIRES_EXACTLY_TWO_SOURCES")
        val verified = sources.map {
            SD02VerifiedSource(
                it.sourceId, 1, it.canonical.hashCode().toString(),
                it.body, emptyMap(), it.validationScope, it.evidenceIds,
            )
        }
        val pattern = SD02AntiUnify.generalize(verified)
        if (pattern.degenerate) return SD03Run(sources, pattern, null, null, "DEGENERATE_PATTERN:${pattern.rejectReason}")
        val macro = CapabilityDefinition(
            "sd03.macro", 1,
            pattern.holes.associate { it.id to it.expectedType },
            CapType.Bool3, pattern.pattern,
        )
        val checker = CapabilityTypeChecker(InMemoryResolver(listOf(macro)))
        if (!checker.check(macro).ok) return SD03Run(sources, pattern, macro, null, "MACRO_TYPE_REJECTED")
        // 机制验证：真实 Expr.Call，但尚未走持久化注册/激活（G2 不由本函数声称）。
        val call = Expr.Call(
            macro.id, macro.version, macro.contentHash(),
            pattern.holes.associate { h ->
                h.id to Expr.Arg("call_${h.id}", h.expectedType)
            },
        )
        val caller = CapabilityDefinition(
            "sd03.caller", 1,
            pattern.holes.associate { "call_${it.id}" to it.expectedType },
            CapType.Bool3, call,
        )
        val resolver = InMemoryResolver(listOf(macro))
        val result = if (CapabilityTypeChecker(resolver).check(caller).ok) {
            val values = pattern.holes.associate { h ->
                "call_${h.id}" to EvalResult.Known(CapValue.Str("x"))
            }
            CapabilityInterpreter(resolver).execute(caller, values, EvalBudget(maxSteps = 1000))
        } else null
        return SD03Run(sources, pattern, macro, result, "MECHANISM_CALL_ONLY_NOT_REGISTERED")
    }
}
