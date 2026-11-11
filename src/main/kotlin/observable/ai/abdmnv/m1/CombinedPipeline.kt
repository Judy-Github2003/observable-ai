// 修复轮（DeepSeek Harness, 2026-11-09）：端到端公开链路。
// ledger（按 world 读取证据）→ StudentView → MinimalCategoryStudent 归纳 → 版本存储（哈希绑定/激活）
// → 槽位提交 → ReferenceScorer 评分 → 计数汇总。弃答路径同样走同一条链路。
package observable.ai.abdmnv.m1

object CombinedPipeline {
    data class Step(
        val worldId: String,
        val studentStatus: String,
        val conceptId: String?,
        val conceptHash: String?,
        val stored: Boolean,
        val activated: Boolean,
        val slotSubmitted: Boolean,
        val verdict: ScoreStatus?,
        val summary: CombinedSubmissionSummary,
        val detail: String,
        val concept: Concept? = null,
    )

    /** 从账本构建 StudentView：只读取指定 world 的证据，并且默认只接受 EXAMINER_VERIFIED。 */
    fun viewFromLedger(
        worldId: String,
        ledger: CombinedEvidenceLedger,
        schemaVersion: Int,
        attributeSorts: Map<String, Sort>,
        pairChannels: Set<String>,
    ): StudentView {
        val objects = ledger.objectsIn(worldId)
        val observations = objects.associateWith { obj ->
            val values = attributeSorts.keys.associateWith { channel ->
                ledger.read(worldId, obj, channel, schemaVersion, requireVerified = true)
            }
            Evidence(
                id = "ev_${worldId}_$obj",
                worldId = worldId,
                objectId = obj,
                schemaVersion = schemaVersion,
                round = 1,
                sourceId = "ledger",
                sourceKind = "EXAMINER_VERIFIED",
                independence = "INDEPENDENT",
                values = values,
                signedByExaminer = true,
            )
        }
        return StudentView(worldId, schemaVersion, objects, attributeSorts, pairChannels, observations)
    }

    fun run(
        worldId: String,
        ledger: CombinedEvidenceLedger,
        store: CombinedConceptVersionStore,
        board: CombinedSlotBoard,
        slotIndex: Int,
        schemaVersion: Int,
        attributeSorts: Map<String, Sort>,
        pairChannels: Set<String>,
        labels: List<TrainingLabel>,
        target: TargetSpec,
    ): Step {
        val view = viewFromLedger(worldId, ledger, schemaVersion, attributeSorts, pairChannels)
        val induction = MinimalCategoryStudent().learn(view, labels)
        val concept = induction.concept
        if (concept == null) {
            board.abstain(slotIndex)
            return Step(worldId, induction.status, null, null, false, false, false, null, board.summary(),
                "ABSTAIN:${induction.status}")
        }
        val storedHash = store.propose(concept)
        val activateCode = store.activate(concept.ref)
        val submitCode = board.submitConcept(slotIndex, concept)
        val catalog = PublicConceptCatalog(listOf(concept))
        val score = ReferenceScorer().score(concept, target, view, catalog)
        if (submitCode == null && activateCode == "OK") board.recordVerdict(slotIndex, score.status)
        return Step(
            worldId = worldId,
            studentStatus = induction.status,
            conceptId = concept.ref.id,
            conceptHash = storedHash,
            stored = true,
            activated = activateCode == "OK",
            slotSubmitted = submitCode == null,
            verdict = score.status,
            summary = board.summary(),
            detail = "activate=$activateCode submit=${submitCode ?: "OK"} score=${score.status}",
            concept = concept,
        )
    }
}

