// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：SD-01 结构发现开发实验；先给保守骨架，契约 RED 后再接入真实候选/验证。
package logic.e3.pack02

import logic.capability.*

data class SD01CandidateRecord(
    val candidateId: String,
    val canonical: String,
    val origin: String,
    val generatorOperation: String,
    val status: String,
    val challengeChecks: Int,
    val challengeCounterexamples: Int,
    val evidenceIds: List<String>,
    val validationScope: String,
    val structureKind: String,
)

data class SD01Structure(
    val structureId: String,
    val version: Int,
    val kind: String, // PROGRAM_INSTANCE / PARAMETRIC_MACRO / GENERATION_RULE
    val parameterTypes: List<String>,
    val body: Expr,
    val sourceCandidateId: String,
    val validationScope: String,
    val origin: String,
)

data class SD01Run(
    val condition: String,
    val generated: Int,
    val retained: Int,
    val candidates: List<SD01CandidateRecord>,
    val structure: SD01Structure?,
    val transferChecks: Int,
    val transferCorrect: Int,
    val note: String,
)

object SD01Discovery {
    fun discover(
        attributes: List<String>,
        definitions: List<CapabilityDefinition>,
        training: List<StudyCase>,
        queryInputs: List<Map<String, String>>,
        challengeInputs: List<Map<String, String>>,
        truth: (Map<String, String>) -> Boolean,
        strategy: MRX2BStrategy = MRX2BStrategy.PAIRWISE,
    ): SD01Run {
        val space = MRX2BSpace(attributes, definitions, training, strategy).generate()
        val retained = space.trace.candidates(MRX2BStage.RETAINED)
        val records = ArrayList<SD01CandidateRecord>()
        var chosen: SD01Structure? = null
        for (candidate in retained) {
            var checks = 0
            var counter = 0
            val evidence = ArrayList<String>()
            for (input in challengeInputs) {
                checks++
                val predicted = Pack02Knowledge.predict(candidate.body, input, definitions)
                evidence += "challenge:${C8Domain.key(input)}"
                if (predicted != truth(input)) counter++
            }
            val pass = counter == 0 && checks == challengeInputs.size
            val record = SD01CandidateRecord(
                candidateId = candidate.canonical,
                canonical = candidate.canonical,
                origin = "STUDENT_DERIVED",
                generatorOperation = "typed-compose:${strategy.wire}",
                status = if (pass) "CHALLENGE_PASS" else "REFUTED",
                challengeChecks = checks,
                challengeCounterexamples = counter,
                evidenceIds = evidence,
                validationScope = "CHALLENGE",
                structureKind = "PROGRAM_INSTANCE",
            )
            records += record
            if (pass && chosen == null) {
                chosen = SD01Structure(
                    structureId = "sd01-${candidate.canonical.hashCode().toUInt().toString(16)}",
                    version = 1,
                    kind = "PROGRAM_INSTANCE",
                    parameterTypes = emptyList(),
                    body = candidate.body,
                    sourceCandidateId = candidate.canonical,
                    validationScope = "CHALLENGE",
                    origin = "STUDENT_DERIVED",
                )
            }
        }
        val note = when {
            chosen != null -> "candidate_passed_challenge_scope_only; closed instance not a reusable macro"
            retained.isEmpty() -> "NO_CANDIDATE_IN_BOUND; not a proof of inexpressibility"
            else -> "retained candidates all failed challenge; no structure registered"
        }
        return SD01Run(
            "STRUCTURE_DISCOVERY", space.generated, retained.size, records, chosen,
            0, 0, note,
        )
    }

    fun transfer(
        structure: SD01Structure?,
        cases: List<StudyCase>,
        definitions: List<CapabilityDefinition>,
    ): Pair<Int, Int> {
        if (structure == null) return 0 to 0
        var correct = 0
        for (case in cases) {
            if (Pack02Knowledge.predict(structure.body, case.values, definitions) == case.label) correct++
        }
        return cases.size to correct
    }
}
