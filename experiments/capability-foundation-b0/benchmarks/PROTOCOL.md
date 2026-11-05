# B0 M5 公开开发基准协议（冻结）

> 版本：`b0-m5-v1`，冻结于 2026-11-05。看到成绩之后不得修改权重、任务集、预算或判分口径。
> 状态：公开开发（`implementer_run`）；独立闭卷 `not_run`。

## 1. 运行入口

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon benchmark
```

自定义种子与规模：

```powershell
.\gradlew.bat --offline --no-daemon benchmark --args="--seed 12345 --tasks-per-family 3"
```

直接运行 CLI（构建后）：

```powershell
java -cp build/classes/kotlin/main;<kotlinx-json.jar>;<sqlite-jdbc.jar> logic.capability.BenchmarkCliKt `
  --output experiments/capability-foundation-b0/benchmarks/results --seed 20261105 --tasks-per-family 2
```

输出：`m5-results-<seed>.json`，包含每组汇总与逐任务结果（不含目标 AST）。

## 2. 世界与任务族

三个符号世界，每个三字段、二值：

| 场景 | 字段 | 基础库复合原语 |
|---|---|---|
| item | color / shape / size | `base.red_round` = color==red AND shape==circle |
| access | role / level / dept | `base.admin_level3` = role==admin AND level==3 |
| device | power / port / cable | `base.usb_c` = power==usb AND port==c |

四类任务族（每场景每族 2 个任务）：

| 任务族 | 目标结构 | 预期作用 |
|---|---|---|
| REUSE | 复合原语内联 AND 第三字段（嵌套深度 4） | A 的搜索深度不够；B/C 用基础库原语组合 |
| SIMILAR | 与基础库同形但常量不同（浅层） | 检查不因“看起来像”而误用/误合并 |
| NO_STRUCTURE | 两个独立字段条件（浅层） | 无公共结构负对照 |
| BOUNDARY | 三层嵌套 AND（深度 5） | 检查预算不足时的歧义/拒答，而不是硬猜 |

- 任务总数：3 场景 × 4 族 × 2 = **24**；训练集 = 每个 `*.0`（12），开发集 = 每个 `*.1`（12）。
- 切分按**结构族**，不是只换对象 ID。
- 每个任务声明域为该场景 8 个取值组合；公开样本 6 个（优先 1 个正例 + 5 个负例），开发样本为剩余 2 个。
- 目标 AST 只用于生成标签与判分，组合器/筛选器只接收公开样本、字段、常量和已激活库。

## 3. 对照分组

| 组 | 能力机制 | 状态 |
|---|---|---|
| A | 每题独立求解，空能力库 | 运行 |
| B | 使用基础库复合原语，不自动抽象 | 运行 |
| C | B + 在训练集已解决程序上做 L1/L2 抽取（M4 组件），有净收益才加入库 | 运行 |
| D | L3 参数化抽象 | `not_implemented`，不运行 |

- 三组使用同一个生成器、同一批公开样本、同一解释器、同一搜索预算与执行预算。
- C 的 helper 只从**训练集已解决程序**抽取，不使用目标 AST；抽取失败/无净收益时 C 与 B 相同。
- 基准在内存中运行，不写注册表、不激活能力；验证/激活/撤销门禁由 M2/M3/M4 测试覆盖。

## 4. 预算（冻结）

```text
maxDepth = 3
maxNodes = 32
maxCandidates = 3000
maxSteps = 10_000
```

## 5. 指标

每组输出：

- `correct / wrong / unknown / error / ambiguous / refused`；`total`；`invalid_promotions` 当前固定为 0（基准不激活能力）。
- `search_candidates` 总数、逐任务候选数、`elapsed_ms`、`peak_memory_bytes`（JVM 堆近似值）。
- `library_cost`、`task_cost`、`static_cost`（冻结 `L_total` 口径）。
- `reuse_count`：已解决候选里含库调用的任务数。
- 能力库规模对搜索的影响：用 B 与 C 的库规模/候选数/耗时对比；本协议不保证单调关系。

三类收益分开报告：

1. **静态压缩收益**：`L_total` 前后变化；
2. **执行速度收益**：同预算下搜索候选数/耗时的变化；
3. **未来任务学习收益**：新任务上正确率/拒答率的变化。

## 6. 等价验证范围（M4 边界）

- `DomainKind.EXHAUSTIVE` 只是调用方声明，**名称本身不证明穷举**。
- 冻结实验中的域由字段取值笛卡尔积生成：3 个二值字段 = 8 点，确实是声明有限域的全枚举；这只是“声明域内穷举”，不是全局语义等价证明。
- 无法枚举时调用方必须标 `BOUNDED`，报告写 `bounded_verified`。
- 比较粒度：`Known` 比值，`Unknown` 比原因，`Error` 比错误码。

## 7. 独立验收

- 外部审查者可用新种子、新 `--tasks-per-family` 或自行改写生成器参数运行；不需要教师或网络。
- 生成器不输出目标 AST；隐藏测试约定由审查者自行生成，不放入本仓库。
- 在本轮没有外部运行证据前，`independent_closed_book = not_run`。
