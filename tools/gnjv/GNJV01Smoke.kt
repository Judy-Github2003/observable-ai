package logic.e3.gnjv

/** Zero-dependency public smoke, runnable with kotlinc (not a scientific experiment). */
fun main() {
    val baseline = GNJVObservation(false, false)
    val learner = GNJV01Student()
    check(learner.identified() == null)
    val first = learner.choose(baseline)
    check(first.intervention?.target == CausalVariable.X)
    val measured = GNJVObservation(true, true) // supplied PUBLIC fixture, not sealed oracle
    val outcome = learner.update(GNJVTrial(baseline, first.intervention!!, measured))
    check(outcome.survivors == 1)
    check(learner.identified() != null)
    check(learner.predict(GNJVObservation(true, true), GNJVIntervention(CausalVariable.Y, false)) == GNJVObservation(true, false))
    println("GNJV01_PUBLIC_SMOKE_PASS")
}
