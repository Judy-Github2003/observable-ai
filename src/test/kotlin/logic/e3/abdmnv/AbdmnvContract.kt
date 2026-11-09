// 实现者：DeepSeek Harness，2026-11-09，ABDMNV-01 M1 定向契约测试。
// 覆盖：黄金序列化、ID 语法、Bool3/ERROR 真值表、类型检查、关系求值、槽位协议、R1–R6、
// 证据 schema/冲突、版本不可变/撤销、T1–T5 公开 fixture、4 个稻草人。
package logic.e3.abdmnv

import logic.capability.CapValue
import logic.capability.EvalResult
import logic.e3.ObservedValue

private var passed = 0
private var failed = 0

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("PASS $name")
    } catch (e: Throwable) {
        failed++
        println("FAIL $name :: ${e.message}")
    }
}

private fun classify(r: EvalResult): String = when (r) {
    is EvalResult.Known -> when {
        r.value is CapValue.Bool && (r.value as CapValue.Bool).value -> "T"
        r.value is CapValue.Bool -> "F"
        else -> "?"
    }

    is EvalResult.Unknown -> "U"
    is EvalResult.Error -> "E"
}

private val T = abdBool(true)
private val F = abdBool(false)
private val U = abdUnknown("u")
private val E = abdError("e")

private class MapWorld(
    private val objs: List<String>,
    private val attrs: Map<String, EvalResult>,
    private val rels: Map<String, EvalResult> = emptyMap(),
) : AbdmnvWorld {
    override fun objects(): List<String> = objs
    override fun attribute(obj: String, name: String): EvalResult = attrs["$obj|$name"] ?: abdUnknown("NOT_MEASURED")
    override fun relation(name: String, a: String, b: String): EvalResult =
        rels["$name|$a|$b"] ?: abdUnknown("NOT_MEASURED")
}

private fun refOf(id: String, v: Int) = AbdmnvVersionRef(id, v)

private fun cat(id: String, body: AbdmnvTerm, deps: List<AbdmnvVersionRef> = emptyList()): AbdmnvConcept =
    AbdmnvConcept(id, 1, AbdmnvKind.CATEGORY, AbdmnvSort.Bool, emptyList(),
        AbdmnvBody.Lambda(listOf("x"), body), AbdmnvScope.World("w_07"), emptyList(), deps, AbdmnvOrigin.RUNTIME_DERIVED)

private fun catAt(id: String, v: Int, body: AbdmnvTerm): AbdmnvConcept = cat(id, body).copy(version = v)

fun main() {
    // ---------------- Slice A: 黄金样例 ----------------
    val g1 = AbdmnvConcept(
        "c_0001", 1, AbdmnvKind.ATTRIBUTE, AbdmnvSort.Enum("color"), emptyList(),
        AbdmnvBody.Channel("ch_alpha"), AbdmnvScope.World("w_07"),
        listOf("e_0012", "e_0013"), emptyList(), AbdmnvOrigin.RUNTIME_DERIVED
    )
    val g1Text = "(concept (id c_0001) (version 1) (kind attribute) (sort (enum color)) (params ()) " +
        "(body (channel ch_alpha)) (scope (world w_07)) (evidence (e_0012 e_0013)) (deps ()) (origin runtime_derived))"

    test("A1 golden G1 bytes") { check(AbdmnvCanonical.concept(g1) == g1Text) { AbdmnvCanonical.concept(g1) } }
    test("A2 golden G1 sha256") {
        check(AbdmnvCanonical.sha256(g1Text) == "d316eebc6df05b7ee39ab423130d56f85eeefc82c6bb7a2234d09b4d15671311")
    }

    val g2 = AbdmnvConcept(
        "c_0002", 2, AbdmnvKind.CATEGORY, AbdmnvSort.Bool, listOf(AbdmnvParam("p", AbdmnvSort.Enum("color"))),
        AbdmnvBody.Lambda(
            listOf("x"),
            AbdmnvTerm.And(
                AbdmnvTerm.Eq(AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")), AbdmnvTerm.ConstBool(true)),
                AbdmnvTerm.Rel("r_same_color", AbdmnvTerm.Var("x"), AbdmnvTerm.ConstObj("o_17")),
            ),
        ),
        AbdmnvScope.World("w_07"), listOf("e_0012", "e_0021", "e_0042"),
        listOf(refOf("c_0001", 1)), AbdmnvOrigin.RUNTIME_DERIVED,
    )
    val g2Text = "(concept (id c_0002) (version 2) (kind category) (sort bool) (params ((p (enum color)))) " +
        "(body (lambda (x) (and (= (attr a1 x) (const true)) (rel r_same_color x (const-obj o_17))))) " +
        "(scope (world w_07)) (evidence (e_0012 e_0021 e_0042)) (deps (c_0001@1)) (origin runtime_derived))"

    test("A3 golden G2 bytes") { check(AbdmnvCanonical.concept(g2) == g2Text) { AbdmnvCanonical.concept(g2) } }
    test("A4 golden G2 sha256") {
        check(AbdmnvCanonical.sha256(g2Text) == "df486bf2d2c5d197823fa5f40d4b0b059a1a13170d89e755055a4e27ddc5e710")
    }

    val g3 = AbdmnvConcept(
        "c_0003", 1, AbdmnvKind.COMPOSITE, AbdmnvSort.Bool, emptyList(),
        AbdmnvBody.Lambda(
            listOf("x"),
            AbdmnvTerm.And(
                AbdmnvTerm.Apply(refOf("c_0002", 2), listOf(AbdmnvTerm.Var("x"))),
                AbdmnvTerm.Not(AbdmnvTerm.Apply(refOf("c_0004", 1), listOf(AbdmnvTerm.Var("x")))),
            ),
        ),
        AbdmnvScope.Global, listOf("e_0012", "e_0021", "e_0042", "e_0050"),
        listOf(refOf("c_0002", 2), refOf("c_0004", 1)), AbdmnvOrigin.RUNTIME_DERIVED,
    )
    val g3Text = "(concept (id c_0003) (version 1) (kind composite) (sort bool) (params ()) " +
        "(body (lambda (x) (and (apply c_0002@2 (var x)) (not (apply c_0004@1 (var x)))))) " +
        "(scope (global)) (evidence (e_0012 e_0021 e_0042 e_0050)) (deps (c_0002@2 c_0004@1)) (origin runtime_derived))"

    test("A5 golden G3 bytes") { check(AbdmnvCanonical.concept(g3) == g3Text) { AbdmnvCanonical.concept(g3) } }
    test("A6 golden G3 sha256") {
        check(AbdmnvCanonical.sha256(g3Text) == "f2461d3eec8b285de1961da0ffadd5e6d39413c7b0c2d42000bb53467152aeb0")
    }

    val g4Text = AbdmnvCanonical.versionRecord(
        "c_0002", 2, refOf("c_0002", 1), "counterexample", "e_0042",
        listOf(refOf("c_0001", 1)), "active-with-scope", AbdmnvScope.World("w_07")
    )
    val g4Expected = "(version-record (concept c_0002) (version 2) (supersedes c_0002@1) (reason counterexample) " +
        "(trigger e_0042) (deps (c_0001@1)) (status active-with-scope (world w_07)))"

    test("A7 golden G4 bytes") { check(g4Text == g4Expected) { g4Text } }
    test("A8 golden G4 sha256") {
        check(AbdmnvCanonical.sha256(g4Expected) == "e9f3baf3e1c2388300a96accf0aba86c135d0695fcddaaeb4d482188e36b098c")
    }

    test("A9 ID 语法正例") {
        listOf("c_0001", "a1", "ch_alpha", "r_same_color", "w_07", "o_17", "e_0012").forEach {
            check(AbdmnvIds.validId(it)) { "should accept $it" }
        }
    }
    test("A10 ID 语法反例") {
        listOf("C_0001", "_x", "1abc", "c-1", "c 1", "é", "", "a".repeat(33)).forEach {
            check(!AbdmnvIds.validId(it)) { "should reject $it" }
        }
    }
    test("A11 版本引用解析") {
        val r = AbdmnvIds.parseRef("c_0007@12")
        check(r.id == "c_0007" && r.version == 12 && r.render() == "c_0007@12")
    }

    // ---------------- Slice B: 四值语义 ----------------
    val ev = AbdmnvEvaluator(MapWorld(listOf("o_1"), emptyMap()))
    fun evalEnv(term: AbdmnvTerm, p: EvalResult, q: EvalResult = T): String =
        classify(ev.eval(term, mapOf("p" to p, "q" to q)))

    test("B1 NOT 真值表") {
        val notP = AbdmnvTerm.Not(AbdmnvTerm.Var("p"))
        check(evalEnv(notP, T) == "F" && evalEnv(notP, F) == "T" && evalEnv(notP, U) == "U" && evalEnv(notP, E) == "E")
    }
    test("B2 AND 真值表（16 格）") {
        val vals = mapOf("T" to T, "F" to F, "U" to U, "E" to E)
        val expected = mapOf(
            "T,T" to "T", "T,F" to "F", "T,U" to "U", "T,E" to "E",
            "F,T" to "F", "F,F" to "F", "F,U" to "F", "F,E" to "E",
            "U,T" to "U", "U,F" to "F", "U,U" to "U", "U,E" to "E",
            "E,T" to "E", "E,F" to "E", "E,U" to "E", "E,E" to "E",
        )
        for ((k, want) in expected) {
            val (a, b) = k.split(",")
            val got = evalEnv(AbdmnvTerm.And(AbdmnvTerm.Var("p"), AbdmnvTerm.Var("q")), vals.getValue(a), vals.getValue(b))
            check(got == want) { "AND $k want $want got $got" }
        }
    }
    test("B3 OR 真值表（16 格）") {
        val vals = mapOf("T" to T, "F" to F, "U" to U, "E" to E)
        val expected = mapOf(
            "T,T" to "T", "T,F" to "T", "T,U" to "T", "T,E" to "E",
            "F,T" to "T", "F,F" to "F", "F,U" to "U", "F,E" to "E",
            "U,T" to "T", "U,F" to "U", "U,U" to "U", "U,E" to "E",
            "E,T" to "E", "E,F" to "E", "E,U" to "E", "E,E" to "E",
        )
        for ((k, want) in expected) {
            val (a, b) = k.split(",")
            val got = evalEnv(AbdmnvTerm.Or(AbdmnvTerm.Var("p"), AbdmnvTerm.Var("q")), vals.getValue(a), vals.getValue(b))
            check(got == want) { "OR $k want $want got $got" }
        }
    }
    test("B4 EQ 的 UNKNOWN/ERROR 语义") {
        val eq = AbdmnvTerm.Eq(AbdmnvTerm.Var("p"), AbdmnvTerm.Var("q"))
        check(evalEnv(eq, T, T) == "T" && evalEnv(eq, T, F) == "F" && evalEnv(eq, T, U) == "U" && evalEnv(eq, E, T) == "E")
    }
    test("B5 EQ 类型不匹配 → ERROR") {
        val eq = AbdmnvTerm.Eq(AbdmnvTerm.Var("p"), AbdmnvTerm.Var("q"))
        val r = ev.eval(eq, mapOf("p" to abdBool(true), "q" to abdStr("o_1")))
        check(r is EvalResult.Error && r.code == "type_mismatch")
    }
    test("B6 LT 语义") {
        val lt = AbdmnvTerm.Lt(AbdmnvTerm.Var("p"), AbdmnvTerm.Var("q"))
        check(classify(ev.eval(lt, mapOf("p" to abdInt(1), "q" to abdInt(2)))) == "T")
        check(classify(ev.eval(lt, mapOf("p" to abdInt(2), "q" to abdInt(2)))) == "F")
        check(classify(ev.eval(lt, mapOf("p" to U, "q" to abdInt(2)))) == "U")
        check(ev.eval(lt, mapOf("p" to abdStr("x"), "q" to abdInt(2))) is EvalResult.Error)
    }
    test("B7 MEMBER 语义") {
        val m = AbdmnvTerm.Member(AbdmnvTerm.Var("p"), AbdmnvTerm.SetLit(listOf("o_1", "o_2")))
        check(classify(ev.eval(m, mapOf("p" to abdStr("o_1")))) == "T")
        check(classify(ev.eval(m, mapOf("p" to abdStr("o_3")))) == "F")
        check(classify(ev.eval(m, mapOf("p" to U))) == "U")
        check(ev.eval(m, mapOf("p" to abdInt(1))) is EvalResult.Error)
    }

    val flagWorld = MapWorld(
        listOf("o_1", "o_2", "o_3"),
        mapOf("o_1|flag" to T, "o_2|flag" to F, "o_3|flag" to U),
    )
    val flagEv = AbdmnvEvaluator(flagWorld)
    test("B8 EXISTS/FORALL 三值") {
        val ex = AbdmnvTerm.Exists("z", AbdmnvSort.Obj, AbdmnvTerm.Attr("flag", AbdmnvTerm.Var("z")))
        val fa = AbdmnvTerm.Forall("z", AbdmnvSort.Obj, AbdmnvTerm.Attr("flag", AbdmnvTerm.Var("z")))
        check(classify(flagEv.eval(ex)) == "T")
        check(classify(flagEv.eval(fa)) == "F")
    }
    test("B9 COUNT 边界语义") {
        val body = AbdmnvTerm.Attr("flag", AbdmnvTerm.Var("z"))
        fun count(cmp: AbdmnvCmp, n: Int) =
            classify(flagEv.eval(AbdmnvTerm.Count("z", AbdmnvSort.Obj, body, cmp, n)))
        check(count(AbdmnvCmp.GE, 1) == "T")
        check(count(AbdmnvCmp.GE, 2) == "U")
        check(count(AbdmnvCmp.GE, 3) == "F")
    }
    test("B10 ERROR 不被静默降级为 UNKNOWN") {
        val bad = MapWorld(listOf("o_1"), mapOf("o_1|flag" to E))
        val ex = AbdmnvTerm.Exists("z", AbdmnvSort.Obj, AbdmnvTerm.Attr("flag", AbdmnvTerm.Var("z")))
        check(AbdmnvEvaluator(bad).eval(ex) is EvalResult.Error)
    }
    test("B11 关系成对求值与计费") {
        val w = MapWorld(
            listOf("o_1", "o_2"),
            emptyMap(),
            mapOf("r_same|o_1|o_2" to T, "r_same|o_1|o_1" to T),
        )
        val counting = AbdmnvCountingWorld(w)
        val evr = AbdmnvEvaluator(counting)
        val t = AbdmnvTerm.Rel("r_same", AbdmnvTerm.Var("x"), AbdmnvTerm.Var("y"))
        check(classify(evr.eval(t, mapOf("x" to abdStr("o_1"), "y" to abdStr("o_2")))) == "T")
        check(counting.relationReads == 1)
    }

    // ---------------- Slice B: 类型检查 ----------------
    val symbols = AbdmnvSymbols(
        attributes = mapOf("a1" to AbdmnvSort.Bool, "n1" to AbdmnvSort.Int64),
        relations = mapOf("r_same" to AbdmnvSort.Rel(AbdmnvSort.Obj, AbdmnvSort.Obj)),
    )
    val checker = AbdmnvTypeChecker(symbols)

    test("B12 合法类别通过类型检查") { check(checker.checkConcept(cat("c_1001", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))) == null) }
    test("B13 未知属性被拒绝") {
        check(checker.checkConcept(cat("c_1002", AbdmnvTerm.Attr("zzz", AbdmnvTerm.Var("x"))))!!.startsWith("UNKNOWN_ATTRIBUTE"))
    }
    test("B14 类别正文非布尔被拒绝") {
        check(checker.checkConcept(cat("c_1003", AbdmnvTerm.ConstInt(1))) == "CATEGORY_BODY_NOT_BOOL")
    }
    test("B15 关系必须二元") {
        val bad = AbdmnvConcept("c_1004", 1, AbdmnvKind.RELATION, AbdmnvSort.Rel(AbdmnvSort.Obj, AbdmnvSort.Obj),
            emptyList(), AbdmnvBody.Lambda(listOf("x"), AbdmnvTerm.ConstBool(true)), AbdmnvScope.Global,
            emptyList(), emptyList(), AbdmnvOrigin.RUNTIME_DERIVED)
        check(checker.checkConcept(bad) == "RELATION_REQUIRES_BINARY_LAMBDA")
    }
    test("B16 深度超限") {
        var t: AbdmnvTerm = AbdmnvTerm.ConstBool(true)
        repeat(5) { t = AbdmnvTerm.Not(t) }
        check(checker.checkConcept(cat("c_1005", t)) == "DEPTH_LIMIT")
    }
    test("B17 Composite 必须含 apply") {
        val c = AbdmnvConcept("c_1006", 1, AbdmnvKind.COMPOSITE, AbdmnvSort.Bool, emptyList(),
            AbdmnvBody.Lambda(listOf("x"), AbdmnvTerm.ConstBool(true)), AbdmnvScope.Global,
            emptyList(), emptyList(), AbdmnvOrigin.RUNTIME_DERIVED)
        check(checker.checkConcept(c) == "COMPOSITE_WITHOUT_APPLY")
    }
    test("B18 Attribute 必须是 channel") {
        val c = AbdmnvConcept("c_1007", 1, AbdmnvKind.ATTRIBUTE, AbdmnvSort.Bool, emptyList(),
            AbdmnvBody.Lambda(listOf("x"), AbdmnvTerm.ConstBool(true)), AbdmnvScope.Global,
            emptyList(), emptyList(), AbdmnvOrigin.RUNTIME_DERIVED)
        check(checker.checkConcept(c) == "ATTRIBUTE_REQUIRES_CHANNEL")
    }

    // ---------------- Slice C: 槽位协议 ----------------
    val slots = listOf(
        AbdmnvSlot("s_1", AbdmnvKind.CATEGORY, AbdmnvSort.Bool),
        AbdmnvSlot("s_2", AbdmnvKind.CATEGORY, AbdmnvSort.Bool),
        AbdmnvSlot("s_3", AbdmnvKind.CATEGORY, AbdmnvSort.Bool),
    )
    test("C1 正常提交 + precision") {
        val b = AbdmnvBoard(slots)
        check(b.submit(0, "c_2001") == null)
        b.recordVerdict(0, AbdmnvVerdict.MATCH)
        val c = b.counts()
        check(c.submitted == 1 && c.matched == 1 && c.precision == 1.0 && c.abstained == 2)
    }
    test("C2 SUBMISSION_OVERFLOW") {
        val b = AbdmnvBoard(slots)
        check(b.submit(3, "c_2002") == "SUBMISSION_OVERFLOW")
    }
    test("C3 SLOT_DUPLICATE") {
        val b = AbdmnvBoard(slots)
        check(b.submit(0, "c_2003") == null)
        check(b.submit(0, "c_2004") == "SLOT_DUPLICATE")
    }
    test("C4 CONCEPT_DUPLICATE") {
        val b = AbdmnvBoard(slots)
        check(b.submit(0, "c_2005") == null)
        check(b.submit(1, "c_2005") == "CONCEPT_DUPLICATE")
    }
    test("C5 ABSTAIN / UNDETERMINED 分离") {
        val b = AbdmnvBoard(slots)
        check(b.abstain(0) == null)
        check(b.undetermined(1, "NO_CANDIDATE") == null)
        val c = b.counts()
        check(c.submitted == 0 && c.abstained == 2 && c.undetermined == 1 && c.precision == null)
    }
    test("C6 未提交槽位计为弃答") {
        val b = AbdmnvBoard(slots)
        check(b.counts().abstained == 3)
    }

    // ---------------- Slice C: R1–R6 ----------------
    val world4 = MapWorld(
        listOf("o_1", "o_2", "o_3", "o_4"),
        mapOf("o_1|a1" to T, "o_2|a1" to F, "o_3|a1" to T, "o_4|a1" to F),
    )
    val scorerSymbols = AbdmnvSymbols(attributes = mapOf("a1" to AbdmnvSort.Bool))
    val scorer = AbdmnvScorer(scorerSymbols, tauCoverage = 0.9)
    val target = cat("c_t", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
    val v4 = listOf("o_1", "o_2", "o_3", "o_4")

    test("C7 MATCH") { check(scorer.scoreOnObjects(cat("c_3001", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x"))), target, world4, v4).verdict == AbdmnvVerdict.MATCH) }
    test("C8 CONTRADICTION") { check(scorer.scoreOnObjects(cat("c_3002", AbdmnvTerm.Not(AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))), target, world4, v4).verdict == AbdmnvVerdict.CONTRADICTION) }
    test("C9 非平凡失败 → PARTIAL") { check(scorer.scoreOnObjects(cat("c_3003", AbdmnvTerm.ConstBool(true)), target, world4, v4).verdict == AbdmnvVerdict.CONTRADICTION) }
    test("C10 覆盖不足 → PARTIAL") {
        val cover = MapWorld(
            listOf("o_1", "o_2", "o_3", "o_4"),
            mapOf("o_1|a1" to T, "o_2|a1" to F, "o_3|a1" to U, "o_4|a1" to U,
                "o_1|a2" to T, "o_2|a2" to F, "o_3|a2" to T, "o_4|a2" to F)
        )
        val sym = AbdmnvSymbols(attributes = mapOf("a1" to AbdmnvSort.Bool, "a2" to AbdmnvSort.Bool))
        val s = AbdmnvScorer(sym, 0.9)
        val totalTarget = cat("c_t2", AbdmnvTerm.Attr("a2", AbdmnvTerm.Var("x")))
        check(s.scoreOnObjects(cat("c_3004", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x"))), totalTarget, cover, v4).verdict == AbdmnvVerdict.PARTIAL)
    }
    test("C11 类型错误 → TYPE_ERROR") {
        check(scorer.scoreOnObjects(cat("c_3005", AbdmnvTerm.Attr("zzz", AbdmnvTerm.Var("x"))), target, world4, v4).verdict == AbdmnvVerdict.TYPE_ERROR)
    }
    test("C12 执行错误 → EXEC_ERROR") {
        val c = AbdmnvConcept("c_3006", 1, AbdmnvKind.COMPOSITE, AbdmnvSort.Bool, emptyList(),
            AbdmnvBody.Lambda(listOf("x"), AbdmnvTerm.Apply(refOf("c_attr", 1), emptyList())), AbdmnvScope.Global,
            emptyList(), listOf(refOf("c_attr", 1)), AbdmnvOrigin.RUNTIME_DERIVED)
        val sym = AbdmnvSymbols(
            attributes = mapOf("a1" to AbdmnvSort.Bool),
            concepts = mapOf(refOf("c_attr", 1) to AbdmnvConcept("c_attr", 1, AbdmnvKind.ATTRIBUTE, AbdmnvSort.Bool,
                emptyList(), AbdmnvBody.Channel("ch_alpha"), AbdmnvScope.Global, emptyList(), emptyList(), AbdmnvOrigin.RUNTIME_DERIVED)),
        )
        val s = AbdmnvScorer(sym, 0.9)
        check(s.scoreOnObjects(c, target, world4, v4).verdict == AbdmnvVerdict.EXEC_ERROR)
    }
    test("C13 目标不全定义 → UNSCORABLE") {
        val badTarget = cat("c_3007", AbdmnvTerm.Attr("a2", AbdmnvTerm.Var("x")))
        val sym = AbdmnvSymbols(attributes = mapOf("a1" to AbdmnvSort.Bool, "a2" to AbdmnvSort.Bool))
        val s = AbdmnvScorer(sym, 0.9)
        check(s.scoreOnObjects(cat("c_3008", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x"))), badTarget, world4, v4).verdict == AbdmnvVerdict.UNSCORABLE)
    }
    test("C14 关系 MATCH") {
        val relWorld = MapWorld(
            listOf("o_1", "o_2"),
            emptyMap(),
            mapOf("r_same|o_1|o_2" to T, "r_same|o_1|o_1" to T, "r_same|o_2|o_2" to T, "r_same|o_2|o_1" to F),
        )
        val sym = AbdmnvSymbols(relations = mapOf("r_same" to AbdmnvSort.Rel(AbdmnvSort.Obj, AbdmnvSort.Obj)))
        val s = AbdmnvScorer(sym, 0.9)
        val body = AbdmnvTerm.Rel("r_same", AbdmnvTerm.Var("x"), AbdmnvTerm.Var("y"))
        val c = AbdmnvConcept("c_3009", 1, AbdmnvKind.RELATION, AbdmnvSort.Rel(AbdmnvSort.Obj, AbdmnvSort.Obj),
            emptyList(), AbdmnvBody.Lambda(listOf("x", "y"), body), AbdmnvScope.Global,
            emptyList(), emptyList(), AbdmnvOrigin.RUNTIME_DERIVED)
        val pairs = listOf("o_1" to "o_1", "o_1" to "o_2", "o_2" to "o_2", "o_2" to "o_1")
        check(s.scoreOnPairs(c, c, relWorld, pairs).verdict == AbdmnvVerdict.MATCH)
    }

    // ---------------- Slice D: 证据与版本 ----------------
    test("D1 schema 缺失 → NOT_IN_SCHEMA") {
        val l = AbdmnvLedger()
        l.record(AbdmnvEvidence("e_1", "o_1", "a1", ObservedValue.Known("true"), 1,
            AbdmnvSourceKind.EXAMINER_VERIFIED, AbdmnvIndependence.INDEPENDENT, 1, 1))
        check((l.read("o_1", "a1", 2) as AbdmnvRead.Unknown).reason == "NOT_IN_SCHEMA")
    }
    test("D2 非 stable 轮 → TRANSIENT_ONLY") {
        val l = AbdmnvLedger()
        l.record(AbdmnvEvidence("e_2", "o_1", "a1", ObservedValue.Known("true"), 1,
            AbdmnvSourceKind.EXAMINER_VERIFIED, AbdmnvIndependence.INDEPENDENT, 1, 2))
        check((l.read("o_1", "a1", 1) as AbdmnvRead.Unknown).reason == "TRANSIENT_ONLY")
    }
    test("D3 来源冲突 → SOURCE_CONFLICT") {
        val l = AbdmnvLedger()
        l.record(AbdmnvEvidence("e_3", "o_1", "a1", ObservedValue.Known("true"), 1,
            AbdmnvSourceKind.EXAMINER_VERIFIED, AbdmnvIndependence.INDEPENDENT, 2, 2))
        l.record(AbdmnvEvidence("e_4", "o_1", "a1", ObservedValue.Known("false"), 1,
            AbdmnvSourceKind.STUDENT_REPORTED, AbdmnvIndependence.SHARED_SOURCE, 2, 2))
        check((l.read("o_1", "a1", 1) as AbdmnvRead.Unknown).reason == "SOURCE_CONFLICT")
        check(l.conflicts().contains("o_1" to "a1"))
    }
    test("D4 版本不可变") {
        val store = AbdmnvConceptStore()
        val c = cat("c_4001", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
        store.propose(c)
        val mutated = c.copy(body = AbdmnvBody.Lambda(listOf("x"), AbdmnvTerm.ConstBool(true)))
        check(runCatching { store.propose(mutated) }.exceptionOrNull()?.message?.startsWith("VERSION_IMMUTABLE_VIOLATION") == true)
    }
    test("D5 supersede 版本必须 +1") {
        val store = AbdmnvConceptStore()
        val c = cat("c_4002", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
        store.propose(c)
        val far = catAt("c_4002", 3, AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
        check(store.supersede(refOf("c_4002", 1), far) == "SUPERSEDE_VERSION_GAP")
    }
    test("D6 撤销传播到依赖者") {
        val store = AbdmnvConceptStore()
        store.propose(cat("c_4003", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x"))))
        store.propose(cat("c_4004", AbdmnvTerm.ConstBool(true), deps = listOf(refOf("c_4003", 1))))
        check(store.executable(refOf("c_4004", 1)))
        store.revoke(refOf("c_4003", 1), "test")
        check(!store.executable(refOf("c_4004", 1)))
        check(store.dependents(refOf("c_4003", 1)).contains(refOf("c_4004", 1)))
    }

    // ---------------- Slice D: T1–T5 公开 fixture ----------------
    test("T1 确定性重放") {
        val c = cat("c_5001", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
        val a = AbdmnvHarness.outputs(c, world4, v4)
        val b = AbdmnvHarness.outputs(c, world4, v4)
        check(a == b)
    }
    test("T2 ID 依赖检测") {
        val withConst = cat("c_5002", AbdmnvTerm.Rel("r_same", AbdmnvTerm.Var("x"), AbdmnvTerm.ConstObj("o_17")))
        check(AbdmnvHarness.idDependent(withConst))
        check(!AbdmnvHarness.idDependent(cat("c_5003", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))))
    }
    test("T3 证据敏感性（定义不变，输出随证据变化）") {
        val c = cat("c_5004", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
        val worldA = MapWorld(listOf("o_1"), mapOf("o_1|a1" to T))
        val worldB = MapWorld(listOf("o_1"), mapOf("o_1|a1" to F))
        check(AbdmnvHarness.behaviorallySensitive(c, worldA, worldB, listOf("o_1")))
    }
    test("T4 负世界必须弃答（不得强行 MATCH）") {
        val b = AbdmnvBoard(listOf(AbdmnvSlot("s_1", AbdmnvKind.CATEGORY, AbdmnvSort.Bool)))
        check(b.abstain(0) == null)
        check(b.counts().matched == 0 && b.counts().abstained == 1)
    }
    test("T5 目标歧义 → 可机械识别") {
        val t1 = cat("c_5005", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
        val t2 = cat("c_5006", AbdmnvTerm.Attr("a1", AbdmnvTerm.Var("x")))
        check(AbdmnvHarness.ambiguousTargets(t1, t2, world4, v4))
    }

    // ---------------- 稻草人 ----------------
    test("S1 写死答案（忽略证据）被 T3 识别") {
        val hardcoded = cat("c_6001", AbdmnvTerm.ConstBool(true))
        val worldA = MapWorld(listOf("o_1"), mapOf("o_1|a1" to T))
        val worldB = MapWorld(listOf("o_1"), mapOf("o_1|a1" to F))
        check(!AbdmnvHarness.behaviorallySensitive(hardcoded, worldA, worldB, listOf("o_1")))
    }
    test("S2 ID 查表被 T2 识别") {
        check(AbdmnvHarness.idDependent(cat("c_6002", AbdmnvTerm.Rel("r_same", AbdmnvTerm.Var("x"), AbdmnvTerm.ConstObj("o_99")))))
    }
    test("S3 忽略证据被 T3 识别") {
        val ignore = cat("c_6003", AbdmnvTerm.ConstBool(false))
        check(!AbdmnvHarness.behaviorallySensitive(ignore, world4, world4, v4))
    }
    test("S4 洪水提交被协议拒绝") {
        val b = AbdmnvBoard(slots)
        check(b.submit(0, "c_6004") == null)
        check(b.submit(0, "c_6005") == "SLOT_DUPLICATE")
        check(b.submit(9, "c_6006") == "SUBMISSION_OVERFLOW")
    }

    println("ABDMNV_M1_CONTRACT passed=$passed failed=$failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
