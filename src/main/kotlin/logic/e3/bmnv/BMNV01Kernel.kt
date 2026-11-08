package logic.e3.bmnv

/** BMNV-01 reference algorithm, supplied by ChatGPT; NOT evidence of student invention.
 * Public student code. No references to test world, target expression, hidden labels or seeds.
 * Dependencies: Kotlin stdlib only. Designed as a separate experimental namespace.
 */
enum class BmnvSensor { ALPHA, BETA, GAMMA }
enum class BmnvOp { ID, NOT, XOR, XNOR, AND, OR, NAND, NOR }
enum class BmnvWrapper { DIRECT, NOT, AND_CONTEXT, OR_CONTEXT, XOR_CONTEXT, XNOR_CONTEXT }
enum class BmnvStatus { NO_VISIBLE_COLLISION, LEARNED_BOUNDED, AMBIGUOUS, NOT_FOUND }

data class BmnvExample(val id: String, val context: Boolean, val label: Boolean)
data class BmnvQuery(val id: String, val context: Boolean)

/** A read is a real, metered measurement. The port must never expose labels or world metadata. */
interface BmnvReadPort { fun read(objectId: String, sensor: BmnvSensor): Boolean }

/** Primitive operators are public. Which sensors/operator implement the target is NOT known to learner. */
data class BmnvExpr(val op: BmnvOp, val first: BmnvSensor, val second: BmnvSensor? = null) {
    init { require((op == BmnvOp.ID || op == BmnvOp.NOT) == (second == null)) }
    fun sensors(): Set<BmnvSensor> = setOfNotNull(first, second)
    fun eval(reading: Map<BmnvSensor, Boolean>): Boolean? {
        val a = reading[first] ?: return null
        val b = second?.let { reading[it] }
        if (second != null && b == null) return null
        return when (op) {
            BmnvOp.ID -> a
            BmnvOp.NOT -> !a
            BmnvOp.XOR -> a != b!!
            BmnvOp.XNOR -> a == b!!
            BmnvOp.AND -> a && b!!
            BmnvOp.OR -> a || b!!
            BmnvOp.NAND -> !(a && b!!)
            BmnvOp.NOR -> !(a || b!!)
        }
    }
    fun encode(): String = "${op.name}|${first.name}|${second?.name ?: "-"}"
    companion object {
        fun decode(s: String): BmnvExpr {
            val fields = s.split('|'); require(fields.size == 3)
            return BmnvExpr(BmnvOp.valueOf(fields[0]), BmnvSensor.valueOf(fields[1]),
                if (fields[2] == "-") null else BmnvSensor.valueOf(fields[2]))
        }
    }
}

object BmnvGrammar {
    fun candidates(known: Collection<BmnvSensor>): List<BmnvExpr> {
        val sensors = known.distinct().sortedBy { it.name }
        val unary = sensors.flatMap { s -> listOf(BmnvExpr(BmnvOp.ID, s), BmnvExpr(BmnvOp.NOT, s)) }
        val binary = mutableListOf<BmnvExpr>()
        for (i in sensors.indices) for (j in i + 1 until sensors.size) {
            for (op in listOf(BmnvOp.XOR, BmnvOp.XNOR, BmnvOp.AND, BmnvOp.OR, BmnvOp.NAND, BmnvOp.NOR)) {
                binary += BmnvExpr(op, sensors[i], sensors[j])
            }
        }
        return unary + binary
    }
    /** Semantic equivalence is over the entire declared 3-bit vocabulary, not observed rows only. */
    fun signature(expr: BmnvExpr): String = (0 until 8).joinToString("") { n ->
        val values = BmnvSensor.entries.associateWith { ((n shr it.ordinal) and 1) != 0 }
        if (expr.eval(values) == true) "1" else "0"
    }
    fun signature(expr: BmnvExpr, wrapper: BmnvWrapper): String = buildString {
        for (n in 0 until 8) for (ctx in listOf(false, true)) {
            val values = BmnvSensor.entries.associateWith { ((n shr it.ordinal) and 1) != 0 }
            val result = applyWrapper(wrapper, expr.eval(values)!!, ctx)
            append(if (result) '1' else '0')
        }
    }
    fun applyWrapper(w: BmnvWrapper, concept: Boolean, context: Boolean): Boolean = when (w) {
        BmnvWrapper.DIRECT -> concept
        BmnvWrapper.NOT -> !concept
        BmnvWrapper.AND_CONTEXT -> concept && context
        BmnvWrapper.OR_CONTEXT -> concept || context
        BmnvWrapper.XOR_CONTEXT -> concept != context
        BmnvWrapper.XNOR_CONTEXT -> concept == context
    }
}

data class BmnvConcept(
    val name: String,
    val expression: BmnvExpr,
    val evidenceIds: List<String>,
    val observationsRead: Int,
    val semanticSignature: String,
) {
    fun encode(): String = listOf(name, expression.encode(), observationsRead.toString(), semanticSignature,
        evidenceIds.joinToString(",")).joinToString("#")
    companion object {
        fun decode(text: String): BmnvConcept {
            val p = text.split('#'); require(p.size == 5)
            val c = BmnvConcept(p[0], BmnvExpr.decode(p[1]), p[4].split(',').filter { it.isNotEmpty() }, p[2].toInt(), p[3])
            require(c.semanticSignature == BmnvGrammar.signature(c.expression))
            return c
        }
    }
}

data class BmnvAResult(
    val status: BmnvStatus,
    val collision: Boolean,
    val concept: BmnvConcept?,
    val measurements: Int,
    val candidatesEvaluated: Int,
    val consistentCandidates: Int,
    val behaviorClasses: Int,
    val queriedChannels: List<BmnvSensor>,
)

data class BmnvBRule(val concept: BmnvConcept, val wrapper: BmnvWrapper) {
    fun infer(context: Boolean, readings: Map<BmnvSensor, Boolean>): Boolean? =
        concept.expression.eval(readings)?.let { BmnvGrammar.applyWrapper(wrapper, it, context) }
}

data class BmnvBResult(val rule: BmnvBRule?, val measurements: Int, val candidatesEvaluated: Int,
    val behaviorClasses: Int, val status: BmnvStatus)

data class BmnvDirectResult(val expr: BmnvExpr?, val wrapper: BmnvWrapper?, val measurements: Int,
    val candidatesEvaluated: Int, val behaviorClasses: Int) {
    fun infer(context: Boolean, readings: Map<BmnvSensor, Boolean>): Boolean? =
        if (expr == null || wrapper == null) null else expr.eval(readings)?.let {
            BmnvGrammar.applyWrapper(wrapper, it, context)
        }
}

/** Evaluates labels on publicly measured rows only. No world-name or target lookup. */
class BMNV01ReferenceStudent {
    fun learnA(train: List<BmnvExample>, sensorPort: BmnvReadPort, maxMeasurements: Int = 48): BmnvAResult {
        require(train.map { it.id }.distinct().size == train.size)
        require(maxMeasurements >= 0)
        val collision = train.groupBy { it.context }.values.any { rows -> rows.map { it.label }.distinct().size > 1 }
        if (!collision) return BmnvAResult(BmnvStatus.NO_VISIBLE_COLLISION, false, null, 0, 0, 0, 0, emptyList())
        val values = train.associate { it.id to mutableMapOf<BmnvSensor, Boolean>() }
        val channels = mutableListOf<BmnvSensor>()
        var reads = 0; var evaluated = 0; var survivorCount = 0; var classCount = 0
        // Cheap-first pilot policy; NOT demonstrated to outperform fixed-order querying.
        for (sensor in BmnvSensor.entries) {
            if (maxMeasurements - reads < train.size) break
            train.forEach { row -> values.getValue(row.id)[sensor] = sensorPort.read(row.id, sensor); reads++ }
            channels += sensor
            val candidates = BmnvGrammar.candidates(channels)
            evaluated += candidates.size
            val fitting = candidates.filter { model -> train.all { row -> model.eval(values.getValue(row.id)) == row.label } }
            val groups = fitting.groupBy { BmnvGrammar.signature(it) }
            survivorCount = fitting.size; classCount = groups.size
            if (groups.size == 1) {
                val chosen = groups.values.single().minWith(compareBy<BmnvExpr> { it.sensors().size }.thenBy { it.encode() })
                val memory = BmnvConcept("LEARNED_${chosen.op.name}_${chosen.first.name}", chosen,
                    train.map { it.id }, reads, BmnvGrammar.signature(chosen))
                return BmnvAResult(BmnvStatus.LEARNED_BOUNDED, true, memory, reads,
                    evaluated, survivorCount, classCount, channels)
            }
        }
        return BmnvAResult(if (classCount > 1) BmnvStatus.AMBIGUOUS else BmnvStatus.NOT_FOUND,
            true, null, reads, evaluated, survivorCount, classCount, channels)
    }

    fun learnB(train: List<BmnvExample>, port: BmnvReadPort, memory: BmnvConcept?, maxMeasurements: Int = 48): BmnvBResult {
        if (memory == null) return BmnvBResult(null, 0, 0, 0, BmnvStatus.NOT_FOUND)
        val required = memory.expression.sensors().sortedBy { it.name }
        if (maxMeasurements < required.size * train.size) return BmnvBResult(null, 0, 0, 0, BmnvStatus.NOT_FOUND)
        val values = train.associate { it.id to required.associateWith { s -> port.read(it.id, s) } }
        val survivors = BmnvWrapper.entries.filter { w -> train.all { row ->
            BmnvGrammar.applyWrapper(w, memory.expression.eval(values.getValue(row.id))!!, row.context) == row.label
        } }
        val groups = survivors.groupBy { BmnvGrammar.signature(memory.expression, it) }
        val selected = groups.values.singleOrNull()?.firstOrNull()
        return BmnvBResult(selected?.let { BmnvBRule(memory, it) }, train.size * required.size,
            BmnvWrapper.entries.size, groups.size,
            if (selected == null) { if (groups.size > 1) BmnvStatus.AMBIGUOUS else BmnvStatus.NOT_FOUND }
            else BmnvStatus.LEARNED_BOUNDED)
    }

    /** NO_MEMORY baseline: re-search the expression AND B wrapper directly from B labels. */
    fun learnBFromScratch(train: List<BmnvExample>, port: BmnvReadPort, maxMeasurements: Int = 48): BmnvDirectResult {
        if (maxMeasurements < BmnvSensor.entries.size * train.size) return BmnvDirectResult(null, null, 0, 0, 0)
        val values = train.associate { it.id to BmnvSensor.entries.associateWith { s -> port.read(it.id, s) } }
        val candidates = BmnvGrammar.candidates(BmnvSensor.entries).flatMap { expr ->
            BmnvWrapper.entries.map { wrapper -> expr to wrapper }
        }
        val fitting = candidates.filter { (expr, wrapper) -> train.all { row ->
            BmnvGrammar.applyWrapper(wrapper, expr.eval(values.getValue(row.id))!!, row.context) == row.label
        } }
        val groups = fitting.groupBy { (expr, wrapper) -> BmnvGrammar.signature(expr, wrapper) }
        val chosen = groups.values.singleOrNull()?.minWithOrNull(compareBy<Pair<BmnvExpr, BmnvWrapper>> { it.first.encode() }.thenBy { it.second.name })
        return BmnvDirectResult(chosen?.first, chosen?.second,
            train.size * BmnvSensor.entries.size, candidates.size, groups.size)
    }
}
