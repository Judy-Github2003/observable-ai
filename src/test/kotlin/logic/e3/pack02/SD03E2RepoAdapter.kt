package logic.e3.pack02

import logic.capability.*

/** Real Capability bridge. No world, truth, Challenge, or HELDOUT in this API. */
class SD03E2RepoAdapter(private val library: List<CapabilityDefinition>) {
    private val entries: List<Pair<String, Expr>> = buildList {
        for (field in C8Domain.attributes) for (value in C8Domain.values) {
            val expr = Expr.Eq(Expr.Arg(field, CapType.Str),Expr.Const(CapValue.Str(value)))
            add(CanonicalJson.expr(expr) to expr)
        }
        for (definition in library) {
            if (definition.inputs.keys.all { it in C8Domain.attributes } && definition.inputs.values.all { it == CapType.Str }) {
                val expr = Expr.Call(definition.id,definition.version,definition.contentHash(),
                    definition.inputs.keys.associateWith { Expr.Arg(it,CapType.Str) })
                add(CanonicalJson.expr(expr) to expr)
            }
        }
    }.distinctBy { it.first }.sortedBy { it.first }
    private val byId = entries.toMap()
    val catalogSize: Int get() = entries.size
    var atomInterpreterCalls: Long = 0
        private set
    var candidateInterpreterCalls: Long = 0
        private set
    var e1PartitionAttempts: Long = 0
        private set
    var e1PredictionReads: Long = 0
        private set
    var e1MemoHits: Long = 0
        private set
    var e1Proposals: Int = 0
        private set

    fun atomPreview(input: Map<String,String>): Map<String,Boolean?> = entries.associate { (id,expr) ->
        atomInterpreterCalls++
        id to Pack02Knowledge.predict(expr,input,library)
    }
    fun atomCatalog(public: List<E1Example>): List<E1Atom> {
        val previews = public.associate { it.id to atomPreview(it.fields) }
        return entries.mapIndexed { i,(id,_) -> E1Atom(id,public.associate { e -> e.id to previews.getValue(e.id)[id] },"PUBLIC_ATOM:$i") }
    }
    fun toExpr(program: E1Program): Expr = when(program) {
        is E1Program.Constant -> Expr.Const(CapValue.Bool(program.value))
        is E1Program.Branch -> Expr.If(byId.getValue(program.atomId),toExpr(program.whenTrue),toExpr(program.whenFalse))
    }
    fun certified(program: E1Program, public: List<E1Example>): Boolean {
        val body=toExpr(program)
        val def=CapabilityDefinition("sd03.e2.proposal",1,C8Domain.attributes.associateWith { CapType.Str },CapType.Bool3,body)
        if(!CapabilityTypeChecker(InMemoryResolver(library)).check(def).ok) return false
        return public.all { e ->
            candidateInterpreterCalls++
            Pack02Knowledge.predict(body,e.fields,library)==e.label
        }
    }
    fun propose(public: List<E1Example>, config: E1Config): E1Result {
        val result=SD03EvidenceCompositionE1.propose(public,atomCatalog(public),config)
        e1Proposals++
        e1PartitionAttempts+=result.splitAttempts
        e1PredictionReads+=result.predictionReads
        e1MemoHits+=result.memoHits
        return result
    }

    /** Deterministic alternative learners trained only on subsets of CURRENT public labels.
     * They may disagree with some TRAIN cases; they are hypotheses for query choice,
     * not Challenge-eligible programs. Do not promote them to verified capabilities.
     */
    fun competingModels(public: List<E1Example>, current: E1Program, config: E1Config): List<E2Model> {
        val models=mutableListOf(E2Model("CURRENT",current))
        for (k in 0..3) {
            val sub=public.filterIndexed { i,_ -> i%4!=k }
            if(sub.isEmpty()) continue
            val fit=propose(sub,config)
            fit.program?.let { models += E2Model("TRAIN_SUBSET_${k}",it) }
        }
        // Smaller depth offers a second type of inductive bias without target hints.
        val shallow=propose(public,config.copy(maxDepth=minOf(config.maxDepth,2),beamWidthPerNode=3))
        shallow.program?.let { models += E2Model("TRAIN_SHALLOW",it) }
        return models
    }
}
