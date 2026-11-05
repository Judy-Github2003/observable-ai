# B0 M5-1a 报告

```text
子任务: M5-1a
状态: complete
基线 SHA: ae1618c8cb70677d657ef9ea56076b9074d81352（M4-1c）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 170/170；新增 3/3；失败 0；合计 173/173
关键语义及接口: BenchmarkRunner + BenchmarkCli；协议 b0-m5-v1 冻结
失败用例/未实现/已知边界: D 组 L3 not_implemented；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M5-1b
```

## 做了什么

- `BenchmarkConfig`：冻结种子 `20261105`、`tasksPerFamily=2`、生成器版本 `b0-m5-v1`、预算 `maxDepth=3 / maxNodes=32 / maxCandidates=3000 / maxSteps=10000`。
- 三个符号世界（item / access / device），每个三字段、二值；基础库各提供一个复合原语。
- 四类任务族：
  - `REUSE`：复合原语内联 + 第三字段，嵌套深度 4，A 的预算内无法表达；
  - `SIMILAR`：同形不同常量，检查不误用；
  - `NO_STRUCTURE`：无公共结构负对照；
  - `BOUNDARY`：深度 5，检查预算不足时的歧义/拒答。
- 24 个任务：每场景每族 2 个；`*.0` 为训练集，`*.1` 为开发集，按结构族切分。
- 每组任务 8 点声明域：公开样本 6、开发样本 2；目标 AST 只用于生成标签与判分。
- A/B/C 同生成器、同公开样本、同解释器、同预算；C 在训练集已解决程序上用 M4 抽取，净收益不成立则保持与 B 相同。
- `BenchmarkCli` + Gradle `benchmark` 任务：输出 JSON（组汇总 + 逐任务结果），支持 `--seed`、`--tasks-per-family`。
- 协议文档：[PROTOCOL.md](./benchmarks/PROTOCOL.md)。

## 协议要点

- D 组（L3）明确 `not_implemented`，不为了补齐实验扩展 L3。
- 不预设 C 必须优于 B；静态压缩、执行速度、未来任务正确率三类收益分开报告。
- 等价验证的域由字段笛卡尔积生成；`EXHAUSTIVE` 仅表示“声明域内全枚举”，不是全局等价证明；无法枚举时标 `bounded_verified`。
- 基准在内存中运行，不写注册表、不激活能力；激活/撤销门禁由 M2/M3/M4 测试覆盖。
- 外部审查者可用新种子/新任务数复跑；没有外部运行前 `independent_closed_book=not_run`。

## 测试

| 用例 | 覆盖 |
|---|---|
| G01 | 固定 24 任务、12 训练 / 12 开发、三组各 12 开发任务 |
| G02 | 同一种子两次运行逐任务结果一致 |
| G03 | 每组 `correct+wrong+unknown+error+ambiguous+refused = total`；JSON 可机读且含生成器版本 |

## 已知边界

- 每族每场景只有 2 个任务，统计功效有限；不得从一次运行断言稳定收益。
- 计时受 JVM 预热/机器负载影响，只作参考。
- 公开开发结果不是独立闭卷；`implementer_run` 不变。
