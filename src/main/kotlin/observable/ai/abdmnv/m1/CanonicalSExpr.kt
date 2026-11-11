package observable.ai.abdmnv.m1

import java.security.MessageDigest

/** Reference canonical S-expression reader/writer; no external dependencies. */
sealed interface SExpr {
    data class Atom(val value: String) : SExpr
    data class ListNode(val children: List<SExpr>) : SExpr
}

object CanonicalSExpr {
    // v2 printed regex [a-z]+_[0-9]+ contradicts four golden samples
    // containing ch_alpha, r_same_color, a1. This grammar accepts all of them.
    private val identifier = Regex("[a-z][a-z0-9_]*")
    private val reference = Regex("[a-z][a-z0-9_]*@[1-9][0-9]*")
    private val keyword = Regex("[a-z][a-z0-9_-]*")
    private val integer = Regex("-?(0|[1-9][0-9]*)")
    private val tokens = Regex("\\(|\\)|[^()\\s]+")

    fun isLegalAtom(atom: String): Boolean = identifier.matches(atom) ||
        reference.matches(atom) || keyword.matches(atom) || integer.matches(atom) || atom == "="

    fun parse(source: String): SExpr {
        require(source.all { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }) {
            "CANONICALIZATION_FAILURE: non-ASCII or control character"
        }
        val items = tokens.findAll(source).map { it.value }.toList()
        require(items.isNotEmpty()) { "empty S-expression" }
        var pos = 0
        fun parseNode(): SExpr {
            require(pos < items.size) { "unexpected end" }
            val token = items[pos++]
            return when (token) {
                "(" -> {
                    val children = mutableListOf<SExpr>()
                    while (pos < items.size && items[pos] != ")") children += parseNode()
                    require(pos < items.size && items[pos] == ")") { "unclosed list" }
                    pos++
                    SExpr.ListNode(children)
                }
                ")" -> error("unexpected closing parenthesis")
                else -> {
                    require(isLegalAtom(token)) { "invalid atom: $token" }
                    SExpr.Atom(token)
                }
            }
        }
        val expr = parseNode()
        require(pos == items.size) { "trailing tokens" }
        return expr
    }

    fun write(expr: SExpr): String = when (expr) {
        is SExpr.Atom -> expr.value
        is SExpr.ListNode -> "(" + expr.children.joinToString(" ") { write(it) } + ")"
    }

    fun canonical(source: String): String = write(parse(source))

    fun hashCanonical(source: String): String = sha256(canonical(source))

    fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
