// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：E3-A9 评测端从可信行动事件重建观察集合；不信任学生自报新增记录。
package logic.e3

object A9TrustedReplay {

    fun reconstruct(
        schema: VisibleSchema,
        original: List<Observation>,
        event: A9TrustedActionEvent,
        context: A9ExecutionContext,
    ): Pair<List<Observation>, String?> {
        if (event.runId != context.runId) return original to "event_run_id_mismatch"
        val stageIndex = context.stageOrder.indexOf(event.stage)
        val hiddenIndex = context.stageOrder.indexOf(context.hiddenScoringStage)
        if (stageIndex < 0 || hiddenIndex < 0 || stageIndex >= hiddenIndex) {
            return original to "event_stage_not_before_hidden_scoring"
        }
        val rebuilt = original.toMutableList()
        val added = event.recordedObservation
        when (event.actionKind) {
            A9ActionKind.NO_ACTION -> if (added != null) return original to "no_action_must_not_add_observation"
            A9ActionKind.REQUEST_PUBLIC_INSTANCE -> {
                val sampleId = event.requestedSampleId ?: return original to "request_missing_sample_id"
                if (sampleId !in context.allowedQueryIds) return original to "request_sample_not_allowed"
                if (added == null) return original to "request_missing_observation"
                if (rebuilt.any { it.observationId == added.observationId }) return original to "duplicate_observation_id"
            }
            A9ActionKind.REPEAT_LABEL -> {
                val objectId = event.repeatedObjectId ?: return original to "repeat_missing_object_id"
                if (rebuilt.none { it.objectId == objectId }) return original to "repeat_object_not_in_evidence"
                if (added == null) return original to "repeat_missing_observation"
                if (added.objectId != objectId) return original to "repeat_object_id_mismatch"
            }
        }
        if (added != null) rebuilt += added
        val validation = validateObservations(schema, rebuilt)
        if (!validation.ok) return original to "reconstructed_evidence_invalid:" + validation.errors.joinToString(",") { it.code }
        if (context.evidenceIds != rebuilt.map { it.observationId }) return original to "context_evidence_ids_mismatch"
        return rebuilt to null
    }
}
