// 本文件实现：DeepSeek Harness（本会话），2026-11-07。
// 原因：MRX 实时假说实验——先给最小接口与保守骨架，契约 RED 后再实现更新/评价。
package logic.e3.pack02

import kotlinx.serialization.json.*

enum class MRXLiveHypothesisStatus { SUPPORTED, REFUTED, CONFLICTED, UNKNOWN }

data class MRXLiveHypothesis(
    val id: String,
    val origin: String, // BUILTIN_BASELINE / TEACHER_PRIOR / STUDENT_DERIVED
    val status: MRXLiveHypothesisStatus,
    val supportIds: List<String>,
    val refuteIds: List<String>,
    val unknownIds: List<String>,
    val lastChangedVersion: Int,
)

data class MRXLiveObservation(
    val id: String,
    val stepIndex: Int,
    val source: String,
    val feedback: String,
    val counterexamples: Int,
    val budgetRemaining: Int,
    val ledgerRefs: List<String>,
    val candidateCount: Int?,
)

data class MRXLiveSnapshot(
    val version: Int,
    val evidenceDigest: String,
    val hypotheses: List<MRXLiveHypothesis>,
)

data class MRXLiveActionEvaluation(
    val action: String,
    val legal: Boolean,
    /** 该动作预计能区分的假说 ID；null 表示 Unknown，不能当作“能区分”。 */
    val predictedDiscrimination: Set<String>?,
    val evidenceIds: List<String>,
    val expectedCost: Double?,
    val uncertaintyReason: String?,
)

data class MRXLiveDecision(
    val snapshotVersion: Int,
    val chosenAction: String, // action / STOP / REQUEST_TEACHER / INSUFFICIENT_INFORMATION
    val rationale: String,
    val evaluations: List<MRXLiveActionEvaluation>,
)

/** 一次真实行动前后的在线假说记录；before 不得引用 after observation。 */
data class MRXLiveStepRecord(
    val stepIndex: Int,
    val snapshotBefore: MRXLiveSnapshot,
    val decision: MRXLiveDecision,
    val observationAfter: MRXLiveObservation?,
    val snapshotAfter: MRXLiveSnapshot?,
)

/**
 * 教师先验动作模型：只声明“这个动作可能区分哪些假说”，不按反馈码选动作。
 * 真实选择仍由 snapshot 状态与 predictedDiscrimination 决定。
 */
object MRXLiveActionModel {
    fun evaluations(): List<MRXLiveActionEvaluation> = listOf(
        MRXLiveActionEvaluation("KNOWLEDGE", true, setOf("H_STRUCTURAL_COVERAGE"), listOf("teacher-prior:knowledge"), 1.0, null),
        MRXLiveActionEvaluation("DNF", true, setOf("H_STRUCTURAL_COVERAGE", "H_COUNTEREXAMPLE_PRESSURE"), listOf("teacher-prior:dnf"), 2.0, null),
        MRXLiveActionEvaluation("IF", true, setOf("H_STRUCTURAL_COVERAGE", "H_COUNTEREXAMPLE_PRESSURE"), listOf("teacher-prior:if"), 2.0, null),
        MRXLiveActionEvaluation("CNF", true, setOf("H_STRUCTURAL_COVERAGE", "H_COUNTEREXAMPLE_PRESSURE"), listOf("teacher-prior:cnf"), 2.0, null),
        MRXLiveActionEvaluation("BUDGET", true, setOf("H_BUDGET_LIMIT"), listOf("teacher-prior:budget"), 1.0, null),
    )
}

/** 预注册消融：LIVE 读实时快照；FROZEN 只读首次基线后冻结的快照；NO_HYP 走固定中性队列。 */
object MRXLiveAblation {
    private val neutralOrder = listOf("DNF", "IF", "CNF", "KNOWLEDGE", "BUDGET")

    fun decide(
        strategy: String,
        live: MRXLiveSnapshot,
        frozen: MRXLiveSnapshot,
        actions: List<MRXLiveActionEvaluation>,
        remainingBudget: Int,
        tried: Set<String>,
    ): MRXLiveDecision = when (strategy) {
        "LIVE_FROZEN_BELIEF" -> MRXLiveEngine.evaluate(frozen, actions, remainingBudget, tried)
            .copy(rationale = "FROZEN_BELIEF_V${frozen.version}; " + MRXLiveEngine.evaluate(frozen, actions, remainingBudget, tried).rationale)
        "LIVE_NO_HYP" -> {
            val action = neutralOrder.firstOrNull { it !in tried } ?: "STOP"
            MRXLiveDecision(
                live.version, action,
                "NO_HYP_FIXED_NEUTRAL_ORDER; no belief/hypothesis/teacher-prior mapping read",
                emptyList(),
            )
        }
        else -> MRXLiveEngine.evaluate(live, actions, remainingBudget, tried)
    }
}

object MRXLiveTraceWriter {
    fun write(path: java.nio.file.Path, sessionId: String, result: MRX2Result) {
        java.nio.file.Files.createDirectories(path.parent)
        val lines = ArrayList<String>()
        fun emit(obj: JsonObject) { lines += Json.encodeToString(JsonObject.serializer(), obj) }
        emit(buildJsonObject {
            put("type", "run")
            put("sessionId", sessionId)
            put("strategy", result.strategy)
            put("status", result.status)
            put("steps", result.steps.size)
            put("liveSteps", result.liveTrace.size)
            put("newQueries", result.cost.oracleNewQueries)
            put("interpreterCalls", result.cost.interpreterCalls)
            put("generatedCandidates", result.cost.generatedCandidates)
            put("totalWallMillis", result.cost.totalWallMillis)
            put("note", "student-visible evidence only; anonymous session id; no examiner target")
        })
        result.liveTrace.forEach { record ->
            emit(buildJsonObject {
                put("type", "live_step")
                put("sessionId", sessionId)
                put("step", record.stepIndex)
                put("snapshotBefore", snapshotJson(record.snapshotBefore))
                put("decision", buildJsonObject {
                    put("action", record.decision.chosenAction)
                    put("rationale", record.decision.rationale)
                    put("snapshotVersion", record.decision.snapshotVersion)
                    put("evaluations", buildJsonArray {
                        record.decision.evaluations.forEach { e ->
                            add(buildJsonObject {
                                put("action", e.action); put("legal", e.legal)
                                put("predictedDiscrimination", buildJsonArray { e.predictedDiscrimination.orEmpty().sorted().forEach { add(it) } })
                                put("expectedCost", e.expectedCost ?: -1.0)
                            })
                        }
                    })
                })
                put("observation", buildJsonObject {
                    put("id", record.observationAfter?.id ?: "")
                    put("stepIndex", record.observationAfter?.stepIndex ?: -1)
                    put("feedback", record.observationAfter?.feedback ?: "")
                    put("counterexamples", record.observationAfter?.counterexamples ?: 0)
                    put("budgetRemaining", record.observationAfter?.budgetRemaining ?: -1)
                    put("source", record.observationAfter?.source ?: "")
                    put("ledgerRefs", buildJsonArray { record.observationAfter?.ledgerRefs.orEmpty().forEach { add(it) } })
                })
                put("snapshotAfter", record.snapshotAfter?.let { snapshotJson(it) } ?: JsonNull)
            })
        }
        java.nio.file.Files.write(path, lines)
    }

    private fun snapshotJson(snapshot: MRXLiveSnapshot): JsonObject = buildJsonObject {
        put("version", snapshot.version)
        put("evidenceDigest", snapshot.evidenceDigest)
        put("hypotheses", buildJsonArray {
            snapshot.hypotheses.forEach { h ->
                add(buildJsonObject {
                    put("id", h.id); put("origin", h.origin); put("status", h.status.name)
                    put("support", buildJsonArray { h.supportIds.forEach { add(it) } })
                    put("refute", buildJsonArray { h.refuteIds.forEach { add(it) } })
                    put("unknown", buildJsonArray { h.unknownIds.forEach { add(it) } })
                    put("lastChangedVersion", h.lastChangedVersion)
                })
            }
        })
    }
}

object MRXLiveEngine {
    private val ids = listOf(
        "H_STRUCTURAL_COVERAGE",
        "H_COUNTEREXAMPLE_PRESSURE",
        "H_BUDGET_LIMIT",
        "H_UNKNOWN_EXECUTION",
    )

    fun initial(): MRXLiveSnapshot = MRXLiveSnapshot(
        version = 0,
        evidenceDigest = "empty",
        hypotheses = ids.map {
            MRXLiveHypothesis(it, "BUILTIN_BASELINE", MRXLiveHypothesisStatus.UNKNOWN, emptyList(), emptyList(), emptyList(), 0)
        },
    )

    fun update(snapshot: MRXLiveSnapshot, observation: MRXLiveObservation): MRXLiveSnapshot {
        val evidenceId = observation.id
        val updated = snapshot.hypotheses.map { h ->
            val support = h.supportIds.toMutableList()
            val refute = h.refuteIds.toMutableList()
            val unknown = h.unknownIds.toMutableList()
            when (h.id) {
                "H_STRUCTURAL_COVERAGE" -> when {
                    observation.feedback.contains("NO_CANDIDATE") ||
                        observation.feedback.contains("ALL_REFUTED") ||
                        observation.feedback.contains("UNVERIFIED") -> support += evidenceId
                    // PASS_ON_CHALLENGE 只限定当前候选/范围，不能撤销历史结构不足。
                    observation.feedback == "PASS_ON_CHALLENGE" -> unknown += evidenceId
                    else -> unknown += evidenceId
                }
                "H_COUNTEREXAMPLE_PRESSURE" -> when {
                    observation.counterexamples > 0 ||
                        observation.feedback.contains("COUNTEREXAMPLE") -> support += evidenceId
                    // 历史反例事实保留；新候选 PASS 不等于历史反例未发生。
                    observation.feedback == "PASS_ON_CHALLENGE" -> unknown += evidenceId
                    else -> unknown += evidenceId
                }
                "H_BUDGET_LIMIT" -> when {
                    observation.budgetRemaining <= 0 ||
                        observation.feedback.contains("BUDGET_EXHAUSTED") -> support += evidenceId
                    observation.feedback == "PASS_ON_CHALLENGE" -> unknown += evidenceId
                    else -> unknown += evidenceId
                }
                "H_UNKNOWN_EXECUTION" -> when {
                    observation.feedback.contains("UNKNOWN") ||
                        observation.feedback.contains("ERROR") -> support += evidenceId
                    observation.feedback == "PASS_ON_CHALLENGE" -> unknown += evidenceId
                    else -> unknown += evidenceId
                }
            }
            val status = when {
                support.isNotEmpty() && refute.isNotEmpty() -> MRXLiveHypothesisStatus.CONFLICTED
                support.isNotEmpty() -> MRXLiveHypothesisStatus.SUPPORTED
                refute.isNotEmpty() -> MRXLiveHypothesisStatus.REFUTED
                else -> MRXLiveHypothesisStatus.UNKNOWN
            }
            val changed = status != h.status ||
                support.size != h.supportIds.size ||
                refute.size != h.refuteIds.size ||
                unknown.size != h.unknownIds.size
            h.copy(
                status = status,
                supportIds = support.distinct(),
                refuteIds = refute.distinct(),
                unknownIds = unknown.distinct(),
                lastChangedVersion = if (changed) snapshot.version + 1 else h.lastChangedVersion,
            )
        }
        val digest = buildString {
            append("v${snapshot.version + 1}|$evidenceId|")
            updated.forEach { append("${it.id}:${it.status}:${it.supportIds.size}:${it.refuteIds.size};") }
        }
        return MRXLiveSnapshot(snapshot.version + 1, digest, updated)
    }

    fun evaluate(
        snapshot: MRXLiveSnapshot,
        actions: List<MRXLiveActionEvaluation>,
        remainingBudget: Int,
        excludeActions: Set<String> = emptySet(),
    ): MRXLiveDecision {
        val legal = actions.filter {
            it.legal && it.action !in excludeActions &&
                (it.expectedCost == null || it.expectedCost <= remainingBudget)
        }
        if (legal.isEmpty()) {
            return MRXLiveDecision(snapshot.version, "STOP", "NO_LEGAL_AFFORDABLE_ACTION", actions)
        }
        if (snapshot.hypotheses.all { it.status == MRXLiveHypothesisStatus.UNKNOWN }) {
            return MRXLiveDecision(
                snapshot.version, "REQUEST_TEACHER",
                "ALL_HYPOTHESES_UNKNOWN; no testable discrimination model", actions,
            )
        }
        val informative = legal.filter { !it.predictedDiscrimination.isNullOrEmpty() }
        if (informative.isEmpty()) {
            return MRXLiveDecision(
                snapshot.version, "INSUFFICIENT_INFORMATION",
                "NO_ACTION_WITH_KNOWN_DISCRIMINATION; do not guess from feedback code", actions,
            )
        }
        val known = snapshot.hypotheses.filter { it.status != MRXLiveHypothesisStatus.UNKNOWN }.map { it.id }.toSet()
        val chosen = informative.maxWithOrNull(
            compareBy<MRXLiveActionEvaluation> { e ->
                e.predictedDiscrimination.orEmpty().count { it in known }
            }.thenByDescending { e -> -(e.expectedCost ?: Double.MAX_VALUE) }.thenBy { it.action },
        )!!
        val tested = chosen.predictedDiscrimination.orEmpty().intersect(known).sorted()
        return MRXLiveDecision(
            snapshot.version, chosen.action,
            "DISCRIMINATE=${tested.joinToString(",")}; predicted=${chosen.predictedDiscrimination.orEmpty().sorted()}; " +
                "cost=${chosen.expectedCost ?: "UNKNOWN"}",
            actions,
        )
    }
}
