// 本文件实现：DeepSeek Harness（本会话），2026-11-05。
// 原因：B0 M5/M6 的可复现运行入口；外部审查者可用新种子/新任务数独立重跑 A/B/C。
package logic.capability

import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    var output = Path.of("experiments/capability-foundation-b0/benchmarks/results")
    var seed = 20261105L
    var tasksPerFamily = 2
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--output" -> output = Path.of(args[++index])
            "--seed" -> seed = args[++index].toLong()
            "--tasks-per-family" -> tasksPerFamily = args[++index].toInt()
            else -> error("unknown argument: ${args[index]}")
        }
        index++
    }
    Files.createDirectories(output)
    val report = BenchmarkRunner(BenchmarkConfig(seed = seed, tasksPerFamily = tasksPerFamily)).run()
    val file = output.resolve("m5-results-$seed.json")
    Files.writeString(file, report.toJson())
    for (group in report.groups) {
        println(
            "${group.group}: correct=${group.correct}/${group.total} wrong=${group.wrong} unknown=${group.unknown} " +
                "error=${group.error} ambiguous=${group.ambiguous} refused=${group.refused} " +
                "candidates=${group.searchCandidates} elapsedMs=${group.elapsedMs} " +
                "staticCost=${group.staticCost} reuse=${group.reuseCount}",
        )
    }
    println("helpers=${report.helperCapabilities}")
    println("wrote=$file")
}
