package logic.e3.bmnv

/** Standalone public checks; no JUnit dependency and no official examiner seeds. */
object BMNV01PublicContract {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun test(block: () -> Unit) { block(); n++ }
        val rows = (0 until 8).flatMap { bits -> (0..1).map { ctx ->
            val p = bits and 1 != 0; val q = bits and 2 != 0
            BmnvExample("A-$bits-$ctx", ctx == 1, p != q)
        } }
        val port = object : BmnvReadPort {
            override fun read(objectId: String, sensor: BmnvSensor): Boolean {
                val bits = objectId.split('-')[1].toInt()
                return (bits shr sensor.ordinal) and 1 != 0
            }
        }
        val learner = BMNV01ReferenceStudent()
        test { check(rows.groupBy { it.context }.values.all { it.map(BmnvExample::label).distinct().size > 1 }) }
        val a = learner.learnA(rows, port)
        test { check(a.status == BmnvStatus.LEARNED_BOUNDED && a.concept != null && a.concept.expression.sensors().size == 2) }
        test { check(a.measurements == 32 && a.queriedChannels.size == 2) }
        val memory = BmnvConcept.decode(a.concept!!.encode())
        test { check(memory == a.concept) }
        val bRows = rows.map { it.copy(id = it.id.replace("A-", "B-"), label = (it.label != it.context)) }
        val b = learner.learnB(bRows, port, memory)
        test { check(b.status == BmnvStatus.LEARNED_BOUNDED && b.rule!!.wrapper == BmnvWrapper.XOR_CONTEXT) }
        val direct = learner.learnBFromScratch(bRows, port)
        test { check(direct.expr != null && direct.measurements == 48) }
        test { check(direct.infer(true, mapOf(BmnvSensor.ALPHA to true, BmnvSensor.BETA to false, BmnvSensor.GAMMA to false)) == false) }
        test { check(learner.learnA(rows, port, 15).status == BmnvStatus.NOT_FOUND) }
        val noGap = rows.map { it.copy(label = it.context) }
        test { check(learner.learnA(noGap, port).status == BmnvStatus.NO_VISIBLE_COLLISION) }
        val badPort = object : BmnvReadPort { override fun read(objectId: String, sensor: BmnvSensor): Boolean = false }
        test { check(learner.learnA(rows, badPort).status == BmnvStatus.NOT_FOUND) }
        test { check(runCatching { BmnvExpr.decode("XOR|ALPHA|MISSING") }.isFailure) }
        println("BMNV01_PUBLIC_CONTRACT_PASS checks=$n")
    }
}
