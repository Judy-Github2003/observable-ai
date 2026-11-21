// OA-NEXT-01 FoundationPilot: minimal Kotlin learning/verification loop.
// Author: DeepSeek Harness, 2026-11-17. Reason: FL-PILOT-01 requires a running
// Kotlin learning module that induces a candidate rule/program from public
// observations, verifies it against those observations, and answers unseen
// questions from accepted state. This is intentionally small and deterministic;
// it does not call the model and it never executes model-generated code.
package observable.ai.foundation

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val VERIFIER_ID = "foundation-pilot-kotlin-v1"

private val json = Json { prettyPrint = false }

data class WorldStudy(
    val worldId: String,
    val domain: String,
    val instruction: String,
    val observations: List<JsonObject>,
)

data class QueryRow(
    val queryId: String,
    val worldId: String,
    val domain: String,
    val payload: JsonObject,
)

data class Validation(
    val passed: Boolean,
    val tested: Int,
    val failure: String?,
)

private fun JsonObject.requireString(key: String): String =
    this[key]?.jsonPrimitive?.content ?: error("missing string field $key")

private fun JsonObject.requireInt(key: String): Int =
    this[key]?.jsonPrimitive?.int ?: error("missing int field $key")

private fun JsonObject.requireArray(key: String): JsonArray =
    this[key]?.jsonArray ?: error("missing array field $key")

private fun readJsonLines(path: String): List<JsonObject> =
    File(path).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun sha256File(path: String): String = sha256(File(path).readBytes())

sealed class LearnedRule {
    abstract val kind: String
    abstract fun expression(): JsonObject
    abstract fun predict(query: QueryRow): JsonElement
    abstract fun validate(study: WorldStudy): Validation
}

private class SemanticsRule(
    val parents: Map<String, Set<String>>,
    val instances: Map<String, String>,
    val negatives: Set<Pair<String, String>>,
) : LearnedRule() {
    override val kind = "isa_closure"

    private fun reachable(from: String, to: String): Boolean {
        if (from == to) return true
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(from)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            for (parent in parents[current].orEmpty()) {
                if (parent == to) return true
                queue.add(parent)
            }
        }
        return false
    }

    override fun expression(): JsonObject = buildJsonObject {
        put("kind", kind)
        putJsonObject("parents") {
            parents.toSortedMap().forEach { (child, supers) ->
                putJsonArray(child) { supers.sorted().forEach { add(it) } }
            }
        }
        putJsonObject("instances") {
            instances.toSortedMap().forEach { (entity, klass) -> put(entity, klass) }
        }
        putJsonArray("negatives") {
            negatives.sortedWith(compareBy({ it.first }, { it.second })).forEach { (entity, klass) ->
                add(buildJsonArray { add(entity); add(klass) })
            }
        }
    }

    override fun predict(query: QueryRow): JsonElement {
        val entity = query.payload.requireString("entity")
        val klass = query.payload.requireString("class")
        if (negatives.contains(entity to klass)) return JsonPrimitive("FALSE")
        val instanceClass = instances[entity]
        if (instanceClass != null && reachable(instanceClass, klass)) return JsonPrimitive("TRUE")
        return JsonPrimitive("UNKNOWN")
    }

    override fun validate(study: WorldStudy): Validation {
        var tested = 0
        for (observation in study.observations) {
            tested++
            when (observation.requireString("kind")) {
                "isa" -> {
                    val sub = observation.requireString("sub")
                    val sup = observation.requireString("sup")
                    if (!parents[sub].orEmpty().contains(sup)) {
                        return Validation(false, tested, "isa edge missing: $sub -> $sup")
                    }
                }
                "instance_of" -> {
                    val entity = observation.requireString("entity")
                    val klass = observation.requireString("class")
                    if (instances[entity] != klass) {
                        return Validation(false, tested, "instance_of mismatch: $entity -> $klass")
                    }
                }
                "not_instance_of" -> {
                    val entity = observation.requireString("entity")
                    val klass = observation.requireString("class")
                    if (!negatives.contains(entity to klass)) {
                        return Validation(false, tested, "explicit negative missing: $entity !-> $klass")
                    }
                }
                else -> return Validation(false, tested, "unknown semantics observation kind")
            }
        }
        return Validation(true, tested, null)
    }
}

private class LogicRule(
    val edges: Set<Pair<String, String>>,
    val negatives: Set<Pair<String, String>>,
) : LearnedRule() {
    override val kind = "transitive_closure"

    private fun reachable(from: String, to: String): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(from)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == to) return true
            if (!seen.add(current)) continue
            for ((left, right) in edges) {
                if (left == current) queue.add(right)
            }
        }
        return false
    }

    override fun expression(): JsonObject = buildJsonObject {
        put("kind", kind)
        putJsonArray("edges") {
            edges.sortedWith(compareBy({ it.first }, { it.second })).forEach { (left, right) ->
                add(buildJsonArray { add(left); add(right) })
            }
        }
        putJsonArray("negatives") {
            negatives.sortedWith(compareBy({ it.first }, { it.second })).forEach { (left, right) ->
                add(buildJsonArray { add(left); add(right) })
            }
        }
    }

    override fun predict(query: QueryRow): JsonElement {
        val left = query.payload.requireString("left")
        val right = query.payload.requireString("right")
        if (negatives.contains(left to right)) return JsonPrimitive("FALSE")
        if (reachable(left, right)) return JsonPrimitive("TRUE")
        return JsonPrimitive("UNKNOWN")
    }

    override fun validate(study: WorldStudy): Validation {
        var tested = 0
        for (observation in study.observations) {
            tested++
            when (observation.requireString("kind")) {
                "R" -> {
                    val left = observation.requireString("left")
                    val right = observation.requireString("right")
                    if (!edges.contains(left to right)) {
                        return Validation(false, tested, "edge missing: R($left,$right)")
                    }
                }
                "not_R" -> {
                    val left = observation.requireString("left")
                    val right = observation.requireString("right")
                    if (!negatives.contains(left to right)) {
                        return Validation(false, tested, "negative missing: !R($left,$right)")
                    }
                    if (reachable(left, right)) {
                        return Validation(false, tested, "contradiction: edge and negative both hold")
                    }
                }
                else -> return Validation(false, tested, "unknown logic observation kind")
            }
        }
        return Validation(true, tested, null)
    }
}

private class MathRule(val p: Int, val a: Int, val b: Int) : LearnedRule() {
    override val kind = "affine_mod"

    override fun expression(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("p", p)
        put("a", a)
        put("b", b)
        put("program", "f(x) = (a*x + b) mod p")
    }

    override fun predict(query: QueryRow): JsonElement {
        val x = query.payload.requireInt("x")
        return JsonPrimitive(((a * x + b) % p + p) % p)
    }

    override fun validate(study: WorldStudy): Validation {
        var tested = 0
        for (observation in study.observations) {
            tested++
            val x = observation.requireInt("x")
            val y = observation.requireInt("y")
            if (((a * x + b) % p + p) % p != y) {
                return Validation(false, tested, "affine mismatch at x=$x")
            }
        }
        return Validation(true, tested, null)
    }
}

private class CodeRule(val parity: Int, val a: Int, val b: Int) : LearnedRule() {
    override val kind = "filter_affine"

    private fun apply(values: List<Int>): List<Int> =
        values.filter { ((it % 2) + 2) % 2 == parity }.map { a * it + b }

    override fun expression(): JsonObject = buildJsonObject {
        put("kind", kind)
        put("parity", parity)
        put("a", a)
        put("b", b)
        put("program", "xs.filter(x % 2 == parity).map(x -> a*x + b)")
    }

    override fun predict(query: QueryRow): JsonElement {
        val input = query.payload.requireArray("input").map { it.jsonPrimitive.int }
        return buildJsonArray { apply(input).forEach { add(it) } }
    }

    override fun validate(study: WorldStudy): Validation {
        var tested = 0
        for (observation in study.observations) {
            tested++
            val input = observation.requireArray("input").map { it.jsonPrimitive.int }
            val output = observation.requireArray("output").map { it.jsonPrimitive.int }
            if (apply(input) != output) {
                return Validation(false, tested, "code behavior mismatch for input=$input")
            }
        }
        return Validation(true, tested, null)
    }
}

private data class Induction(
    val rule: LearnedRule?,
    val status: String,
    val reason: String?,
    val candidateCount: Int,
)

private fun induceSemantics(study: WorldStudy): Induction {
    val parents = LinkedHashMap<String, MutableSet<String>>()
    val instances = LinkedHashMap<String, String>()
    val negatives = LinkedHashSet<Pair<String, String>>()
    for (observation in study.observations) {
        when (observation.requireString("kind")) {
            "isa" -> parents.getOrPut(observation.requireString("sub")) { LinkedHashSet() }
                .add(observation.requireString("sup"))
            "instance_of" -> instances[observation.requireString("entity")] = observation.requireString("class")
            "not_instance_of" -> negatives.add(
                observation.requireString("entity") to observation.requireString("class")
            )
        }
    }
    val rule = SemanticsRule(parents.mapValues { it.value.toSet() }, instances, negatives)
    val validation = rule.validate(study)
    return if (validation.passed) Induction(rule, "ACCEPTED", null, 1)
    else Induction(null, "REJECTED", validation.failure, 1)
}

private fun induceLogic(study: WorldStudy): Induction {
    val edges = LinkedHashSet<Pair<String, String>>()
    val negatives = LinkedHashSet<Pair<String, String>>()
    for (observation in study.observations) {
        when (observation.requireString("kind")) {
            "R" -> edges.add(observation.requireString("left") to observation.requireString("right"))
            "not_R" -> negatives.add(observation.requireString("left") to observation.requireString("right"))
        }
    }
    val rule = LogicRule(edges, negatives)
    val validation = rule.validate(study)
    return if (validation.passed) Induction(rule, "ACCEPTED", null, 1)
    else Induction(null, "REJECTED", validation.failure, 1)
}

private fun induceMath(study: WorldStudy): Induction {
    val p = Regex("mod\\s+(\\d+)").find(study.instruction)?.groupValues?.get(1)?.toIntOrNull()
        ?: return Induction(null, "REJECTED", "cannot read modulus p from instruction", 0)
    if (p < 5) return Induction(null, "REJECTED", "invalid modulus", 0)
    val pairs = study.observations.map { it.requireInt("x") to it.requireInt("y") }
    val candidates = ArrayList<MathRule>()
    for (a in 1 until p) {
        for (b in 0 until p) {
            if (pairs.all { (x, y) -> ((a * x + b) % p + p) % p == y }) candidates.add(MathRule(p, a, b))
        }
    }
    if (candidates.isEmpty()) return Induction(null, "REJECTED", "no consistent affine parameters", 0)
    if (candidates.size > 1) return Induction(null, "REJECTED", "ambiguous affine parameters: ${candidates.size}", candidates.size)
    val rule = candidates.single()
    val validation = rule.validate(study)
    return if (validation.passed) Induction(rule, "ACCEPTED", null, 1)
    else Induction(null, "REJECTED", validation.failure, 1)
}

private fun induceCode(study: WorldStudy): Induction {
    val pairs = study.observations.map {
        it.requireArray("input").map { value -> value.jsonPrimitive.int } to
            it.requireArray("output").map { value -> value.jsonPrimitive.int }
    }
    val candidates = ArrayList<CodeRule>()
    for (parity in 0..1) {
        for (a in intArrayOf(1, 3, 5)) {
            for (b in -4..4) {
                val rule = CodeRule(parity, a, b)
                if (pairs.all { (input, output) -> rule.validate(WorldStudy(study.worldId, study.domain, study.instruction, listOf(
                    buildJsonObject {
                        putJsonArray("input") { input.forEach { add(it) } }
                        putJsonArray("output") { output.forEach { add(it) } }
                    }
                ))).passed }) {
                    candidates.add(rule)
                }
            }
        }
    }
    if (candidates.isEmpty()) return Induction(null, "REJECTED", "no consistent filter/affine program", 0)
    if (candidates.size > 1) return Induction(null, "REJECTED", "ambiguous programs: ${candidates.size}", candidates.size)
    val rule = candidates.single()
    val validation = rule.validate(study)
    return if (validation.passed) Induction(rule, "ACCEPTED", null, 1)
    else Induction(null, "REJECTED", validation.failure, 1)
}

private fun induce(study: WorldStudy): Induction = when (study.domain) {
    "semantics" -> induceSemantics(study)
    "logic" -> induceLogic(study)
    "mathematics" -> induceMath(study)
    "code_behavior" -> induceCode(study)
    else -> Induction(null, "REJECTED", "unsupported domain: ${study.domain}", 0)
}

private fun proposal(study: WorldStudy, induction: Induction, validation: Validation): JsonObject = buildJsonObject {
    put("proposal_id", "P-${study.worldId}")
    put("world_id", study.worldId)
    put("domain", study.domain)
    put("kind", induction.rule?.kind ?: "none")
    put("expression", induction.rule?.expression() ?: JsonNull)
    put("origin", "SYSTEM_DERIVATION")
    put("evidence_ids", buildJsonArray {
        study.observations.indices.forEach { add("${study.worldId}-obs-${it + 1}") }
    })
    putJsonArray("dependencies") {}
    put("status", induction.status)
    put("scope", "World ${study.worldId}; validated on ${validation.tested} public observations; no claim outside the generator family.")
    put("version", 1)
    put("verifier_id", VERIFIER_ID)
    put("validation", buildJsonObject {
        put("passed", validation.passed)
        put("tested", validation.tested)
        put("failure", validation.failure)
    })
    put("candidate_count", induction.candidateCount)
    put("rejection_reason", induction.reason)
}

private fun writeLines(path: String, rows: List<JsonObject>) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(rows.joinToString("\n") { it.toString() } + if (rows.isEmpty()) "" else "\n", Charsets.UTF_8)
}

fun main(args: Array<String>) {
    val options = LinkedHashMap<String, String>()
    var index = 0
    while (index < args.size) {
        if (args[index].startsWith("--") && index + 1 < args.size) {
            options[args[index].removePrefix("--")] = args[index + 1]
            index += 2
        } else {
            index++
        }
    }
    val studyPath = options["study"] ?: error("--study is required")
    val questionPath = options["questions"] ?: error("--questions is required")
    val outDir = options["out"] ?: error("--out is required")

    val studies = readJsonLines(studyPath).map {
        WorldStudy(
            worldId = it.requireString("world_id"),
            domain = it.requireString("domain"),
            instruction = it.requireString("instruction"),
            observations = it.requireArray("observations").map { row -> row.jsonObject },
        )
    }
    val queries = readJsonLines(questionPath).map {
        QueryRow(
            queryId = it.requireString("query_id"),
            worldId = it.requireString("world_id"),
            domain = it.requireString("domain"),
            payload = it["query"]!!.jsonObject,
        )
    }

    val candidates = ArrayList<JsonObject>()
    val accepted = ArrayList<JsonObject>()
    val rules = LinkedHashMap<String, LearnedRule>()
    val validations = LinkedHashMap<String, Validation>()
    for (study in studies) {
        val induction = induce(study)
        val validation = induction.rule?.validate(study)
            ?: Validation(false, 0, induction.reason)
        validations[study.worldId] = validation
        candidates.add(proposal(study, induction, validation))
        if (induction.rule != null && induction.status == "ACCEPTED") {
            accepted.add(proposal(study, induction, validation))
            rules[study.worldId] = induction.rule
        }
    }

    val predictions = ArrayList<JsonObject>()
    for (query in queries) {
        val rule = rules[query.worldId]
        val answer: JsonElement = when {
            rule != null -> rule.predict(query)
            query.domain in setOf("semantics", "logic") -> JsonPrimitive("UNKNOWN")
            else -> JsonNull
        }
        predictions.add(buildJsonObject {
            put("query_id", query.queryId)
            put("answer", answer)
            put("arm", "C")
            put("proposal_id", if (rule != null) "P-${query.worldId}" else "NONE")
        })
    }

    File(outDir).mkdirs()
    writeLines(File(outDir, "candidates.jsonl").path, candidates)
    writeLines(File(outDir, "accepted_state.jsonl").path, accepted)
    writeLines(File(outDir, "predictions.jsonl").path, predictions)

    val manifest = buildJsonObject {
        put("module", "observable.ai.foundation.FoundationPilot")
        put("verifier_id", VERIFIER_ID)
        put("generated_at", Instant.now().toString())
        put("study_file_sha256", sha256File(studyPath))
        put("question_file_sha256", sha256File(questionPath))
        put("worlds", studies.size)
        put("queries", queries.size)
        put("accepted", accepted.size)
        put("rejected", candidates.size - accepted.size)
        putJsonObject("accepted_worlds") {
            rules.keys.sorted().forEach { put(it, "ACCEPTED") }
        }
    }
    File(outDir, "manifest.json").writeText(manifest.toString() + "\n", Charsets.UTF_8)
    println("FOUNDATION_PILOT_DONE worlds=${studies.size} queries=${queries.size} accepted=${accepted.size} rejected=${candidates.size - accepted.size}")
}
