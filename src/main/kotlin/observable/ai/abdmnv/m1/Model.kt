package observable.ai.abdmnv.m1

/** Compact M1 reference types; adapter to B0 CapabilityIr/Registry is DeepSeek's integration task. */
sealed interface Sort {
    data object Bool : Sort
    data object Int : Sort
    data object Object : Sort
    data class Enum(val name: String) : Sort
    data class Set(val element: Sort) : Sort
}

enum class ConceptKind { ATTRIBUTE, CATEGORY, RELATION, COMPOSITE }

data class ConceptRef(val id: String, val version: Int, val hash: String)
data class Param(val name: String, val sort: Sort)

data class Concept(
    val ref: ConceptRef,
    val kind: ConceptKind,
    val output: Sort,
    val params: List<Param>,
    val body: Term,
    val worldScope: String? = null,
    val evidenceIds: Set<String> = emptySet(),
    val dependencies: Set<ConceptRef> = emptySet(),
)

sealed interface Term {
    data class Var(val name: String) : Term
    data class Constant(val value: TypedValue) : Term
    data class Attribute(val channel: String, val of: Term) : Term
    data class PairRelation(val channel: String, val left: Term, val right: Term) : Term
    data class Call(val ref: ConceptRef, val args: List<Term>) : Term
    data class Not(val child: Term) : Term
    data class And(val left: Term, val right: Term) : Term
    data class Or(val left: Term, val right: Term) : Term
    data class Eq(val left: Term, val right: Term) : Term
    data class LessThan(val left: Term, val right: Term) : Term
    data class Member(val value: Term, val set: Term) : Term
    data class Exists(val variable: String, val body: Term) : Term
    data class ForAll(val variable: String, val body: Term) : Term
    data class CountAtLeast(val variable: String, val body: Term, val minimum: Long) : Term
}

data class Evidence(
    val id: String,
    val worldId: String,
    val objectId: String,
    val schemaVersion: Int,
    val round: Int,
    val sourceId: String,
    val sourceKind: String,
    val independence: String,
    val values: Map<String, ValueResult>,
    val signedByExaminer: Boolean = false,
)

data class PairKey(val channel: String, val left: String, val right: String)

data class StudentView(
    val worldId: String,
    val schemaVersion: Int,
    val knownObjects: List<String>,
    val attributeSorts: Map<String, Sort>,
    val pairChannels: Set<String>,
    val observations: Map<String, Evidence>,
    val measuredPairs: Map<PairKey, ValueResult> = emptyMap(),
) {
    init {
        require(observations.values.all { it.worldId == worldId }) { "WORLD_SCOPE_VIOLATION" }
        require(knownObjects.size == knownObjects.toSet().size) { "DUPLICATE_OBJECT" }
    }
}

interface ConceptCatalog {
    fun resolve(ref: ConceptRef): Concept?
}

class PublicConceptCatalog(defs: List<Concept> = emptyList()) : ConceptCatalog {
    private val versions = mutableMapOf<Pair<String, Int>, Concept>()
    init { defs.forEach(::add) }
    fun add(concept: Concept) {
        val key = concept.ref.id to concept.ref.version
        require(key !in versions) { "VERSION_IMMUTABLE_VIOLATION" }
        versions[key] = concept
    }
    override fun resolve(ref: ConceptRef): Concept? = versions[ref.id to ref.version]
        ?.takeIf { it.ref.hash == ref.hash }
}

/** Public-tests-only counter; actual query billing should be enforced in the examiner gateway. */
class MeasurementMeter {
    private val accessed = mutableListOf<String>()
    fun record(id: String) { accessed += id }
    val accesses: List<String> get() = accessed.toList()
}
