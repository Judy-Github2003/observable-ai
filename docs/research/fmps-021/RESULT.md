# FMPS-02.1 收尾轮结果（FMPS021-CLOSURE-2026-11-11）

执行者：DeepSeek Harness，2026-11-11。
授权：repair grant `FMPS021-CLOSURE-2026-11-11`（研究负责人 2026-11-11 明确批准 B+C 全量；
preflight 返回 `AUTHORIZED_REPAIR`）。性质：`IMPLEMENTER_RUN`，非独立闭卷。

## 输入

| 文件 | sha256 |
|---|---|
| 工作包 `Observable-AI-FMPS021-Closure-Kit-2026-11-11.zip` | `d94a5d28f639fa0a569b182fe63b188cca2e1dd43e6f8e00e80c5d5245400756` |
| 官方参考包 `ObservableAI-FMPS-02.1-Fair-Baselines-Reference-2026-11-10.zip` | `392ba13b8024fb11229600fbc87c86fb2519d7cf9832b401d55be68795e7884c` |
| 上轮修复 `FMPS021-DeepSeek-Fix-2026-11-10.zip` | `9e9103e81488620db6ba8df63af7d3a30516b2dd1c13ce411028b93709c69a11` |

## 结果

1. **G1 编译**：官方源码原样 40 个 `.kt` → `exit=1`、20 errors；修复后 0 errors。
   20 errors 只由 3 处语义缺陷引起：`FMPS021FairComparison.kt` 重复 `score` 声明（193/438，
   级联 17 个错误）、测试第 74 行字符串模板漏 `${}`、测试期望基线名 `single_*` 与实际
   `single_view_*` 不一致（后两者之一仅在运行期暴露）。`kotlinc 2.4.21` 交叉核对同为 20 errors。
2. **G2 保真**：`FMPS021FairComparisonTests` 通过 —— object `128/128`、combination
   `0 correct / 22 wrong / 10 abstain`，真实 FMPS01 adapter 同值；历史阴性结论未改写。
   `FMPS02Tests` 64/64，缺失/未见对/冲突弃答、标签对调与外来 world/object 均拒绝；
   `FMPS01Tests` 5 seeds 40/40。
3. **G3 等预算对照**：7 臂同一预算 250,000、同一训练/测试/切分；`BENCHMARK.csv` 记录
   `train_ops/predict_ops/total_ops/candidate_ops/query_ops/wall_ms` 与正确/错误/弃答。
   14/14 行分阶段总成本与官方 `runBaselines().workUnits` 相等；两次运行除 `wall_ms` 外一致。
   `candidate_ops` 只对 FMPS DNF 镜像有定义（object 6,840 / combination 4,472），其余为不适用。
4. **镜像 vs 实跑**：`NOT_EQUIVALENT`。seed=1 时 Python 镜像通道 `(domain, shape)`，Kotlin 实跑
   `[domain, usage]`，样本值也不同；`reference-summary.json` 的单视图行不能当 Kotlin 结果引用。
5. **G4 冻结**：M1/B0 与本轮未触碰；但官方 FMPS-02.1 包自带的 M1 副本已含 M2-03.1 的 +23 行
   （270 行，sha `0a224a7a…`），pristine M1 为 247 行（sha `a2812c59…`），集成须以 pristine 为基线。

## 交付

`C:\Users\j1437\Downloads\FMPS021-Closure-DeepSeek-2026-11-11.zip`
（sha256 `3cf1eac2769c0d623e76796205a7c980989bc0fba87f3d33e0ce323fb0229770`）：
README / TEST-RESULTS / BENCHMARK.csv / INTEGRATION-PORTS / OPEN-ISSUES /
`01-compile-fix.patch` / `02-cost-instrumentation.patch` / 修复后源码 / 官方原样源码 / 脚本 / 日志 / HASHES。

## 边界

未跑历史全量回归、无隐藏考官/L2 重放/六臂、未启动 X-01、未改主仓代码、未修改任何历史结果文件。
`wall_ms` 含 JIT 预热，只作量级参考；逻辑操作单位不等于 CPU 时间。
