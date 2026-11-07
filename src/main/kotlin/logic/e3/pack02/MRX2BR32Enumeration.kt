package logic.e3.pack02

/** R3.2: only an explicitly opted-in experiment mode changes R3.1 enumeration. */
enum class MRX2BR32IfPolicy { R31_LEXICOGRAPHIC, ROTATING_DIRECT, ROTATING_WITH_NESTED }

/** Roles are deliberately named: outer condition, then TRUE branch, then FALSE branch. */
data class MRX2BR32Direct(val condition: Int, val whenTrue: Int, val whenFalse: Int)

/** A small, predeclared nested-If grammar; not a general exhaustive program generator. */
data class MRX2BR32Nested(
    val outerCondition: Int,
    val trueCondition: Int,
    val falseCondition: Int,
    val trueWhenTrue: Int,
    val trueWhenFalse: Int,
    val falseWhenTrue: Int,
    val falseWhenFalse: Int,
)

object MRX2BR32Enumeration {
    /** All n^3 direct triples exactly once; n=24 produces 13,824 triples.
     *  Lexicographic matches R3.1 QUOTA_IF24 order exactly.
     *  Rotating gives every literal a chance in every role in the first n records,
     *  rather than exhausting one fixed whenTrue branch. Fixed +1/+2 shifts
     *  avoid structurally trivial identical-role triples in the initial round.
     */
    fun direct(n: Int, policy: MRX2BR32IfPolicy): Sequence<MRX2BR32Direct> = sequence {
        require(n in 2..64)
        when (policy) {
            MRX2BR32IfPolicy.R31_LEXICOGRAPHIC ->
                for (a in 0 until n) for (b in 0 until n) for (c in 0 until n)
                    yield(MRX2BR32Direct(c, a, b))
            MRX2BR32IfPolicy.ROTATING_DIRECT, MRX2BR32IfPolicy.ROTATING_WITH_NESTED ->
                for (offset in 0 until n) for (shift in 0 until n) for (i in 0 until n)
                    yield(MRX2BR32Direct((i + offset + 1) % n, i, (i + shift + 2) % n))
        }
    }

    /** Deterministic constrained sampler for the two-branch nested shape:
     * If(c0, If(c1,a,b), If(c2,c,d)). No examiner target or labels enter here.
     * This is NOT an enumeration of all n^7 possible nested trees; its 3 degrees
     * of freedom make full nested-grammar coverage claims invalid.
     */
    fun nested(n: Int): Sequence<MRX2BR32Nested> = sequence {
        require(n in 2..64)
        for (s1 in 0 until n) for (s2 in 0 until n) for (i in 0 until n) {
            yield(MRX2BR32Nested(
                outerCondition = i,
                trueCondition = (i + s1 + 1) % n,
                falseCondition = (i + s2 + 2) % n,
                trueWhenTrue = (i + 2*s1 + 3) % n,
                trueWhenFalse = (i + 3*s1 + 4) % n,
                falseWhenTrue = (i + 2*s2 + 5) % n,
                falseWhenFalse = (i + 3*s2 + 6) % n,
            ))
        }
    }
}
