// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01R 扩大评测世界；包含 8 个新定义与 12 个沿用 MRX-01 定义但使用全新种子的世界。
package logic.e3.pack02

object MRXRWorlds {
    private fun base(id: String): MRXWorld = MRXWorlds.baseline.first { it.id == id }

    val dev: List<MRXWorld> = listOf(
        base("mrx-depth-w4"),
        base("mrx-ambig-1"),
        base("mrx-miss-or-r-d1"),
        base("mrx-noise-or-r-d1"),
    )

    val devSeeds: Map<String, Int> = mapOf(
        "mrx-depth-w4" to 1449,
        "mrx-ambig-1" to 1857,
        "mrx-miss-or-r-d1" to 2020,
        "mrx-noise-or-r-d1" to 2227,
    )

    val formal: List<MRXWorld> = listOf(
        // 8 个新定义
        MRXWorld("mrxr-base-p-b2", MRXCategory.EXPRESSIBLE, "P OR b2", { m -> m["a"] == "1" || m["b"] == "2" }),
        MRXWorld("mrxr-depth-p-xor-b2", MRXCategory.DEPTH, "P XOR b2", { m -> (m["a"] == "1") != (m["b"] == "2") }, expectedUpgrades = setOf("DNF")),
        MRXWorld("mrxr-depth-q-xor-d2", MRXCategory.DEPTH, "Q XOR d2", { m -> (m["c"] == "1") != (m["d"] == "2") }, expectedUpgrades = setOf("DNF")),
        MRXWorld("mrxr-if-nested-4", MRXCategory.IF_STRUCTURE, "if P then (if R then d2 else b0) else Q",
            { m -> if (m["a"] == "1") { if (m["a"] == "1" && m["c"] == "1") m["d"] == "2" else m["b"] == "0" } else m["c"] == "1" },
            expectedUpgrades = setOf("IF", "DNF")),
        MRXWorld("mrxr-ambig-5", MRXCategory.AMBIGUOUS, "Q XNOR b1 with one heldout exception",
            run {
                val exception = mapOf("a" to "0", "b" to "2", "c" to "0", "d" to "1")
                val base = { m: Map<String, String> -> (m["c"] == "1") == (m["b"] == "1") }
                { m: Map<String, String> -> if (m == exception) !base(m) else base(m) }
            },
            ambiguousException = mapOf("a" to "0", "b" to "2", "c" to "0", "d" to "1"), expectedUpgrades = setOf("NONE")),
        MRXWorld("mrxr-miss-or-r-b2", MRXCategory.MISSING_KNOWLEDGE, "R OR b2 with NO-R",
            { m -> (m["a"] == "1" && m["c"] == "1") || m["b"] == "2" }, libraryMode = "NO-R", expectedUpgrades = setOf("KNOWLEDGE", "DNF")),
        MRXWorld("mrxr-search-q-xnor-d1", MRXCategory.SEARCH_BUDGET, "Q XNOR d1 with cap 500",
            { m -> (m["c"] == "1") == (m["d"] == "1") }, maxCandidates = 500, expectedUpgrades = setOf("BUDGET")),
        MRXWorld("mrxr-noise-p-or-d2", MRXCategory.NOISY, "P OR d2 with conflict",
            { m -> m["a"] == "1" || m["d"] == "2" }, noise = true, expectedUpgrades = setOf("NONE")),

        // 12 个沿用 MRX-01 世界定义，但正式种子全新
        base("mrx-base-or-r-d1").copyId("mrxr-base-or-r-d1"),
        base("mrx-base-or-q-a2").copyId("mrxr-base-or-q-a2"),
        base("mrx-base-xnor-p-d2").copyId("mrxr-base-xnor-p-d2"),
        base("mrx-depth-w4").copyId("mrxr-depth-w4"),
        base("mrx-depth-r-xor-b2").copyId("mrxr-depth-r-xor-b2"),
        base("mrx-if-nested-2").copyId("mrxr-if-nested-2"),
        base("mrx-ambig-2").copyId("mrxr-ambig-2"),
        base("mrx-ambig-4").copyId("mrxr-ambig-4"),
        base("mrx-miss-or-r-d1").copyId("mrxr-miss-or-r-d1"),
        base("mrx-miss-xnor-r-d1").copyId("mrxr-miss-xnor-r-d1"),
        base("mrx-search-or-r-d1").copyId("mrxr-search-or-r-d1"),
        base("mrx-noise-q-a2").copyId("mrxr-noise-q-a2"),
    )

    private fun MRXWorld.copyId(newId: String): MRXWorld = MRXWorld(
        newId, category, description, truth, libraryMode, maxCandidates, noise, ambiguousException, expectedUpgrades,
    )

    fun splitFor(world: MRXWorld, seed: Int): MRXSplit = MRXWorlds.splitFor(world, seed)

    fun findSeed(world: MRXWorld, from: Int): Pair<Int, MRXSplit> = MRXWorlds.findSeed(world, from = from)
}
