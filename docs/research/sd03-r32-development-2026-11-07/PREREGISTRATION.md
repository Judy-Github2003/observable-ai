# SD-03 R3.2 预注册：枚举公平性

- 任务：`SD-03-R32-ENUMERATION-FAIRNESS-2026-11-07`
- 起始 HEAD：`0fe392a`（R3.1）
- Console：`SD-03-R32-ENUMERATION-FAIRNESS-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 任务/seed | `mrx-if-nested-3`，20261109 |
| 分割 | 40 TRAIN /20 CHALLENGE/21 HELDOUT，FULL，PAIRWISE Top-12 |
| 预算 | maxCandidates=30,000；quotas LITERAL 256/AND 1024/OR 23000/IF 5720 |
| Arms | LEX_DIRECT24（R3.1 兼容）；ROTATING_DIRECT24（同直接语法，仅顺序）；ROTATING_NESTED24（2860 direct + restricted nested；组合干预） |
| 指标 | family attempted/admitted/rejected；direct role kinds；nested accepted；TRAIN consistent；Challenge/HELDOUT；interpreter calls/wall |
| 停止 | LEX 与 R3.1 QUOTA_IF24 canonical 不一致则停止；不以结果调顺序/配额 |

## 解释边界

- ROTATING_DIRECT vs LEX 是纯枚举顺序对照。
- ROTATING_NESTED 同时改语法与把 2860 direct 槽位换成 nested，**不是纯嵌套语法对照**。
- 无 TRAIN-consistent/Challenge 结果不得称语义不可表达；syntactic nested ≠ semantic expressivity。
