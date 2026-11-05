// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A1 输入校验与规范编码；非法输入返回机器可读错误，不做诊断推断。
package logic.e3

import kotlinx.serialization.json.*

data class ContractError(
    val code: String,
    val message: String,
    val observationId: String? = null,
    val field: String? = null,
)

data class ObservationValidation(
    val errors: List<ContractError>,
    val validObservations: List<Observation>,
) {
    val ok: Boolean get() = errors.isEmpty()
}

/** 校验 schema 本身；字段/取值域必须非空且非空白。 */
fun validateSchema(schema: VisibleSchema): List<ContractError> {
    val errors = ArrayList<ContractError>()
    if (schema.fields.isEmpty()) errors += ContractError("schema_empty", "schema has no fields")
    for ((name, values) in schema.fields) {
        if (name.isBlank()) errors += ContractError("schema_blank_field", "blank field name")
        if (values.isEmpty() || values.any { it.isBlank() }) {
            errors += ContractError("schema_empty_domain", "field $name has empty/blank value domain", field = name)
        }
    }
    return errors
}

/**
 * 校验一批观察：
 * - 字段缺失与未知值严格区分：缺少 key 报 missing_field，ObservedValue.Unknown 合法；
 * - 观察 ID 全局唯一；同一 objectId + round 视为重复记录；
 * - 标签为 null 合法（未观察），不能当成 false。
 */
fun validateObservations(schema: VisibleSchema, observations: List<Observation>): ObservationValidation {
    val errors = ArrayList<ContractError>()
    errors += validateSchema(schema)
    val schemaErrors = errors.isNotEmpty()

    val ids = HashSet<String>()
    // 同一 objectId+round 允许多个独立来源；同一来源在同一轮重复写只能算重复记录。
    val sourceRounds = HashSet<Triple<String, Int, String>>()
    val valid = ArrayList<Observation>()

    for (observation in observations) {
        val before = errors.size
        if (observation.observationId.isBlank()) {
            errors += ContractError("blank_observation_id", "observationId is blank")
        } else if (!ids.add(observation.observationId)) {
            errors += ContractError("duplicate_observation_id", "duplicate observationId", observation.observationId)
        }
        if (observation.objectId.isBlank()) {
            errors += ContractError("blank_object_id", "objectId is blank", observation.observationId)
        }
        if (observation.provenance.sourceId.isBlank()) {
            errors += ContractError("blank_source_id", "sourceId is blank", observation.observationId)
        }
        if (observation.round < 1) {
            errors += ContractError("invalid_round", "round must be >= 1", observation.observationId)
        } else if (!sourceRounds.add(Triple(observation.objectId, observation.round, observation.provenance.sourceId))) {
            errors += ContractError(
                "duplicate_source_round",
                "duplicate source record for objectId+round+sourceId",
                observation.observationId,
            )
        }
        if (!schemaErrors) {
            for (field in schema.fieldNames()) {
                if (field !in observation.visible) {
                    errors += ContractError("missing_field", "missing visible field $field", observation.observationId, field)
                }
            }
            for ((field, value) in observation.visible) {
                val domain = schema.fields[field]
                if (domain == null) {
                    errors += ContractError("unknown_field", "field $field is not in schema", observation.observationId, field)
                } else if (value is ObservedValue.Known && value.value !in domain) {
                    errors += ContractError(
                        "invalid_value",
                        "value ${value.value} is outside the public domain of $field",
                        observation.observationId,
                        field,
                    )
                }
            }
        }
        if (errors.size == before) valid += observation
    }
    return ObservationValidation(errors, valid)
}

/** 契约 JSON：字段/可见值键排序，未知值单独编码；不含 worldKind/hiddenFeature/targetAst。 */
object ContractJson {

    fun encodeSchema(schema: VisibleSchema): String = buildJsonObject {
        put("fields", buildJsonObject {
            schema.fieldNames().forEach { name ->
                put(name, buildJsonArray { schema.fields.getValue(name).sorted().forEach { add(it) } })
            }
        })
    }.toString()

    fun encodeObservation(observation: Observation): String = buildJsonObject {
        put("observationId", observation.observationId)
        put("objectId", observation.objectId)
        put("visible", buildJsonObject {
            observation.visible.keys.sorted().forEach { name ->
                put(name, when (val value = observation.visible.getValue(name)) {
                    is ObservedValue.Known -> buildJsonObject {
                        put("status", "known")
                        put("value", value.value)
                    }
                    ObservedValue.Unknown -> buildJsonObject { put("status", "unknown") }
                })
            }
        })
        if (observation.label == null) put("label", JsonNull) else put("label", observation.label)
        put("provenance", buildJsonObject {
            put("sourceId", observation.provenance.sourceId)
            put("sourceKind", observation.provenance.sourceKind.name)
            put("requestedByStudent", observation.provenance.requestedByStudent)
            if (observation.provenance.reliableDeterministicLabels == null) {
                put("reliableDeterministicLabels", JsonNull)
            } else {
                put("reliableDeterministicLabels", observation.provenance.reliableDeterministicLabels)
            }
        })
        put("round", observation.round)
        if (observation.batchId == null) put("batchId", JsonNull) else put("batchId", observation.batchId)
    }.toString()

    /** 按 observationId 排序，保证同一集合的编码稳定，不受输入顺序影响。 */
    fun encodeObservations(observations: List<Observation>): String = buildJsonArray {
        observations.sortedBy { it.observationId }.forEach { add(Json.parseToJsonElement(encodeObservation(it))) }
    }.toString()

    fun encodeReport(report: DiagnosticReport): String = buildJsonObject {
        put("findings", buildJsonArray {
            report.findings.forEach { finding ->
                addJsonObject {
                    put("kind", finding.kind.name)
                    put("evidenceIds", buildJsonArray { finding.evidenceIds.forEach { add(it) } })
                    put("scope", buildJsonObject {
                        put("hypothesisSpace", finding.scope.hypothesisSpace)
                        put("observationDomain", finding.scope.observationDomain)
                        if (finding.scope.queryDomain == null) put("queryDomain", JsonNull) else put("queryDomain", finding.scope.queryDomain)
                    })
                    put("sourceHypotheses", buildJsonArray { finding.sourceHypotheses.forEach { add(it) } })
                    if (finding.observationConclusion == null) put("observationConclusion", JsonNull) else put("observationConclusion", finding.observationConclusion)
                    put("assumptions", buildJsonArray { finding.assumptions.forEach { add(it) } })
                    put("alternativeExplanations", buildJsonArray { finding.alternativeExplanations.forEach { add(it) } })
                }
            }
        })
        put("proposedProbe", when (val probe = report.proposedProbe) {
            null, ProbeRequest.NoAction -> buildJsonObject { put("action", "NO_ACTION") }
            is ProbeRequest.RepeatLabel -> buildJsonObject { put("action", "REPEAT_LABEL"); put("objectId", probe.objectId) }
            is ProbeRequest.RequestPublicInstance -> buildJsonObject { put("action", "REQUEST_PUBLIC_INSTANCE"); put("poolId", probe.poolId) }
        })
        put("stoppedBecause", report.stoppedBecause)
        put("strategySource", report.strategySource)
    }.toString()

    fun encodeCollisionReport(report: CollisionReport): String = buildJsonObject {
        put("findings", buildJsonArray {
            report.findings.forEach { finding ->
                addJsonObject {
                    put("featureKey", finding.featureKey)
                    put("participants", buildJsonArray {
                        finding.participants.forEach { p ->
                            addJsonObject {
                                put("observationId", p.observationId)
                                put("objectId", p.objectId)
                                put("sourceId", p.sourceId)
                                put("round", p.round)
                                put("label", p.label)
                            }
                        }
                    })
                    put("scope", buildJsonObject {
                        put("hypothesisSpace", finding.scope.hypothesisSpace)
                        put("observationDomain", finding.scope.observationDomain)
                        if (finding.scope.queryDomain == null) put("queryDomain", JsonNull) else put("queryDomain", finding.scope.queryDomain)
                    })
                }
            }
        })
        put("excludedUnknownValueIds", buildJsonArray { report.excludedUnknownValueIds.forEach { add(it) } })
        put("excludedUnlabeledIds", buildJsonArray { report.excludedUnlabeledIds.forEach { add(it) } })
        put("validationErrors", buildJsonArray {
            report.validationErrors.forEach { error ->
                addJsonObject {
                    put("code", error.code)
                    put("message", error.message)
                    if (error.observationId == null) put("observationId", JsonNull) else put("observationId", error.observationId)
                    if (error.field == null) put("field", JsonNull) else put("field", error.field)
                }
            }
        })
    }.toString()
}
