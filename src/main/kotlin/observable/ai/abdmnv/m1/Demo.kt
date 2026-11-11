package observable.ai.abdmnv.m1

/** Executable public demonstration: the student cannot see the held-out test labels. */
object Demo {
    @JvmStatic
    fun main(args: Array<String>) {
        val world = "public_world_01"
        fun evidence(obj: String, color: String): Evidence = Evidence(
            id = "ev_$obj", worldId = world, objectId = obj,
            schemaVersion = 1, round = 1, sourceId = "public_sensor",
            sourceKind = "EXAMINER_VERIFIED", independence = "INDEPENDENT",
            values = mapOf("color" to ValueResult.Known(TypedValue.EnumSymbol(color, "color")))
        )
        fun view(objects: Map<String, String>): StudentView = StudentView(
            worldId = world, schemaVersion = 1,
            knownObjects = objects.keys.toList(),
            attributeSorts = mapOf("color" to Sort.Enum("color")),
            pairChannels = emptySet(),
            observations = objects.mapValues { (obj, color) -> evidence(obj, color) }
        )
        // Labels are delivered only for the four public training objects.
        val trainView = view(linkedMapOf("o_01" to "red","o_02" to "blue","o_03" to "red","o_04" to "blue"))
        val labels = listOf(
            TrainingLabel("o_01",true),TrainingLabel("o_02",false),
            TrainingLabel("o_03",true),TrainingLabel("o_04",false)
        )
        val studentResult = MinimalCategoryStudent().learn(trainView,labels)
        check(studentResult.status == "STUDENT_COMPOSED_LIMITED")
        val concept = checkNotNull(studentResult.concept)
        // These new objects are NOT delivered to the student during induction.
        val testView = view(linkedMapOf("o_05" to "red", "o_06" to "blue"))
        val publicOracle = TargetSpec(
            Slot("slot_01",ConceptKind.CATEGORY,Sort.Bool),
            listOf(listOf("o_05"),listOf("o_06")),
            listOf(Truth.TRUE,Truth.FALSE)
        )
        val scored = ReferenceScorer(coverageMinimum=1.0).score(concept,publicOracle,testView,PublicConceptCatalog())
        println("mode=PUBLIC_DEMO_NOT_INDEPENDENT_EXAM")
        println("student_status=${studentResult.status}")
        println("candidate_count=${studentResult.evaluatedCandidates}")
        println("survivor_count=${studentResult.survivingCandidates}")
        println("student_concept_body=${concept.body}")
        println("student_concept_hash=${concept.ref.hash}")
        println("training_evidence=${studentResult.evidenceIds}")
        println("heldout_status=${scored.status}")
        println("heldout_correct=${scored.correct}/${publicOracle.truth.size}")
        println("heldout_coverage=${scored.coverage}")
        check(scored.status == ScoreStatus.MATCH)
    }
}
