package logic.e3.pack02

/** R3.1 engineering instrumentation. Defaults preserve LEGACY behavior exactly. */
enum class MRX2BR31Schedule { LEGACY, QUOTA_IF_FIRST }
enum class MRX2BR31Family { LITERAL, AND, OR, IF }

/** Total quota is exactly maxCandidates in the preregistered 30k experiment.
 * Empty reservations cannot be silently loaned to another family.
 */
data class MRX2BR31Quotas(
    val literals: Int = 256,
    val andTerms: Int = 1024,
    val orTerms: Int = 23000,
    val ifTerms: Int = 5720,
) {
    init { require(listOf(literals, andTerms, orTerms, ifTerms).all { it >= 0 }) }
    val total: Int get() = literals + andTerms + orTerms + ifTerms
    fun limit(family: MRX2BR31Family): Int = when (family) {
        MRX2BR31Family.LITERAL -> literals
        MRX2BR31Family.AND -> andTerms
        MRX2BR31Family.OR -> orTerms
        MRX2BR31Family.IF -> ifTerms
    }
}

data class MRX2BR31FamilyCounters(
    var attempted: Long = 0,
    var admitted: Long = 0,
    var globalCapRejected: Long = 0,
    var familyQuotaRejected: Long = 0,
    var duplicateRejected: Long = 0,
    var nodeRejected: Long = 0,
    var canonicalRejected: Long = 0,
    var typeRejected: Long = 0,
)

class MRX2BR31Counters {
    val byFamily: MutableMap<MRX2BR31Family, MRX2BR31FamilyCounters> =
        linkedMapOf<MRX2BR31Family, MRX2BR31FamilyCounters>().apply {
            MRX2BR31Family.entries.forEach { put(it, MRX2BR31FamilyCounters()) }
        }
    var interpreterCalls: Long = 0 // atom predictions + candidate TRAIN evals; excludes grading
    val attempts: Long get() = byFamily.values.sumOf { it.attempted }
    val admitted: Long get() = byFamily.values.sumOf { it.admitted }
    fun forFamily(family: MRX2BR31Family): MRX2BR31FamilyCounters = byFamily.getValue(family)
}
