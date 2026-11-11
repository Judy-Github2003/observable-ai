// ABDMNV-01 M1 reference, 2026-11-09. New, unfrozen code; do not copy into frozen B0.
package observable.ai.abdmnv.m1

/** Three-valued assertions, plus a separate ERROR channel for evaluation faults. */
enum class Truth { TRUE, FALSE, UNKNOWN, ERROR }

object TruthLogic {
    fun not(x: Truth): Truth = when (x) {
        Truth.TRUE -> Truth.FALSE
        Truth.FALSE -> Truth.TRUE
        Truth.UNKNOWN -> Truth.UNKNOWN
        Truth.ERROR -> Truth.ERROR
    }

    // Deliberately strict on ERROR. B0's existing evaluator short-circuits;
    // M1 does NOT alter B0 semantics. Adapter must explicitly translate.
    fun and(a: Truth, b: Truth): Truth = when {
        a == Truth.ERROR || b == Truth.ERROR -> Truth.ERROR
        a == Truth.FALSE || b == Truth.FALSE -> Truth.FALSE
        a == Truth.UNKNOWN || b == Truth.UNKNOWN -> Truth.UNKNOWN
        else -> Truth.TRUE
    }

    fun or(a: Truth, b: Truth): Truth = when {
        a == Truth.ERROR || b == Truth.ERROR -> Truth.ERROR
        a == Truth.TRUE || b == Truth.TRUE -> Truth.TRUE
        a == Truth.UNKNOWN || b == Truth.UNKNOWN -> Truth.UNKNOWN
        else -> Truth.FALSE
    }

    fun implies(a: Truth, b: Truth): Truth = or(not(a), b)
}

sealed interface TypedValue {
    data class Bool(val value: Boolean) : TypedValue
    data class IntNumber(val value: Long) : TypedValue
    data class EnumSymbol(val value: String, val enumId: String) : TypedValue
    data class ObjectId(val id: String) : TypedValue
    data class SetValue(val values: Set<TypedValue>, val element: Sort) : TypedValue
}

sealed interface ValueResult {
    data class Known(val value: TypedValue) : ValueResult
    data class Unknown(val reason: String) : ValueResult
    data class Error(val codes: Set<String>) : ValueResult {
        constructor(code: String) : this(setOf(code))
    }
}

fun ValueResult.truth(): Truth = when (this) {
    is ValueResult.Unknown -> Truth.UNKNOWN
    is ValueResult.Error -> Truth.ERROR
    is ValueResult.Known -> when (value) {
        is TypedValue.Bool -> if (value.value) Truth.TRUE else Truth.FALSE
        else -> Truth.ERROR
    }
}

fun Truth.toValue(): ValueResult = when (this) {
    Truth.TRUE -> ValueResult.Known(TypedValue.Bool(true))
    Truth.FALSE -> ValueResult.Known(TypedValue.Bool(false))
    Truth.UNKNOWN -> ValueResult.Unknown("UNKNOWN_OPERAND")
    Truth.ERROR -> ValueResult.Error("ERROR_OPERAND")
}

/** An unordered set of error codes is canonically sorted when printed/hashed. */
fun ValueResult.errorCodesCanonical(): String = when (this) {
    is ValueResult.Error -> codes.sorted().joinToString(",")
    else -> ""
}
