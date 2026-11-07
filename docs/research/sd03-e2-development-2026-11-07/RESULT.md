# SD-03 E2 结果：主动查询有真实分歧，但无收益；局部修复 0 次

## 0. 元数据

- 任务：`SD-03-E2-ACTIVE-QUERY-LOCAL-REPAIR-2026-11-07`
- 起始 HEAD：`74972d8`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03ActiveQueryLocalRepairE2.kt`、`SD03E2ContractTest.kt`、`SD03E2RepoAdapter.kt`、`SD03E2DevelopmentIntegrationTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 1. 四组真实结果

4 个公开开发任务 × 4 arms = 16 次运行：

| Arm | Challenge pass | HELDOUT 正确/42 | QUERY new | atom preview | E1 proposals | refits | local repairs |
|---|---:|---:|---:|---:|---:|---:|---:|
| STATIC | 2 | 42 | 0 | 1,920 | 4 | 0 | 0 |
| RANDOM | 2 | 42 | 7 | 5,490 | 11 | 7 | 0 |
| ACTIVE_FULL | 2 | 42 | 7 | 30,240 | 61 | 7 | 0 |
| ACTIVE_LOCAL | 2 | 42 | 7 | 5,490 | 4 | 0 | **0** |

逐任务：

- 2 个任务初始 E1 已 `PASS_ON_CHALLENGE`，四臂都保持 21/21；
- 2 个任务初始为 `COUNTEREXAMPLE`，四臂都未恢复；
- ACTIVE_FULL 在 `mrx-if-nested-3` 使用了 4 次 QUERY，但最终仍 `COUNTEREXAMPLE`；
- ACTIVE_LOCAL 的 `LOCAL_REPAIR` 全部为 `NO_COUNTEREXAMPLE` 或 `REPLAY_DONE`，**没有发生一次非根局部替换**。

## 2. 关键事实

**主动查询：**

- ACTIVE_FULL 确实基于真实预测分歧选择了 7 个 QUERY，事件记录 `PREDICTIVE_DISAGREEMENT`、true/false votes、model IDs、leafSupport；
- 决策先写 `QUERY_DECISION_FROZEN` 再 reveal；
- 但新标签只触发 refit，没有把失败任务变成 Challenge pass；
- STATIC 没有查询；RANDOM 与 ACTIVE_FULL 实际 QUERY 数量匹配（7）。

**局部修复：**

- 0 次 `LOCAL_REPAIRED`；
- 没有非根子树替换；
- 因此“局部修复”未被证明，不能把整体 refit 冒充为 local。

**成本：**

- ACTIVE_FULL 的 atom preview 30,240，是 STATIC/RANDOM/LOCAL 的 5.5 倍；
- 多出的查询/refit 没有带来 Challenge/HELDOUT 改善；
- 完整解释器/CPU 成本仍 `NOT_INSTRUMENTED`。

## 3. Gate

| Gate | 判定 | 原因 |
|---|---|---|
| E2-G0 边界 | **PASS_LIMITED** | 决策先冻结；QUERY/Challenge/HELDOUT 分账；成本部分 UNKNOWN |
| E2-G1 主动选择 | **PASS_LIMITED** | 7 个真实分歧 QUERY；ABSTAIN 在无分歧任务 |
| E2-G2 认知改变 | **NOT_DEMONSTRATED** | refit 7 次但无 Challenge/HELDOUT 改变 |
| E2-G3 局部修复 | **NOT_DEMONSTRATED** | local repairs=0 |
| E2-G4 学习收益 | **NO_BENEFIT** | 四臂 2 pass/42 heldout；ACTIVE_FULL 成本更高 |
| E2-G5 独立迁移 | **NOT_RUN** | 公开开发任务 |

## 4. 测试与真实性

- E2 契约 8/8 + 集成 1/1 = **9/9，0 failed，1.929s**（集成 1.862s）
- **没有新的业务 RED**；未复用旧 RED
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=NOT_RUN`
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试
- 证据文件 `E2-RUN-STARTED.txt` 阻止覆盖；本次单次干净运行

## 5. 结论

E2 证明了**主动查询可以由真实预测分歧驱动**，且 QUERY 冻结/reveal 顺序正确；但没有证明：

- 查询带来 Challenge/HELDOUT 改善；
- 局部修复真实发生（0 次）；
- ACTIVE 优于匹配 RANDOM。

因此：

- 主动选择：有限成立；
- 局部修复：未发生；
- 学习收益：NO_BENEFIT；
- 不能把 refit 当 local repair，也不能把绿色测试当科研成功。

下一问题：为什么 E1 初始失败任务在 4 个主动标签后仍无法恢复？需要区分“查询信息不足”“局部替换不可行”与“E1 表达空间不足”，而不是直接扩大预算。

## 6. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03E2ContractTest"
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03E2DevelopmentIntegrationTest"
```

本轮停止，未启动 SD-04。
