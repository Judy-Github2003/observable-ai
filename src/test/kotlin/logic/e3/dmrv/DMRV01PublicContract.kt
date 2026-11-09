package logic.e3.dmrv

/** Standalone public checks, no hidden official examiner scoring. */
object DMRV01PublicContract {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun test(name: String, block: () -> Unit) { block(); n++; println("PASS $n $name") }
        val student = DmrvReferenceStudent()
        fun task(target: DmrvExpr, inputs: List<Int> = (0..15).toList()): DmrvPublicTask =
            DmrvPublicTask("public-task", inputs.map { DmrvExample(it, target.eval(it)) })
        test("enum representation count") { check(DmrvAction.entries.size == 4) }
        test("bounded grammar sizes") {
            check(DmrvGrammar.options(DmrvAction.BASE_ONLY).size == 8)
            check(DmrvGrammar.options(DmrvAction.ADD_BINARY).size == 44)
            check(DmrvGrammar.options(DmrvAction.ADD_PARITY3).size == 16)
            check(DmrvGrammar.options(DmrvAction.ADD_MUX).size == 32)
        }
        test("all expressions encode/decode") {
            DmrvAction.entries.flatMap(DmrvGrammar::options).forEach { e ->
                val roundtrip = DmrvExpr.decode(e.encode())
                check(e.encode() == roundtrip.encode())
                check((0..15).all { e.eval(it) == roundtrip.eval(it) })
            }
        }
        test("unary target can be learned without representation extension") {
            val r = student.synthesize(task(DmrvExpr.Literal(2, true)), DmrvAction.BASE_ONLY)
            check(r.expr != null && r.behaviorClasses == 1)
            check((0..15).all { r.predict(it) == DmrvExpr.Literal(2, true).eval(it) })
        }
        test("XOR target requires an extension") {
            val t = task(DmrvExpr.Pair(0,1,DmrvBinary.XOR))
            check(student.synthesize(t,DmrvAction.BASE_ONLY).expr == null)
            check(student.synthesize(t,DmrvAction.ADD_BINARY).expr != null)
        }
        test("three-input parity target requires its extension") {
            val t = task(DmrvExpr.Parity3(0,1,3))
            check(student.synthesize(t,DmrvAction.ADD_BINARY).expr == null)
            check(student.synthesize(t,DmrvAction.ADD_PARITY3).expr != null)
        }
        test("conditional target requires its extension") {
            val t = task(DmrvExpr.Mux(0,1,2))
            check(student.synthesize(t,DmrvAction.ADD_BINARY).expr == null)
            check(student.synthesize(t,DmrvAction.ADD_MUX).expr != null)
        }
        test("underidentified behavior must abstain") {
            val t = DmrvPublicTask("ambiguous", listOf(DmrvExample(0,false)))
            val r = student.synthesize(t,DmrvAction.ADD_BINARY)
            check(r.behaviorClasses > 1 && r.expr == null)
        }
        test("task id not a feature of diagnostic") {
            val sample = task(DmrvExpr.Pair(1,3,DmrvBinary.XOR), listOf(0,1,2,3,4,5,6,7))
            check(student.diagnose(sample).context() == student.diagnose(sample.copy(taskId="different")).context())
        }
        test("contextual policy learns from scalar reward") {
            val p = DmrvMetaPolicy(priorStrength=1.0)
            repeat(4) { p.observe(DmrvFeedback("GAP/MID/CHANGING",DmrvAction.ADD_BINARY,1.0)) }
            repeat(4) { p.observe(DmrvFeedback("GAP/MID/CHANGING",DmrvAction.ADD_MUX,-0.2)) }
            repeat(4) { p.observe(DmrvFeedback("FIT/MID/CALM",DmrvAction.ADD_BINARY,-0.2)) }
            repeat(4) { p.observe(DmrvFeedback("FIT/MID/CALM",DmrvAction.ADD_MUX,1.0)) }
            check(p.choose("GAP/MID/CHANGING",true).action == DmrvAction.ADD_BINARY)
            check(p.choose("FIT/MID/CALM",true).action == DmrvAction.ADD_MUX)
        }
        test("global-only control uses same rewards without context") {
            val p = DmrvMetaPolicy(priorStrength=1.0)
            repeat(2) { p.observe(DmrvFeedback("a",DmrvAction.ADD_BINARY,1.0)); p.observe(DmrvFeedback("b",DmrvAction.ADD_MUX,0.1)) }
            check(p.choose("a",false).action == p.choose("b",false).action)
        }
        test("policy frozen snapshot reload") {
            val p = DmrvMetaPolicy()
            p.observe(DmrvFeedback("NEAR/LOW/CALM",DmrvAction.BASE_ONLY,0.3))
            p.observe(DmrvFeedback("GAP/MID/CHANGING",DmrvAction.ADD_BINARY,0.8))
            val restored = DmrvMetaPolicy.restore(p.snapshot())
            check(restored.snapshot() == p.snapshot())
        }
        test("reject duplicate/illegal public observations") {
            var rejected = false
            try { DmrvPublicTask("a",listOf(DmrvExample(0,true),DmrvExample(0,false))) } catch (_:IllegalArgumentException) { rejected = true }
            check(rejected)
        }
        test("representation decision has no hidden family field") {
            val fields = DmrvPublicTask::class.java.declaredFields.map { it.name }.toSet()
            check(!fields.contains("family") && !fields.contains("target") && !fields.contains("heldoutTruth"))
        }
        println("DMRV01_PUBLIC_PASS tests=$n")
    }
}
