// 参考实现：云端 ChatGPT，2026-11-04；DSH（本会话）集成、测试、修复。
// B0 M1 implementation reference. Pure immutable IR; no filesystem/network/teacher primitives.
package logic.capability

import java.security.MessageDigest

const val IR_SEMANTICS_VERSION = "cap-ir-v1"

sealed interface CapType {
    data object Bool3 : CapType
    data object Str : CapType
    data object Int64 : CapType
    data class Record(val fields: Map<String, CapType>) : CapType
}

sealed interface CapValue {
    data class Bool(val value: Boolean) : CapValue
    data class Str(val value: String) : CapValue
    data class I64(val value: Long) : CapValue
    data class Rec(val fields: Map<String, CapValue>) : CapValue
}

sealed interface Expr {
    data class Arg(val name: String, val type: CapType) : Expr
    data class Const(val value: CapValue) : Expr
    data class Field(val record: Expr, val name: String) : Expr
    data class Eq(val left: Expr, val right: Expr) : Expr
    data class And(val left: Expr, val right: Expr) : Expr
    data class Or(val left: Expr, val right: Expr) : Expr
    data class Not(val value: Expr) : Expr
    data class If(val condition: Expr, val whenTrue: Expr, val whenFalse: Expr) : Expr
    data class Let(val name: String, val value: Expr, val body: Expr) : Expr
    data class Ref(val name: String) : Expr
    // The hash pins the callee definition's entire contract+AST, including nested locked calls.
    data class Call(val id: String, val version: Int, val bodyHash: String, val args: Map<String, Expr>) : Expr
}

data class CapabilityDefinition(
    val id: String,
    val version: Int,
    val inputs: Map<String, CapType>,
    val output: CapType,
    val body: Expr,
) {
    fun contentHash(): String = sha256(
        "$IR_SEMANTICS_VERSION\n" +
            "{" + inputs.toSortedMap().entries.joinToString(",") { (n, t) ->
                "${CanonicalJson.string(n)}:${CanonicalJson.type(t)}"
            } + "}\n" + CanonicalJson.type(output) + "\n" + CanonicalJson.expr(body)
    )
}

interface CapabilityResolver {
    fun resolve(id: String, version: Int): CapabilityDefinition?
}

class InMemoryResolver(definitions: Collection<CapabilityDefinition> = emptyList()) : CapabilityResolver {
    private val definitions = mutableMapOf<Pair<String, Int>, CapabilityDefinition>()
    init { definitions.forEach { add(it) } }
    fun add(def: CapabilityDefinition) {
        val key = def.id to def.version
        require(key !in definitions) { "immutable capability version: $key" }
        definitions[key] = def
    }
    override fun resolve(id: String, version: Int): CapabilityDefinition? = definitions[id to version]
}

fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Emits canonical JSON. Keys are sorted where they are semantically unordered.
 *  Expr operands are NOT sorted: lazy error/short-circuit behavior is order-sensitive.
 */
object CanonicalJson {
    fun string(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    fun type(t: CapType): String = when (t) {
        CapType.Bool3 -> string("Bool3")
        CapType.Str -> string("String")
        CapType.Int64 -> string("Int64")
        is CapType.Record -> "{" + "\"kind\":\"Record\",\"fields\":{" +
            t.fields.toSortedMap().entries.joinToString(",") { (k, v) -> "${string(k)}:${type(v)}" } + "}}"
    }

    fun expr(e: Expr): String = when (e) {
        is Expr.Arg -> "{\"op\":\"arg\",\"name\":${string(e.name)},\"type\":${type(e.type)}}"
        is Expr.Const -> when (val v = e.value) {
            is CapValue.Bool -> "{\"op\":\"const\",\"type\":\"Bool3\",\"value\":${string(v.value.toString())}}"
            is CapValue.Str -> "{\"op\":\"const\",\"type\":\"String\",\"value\":${string(v.value)}}"
            is CapValue.I64 -> "{\"op\":\"const\",\"type\":\"Int64\",\"value\":${string(v.value.toString())}}"
            is CapValue.Rec -> error("Record literals are not supported in M1")
        }
        is Expr.Field -> "{\"op\":\"field\",\"record\":${expr(e.record)},\"name\":${string(e.name)}}"
        is Expr.Eq -> "{\"op\":\"eq\",\"left\":${expr(e.left)},\"right\":${expr(e.right)}}"
        is Expr.And -> "{\"op\":\"and\",\"left\":${expr(e.left)},\"right\":${expr(e.right)}}"
        is Expr.Or -> "{\"op\":\"or\",\"left\":${expr(e.left)},\"right\":${expr(e.right)}}"
        is Expr.Not -> "{\"op\":\"not\",\"value\":${expr(e.value)}}"
        is Expr.If -> "{\"op\":\"if\",\"condition\":${expr(e.condition)},\"then\":${expr(e.whenTrue)},\"else\":${expr(e.whenFalse)}}"
        is Expr.Let -> "{\"op\":\"let\",\"name\":${string(e.name)},\"value\":${expr(e.value)},\"body\":${expr(e.body)}}"
        is Expr.Ref -> "{\"op\":\"ref\",\"name\":${string(e.name)}}"
        is Expr.Call -> "{\"op\":\"call\",\"id\":${string(e.id)},\"version\":${e.version},\"body_hash\":${string(e.bodyHash)},\"args\":{" +
            e.args.toSortedMap().entries.joinToString(",") { (k, v) -> "${string(k)}:${expr(v)}" } + "}}"
    }
    fun astHash(e: Expr): String = sha256("$IR_SEMANTICS_VERSION\n${expr(e)}")
}
