# SD-03 Blind Attribution 结果：归因器全部 ABSTAIN，无证据驱动行动

## 0. 元数据

- 任务：`SD-03-BLIND-ATTRIBUTION-2026-11-07`
- 起始 HEAD：`f12f7c0`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03BlindAttribution.kt`、`SD03BlindAttributionContractTest.kt`、`SD03BlindAttributionIntegrationTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 盲任务执行

- 6 个盲世界（3 类各 2）× 2 种子 = 12 个任务-种子。
- 每任务 3 arms = **36 次真实运行**。
- 归因器只接收 TRAIN observations、ranked atoms、lifecycle、allowed actions；无 world id/category/truth/heldout。
- 原始数据：`TRACE-BLIND.jsonl`、`DECISIONS.jsonl`、`COST-MATRIX.csv`。

## 2. 结果

`ATTRIBUTION-SUMMARY.json`：

| 指标 | 值 |
|---|---:|
| tasks | 6 |
| task-seeds | 12 |
| runs | 36 |
| CONTROL challenge pass 总数 | 38 |
| GENERIC challenge pass 总数 | 32 |
| ATTRIBUTION challenge pass 总数 | 38 |
| ATTRIBUTION − CONTROL | **0** |
| ATTRIBUTION − GENERIC | +6（GENERIC 有负作用） |

- ATTRIBUTION 在 12 个 task-seed 中全部选择 **ABSTAIN**。
- 典型 claims：
  - `REQUIRED_INPUT_DROPPED = REFUTED`
  - `ALL_TRAIN_CANDIDATES_REFUTED = REFUTED/SUPPORTED`
  - `POST_TRAIN_SELECTION_FAILURE = UNKNOWN/REFUTED`
- 一个失败任务（taskHash `c2531032`）A0=0，claims 判定 `ALL_TRAIN_CANDIDATES_REFUTED=SUPPORTED`，但归因器只在 `REQUIRED_INPUT_DROPPED` 支持时建议 DIVERSITY；这里没有支持，故 ABSTAIN。
- 这是保守正确行为，不是“学习成功”。

## 3. Gate 判定

| Gate | 判定 | 原因 |
|---|---|---|
| G0 数据/证据隔离 | **PASS_LIMITED** | 快照无 truth/world id/category/heldout；真实 student trace；但成本不完整 |
| G1 多假说 | **PASS_LIMITED** | 3 claims + UNKNOWN；真实 TRAIN 证据；但未形成可行动区分 |
| G2 区分性诊断/反驳 | **NOT_DEMONSTRATED** | 只有静态观察；无真实反事实干预 |
| G3 证据影响行动 | **NOT_DEMONSTRATED** | 12/12 ATTRIBUTION=ABSTAIN；无行动变化 |
| G4 收益 | **NO_BENEFIT** | ATTRIBUTION 与 CONTROL 相同（38 vs 38）；GENERIC 更差 |
| G5 经验迁移 | **NOT_DEMONSTRATED** | 无行动/无收益 |

## 4. 关键阴性结果

- 在冻结盲任务上，`SD03BlindAttributor` 没有获得足够证据支持任何安全修复动作。
- 一个 A0=0 任务被归因为“所有 TRAIN 候选被反驳”，而不是“必需输入被丢弃”；因此按冻结策略不能建议 DIVERSITY。
- 不能把 CONTROL/GENERIC 的差异当成归因器收益；ATTRIBUTION 没有执行任何新搜索动作。
- 不能把 6 个盲任务的小样本写成统计泛化；HELDOUT 未用于学生/归因器。

## 5. 测试与真实性

- `SD03BlindAttributionContractTest`：6/6（合成 fixture，非科研 PASS）。
- `SD03BlindAttributionIntegrationTest`：1/1，36 行真实 trace；51s。
- 本轮新增测试首跑通过，**没有新的业务 RED**；未复用旧 RED。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=not_run`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 6. 结论

- 盲归因内核可以安全地输出 ABSTAIN，但没有在 12 个冻结盲任务上找到可行动的支持证据。
- 因此“证据改变行动/收益/迁移”均未证明；不能声称 G1–G5 闭环。
- 下一步问题：为什么失败任务被判为 ALL_TRAIN_CANDIDATES_REFUTED，而不是 REQUIRED_INPUT_DROPPED；需要区分“候选全被反驳”和“原子未保留”的独立可观察证据。

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03BlindAttributionContractTest,logic.e3.pack02.SD03BlindAttributionIntegrationTest"
```

本轮停止，未启动 SD-04。
