// 参考实现：云端 ChatGPT，2026-11-04；DSH（本会话）集成、测试、修复。
// B0 M1 JSON adapter. Uses existing kotlinx-serialization-json dependency (no new deps).
// Validation and execution are separate: parse -> typecheck -> interpret.
package logic.capability

import kotlinx.serialization.json.*

class CapIrParseException(val code: String, message: String = code) : IllegalArgumentException(message)

object CapabilityJson {
    private val parser = Json { isLenient = false }

    fun decode(text: String): Expr {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) throw CapIrParseException("json_too_large")
        val counter = intArrayOf(0)
        return try {
            node(parser.parseToJsonElement(text), 0, counter)
        } catch (error: CapIrParseException) {
        throw error
    } catch (error: Exception) {
            throw CapIrParseException("invalid_json_or_ast", error.message ?: "bad JSON")
        }
    }

    fun canonical(text: String): String = CanonicalJson.expr(decode(text))
    fun canonical(expr: Expr): String = CanonicalJson.expr(expr)

    fun decodeType(text: String): CapType = type(parser.parseToJsonElement(text), 0)

    private fun obj(el: JsonElement): JsonObject = el as? JsonObject
        ?: throw CapIrParseException("expected_object")
    private fun keys(o: JsonObject, vararg expected: String) {
        if (o.keys != expected.toSet()) throw CapIrParseException("unexpected_or_missing_key", "keys=${o.keys}, expected=${expected.toSet()}")
    }
    private fun str(o: JsonObject, name: String): String {
        val s = o[name] as? JsonPrimitive ?: throw CapIrParseException("expected_string")
        if (!s.isString) throw CapIrParseException("expected_string")
        return s.content
    }
    private fun version(o: JsonObject): Int {
        val p = o["version"] as? JsonPrimitive ?: throw CapIrParseException("expected_int")
        if (p.isString || !Regex("[1-9][0-9]*").matches(p.content)) throw CapIrParseException("expected_int")
        return p.intOrNull ?: throw CapIrParseException("expected_int")
    }
    private fun type(e: JsonElement?, depth: Int = 0): CapType {
        if (depth > 32) throw CapIrParseException("ast_depth_limit")
        val p = e as? JsonPrimitive
        if (p != null) {
            if (!p.isString) throw CapIrParseException("invalid_type")
            return when (p.content) {
                "Bool3" -> CapType.Bool3
                "String" -> CapType.Str
                "Int64" -> CapType.Int64
                else -> throw CapIrParseException("not_implemented_type")
            }
        }
        val o = obj(e ?: throw CapIrParseException("invalid_type"))
        keys(o, "kind", "fields")
        if (str(o, "kind") != "Record") throw CapIrParseException("not_implemented_type")
        val fields = obj(o.getValue("fields"))
        if (fields.isEmpty()) throw CapIrParseException("empty_record_schema")
        if (fields.size > MAX_RECORD_FIELDS) throw CapIrParseException("record_field_limit")
        return CapType.Record(fields.entries.sortedBy { it.key }.associate { (k, v) ->
            if (k.isBlank()) throw CapIrParseException("invalid_field")
            k to type(v, depth + 1)
        })
    }

    private fun node(e: JsonElement, depth: Int, counter: IntArray): Expr {
        if (depth > 32) throw CapIrParseException("ast_depth_limit")
        counter[0]++
        if (counter[0] > MAX_AST_NODES) throw CapIrParseException("ast_node_limit")
        val o = obj(e)
        val op = str(o, "op")
        fun child(name: String) = node(o[name] ?: throw CapIrParseException("missing_node"), depth + 1, counter)
        return when (op) {
            "arg" -> {
                keys(o, "op", "name", "type")
                Expr.Arg(str(o, "name"), type(o["type"]))
            }
            "const" -> {
                keys(o, "op", "type", "value")
                val literalType = type(o["type"])
                val raw = str(o, "value")
                val value = when (literalType) {
                    CapType.Bool3 -> when (raw) {
                        "true" -> CapValue.Bool(true)
                        "false" -> CapValue.Bool(false)
                        else -> throw CapIrParseException("invalid_bool3_literal")
                    }
                    CapType.Str -> CapValue.Str(raw)
                    CapType.Int64 -> {
                        if (!Regex("-?(0|[1-9][0-9]*)").matches(raw)) throw CapIrParseException("invalid_int64")
                        CapValue.I64(raw.toLongOrNull() ?: throw CapIrParseException("int64_out_of_range"))
                    }
                    is CapType.Record -> throw CapIrParseException("record_literal_not_supported")
                }
                Expr.Const(value)
            }
            "field" -> { keys(o, "op", "record", "name"); Expr.Field(child("record"), str(o, "name")) }
            "eq" -> { keys(o, "op", "left", "right"); Expr.Eq(child("left"), child("right")) }
            "and" -> { keys(o, "op", "left", "right"); Expr.And(child("left"), child("right")) }
            "or" -> { keys(o, "op", "left", "right"); Expr.Or(child("left"), child("right")) }
            "not" -> { keys(o, "op", "value"); Expr.Not(child("value")) }
            "if" -> { keys(o, "op", "condition", "then", "else"); Expr.If(child("condition"), child("then"), child("else")) }
            "let" -> { keys(o, "op", "name", "value", "body"); Expr.Let(str(o, "name"), child("value"), child("body")) }
            "ref" -> { keys(o, "op", "name"); Expr.Ref(str(o, "name")) }
            "call" -> {
                keys(o, "op", "id", "version", "body_hash", "args")
                val args = obj(o.getValue("args"))
                val hash = str(o, "body_hash")
                if (!Regex("[0-9a-f]{64}").matches(hash)) throw CapIrParseException("invalid_body_hash")
                Expr.Call(str(o, "id"), version(o), hash,
                    args.entries.sortedBy { it.key }.associate { (k, v) -> k to node(v, depth + 1, counter) })
            }
            else -> throw CapIrParseException("unknown_op")
        }
    }
}
