# SD-03 E2 预注册：主动查询与局部修复

- 任务：`SD-03-E2-ACTIVE-QUERY-LOCAL-REPAIR-2026-11-07`
- 起始 HEAD：`74972d8`（E1）
- Console：`SD-03-E2-ACTIVE-QUERY-LOCAL-REPAIR-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 任务/seed | base-or-r-d1/20261111；w4/20261110；xor-b2/20261112；if-nested-3/20261109 |
| 分割 | 32 TRAIN /12 QUERY/16 CHALLENGE/21 HELDOUT |
| Arms | STATIC；RANDOM；ACTIVE_FULL；ACTIVE_LOCAL |
| 查询 | 每任务最多 4；RANDOM 与 ACTIVE_FULL 实际数量匹配；ACTIVE_LOCAL 回放同一 QUERY IDs |
| 修复 | ACTIVE_LOCAL 只替换最深非根子树；不得隐藏 full refit |
| 评分 | 决策冻结后独立 ledger Challenge；HELDOUT 仅 eligible pass |

## 判据

- G1：真实预测分歧 → QUERY ID；ABSTAIN 合法。
- G2：新标签改变候选/模型；无变化 NOT_DEMONSTRATED。
- G3：真实非根局部替换且兄弟结构不变；0 次则 NOT_DEMONSTRATED。
- G4：相对 STATIC/RANDOM 的 Challenge/HELDOUT/成本收益。
- G5：独立闭卷 NOT_RUN。
