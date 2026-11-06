// 本文件实现：DeepSeek Harness（本会话），2026-11-06。
// 原因：MRX-01 未知任务世界与四段数据划分；基线阶段只调用冻结 C8-03 学生。
package logic.e3.pack02

import logic.capability.sha256
import java.nio.file.Path

enum class MRXCategory(val wire: String) {
    EXPRESSIBLE("expressible"),
    DEPTH("depth"),
    IF_STRUCTURE("if_structure"),
    AMBIGUOUS("ambiguous"),
    MISSING_KNOWLEDGE("missing_knowledge"),
    SEARCH_BUDGET("search_budget"),
    NOISY("noisy"),
}

class MRXWorld(
    val id: String,
    val category: MRXCategory,
    val description: String,
    val truth: (Map<String, String>) -> Boolean,
    val libraryMode: String = "FULL",
    val maxCandidates: Int = 50_000,
    val noise: Boolean = false,
    val ambiguousException: Map<String, String>? = null,
    val expectedUpgrades: Set<String> = emptySet(),
)

data class MRXSplit(
    val seed: Int,
    val train: List<Map<String, String>>,
    val query: List<Map<String, String>>,
    val challenge: List<Map<String, String>>,
    val heldout: List<Map<String, String>>,
    val digest: String,
) {
    companion object {
        const val TRAIN = 9
        const val QUERY = 29
        const val CHALLENGE = 16
        const val HELDOUT = 27

        fun freeze(seed: Int): MRXSplit {
            val shuffled = C8Domain.all().shuffled(kotlin.random.Random(seed.toLong()))
            val train = shuffled.take(TRAIN)
            val query = shuffled.drop(TRAIN).take(QUERY)
            val challenge = shuffled.drop(TRAIN + QUERY).take(CHALLENGE)
            val heldout = shuffled.drop(TRAIN + QUERY + CHALLENGE).take(HELDOUT)
            require(train.size == TRAIN && query.size == QUERY && challenge.size == CHALLENGE && heldout.size == HELDOUT)
            val keys = (train + query + challenge + heldout).map { C8Domain.key(it) }
            require(keys.size == 81 && keys.toSet().size == 81)
            val canonical = buildString {
                for ((name, group) in listOf("train" to train, "query" to query, "challenge" to challenge, "heldout" to heldout)) {
                    append(name).append(':')
                    group.forEach { append(C8Domain.key(it)).append(';') }
                }
            }
            return MRXSplit(seed, train, query, challenge, heldout, sha256(canonical))
        }
    }
}

object MRXWorlds {
    private fun r(m: Map<String, String>) = m["a"] == "1" && m["c"] == "1"
    private fun p(m: Map<String, String>) = m["a"] == "1"
    private fun q(m: Map<String, String>) = m["c"] == "1"
    private fun d(m: Map<String, String>, v: String) = m["d"] == v
    private fun b(m: Map<String, String>, v: String) = m["b"] == v

    private val exception1 = mapOf("a" to "1", "b" to "2", "c" to "1", "d" to "2")
    private val exception2 = mapOf("a" to "0", "b" to "0", "c" to "0", "d" to "0")
    private val exception3 = mapOf("a" to "2", "b" to "1", "c" to "2", "d" to "1")

    private fun ambiguous(base: (Map<String, String>) -> Boolean, exception: Map<String, String>) =
        { m: Map<String, String> -> if (m == exception) !base(m) else base(m) }

    val baseline: List<MRXWorld> = listOf(
        // 1) 现有表达空间可解决
        MRXWorld("mrx-base-or-r-d1", MRXCategory.EXPRESSIBLE, "R OR d==1", { m -> r(m) || d(m, "1") }),
        MRXWorld("mrx-base-or-r-d2", MRXCategory.EXPRESSIBLE, "R OR d==2", { m -> r(m) || d(m, "2") }),
        MRXWorld("mrx-base-xnor-r-d1", MRXCategory.EXPRESSIBLE, "R XNOR d==1", { m -> r(m) == d(m, "1") }),
        MRXWorld("mrx-base-or-q-a2", MRXCategory.EXPRESSIBLE, "Q OR a==2", { m -> q(m) || m["a"] == "2" }),
        MRXWorld("mrx-base-xnor-p-d2", MRXCategory.EXPRESSIBLE, "P XNOR d==2", { m -> p(m) == d(m, "2") }),
        MRXWorld("mrx-base-or-p-c2", MRXCategory.EXPRESSIBLE, "P OR c==2", { m -> p(m) || m["c"] == "2" }),

        // 2) 当前 depth=3 表达空间不能解决，但 depth=4 DNF 可解决
        MRXWorld("mrx-depth-w4", MRXCategory.DEPTH, "if R then d1 else d0", { m -> if (r(m)) d(m, "1") else d(m, "0") }, expectedUpgrades = setOf("DNF", "IF")),
        MRXWorld("mrx-depth-r-xor-b2", MRXCategory.DEPTH, "R XOR b==2", { m -> r(m) != b(m, "2") }, expectedUpgrades = setOf("DNF")),
        MRXWorld("mrx-depth-p-xor-d2", MRXCategory.DEPTH, "P XOR d==2", { m -> p(m) != d(m, "2") }, expectedUpgrades = setOf("DNF")),
        MRXWorld("mrx-depth-q-xor-b1", MRXCategory.DEPTH, "Q XOR b==1", { m -> q(m) != b(m, "1") }, expectedUpgrades = setOf("DNF")),
        MRXWorld("mrx-depth-r-and-not", MRXCategory.DEPTH, "(R OR d1) AND NOT(R AND d2)", { m -> (r(m) || d(m, "1")) && !(r(m) && d(m, "2")) }, expectedUpgrades = setOf("DNF")),
        MRXWorld("mrx-depth-q-and-not", MRXCategory.DEPTH, "(Q OR d2) AND NOT(Q AND b1)", { m -> (q(m) || d(m, "2")) && !(q(m) && b(m, "1")) }, expectedUpgrades = setOf("DNF")),

        // 3) 需要条件结构；扁平化超过 depth=4
        MRXWorld("mrx-if-nested-1", MRXCategory.IF_STRUCTURE, "if R then (if P then d1 else d2) else d0",
            { m -> if (r(m)) { if (p(m)) d(m, "1") else d(m, "2") } else d(m, "0") }, expectedUpgrades = setOf("IF")),
        MRXWorld("mrx-if-nested-2", MRXCategory.IF_STRUCTURE, "if P then (if Q then R else d1) else b0",
            { m -> if (p(m)) { if (q(m)) r(m) else d(m, "1") } else b(m, "0") }, expectedUpgrades = setOf("IF")),
        MRXWorld("mrx-if-nested-3", MRXCategory.IF_STRUCTURE, "if Q then (if R then b2 else d1) else (if P then d0 else b1)",
            { m -> if (q(m)) { if (r(m)) b(m, "2") else d(m, "1") } else { if (p(m)) d(m, "0") else b(m, "1") } }, expectedUpgrades = setOf("IF")),
        MRXWorld("mrx-if-simple", MRXCategory.IF_STRUCTURE, "if R then d1 else d0 (also depth-DNF)",
            { m -> if (r(m)) d(m, "1") else d(m, "0") }, expectedUpgrades = setOf("IF", "DNF")),

        // 4) 公开证据不足：只有一个 HELDOUT 输入是反例
        MRXWorld("mrx-ambig-1", MRXCategory.AMBIGUOUS, "R OR d1 with one heldout exception",
            ambiguous({ m -> r(m) || d(m, "1") }, exception1), ambiguousException = exception1, expectedUpgrades = setOf("NONE")),
        MRXWorld("mrx-ambig-2", MRXCategory.AMBIGUOUS, "Q OR a2 with one heldout exception",
            ambiguous({ m -> q(m) || m["a"] == "2" }, exception2), ambiguousException = exception2, expectedUpgrades = setOf("NONE")),
        MRXWorld("mrx-ambig-3", MRXCategory.AMBIGUOUS, "P XNOR d2 with one heldout exception",
            ambiguous({ m -> p(m) == d(m, "2") }, exception3), ambiguousException = exception3, expectedUpgrades = setOf("NONE")),
        MRXWorld("mrx-ambig-4", MRXCategory.AMBIGUOUS, "R XNOR d1 with one heldout exception",
            ambiguous({ m -> r(m) == d(m, "1") }, exception1), ambiguousException = exception1, expectedUpgrades = setOf("NONE")),

        // 5) 缺少已有知识；当前 QUERY/HELDOUT 目标在 NO-R 库下需要更高深度
        MRXWorld("mrx-miss-or-r-d1", MRXCategory.MISSING_KNOWLEDGE, "R OR d1 with NO-R library",
            { m -> r(m) || d(m, "1") }, libraryMode = "NO-R", expectedUpgrades = setOf("KNOWLEDGE", "DNF")),
        MRXWorld("mrx-miss-xnor-r-d1", MRXCategory.MISSING_KNOWLEDGE, "R XNOR d1 with NO-R library",
            { m -> r(m) == d(m, "1") }, libraryMode = "NO-R", expectedUpgrades = setOf("KNOWLEDGE", "DNF")),
        MRXWorld("mrx-miss-or-r-b2", MRXCategory.MISSING_KNOWLEDGE, "R OR b2 with NO-R library",
            { m -> r(m) || b(m, "2") }, libraryMode = "NO-R", expectedUpgrades = setOf("KNOWLEDGE", "DNF")),

        // 6) 搜索预算不足
        MRXWorld("mrx-search-or-r-d1", MRXCategory.SEARCH_BUDGET, "R OR d1 with candidate cap 500",
            { m -> r(m) || d(m, "1") }, maxCandidates = 500, expectedUpgrades = setOf("BUDGET")),
        MRXWorld("mrx-search-xnor-q-d1", MRXCategory.SEARCH_BUDGET, "Q XNOR d1 with candidate cap 500",
            { m -> q(m) == d(m, "1") }, maxCandidates = 500, expectedUpgrades = setOf("BUDGET")),

        // 7) 观察冲突/噪声
        MRXWorld("mrx-noise-or-r-d1", MRXCategory.NOISY, "R OR d1 with one conflicting duplicate",
            { m -> r(m) || d(m, "1") }, noise = true, expectedUpgrades = setOf("NONE")),
        MRXWorld("mrx-noise-q-a2", MRXCategory.NOISY, "Q OR a2 with one conflicting duplicate",
            { m -> q(m) || m["a"] == "2" }, noise = true, expectedUpgrades = setOf("NONE")),
    )

    // 正式考试世界：ID、目标与基线/开发世界不同；种子另行选择。
    val formal: List<MRXWorld> = listOf(
        MRXWorld("mrx-f-expr", MRXCategory.EXPRESSIBLE, "R OR b2", { m -> r(m) || b(m, "2") }),
        MRXWorld("mrx-f-depth", MRXCategory.DEPTH, "R XOR d2", { m -> r(m) != d(m, "2") }, expectedUpgrades = setOf("DNF")),
        MRXWorld("mrx-f-if", MRXCategory.IF_STRUCTURE, "if R then (if Q then d2 else b1) else d0",
            { m -> if (r(m)) { if (q(m)) d(m, "2") else b(m, "1") } else d(m, "0") }, expectedUpgrades = setOf("IF", "DNF")),
        MRXWorld("mrx-f-ambig", MRXCategory.AMBIGUOUS, "Q OR d1 with one heldout exception",
            ambiguous({ m -> q(m) || d(m, "1") }, exception3), ambiguousException = exception3, expectedUpgrades = setOf("NONE")),
        MRXWorld("mrx-f-miss", MRXCategory.MISSING_KNOWLEDGE, "R OR d2 with NO-R library",
            { m -> r(m) || d(m, "2") }, libraryMode = "NO-R", expectedUpgrades = setOf("KNOWLEDGE", "DNF")),
        MRXWorld("mrx-f-search", MRXCategory.SEARCH_BUDGET, "Q XNOR d1 with candidate cap 500",
            { m -> q(m) == d(m, "1") }, maxCandidates = 500, expectedUpgrades = setOf("BUDGET")),
        MRXWorld("mrx-f-noise", MRXCategory.NOISY, "P XNOR d2 with one conflicting duplicate",
            { m -> p(m) == d(m, "2") }, noise = true, expectedUpgrades = setOf("NONE")),
    )

    fun splitFor(world: MRXWorld, seed: Int): MRXSplit {
        val split = MRXSplit.freeze(seed)
        require(coverage(world, split).isEmpty()) { "coverage failed for ${world.id} seed=$seed: ${coverage(world, split)}" }
        if (world.ambiguousException != null) {
            require(world.ambiguousException in split.heldout) { "exception not in heldout for ${world.id} seed=$seed" }
        }
        return split
    }

    fun coverage(world: MRXWorld, split: MRXSplit): List<String> {
        val problems = ArrayList<String>()
        val trainPos = split.train.count(world.truth)
        val trainNeg = split.train.size - trainPos
        val heldPos = split.heldout.count(world.truth)
        val heldNeg = split.heldout.size - heldPos
        if (trainPos < 4 || trainNeg < 4) problems += "train=$trainPos/$trainNeg"
        if (heldPos < 6 || heldNeg < 6) problems += "heldout=$heldPos/$heldNeg"
        return problems
    }

    /** 开发/基线种子：先满足覆盖率与歧义例外位置，再固定使用。 */
    fun findSeed(world: MRXWorld, from: Int, step: Int = 1, avoid: Set<Int> = emptySet()): Pair<Int, MRXSplit> {
        var seed = from
        while (seed < from + 20_000) {
            if (seed !in avoid) {
                val split = MRXSplit.freeze(seed)
                if (coverage(world, split).isEmpty() && (world.ambiguousException == null || world.ambiguousException in split.heldout)) {
                    return seed to split
                }
            }
            seed += step
        }
        error("no seed for ${world.id}")
    }
}
