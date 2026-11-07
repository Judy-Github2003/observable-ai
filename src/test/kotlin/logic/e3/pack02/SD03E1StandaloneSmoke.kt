package logic.e3.pack02

/** Compile with the pure E1 core; this is intentionally not a JUnit or research result. */
fun main() {
    fun run(label: String, target: (Boolean,Boolean,Boolean)->Boolean, depth:Int): E1Result {
        val cases = mutableListOf<E1Example>()
        for (a in listOf(false,true)) for (b in listOf(false,true)) for (c in listOf(false,true)) {
            val fields = mapOf("a" to a.toString(), "b" to b.toString(), "c" to c.toString())
            cases += E1Example("$a-$b-$c", fields, target(a,b,c))
        }
        val atoms = listOf("a","b","c").map { name ->
            E1Atom(name, cases.associate { it.id to (it.fields[name] == "true") }, "fixture:$name")
        }
        val r = SD03EvidenceCompositionE1.propose(cases,atoms,E1Config(maxDepth=depth,beamWidthPerNode=3))
        println("$label: ${r.status} correct=${r.trainCorrect}/${r.trainTotal} nodes=${r.proposedNodes} splits=${r.splitAttempts}")
        return r
    }
    check(run("nested IF",{a,b,c->if(a)b else c},2).status == E1Status.TRAIN_CONSISTENT_PROPOSAL)
    check(run("XOR",{a,b,_->a!=b},2).status == E1Status.TRAIN_CONSISTENT_PROPOSAL)
    check(run("shallow negative",{a,b,c->if(a)b else c},1).status == E1Status.TRAIN_INCONSISTENT_PROPOSAL)
}
