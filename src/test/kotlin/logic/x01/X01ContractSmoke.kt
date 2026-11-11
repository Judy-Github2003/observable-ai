package logic.x01
import observable.ai.abdmnv.m1.*
import logic.capability.*

private fun expectReject(code: String, action: () -> Unit) {
    try { action(); error("expected reject $code") }
    catch (e: X01Reject) { check(e.reasonCode.startsWith(code)) { "expected $code got ${e.reasonCode}" } }
}

private fun evaluate(e: Expr, args: Map<String, EvalResult>): CapValue = when (e) {
    is Expr.Arg -> ((args[e.name] as? EvalResult.Known) ?: error("missing argument")).value
    is Expr.Const -> e.value
    is Expr.Field -> (evaluate(e.record,args) as CapValue.Rec).fields[e.name] ?: error("missing field")
    is Expr.Eq -> CapValue.Bool(evaluate(e.left,args)==evaluate(e.right,args))
    is Expr.And -> CapValue.Bool((evaluate(e.left,args) as CapValue.Bool).value && (evaluate(e.right,args) as CapValue.Bool).value)
    is Expr.Or -> CapValue.Bool((evaluate(e.left,args) as CapValue.Bool).value || (evaluate(e.right,args) as CapValue.Bool).value)
    is Expr.Not -> CapValue.Bool(!(evaluate(e.value,args) as CapValue.Bool).value)
    // 真实 B0 Expr 比离线契约桩多 If/Let/Ref/Call；本片不支持，直接失败而不是猜语义。
    else -> error("UNSUPPORTED_EXPR_IN_CONTRACT_EVALUATOR:${e.javaClass.simpleName}")
}

private class Fixture {
    val store=CombinedConceptVersionStore()
    val ledger=CombinedEvidenceLedger()
    val colors=listOf("red", "blue", "green", "yellow")
    val observations=colors.mapIndexed { idx, color ->
        val e=Evidence("ev_$idx","world_x01","obj_$idx",1,1,"student","PUBLIC_QUERY","INDEPENDENT",
            mapOf("color" to ValueResult.Known(TypedValue.EnumSymbol(color,"palette"))),false)
        check(ledger.record(e)=="OK");e
    }
    val view=StudentView("world_x01",1, observations.map { it.objectId }, mapOf("color" to Sort.Enum("palette")), emptySet(),
        observations.associateBy { it.objectId })
    val concept: Concept
    val ref:ConceptRef
    val bridge=X01ConceptBridge(store, ledger)
    init {
        // The source is an ACTUAL M1 student-induced bounded concept, not a hard-coded rule.
        val learned=MinimalCategoryStudent().learn(view, listOf(
            TrainingLabel("obj_0",true),TrainingLabel("obj_1",false),
            TrainingLabel("obj_2",false),TrainingLabel("obj_3",false)))
        check(learned.status=="STUDENT_COMPOSED_LIMITED") { learned.status }
        concept=learned.concept ?: error("No student-produced concept")
        ref=concept.ref
        check(store.propose(concept)==ref.hash)
        check(store.activate(ref)=="OK")
    }
}

fun runContractSmoke() {
    var passes=0
    fun run(name:String,body:()->Unit) {body();passes++; println("PASS $name")}
    val f=Fixture()
    val candidate=f.bridge.compile(f.ref,f.view)
    run("01 M1 student induced a stored active concept") {check(f.store.executable(f.ref))}
    run("02 M1 to B0 typed AST + separate hashes") {
        check(candidate.definition.body is Expr.Eq)
        check(candidate.definition.output==CapType.Bool3)
        check(candidate.provenanceJson.contains("UNVERIFIED_BY_B0"))
        check(candidate.source.hash==f.ref.hash)
        check(candidate.b0ContentHash.isNotBlank())
    }
    run("03 observed finite-domain evaluator agreement") {
        for(id in f.view.knownObjects) {
            val observed=f.bridge.materialize(candidate,f.view,id)
            val m1=M1Evaluator(f.view,PublicConceptCatalog(listOf(f.concept))).evaluate(f.concept,listOf(id))
            val b0=evaluate(candidate.definition.body,observed)
            check(m1 == ValueResult.Known(TypedValue.Bool((b0 as CapValue.Bool).value))) { "$id $m1 $b0" }
        }
    }
    run("04 scope mismatch rejected") {expectReject("WORLD_SCOPE_UNSUPPORTED") {
        f.bridge.compile(f.ref,StudentView("other_world",1,emptyList(),f.view.attributeSorts,emptySet(),emptyMap()))
    }}
    run("05 forged content hash rejected") {expectReject("SOURCE_HASH_OR_REF_INVALID") {
        f.bridge.compile(f.ref.copy(hash="f".repeat(64)),f.view)
    }}
    run("06 unknown value rejected not false") {
        val obs=f.view.observations.toMutableMap()
        obs["obj_0"]=obs.getValue("obj_0").copy(values=mapOf("color" to ValueResult.Unknown("missing")))
        expectReject("INCOMPLETE_OR_UNKNOWN_ATTRIBUTE") {
            f.bridge.materialize(candidate,f.view.copy(observations=obs),"obj_0")
        }
    }
    run("07 mismatched enum rejected") {
        val obs=f.view.observations.toMutableMap()
        obs["obj_1"]=obs.getValue("obj_1").copy(values=mapOf("color" to ValueResult.Known(TypedValue.EnumSymbol("blue","wrong_enum"))))
        expectReject("ATTRIBUTE_VALUE_TYPE_MISMATCH") {
            f.bridge.materialize(candidate,f.view.copy(observations=obs),"obj_1")
        }
    }
    run("08 missing evidence rejected") {
        val store=CombinedConceptVersionStore()
        val c=CombinedConceptHashing.seal(f.concept.copy(ref=ConceptRef("other",1,""),evidenceIds=setOf("absent")))
        store.propose(c);store.activate(c.ref)
        expectReject("SOURCE_EVIDENCE_NOT_FOUND") {X01ConceptBridge(store,f.ledger).compile(c.ref,f.view)}
    }
    run("09 unsupported syntax rejected") {
        val store=CombinedConceptVersionStore()
        val c=CombinedConceptHashing.seal(f.concept.copy(ref=ConceptRef("other2",1,""),body=Term.LessThan(Term.Constant(TypedValue.IntNumber(1)),Term.Constant(TypedValue.IntNumber(2)))))
        store.propose(c);store.activate(c.ref)
        expectReject("UNSUPPORTED_TERM") {X01ConceptBridge(store,f.ledger).compile(c.ref,f.view)}
    }
    run("10 inactive or revoked source rejected") {
        check(f.store.revoke(f.ref,"superseded") == "OK")
        expectReject("SOURCE_NOT_ACTIVE_OR_DEPENDENCY_INVALID") {f.bridge.compile(f.ref,f.view)}
    }
    run("11 M1 signed flag cannot become B0 trusted fixture") {
        check(candidate.provenanceJson.contains("UNVERIFIED_BY_B0"))
        check(candidate.provenanceJson.contains("PUBLIC_QUERY"))
        check(candidate.provenanceJson.contains("signed_by_examiner"))
    }
    run("12 And/Not composition on total attributes") {
        val store=CombinedConceptVersionStore()
        val c=CombinedConceptHashing.seal(f.concept.copy(ref=ConceptRef("and_not",1,""),
            body=Term.And(f.concept.body,Term.Not(Term.Constant(TypedValue.Bool(false))))))
        store.propose(c);store.activate(c.ref)
        val bridge=X01ConceptBridge(store,f.ledger)
        val compiled=bridge.compile(c.ref,f.view)
        for(id in f.view.knownObjects) {
            val observed=bridge.materialize(compiled,f.view,id)
            val expected=M1Evaluator(f.view,PublicConceptCatalog(listOf(c))).evaluate(c,listOf(id))
            val actual=evaluate(compiled.definition.body,observed)
            check(expected==ValueResult.Known(TypedValue.Bool((actual as CapValue.Bool).value)))
        }
    }
    run("13 Or composition on total attributes") {
        val store=CombinedConceptVersionStore()
        val c=CombinedConceptHashing.seal(f.concept.copy(ref=ConceptRef("or_bool",1,""),
            body=Term.Or(f.concept.body,Term.Constant(TypedValue.Bool(false)))))
        store.propose(c);store.activate(c.ref)
        val bridge=X01ConceptBridge(store,f.ledger)
        val compiled=bridge.compile(c.ref,f.view)
        for(id in f.view.knownObjects) {
            val observed=bridge.materialize(compiled,f.view,id)
            val expected=M1Evaluator(f.view,PublicConceptCatalog(listOf(c))).evaluate(c,listOf(id))
            val actual=evaluate(compiled.definition.body,observed)
            check(expected==ValueResult.Known(TypedValue.Bool((actual as CapValue.Bool).value)))
        }
    }
    run("14 cross-world evidence refused") {
        val store=CombinedConceptVersionStore()
        val foreign=Evidence("foreign", "another_world", "o_5",1,1,"attacker","PUBLIC_QUERY","INDEPENDENT",emptyMap())
        val ledger=CombinedEvidenceLedger()
        ledger.record(foreign)
        val c=CombinedConceptHashing.seal(f.concept.copy(ref=ConceptRef("foreign_c",1,""),evidenceIds=setOf("foreign")))
        store.propose(c);store.activate(c.ref)
        expectReject("EVIDENCE_WORLD_MISMATCH") {X01ConceptBridge(store,ledger).compile(c.ref,f.view)}
    }
    println("X01_CONTRACT_SMOKE=$passes/14; B0_DB_E2E=NOT_RUN; INDEPENDENT_CLOSED_BOOK=NOT_RUN")
}

fun main() { runContractSmoke() }

