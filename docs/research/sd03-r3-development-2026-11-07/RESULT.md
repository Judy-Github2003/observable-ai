# SD-03 Sequential Attribution R3 结果：第二次决策是诊断请求，不是修复成功

## 0. 元数据

- 任务：`SD-03-SEQUENTIAL-ATTRIBUTION-R3-2026-11-07`
- 起始 HEAD：`38dcba7`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03SequentialAttributionR3.kt`、`SD03SequentialAttributionR3ContractTest.kt`、`SD03R3TraceAdapter.kt`、`SD03R3DevelopmentIntegrationTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 真实开发轨迹

单次干净运行（旧两次运行归档于 `ATTEMPTS/`）：

| task | seed | 第一次决策 | 第二次决策 | 结果 |
|---|---|---:|---|---|
| `edb6ed...` | 20261109 | `PROBE_PAIRWISE_12` | `AUDIT_GENERATION_CAP` | 0 来源；generated=30,000；capHit |
| `068096...` | 20261110 | `ABSTAIN` | 无 | 初始已有 3 来源；Control/Attr 21/21 |

`CHRONOLOGY.jsonl` 共 7 个事件、3 个 decision：

- `FROZEN_BEFORE` → `DECISION_0` → `FROZEN_AFTER` → `DECISION_1`；
- 决策先写入，后执行 Challenge/HELDOUT；
- 第二次快照仍是同一 TRAIN、同一 atom universe，仅原子状态/lifecycle 变化。

`edb6ed` 的第二次证据：

- `SIGNATURE_OBSTRUCTION_REMOVED=SUPPORTED`；
- `TRAIN_SURVIVOR_RECOVERED=REFUTED`；
- `GENERATION_CAP_PRESSURE=SUPPORTED`；
- `GRAMMAR_OR_ENUMERATION_UNRESOLVED=SUPPORTED`；
- 动作 `AUDIT_GENERATION_CAP`，`diagnosticOnly=true`。

这正说明：**必要签名阻塞被修复，但学习未恢复**；诊断请求不是修复成功。

## 2. 三臂对照（单次干净运行）

| Arm | Challenge pass | HELDOUT 正确/42 | new Oracle | preview calls |
|---|---:|---:|---:|---:|
| CONTROL | 3 | 21 | 20 | 0 |
| GENERIC | 0 | 0 | 0 | 0 |
| ATTRIBUTION | 3 | 21 | 20 | 1,200 |

- ATTRIBUTION 与 CONTROL 相同；`068096` 的 ABSTAIN 复用了 CONTROL 结果，不能算独立重跑。
- `edb6ed` 的探针失败（0 来源、capHit）；额外 1,200 preview interpreter calls 无收益。
- GENERIC 在该开发集上更差。

## 3. Gate 判定

| Gate | 判定 | 原因 |
|---|---|---|
| G0 保密/时间 | **PASS_LIMITED** | 决策先于评分；无 truth/HELDOUT 进入 R3；成本不完整 |
| G1 真实第二次快照 | **PASS_LIMITED** | 真实 MRX2BSpace 重跑，TRAIN/atom universe 不变 |
| G2 多假说更新 | **PASS_LIMITED** | 7 个 claim，signature 移除/生存者/cap 分离 |
| G3 证据驱动第二步 | **PASS_LIMITED（诊断）** | 因新证据改选 `AUDIT_GENERATION_CAP`；但不是修复动作 |
| G4 收益 | **NO_BENEFIT** | ATTRIBUTION=CONTROL（3/21）；探针失败；GENERIC 0 |
| G5 跨任务/盲测 | **NOT_DEMONSTRATED** | 仅 2 个公开开发任务，非独立盲测 |

## 4. 关键阴性结果

- `edb6ed` 中修复了签名阻塞，但没有 TRAIN-consistent 候选，且撞 30k 上限；R3 正确输出诊断请求，未声称修复。
- `068096` 初始已成功，R3 正确 ABSTAIN；ATTRIBUTION 复用 CONTROL，不构成独立收益。
- 没有第二次真实 probe；没有 HELDOUT 改善。
- 不能把诊断请求 `AUDIT_GENERATION_CAP` 写成“学习成功”。
- 开发任务公开，不能称独立闭卷。

## 5. RED/GREEN 真实性

- `SD03SequentialAttributionR3ContractTest` 9/9 首跑通过；`SD03R3DevelopmentIntegrationTest` 1/1。
- **没有新的业务 RED**；未复用旧 RED。
- 合计 **10/10，0 failed，9.969s**；`BUILD SUCCESSFUL in 21s`。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=not_run`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 6. 结论

R3 在真实开发任务上完成了：

1. 第一次决策封存；
2. 真实第二次搜索；
3. 第二次 TRAIN-only 快照；
4. 基于新证据从 `PROBE_PAIRWISE_12` 改为 `AUDIT_GENERATION_CAP`；
5. 明确诊断请求 ≠ 修复。

但没有学习成功或收益：ATTRIBUTION 与 CONTROL 相同，第二次动作是诊断请求，capHit 仍阻断了 TRAIN survivor。因此：

- G3 仅 `PASS_LIMITED（diagnostic）`；
- G4 `NO_BENEFIT`；
- G5 `NOT_DEMONSTRATED`。

下一问题：在 30k 候选上限与组合覆盖之间，如何区分“语法表达不足”和“枚举预算不足”，并设计可执行的下一探针，而不是继续请求审计。

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03SequentialAttributionR3ContractTest,logic.e3.pack02.SD03R3DevelopmentIntegrationTest"
```

本轮停止，未启动 SD-04。
