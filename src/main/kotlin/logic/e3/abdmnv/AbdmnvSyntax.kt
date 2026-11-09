// 实现者：DeepSeek Harness，2026-11-09，ABDMNV-01 M1 垂直切片（授权 ABDMNV-01-M1-2026-11-09）。
// 依据：ABDMNV-01 技术设计评审 v2 附录 A / §5。本文件只做概念 AST 与规范序列化，不含学习器。
package logic.e3.abdmnv

import java.security.MessageDigest

/** 标识符语法（修正 v2 内部不一致）：小写字母开头，允许小写字母/数字/下划线，长度 1..32。
 *  可接受 c_0001 / a1 / ch_alpha / r_same_color / w_07 / o_17 / e_0012；拒绝大写、数字开头、连字符。 */
object AbdmnvIds {
    private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val VERSION_REF = Regex("^([a-z][a-z0-9_]{0,31})@([1-9][0-9]{0,8})$")

    fun validId(s: String): Boolean = ID.matches(s)
    fun requireId(s: String): String {
        require(validId(s)) { "INVALID_ID:$s" }
        return s
    }

    fun parseRef(s: String): AbdmnvVersionRef {
        val m = VERSION_REF.matchEntire(s) ?: error("INVALID_VERSION_REF:$s")
        return AbdmnvVersionRef(m.groupValues[1], m.groupValues[2].toInt())
    }
}

data class AbdmnvVersionRef(val id: String, val version: Int) {
    init {
        AbdmnvIds.requireId(id)
        require(version >= 1) { "INVALID_VERSION:$version" }
    }

    fun render(): String = "$id@$version"
}

/** v2 §5.3 的 Sort；M1 补入 obj（const-obj 与量词变量需要），已在变更说明中记录。 */
sealed interface AbdmnvSort {
    data object Bool : AbdmnvSort
    data object Int64 : AbdmnvSort
    data object Obj : AbdmnvSort
    data class Enum(val name: String) : AbdmnvSort { init { AbdmnvIds.requireId(name) } }
    data class SetOf(val element: AbdmnvSort) : AbdmnvSort
    data class Rel(val left: AbdmnvSort, val right: AbdmnvSort) : AbdmnvSort
}

enum class AbdmnvKind(val wire: String) {
    ATTRIBUTE("attribute"), CATEGORY("category"), RELATION("relation"), COMPOSITE("composite")
}

enum class AbdmnvCmp(val wire: String) { EQ("eq"), LT("lt"), LE("le"), GT("gt"), GE("ge") }

sealed interface AbdmnvTerm {
    data class Var(val name: String) : AbdmnvTerm { init { AbdmnvIds.requireId(name) } }
    data class ConstBool(val value: Boolean) : AbdmnvTerm
    data class ConstObj(val id: String) : AbdmnvTerm { init { AbdmnvIds.requireId(id) } }
    data class ConstInt(val value: Int) : AbdmnvTerm
    data class SetLit(val ids: List<String>) : AbdmnvTerm {
        init { ids.forEach(AbdmnvIds::requireId); require(ids == ids.sorted()) { "SET_NOT_SORTED" } }
    }

    data class Attr(val name: String, val on: AbdmnvTerm) : AbdmnvTerm { init { AbdmnvIds.requireId(name) } }
    data class Rel(val name: String, val a: AbdmnvTerm, val b: AbdmnvTerm) : AbdmnvTerm {
        init { AbdmnvIds.requireId(name) }
    }

    data class Apply(val ref: AbdmnvVersionRef, val args: List<AbdmnvTerm>) : AbdmnvTerm
    data class Not(val value: AbdmnvTerm) : AbdmnvTerm
    data class And(val left: AbdmnvTerm, val right: AbdmnvTerm) : AbdmnvTerm
    data class Or(val left: AbdmnvTerm, val right: AbdmnvTerm) : AbdmnvTerm
    data class Eq(val left: AbdmnvTerm, val right: AbdmnvTerm) : AbdmnvTerm
    data class Lt(val left: AbdmnvTerm, val right: AbdmnvTerm) : AbdmnvTerm
    data class Member(val element: AbdmnvTerm, val set: AbdmnvTerm) : AbdmnvTerm
    data class Exists(val varName: String, val over: AbdmnvSort, val body: AbdmnvTerm) : AbdmnvTerm {
        init { AbdmnvIds.requireId(varName) }
    }

    data class Forall(val varName: String, val over: AbdmnvSort, val body: AbdmnvTerm) : AbdmnvTerm {
        init { AbdmnvIds.requireId(varName) }
    }

    data class Count(val varName: String, val over: AbdmnvSort, val body: AbdmnvTerm, val cmp: AbdmnvCmp, val n: Int) :
        AbdmnvTerm {
        init { AbdmnvIds.requireId(varName); require(n >= 0) { "NEGATIVE_COUNT" } }
    }
}

sealed interface AbdmnvBody {
    data class Channel(val channelId: String) : AbdmnvBody { init { AbdmnvIds.requireId(channelId) } }
    data class Lambda(val params: List<String>, val body: AbdmnvTerm) : AbdmnvBody {
        init {
            params.forEach(AbdmnvIds::requireId)
            require(params.isNotEmpty()) { "EMPTY_LAMBDA" }
            require(params == params.distinct()) { "DUPLICATE_LAMBDA_PARAM" }
        }
    }
}

sealed interface AbdmnvScope {
    data object Global : AbdmnvScope
    data class World(val worldId: String) : AbdmnvScope { init { AbdmnvIds.requireId(worldId) } }
}

data class AbdmnvParam(val name: String, val sort: AbdmnvSort) {
    init { AbdmnvIds.requireId(name) }
}

enum class AbdmnvOrigin(val wire: String) { DESIGNER_PRIMITIVE("designer_primitive"), RUNTIME_DERIVED("runtime_derived") }

/** 概念版本记录。版本不可变；修订必须新建 version（v2 §6.11）。 */
data class AbdmnvConcept(
    val id: String,
    val version: Int,
    val kind: AbdmnvKind,
    val sort: AbdmnvSort,
    val params: List<AbdmnvParam>,
    val body: AbdmnvBody,
    val scope: AbdmnvScope,
    val evidence: List<String>,
    val deps: List<AbdmnvVersionRef>,
    val origin: AbdmnvOrigin,
) {
    init {
        AbdmnvIds.requireId(id)
        require(version >= 1) { "INVALID_VERSION:$version" }
        evidence.forEach(AbdmnvIds::requireId)
        require(evidence == evidence.distinct()) { "DUPLICATE_EVIDENCE" }
        require(params.map { it.name } == params.map { it.name }.distinct()) { "DUPLICATE_PARAM" }
    }

    /** 概念正文的语法深度，用于 DEPTH_LIMIT 与 Composite 的 depth>=2 规则。 */
    fun depth(): Int = when (val b = body) {
        is AbdmnvBody.Channel -> 1
        is AbdmnvBody.Lambda -> AbdmnvTerms.depth(b.body)
    }
}

object AbdmnvTerms {
    fun depth(t: AbdmnvTerm): Int = when (t) {
        is AbdmnvTerm.Var, is AbdmnvTerm.ConstBool, is AbdmnvTerm.ConstObj, is AbdmnvTerm.ConstInt,
        is AbdmnvTerm.SetLit -> 1
        is AbdmnvTerm.Attr -> 1 + depth(t.on)
        is AbdmnvTerm.Rel -> 1 + maxOf(depth(t.a), depth(t.b))
        is AbdmnvTerm.Apply -> 1 + (t.args.maxOfOrNull { depth(it) } ?: 0)
        is AbdmnvTerm.Not -> 1 + depth(t.value)
        is AbdmnvTerm.And -> 1 + maxOf(depth(t.left), depth(t.right))
        is AbdmnvTerm.Or -> 1 + maxOf(depth(t.left), depth(t.right))
        is AbdmnvTerm.Eq -> 1 + maxOf(depth(t.left), depth(t.right))
        is AbdmnvTerm.Lt -> 1 + maxOf(depth(t.left), depth(t.right))
        is AbdmnvTerm.Member -> 1 + maxOf(depth(t.element), depth(t.set))
        is AbdmnvTerm.Exists -> 1 + depth(t.body)
        is AbdmnvTerm.Forall -> 1 + depth(t.body)
        is AbdmnvTerm.Count -> 1 + depth(t.body)
    }
}

/** 规范序列化（v2 附录 A）：单行、令牌间一个空格、小写关键字、列表排序规则。 */
object AbdmnvCanonical {
    fun sort(s: AbdmnvSort): String = when (s) {
        AbdmnvSort.Bool -> "bool"
        AbdmnvSort.Int64 -> "int"
        AbdmnvSort.Obj -> "obj"
        is AbdmnvSort.Enum -> "(enum ${s.name})"
        is AbdmnvSort.SetOf -> "(set ${sort(s.element)})"
        is AbdmnvSort.Rel -> "(rel ${sort(s.left)} ${sort(s.right)})"
    }

    private fun params(ps: List<AbdmnvParam>): String =
        "(" + ps.joinToString(" ") { "(${it.name} ${sort(it.sort)})" } + ")"

    private fun ids(xs: List<String>): String = "(" + xs.sorted().joinToString(" ") + ")"

    private fun refs(xs: List<AbdmnvVersionRef>): String =
        "(" + xs.sortedWith(compareBy({ it.id }, { it.version })).joinToString(" ") { it.render() } + ")"

    fun scope(s: AbdmnvScope): String = when (s) {
        AbdmnvScope.Global -> "(global)"
        is AbdmnvScope.World -> "(world ${s.worldId})"
    }

    /** 黄金样例 G2 的写法：attr/rel 的对象实参若是变量则写裸名（`x`），常量对象写 `(const-obj o_17)`；
     *  其他项（如 Apply）仍按完整项渲染。此差异来自 v2 附录 A 原文，禁止改样例迁就实现。 */
    private fun objectTerm(t: AbdmnvTerm): String = when (t) {
        is AbdmnvTerm.Var -> t.name
        is AbdmnvTerm.ConstObj -> "(const-obj ${t.id})"
        else -> term(t)
    }

    fun term(t: AbdmnvTerm): String = when (t) {
        is AbdmnvTerm.Var -> "(var ${t.name})"
        is AbdmnvTerm.ConstBool -> "(const ${t.value})"
        is AbdmnvTerm.ConstObj -> "(const-obj ${t.id})"
        is AbdmnvTerm.ConstInt -> "(const-int ${t.value})"
        is AbdmnvTerm.SetLit -> "(" + "set " + t.ids.joinToString(" ") + ")"
        is AbdmnvTerm.Attr -> "(attr ${t.name} ${objectTerm(t.on)})"
        is AbdmnvTerm.Rel -> "(rel ${t.name} ${objectTerm(t.a)} ${objectTerm(t.b)})"
        is AbdmnvTerm.Apply -> "(apply ${t.ref.render()}" + (if (t.args.isEmpty()) "" else " " + t.args.joinToString(" ") { term(it) }) + ")"
        is AbdmnvTerm.Not -> "(not ${term(t.value)})"
        is AbdmnvTerm.And -> "(and ${term(t.left)} ${term(t.right)})"
        is AbdmnvTerm.Or -> "(or ${term(t.left)} ${term(t.right)})"
        is AbdmnvTerm.Eq -> "(= ${term(t.left)} ${term(t.right)})"
        is AbdmnvTerm.Lt -> "(lt ${term(t.left)} ${term(t.right)})"
        is AbdmnvTerm.Member -> "(member ${term(t.element)} ${term(t.set)})"
        is AbdmnvTerm.Exists -> "(exists (${t.varName} ${sort(t.over)}) ${term(t.body)})"
        is AbdmnvTerm.Forall -> "(forall (${t.varName} ${sort(t.over)}) ${term(t.body)})"
        is AbdmnvTerm.Count -> "(count (${t.varName} ${sort(t.over)}) ${term(t.body)} (${t.cmp.wire} ${t.n}))"
    }

    fun body(b: AbdmnvBody): String = when (b) {
        is AbdmnvBody.Channel -> "(channel ${b.channelId})"
        is AbdmnvBody.Lambda -> "(lambda (" + b.params.joinToString(" ") + ") " + term(b.body) + ")"
    }

    fun concept(c: AbdmnvConcept): String = buildString {
        append("(concept")
        append(" (id ").append(c.id).append(")")
        append(" (version ").append(c.version).append(")")
        append(" (kind ").append(c.kind.wire).append(")")
        append(" (sort ").append(sort(c.sort)).append(")")
        append(" (params ").append(params(c.params)).append(")")
        append(" (body ").append(body(c.body)).append(")")
        append(" (scope ").append(scope(c.scope)).append(")")
        append(" (evidence ").append(ids(c.evidence)).append(")")
        append(" (deps ").append(refs(c.deps)).append(")")
        append(" (origin ").append(c.origin.wire).append(")")
        append(")")
    }

    /** 版本记录（v2 附录 A G4）。 */
    fun versionRecord(
        conceptId: String,
        version: Int,
        supersedes: AbdmnvVersionRef,
        reason: String,
        trigger: String,
        deps: List<AbdmnvVersionRef>,
        status: String,
        scope: AbdmnvScope,
    ): String = buildString {
        append("(version-record")
        append(" (concept ").append(conceptId).append(")")
        append(" (version ").append(version).append(")")
        append(" (supersedes ").append(supersedes.render()).append(")")
        append(" (reason ").append(reason).append(")")
        append(" (trigger ").append(trigger).append(")")
        append(" (deps ").append(refs(deps)).append(")")
        append(" (status ").append(status).append(" ").append(scope(scope)).append(")")
        append(")")
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 概念内容哈希：规范序列化的 SHA-256（版本不可变校验用）。 */
    fun contentHash(c: AbdmnvConcept): String = sha256(concept(c))
}

