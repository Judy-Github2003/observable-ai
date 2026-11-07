# SD-03 Blind Attribution R2 预注册

- 任务：`SD-03-BLIND-ATTRIBUTION-R2-2026-11-07`
- 起始 HEAD：`50e7921`（盲归因初版）
- Console：`SD-03-BLIND-ATTRIBUTION-R2-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 冻结条件

| 项 | 值 |
|---|---|
| 开发集成任务 | EXPRESSIBLE/DEPTH/IF_STRUCTURE 各 id 排序 `drop(2).take(2)`，6 世界 |
| 种子 | 20261109、20261110（12 任务-种子） |
| 分割 | `C8Domain.all().shuffled(Random(seed))`：40/20/21 |
| 学生 | MRX2BSpace PAIRWISE/10；maxCandidates=30000，consistentLimit=2000 |
| Arms | CONTROL=KEEP；GENERIC=DIVERSITY/10；ATTRIBUTION=R2 冻结决策 |
| 动作集 | KEEP/ABSTAIN/DIVERSITY_10/BASELINE_10/PAIRWISE_12 |
| 归因器 | `SD03BlindAttributionR2.decide`，只接收 TRAIN observations、原子预测签名、lifecycle、alternatives |
| 评估 | Challenge 独立 ledger；HELDOUT 仅 decision 冻结后考官评分 |
| 停止 | 仅当 trainConsistent=0、无 capHit、无 Unknown、存在 blocked witness 且替代动作覆盖全部 blocked pairs 才行动；否则 ABSTAIN |

## 关键修正

- 不再把“属性被提及”当“能区分”；比较相反标签 TRAIN 对在保留原子上的**真实预测签名**。
- 若保留签名相同、被弃原子能区分，才支持裁剪假说；不把 `d==1` 写死。
- 干预是 `PROBE_NOT_CAUSAL_PROOF`，不能直接称因果修复。

## 负对照

- 相同来源/合成 fixture 不计科研；
- 无 witness/Unknown/capHit → ABSTAIN；
- 不以 0/0 做 DISABLED；
- 不用 HELDOUT 调整动作。
