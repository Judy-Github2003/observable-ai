package observable.ai.abdmnv.m1

class M1ContractException(val reason: String) : IllegalArgumentException(reason)

data class CheckResult(val ok: Boolean, val reason: String? = null)

class M1TypeChecker(private val view: StudentView, private val catalog: ConceptCatalog) {
    fun check(concept: Concept): CheckResult = try {
        checkConcept(concept, mutableSetOf(), 0)
        CheckResult(true)
    } catch (ex: M1ContractException) {
        CheckResult(false, ex.reason)
    }

    private fun fail(reason: String): Nothing = throw M1ContractException(reason)

    private fun checkConcept(concept: Concept, stack: MutableSet<Pair<String, Int>>, depth: Int) {
        if (depth > 24) fail("DEPTH_LIMIT")
        if (concept.ref.version <= 0 || concept.ref.hash.isBlank() || concept.ref.id.isBlank()) fail("INVALID_REF")
        if (concept.worldScope != null && concept.worldScope != view.worldId) fail("WORLD_SCOPE_VIOLATION")
        if (concept.params.map { it.name }.toSet().size != concept.params.size) fail("DUPLICATE_PARAMETER")
        val key = concept.ref.id to concept.ref.version
        if (!stack.add(key)) fail("CYCLE_DETECTED")
        try {
            when (concept.kind) {
                ConceptKind.ATTRIBUTE -> {
                    if (concept.params.size != 1 || concept.params[0].sort != Sort.Object ||
                        concept.body !is Term.Attribute) fail("ATTRIBUTE_SIGNATURE")
                }
                ConceptKind.CATEGORY -> {
                    if (concept.params.size != 1 || concept.params[0].sort != Sort.Object ||
                        concept.output != Sort.Bool) fail("CATEGORY_SIGNATURE")
                }
                ConceptKind.RELATION -> {
                    if (concept.params.size != 2 || concept.params.any { it.sort != Sort.Object } ||
                        concept.output != Sort.Bool) fail("RELATION_SIGNATURE")
                }
                ConceptKind.COMPOSITE -> if (!hasCall(concept.body)) fail("COMPOSITE_REQUIRES_CALL")
            }
            val actual = infer(concept.body, concept.params.associate { it.name to it.sort }, stack, depth, 0)
            if (actual != concept.output) fail("OUTPUT_TYPE_MISMATCH")
            for (dependency in concept.dependencies) {
                val target = catalog.resolve(dependency) ?: fail("DEPENDENCY_HASH_MISMATCH")
                checkConcept(target, stack, depth + 1)
            }
        } finally {
            stack.remove(key)
        }
    }

    private fun hasCall(term: Term): Boolean = when (term) {
        is Term.Call -> true
        is Term.Not -> hasCall(term.child)
        is Term.And -> hasCall(term.left) || hasCall(term.right)
        is Term.Or -> hasCall(term.left) || hasCall(term.right)
        is Term.Eq -> hasCall(term.left) || hasCall(term.right)
        is Term.LessThan -> hasCall(term.left) || hasCall(term.right)
        is Term.Member -> hasCall(term.value) || hasCall(term.set)
        is Term.Exists -> hasCall(term.body)
        is Term.ForAll -> hasCall(term.body)
        is Term.CountAtLeast -> hasCall(term.body)
        else -> false
    }

    private fun infer(
        term: Term, env: Map<String, Sort>, stack: MutableSet<Pair<String, Int>>,
        depth: Int, expressionDepth: Int
    ): Sort {
        if (expressionDepth > 64) fail("DEPTH_LIMIT")
        fun child(t: Term): Sort = infer(t, env, stack, depth, expressionDepth + 1)
        fun requireBool(t: Term) { if (child(t) != Sort.Bool) fail("EXPECTED_BOOL") }
        fun requireObject(t: Term) { if (child(t) != Sort.Object) fail("EXPECTED_OBJECT") }
        return when (term) {
            is Term.Var -> env[term.name] ?: fail("UNBOUND_VARIABLE")
            is Term.Constant -> sortOf(term.value)
            is Term.Attribute -> {
                requireObject(term.of)
                view.attributeSorts[term.channel] ?: fail("UNKNOWN_ATTRIBUTE_CHANNEL")
            }
            is Term.PairRelation -> {
                requireObject(term.left); requireObject(term.right)
                if (term.channel !in view.pairChannels) fail("UNKNOWN_PAIR_CHANNEL")
                Sort.Bool
            }
            is Term.Call -> {
                val target = catalog.resolve(term.ref) ?: fail("DEPENDENCY_HASH_MISMATCH")
                if (target.params.size != term.args.size) fail("CALL_ARITY")
                for ((param, arg) in target.params.zip(term.args)) {
                    if (param.sort != child(arg)) fail("CALL_ARGUMENT_TYPE")
                }
                checkConcept(target, stack, depth + 1)
                target.output
            }
            is Term.Not -> { requireBool(term.child); Sort.Bool }
            is Term.And -> { requireBool(term.left); requireBool(term.right); Sort.Bool }
            is Term.Or -> { requireBool(term.left); requireBool(term.right); Sort.Bool }
            is Term.Eq -> {
                if (child(term.left) != child(term.right)) fail("EQ_TYPE_MISMATCH")
                Sort.Bool
            }
            is Term.LessThan -> {
                if (child(term.left) != Sort.Int || child(term.right) != Sort.Int) fail("EXPECTED_INT")
                Sort.Bool
            }
            is Term.Member -> {
                val valueSort = child(term.value)
                if (child(term.set) != Sort.Set(valueSort)) fail("MEMBER_TYPE_MISMATCH")
                Sort.Bool
            }
            is Term.Exists -> {
                if (term.variable in env) fail("SHADOWED_VARIABLE")
                if (infer(term.body, env + (term.variable to Sort.Object), stack, depth, expressionDepth + 1) != Sort.Bool) fail("EXPECTED_BOOL")
                Sort.Bool
            }
            is Term.ForAll -> {
                if (term.variable in env) fail("SHADOWED_VARIABLE")
                if (infer(term.body, env + (term.variable to Sort.Object), stack, depth, expressionDepth + 1) != Sort.Bool) fail("EXPECTED_BOOL")
                Sort.Bool
            }
            is Term.CountAtLeast -> {
                if (term.variable in env) fail("SHADOWED_VARIABLE")
                if (term.minimum < 0) fail("INVALID_COUNT_THRESHOLD")
                if (infer(term.body, env + (term.variable to Sort.Object), stack, depth, expressionDepth + 1) != Sort.Bool) fail("EXPECTED_BOOL")
                Sort.Bool
            }
        }
    }

    companion object {
        fun sortOf(v: TypedValue): Sort = when (v) {
            is TypedValue.Bool -> Sort.Bool
            is TypedValue.IntNumber -> Sort.Int
            is TypedValue.EnumSymbol -> Sort.Enum(v.enumId)
            is TypedValue.ObjectId -> Sort.Object
            is TypedValue.SetValue -> Sort.Set(v.element)
        }
    }
}

/** Reference evaluator accepts PUBLIC observations only. No examiner data type is exposed. */
class M1Evaluator(private val view: StudentView, private val catalog: ConceptCatalog, val meter: MeasurementMeter = MeasurementMeter()) {
    private var steps = 0

    fun evaluate(concept: Concept, objectIds: List<String>, maxSteps: Int = 10_000): ValueResult {
        steps = 0
        val check = M1TypeChecker(view, catalog).check(concept)
        if (!check.ok) return ValueResult.Error(check.reason ?: "TYPE_ERROR")
        if (concept.params.size != objectIds.size) return ValueResult.Error("CALL_ARITY")
        if (objectIds.any { it !in view.knownObjects }) return ValueResult.Error("UNKNOWN_OBJECT")
        val env = concept.params.zip(objectIds).associate { (p, obj) ->
            p.name to ValueResult.Known(TypedValue.ObjectId(obj)) as ValueResult
        }
        return run(concept.body, env, maxSteps, 0)
    }

    private fun run(term: Term, env: Map<String, ValueResult>, maxSteps: Int, depth: Int): ValueResult {
        steps++
        if (steps > maxSteps || depth > 128) return ValueResult.Error("RESOURCE_LIMIT")
        fun child(x: Term): ValueResult = run(x, env, maxSteps, depth + 1)
        fun bool(x: ValueResult): Truth = x.truth()
        return when (term) {
            is Term.Var -> env[term.name] ?: ValueResult.Error("UNBOUND_VARIABLE")
            is Term.Constant -> ValueResult.Known(term.value)
            is Term.Attribute -> {
                when (val target = child(term.of)) {
                    is ValueResult.Unknown -> target
                    is ValueResult.Error -> target
                    is ValueResult.Known -> {
                        val obj = (target.value as? TypedValue.ObjectId)?.id
                            ?: return ValueResult.Error("EXPECTED_OBJECT")
                        val obs = view.observations[obj]
                        if (obs == null) ValueResult.Unknown("NOT_OBSERVED")
                        else if (term.channel !in obs.values) ValueResult.Unknown("NOT_IN_SCHEMA")
                        else obs.values[term.channel] ?: ValueResult.Unknown("NOT_OBSERVED")
                    }
                }
            }
            is Term.PairRelation -> {
                val l = child(term.left)
                val r = child(term.right)
                when {
                    l is ValueResult.Error -> l
                    r is ValueResult.Error -> r
                    l is ValueResult.Unknown || r is ValueResult.Unknown -> ValueResult.Unknown("UNKNOWN_PAIR")
                    else -> {
                        val left = ((l as ValueResult.Known).value as? TypedValue.ObjectId)?.id
                        val right = ((r as ValueResult.Known).value as? TypedValue.ObjectId)?.id
                        if (left == null || right == null) ValueResult.Error("EXPECTED_OBJECT")
                        else {
                            val key = PairKey(term.channel, left, right)
                            meter.record("pair:${key.channel}:${key.left}:${key.right}")
                            view.measuredPairs[key] ?: ValueResult.Unknown("NOT_MEASURED")
                        }
                    }
                }
            }
            is Term.Call -> {
                val target = catalog.resolve(term.ref) ?: return ValueResult.Error("DEPENDENCY_HASH_MISMATCH")
                val args = term.args.map(::child)
                val problem = args.firstOrNull { it is ValueResult.Error }
                if (problem != null) problem
                else if (args.any { it is ValueResult.Unknown }) ValueResult.Unknown("UNKNOWN_ARGUMENT")
                else {
                    val next = target.params.zip(args).associate { (param, value) -> param.name to value }
                    run(target.body, next, maxSteps, depth + 1)
                }
            }
            is Term.Not -> bool(child(term.child)).let(TruthLogic::not).toValue()
            is Term.And -> combine(child(term.left), child(term.right), and = true)
            is Term.Or -> combine(child(term.left), child(term.right), and = false)
            is Term.Eq -> compare(child(term.left), child(term.right)) { a, b -> a == b }
            is Term.LessThan -> compare(child(term.left), child(term.right)) { a, b ->
                a is TypedValue.IntNumber && b is TypedValue.IntNumber && a.value < b.value
            }
            is Term.Member -> compare(child(term.value), child(term.set)) { a, b ->
                b is TypedValue.SetValue && a in b.values
            }
            is Term.Exists -> quantified(term.variable, term.body, env, maxSteps, depth, exists = true)
            is Term.ForAll -> quantified(term.variable, term.body, env, maxSteps, depth, exists = false)
            is Term.CountAtLeast -> {
                val vals = view.knownObjects.sorted().map { obj ->
                    run(term.body, env + (term.variable to ValueResult.Known(TypedValue.ObjectId(obj))), maxSteps, depth + 1)
                }
                when {
                    vals.any { it is ValueResult.Error } -> mergedErrors(vals)
                    vals.any { it is ValueResult.Unknown } -> ValueResult.Unknown("COUNT_INCOMPLETE")
                    else -> ValueResult.Known(TypedValue.Bool(vals.count { it.truth() == Truth.TRUE }.toLong() >= term.minimum))
                }
            }
        }
    }

    private fun compare(a: ValueResult, b: ValueResult, equal: (TypedValue, TypedValue) -> Boolean): ValueResult = when {
        a is ValueResult.Error || b is ValueResult.Error -> mergedErrors(listOf(a, b))
        a is ValueResult.Unknown || b is ValueResult.Unknown -> ValueResult.Unknown("UNKNOWN_OPERAND")
        else -> ValueResult.Known(TypedValue.Bool(equal((a as ValueResult.Known).value, (b as ValueResult.Known).value)))
    }

    private fun combine(a: ValueResult, b: ValueResult, and: Boolean): ValueResult {
        if (a is ValueResult.Error || b is ValueResult.Error) return mergedErrors(listOf(a,b))
        val result = if (and) TruthLogic.and(a.truth(), b.truth()) else TruthLogic.or(a.truth(), b.truth())
        return result.toValue()
    }

    private fun mergedErrors(items: List<ValueResult>): ValueResult.Error =
        ValueResult.Error(items.filterIsInstance<ValueResult.Error>().flatMap { it.codes }.toSortedSet())

    private fun quantified(
        variable: String, body: Term, env: Map<String, ValueResult>,
        maxSteps: Int, depth: Int, exists: Boolean
    ): ValueResult {
        val vals = view.knownObjects.sorted().map { obj ->
            run(body, env + (variable to ValueResult.Known(TypedValue.ObjectId(obj))), maxSteps, depth + 1)
        }
        if (vals.any { it is ValueResult.Error }) return mergedErrors(vals)
        val accumulated = vals.map { it.truth() }.fold(if (exists) Truth.FALSE else Truth.TRUE) { a, b ->
            if (exists) TruthLogic.or(a, b) else TruthLogic.and(a, b)
        }
        return accumulated.toValue()
    }
}
