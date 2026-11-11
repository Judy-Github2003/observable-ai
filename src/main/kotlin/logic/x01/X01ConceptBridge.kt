// 本文件：X01-SLICE1 概念桥接（M1 Concept -> B0 CapabilityDefinition 候选）。
// 来源：Observable-AI-X01-SLICE1-Start-2026-11-11.zip 内同名文件；判定逻辑保持一致。
// 修改：DeepSeek Harness，2026-11-11；原因：接入真实仓库模块（vendored M1 + logic.capability），
//       仅补文件头与中文说明；所有“拒绝”分支与 provenance 字段含义未改。
// 安全边界：本类从不验证、激活或执行候选；只有宿主登记的验证套件才能触发 B0 激活。
package logic.x01

import logic.capability.CapType
import logic.capability.CapValue
import logic.capability.CapabilityDefinition
import logic.capability.CanonicalJson
import logic.capability.EvalResult
import logic.capability.Expr
import observable.ai.abdmnv.m1.*
import java.security.MessageDigest

/**
 * X01-SLICE1: strict and intentionally narrow bridge.
 * This bridge NEVER verifies, activates or executes a candidate on its own.
 * Only a host-controlled B0 verification suite may authorize activation.
 *
 * Scope of this prototype: one-object Bool category, a finite total attribute record,
 * no dependencies/calls/quantifiers and no Unknown/Error values at the B0 boundary.
 * M1's ERROR/UNKNOWN logic and B0's lazy Boolean logic are NOT generally equivalent.
 */
class X01Reject(val reasonCode: String) : IllegalArgumentException(reasonCode)

private fun reject(code: String): Nothing = throw X01Reject(code)

private fun sha256Text(s: String): String = MessageDigest.getInstance("SHA-256")
    .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class X01Candidate(
    val definition: CapabilityDefinition,
    val source: ConceptRef,
    val worldId: String,
    val schemaVersion: Int,
    val channels: Map<String, Sort>,
    val evidenceIds: Set<String>,
    val provenanceJson: String,
    val b0ContentHash: String,
)

class X01ConceptBridge(
    private val sourceStore: CombinedConceptVersionStore,
    private val evidenceLedger: CombinedEvidenceLedger,
) {
    fun compile(source: ConceptRef, view: StudentView): X01Candidate {
        val concept = sourceStore.get(source) ?: reject("SOURCE_HASH_OR_REF_INVALID")
        if (!sourceStore.executable(source)) reject("SOURCE_NOT_ACTIVE_OR_DEPENDENCY_INVALID")
        // First slice does not attempt to prove global/multi-world applicability.
        if (concept.worldScope == null || concept.worldScope != view.worldId) reject("WORLD_SCOPE_UNSUPPORTED")
        if (concept.kind != ConceptKind.CATEGORY || concept.output != Sort.Bool ||
            concept.params.size != 1 || concept.params.single().sort != Sort.Object
        ) reject("UNSUPPORTED_SIGNATURE")
        if (concept.dependencies.isNotEmpty()) reject("DEPENDENCIES_NOT_SUPPORTED_IN_SLICE1")
        if (concept.evidenceIds.isEmpty()) reject("NO_SOURCE_EVIDENCE")
        val found = evidenceLedger.all().associateBy { it.id }
        for (id in concept.evidenceIds) {
            val record = found[id] ?: reject("SOURCE_EVIDENCE_NOT_FOUND")
            if (record.worldId != view.worldId) reject("EVIDENCE_WORLD_MISMATCH")
            if (record.schemaVersion != view.schemaVersion) reject("EVIDENCE_SCHEMA_MISMATCH")
        }
        val checked = M1TypeChecker(view, PublicConceptCatalog(listOf(concept))).check(concept)
        if (!checked.ok) reject("M1_TYPECHECK:${checked.reason}")
        val referenced = linkedSetOf<String>()
        fun scan(term: Term) {
            when (term) {
                is Term.Attribute -> {
                    if (term.of != Term.Var(concept.params.single().name)) reject("UNSUPPORTED_ATTRIBUTE_RECEIVER")
                    referenced += term.channel
                }
                is Term.Not -> scan(term.child)
                is Term.And -> { scan(term.left); scan(term.right) }
                is Term.Or -> { scan(term.left); scan(term.right) }
                is Term.Eq -> { scan(term.left); scan(term.right) }
                is Term.Var, is Term.Constant -> Unit
                else -> reject("UNSUPPORTED_TERM:${term.javaClass.simpleName}")
            }
        }
        scan(concept.body)
        if (referenced.isEmpty()) reject("NO_OBJECT_ATTRIBUTE_USED")
        val channels = referenced.associateWith { channel ->
            view.attributeSorts[channel] ?: reject("ATTRIBUTE_NOT_IN_SCHEMA")
        }
        fun valueType(sort: Sort): CapType = when (sort) {
            Sort.Bool -> CapType.Bool3
            Sort.Int -> CapType.Int64
            is Sort.Enum -> CapType.Str
            else -> reject("UNSUPPORTED_ATTRIBUTE_SORT")
        }
        val recordType = CapType.Record(channels.mapValues { valueType(it.value) })
        fun expression(t: Term): Expr = when (t) {
            is Term.Attribute -> Expr.Field(Expr.Arg("item", recordType), t.channel)
            is Term.Constant -> Expr.Const(when (val v = t.value) {
                is TypedValue.Bool -> CapValue.Bool(v.value)
                is TypedValue.IntNumber -> CapValue.I64(v.value)
                is TypedValue.EnumSymbol -> CapValue.Str(v.value)
                else -> reject("UNSUPPORTED_CONSTANT")
            })
            is Term.Eq -> Expr.Eq(expression(t.left), expression(t.right))
            is Term.And -> Expr.And(expression(t.left), expression(t.right))
            is Term.Or -> Expr.Or(expression(t.left), expression(t.right))
            is Term.Not -> Expr.Not(expression(t.child))
            else -> reject("UNSUPPORTED_TERM")
        }
        val schemaFingerprint = sha256Text(view.schemaVersion.toString() + "|" +
            channels.toSortedMap().entries.joinToString("|") { (key, sort) -> "$key=$sort" })
        val provenanceEvidence = concept.evidenceIds.sorted().joinToString(",") { id ->
            val e = found.getValue(id)
            "{" + "\"id\":${CanonicalJson.string(id)}," +
                "\"source_kind\":${CanonicalJson.string(e.sourceKind)}," +
                "\"source_id\":${CanonicalJson.string(e.sourceId)}," +
                "\"signed_by_examiner\":${e.signedByExaminer}}"
        }
        val id = "x01_" + sha256Text("${view.worldId}|${source.id}|${source.version}|${source.hash}|" +
            schemaFingerprint + "|" + concept.evidenceIds.sorted().joinToString("|")).take(32)
        val definition = CapabilityDefinition(id, 1, mapOf("item" to recordType), CapType.Bool3, expression(concept.body))
        // B0 contentHash and M1 ConceptRef.hash are intentionally NOT interchangeable.
        val b0Hash = definition.contentHash()
        val provenance = "{" +
            "\"source_system\":\"ABDMNV_M1\"," +
            "\"source_id\":${CanonicalJson.string(source.id)}," +
            "\"source_version\":${source.version}," +
            "\"source_content_hash\":${CanonicalJson.string(source.hash)}," +
            "\"source_world\":${CanonicalJson.string(view.worldId)}," +
            "\"schema_version\":${view.schemaVersion}," +
            "\"schema_fingerprint\":${CanonicalJson.string(schemaFingerprint)}," +
            "\"source_evidence\":[${provenanceEvidence}]," +
            "\"b0_content_hash\":${CanonicalJson.string(b0Hash)}," +
            "\"trust_status\":\"UNVERIFIED_BY_B0\"}"
        return X01Candidate(definition, source, view.worldId, view.schemaVersion,
            channels.toMap(), concept.evidenceIds.toSet(), provenance, b0Hash)
    }

    /** Materialize a concrete observed object; missing/Unknown/Error never becomes false. */
    fun materialize(candidate: X01Candidate, view: StudentView, objectId: String): Map<String, EvalResult> {
        if (view.worldId != candidate.worldId || view.schemaVersion != candidate.schemaVersion)
            reject("MATERIALIZATION_SCOPE_MISMATCH")
        if (objectId !in view.knownObjects) reject("OBJECT_NOT_IN_VIEW")
        val obs = view.observations[objectId] ?: reject("OBSERVATION_NOT_FOUND")
        if (obs.worldId != view.worldId || obs.schemaVersion != view.schemaVersion)
            reject("OBSERVATION_SCOPE_MISMATCH")
        val fields = candidate.channels.mapValues { (channel, sort) ->
            val measured = obs.values[channel] as? ValueResult.Known
                ?: reject("INCOMPLETE_OR_UNKNOWN_ATTRIBUTE:$channel")
            when (sort) {
                Sort.Bool -> (measured.value as? TypedValue.Bool)?.let { CapValue.Bool(it.value) }
                Sort.Int -> (measured.value as? TypedValue.IntNumber)?.let { CapValue.I64(it.value) }
                is Sort.Enum -> (measured.value as? TypedValue.EnumSymbol)
                    ?.takeIf { it.enumId == sort.name }?.let { CapValue.Str(it.value) }
                else -> null
            } ?: reject("ATTRIBUTE_VALUE_TYPE_MISMATCH:$channel")
        }
        return mapOf("item" to EvalResult.Known(CapValue.Rec(fields)))
    }
}


