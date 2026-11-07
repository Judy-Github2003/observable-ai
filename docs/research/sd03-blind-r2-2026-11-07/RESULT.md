# SD-03 Blind Attribution R2 结果：机制修正生效，1 次真实探针，无收益

## 0. 元数据

- 任务：`SD-03-BLIND-ATTRIBUTION-R2-2026-11-07`
- 起始 HEAD：`50e7921`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03BlindAttributionR2.kt`、`SD03BlindAttributionR2ContractTest.kt`、`SD03R2RepoSnapshotAdapter.kt`、`SD03BlindAttributionR2IntegrationTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 真实执行

- 6 个开发世界 × 2 种子 = 12 任务-种子；3 arms = **36 次真实运行**。
- 归因器只读 TRAIN observations、原子预测签名、lifecycle、预计算 alternatives；无 truth/world id/heldout。
- 决策先写 `DECISIONS-R2.jsonl`，之后才用 Challenge/HELDOUT 评分。

## 2. 结果

| Arm | Challenge pass 总数 | HELDOUT 正确总数/252 | new Oracle | reuse | validation calls | generation wall |
|---|---:|---:|---:|---:|---:|---:|
| CONTROL | 38 | 210 | 200 | 580 | 780 | 22,788 ms |
| GENERIC | 24 | 126 | 120 | 400 | 520 | 23,998 ms |
| ATTRIBUTION | 38 | 210 | 200 | 580 | 780 | 22,617 ms |

决策：

- ABSTAIN：11/12
- `PAIRWISE_12`：1/12（task `edb6ed412bc99785`，seed 20261109）

该探针：

- 初始 A0：generated 28,523，train-consistent 0，retained 0，0 来源；
- blocked witness：`t-10`/`t-13`，仅 `d` 不同，保留原子签名相同，被弃原子可区分；
- 动作 `PAIRWISE_12`；实际结果仍 0 来源，generated=30,000（capHit=true），0 heldout；
- 成本：preview 600 interpreter calls + 1 次真实学生重跑；未带来来源或 HELDOUT 收益。

## 3. Gate 判定

| Gate | 判定 | 原因 |
|---|---|---|
| G0 保密/时间隔离 | **PASS_LIMITED** | 快照/决策无 truth/world id/heldout；先决策后评分；成本不完整 |
| G1 多原因竞争 | **PASS_LIMITED** | 7 个 hypotheses；有 SUPPORTED/REFUTED/UNKNOWN；11 个任务无 blocked witness |
| G2 机制可辨识性 | **PASS_LIMITED** | 1 个真实 signature-blocked witness，被弃原子可区分；不是因果证明 |
| G3 证据驱动干预 | **PASS_LIMITED** | 1 次真实 `PAIRWISE_12`，与 GENERIC 不同且有 witness 依据；但结果失败 |
| G4 收益 | **NO_BENEFIT** | ATTRIBUTION 与 CONTROL 相同（38/210）；GENERIC 更差；探针无收益 |
| G5 冻结新任务迁移 | **NOT_DEMONSTRATED** | 仅 1 次探针，无跨任务成功复用 |

## 4. 关键阴性结果

- 12 个任务中 11 个没有可观测 signature obstruction，归因器正确 ABSTAIN。
- 唯一有 witness 的失败任务，`PAIRWISE_12` 探针仍 0 来源，且撞 30k 上限；说明“被弃原子能区分训练对”不等于“扩宽 top-K 就能恢复候选”。
- 没有把 `d=1` 硬编码进归因器；witness 由真实 TRAIN 预测签名产生。
- 不能把 36 行测试绿灯当 G3/G4 成功。

## 5. RED/GREEN 真实性

- `SD03BlindAttributionR2ContractTest` 8/8 首跑通过；`SD03BlindAttributionR2IntegrationTest` 1/1。
- **没有新的业务 RED**；未复用旧 RED，不伪造 RED。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=not_run`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 6. 结论

R2 修正了“属性存在≠可区分”的概念错误，真实产生了一个 signature witness 和一次有界探针。  
但探针没有恢复来源/HELDOUT 正确率，ATTRIBUTION 与 CONTROL 无差异；因此：

- G2/G3 只能 `PASS_LIMITED`（机制与一次干预存在）；
- G4 `NO_BENEFIT`；
- G5 `NOT_DEMONSTRATED`。

下一问题：为什么扩宽 top-K 仍无候选？需要区分“原子保留不足”与“30k 候选/组合深度不足”，不能再用同一探针重复。

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03BlindAttributionR2ContractTest,logic.e3.pack02.SD03BlindAttributionR2IntegrationTest"
```

本轮停止，未启动 SD-04。
