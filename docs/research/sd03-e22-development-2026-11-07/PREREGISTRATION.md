# SD-03 E2.2 预注册：一致猜想与查询命中

- 任务：`SD-03-E22-CONSISTENT-CONJECTURES-2026-11-07`
- 起始 HEAD：`5636a2c`（E2.1）
- Console：`SD-03-E22-CONSISTENT-CONJECTURES-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 任务 | 4 世界 × 2 seeds（20261111/21、20261110/22、20261112/23、20261109/24） |
| 分割 | 32 TRAIN /12 QUERY/16 CHALLENGE/21 HELDOUT |
| Arms | STATIC；RANDOM_FIXED_4；E2_LEGACY；E21_STRICT；E22_CONJECTURE |
| E1 | depth4, beam5, nodes63, attempts30000 |
| E22 | maxConjectures64, depth5, nodes65, minLeafSupport1 |
| 查询上限 | 4/task/arm；ABSTAIN 合法 |
| 审计 | 五组冻结后考官审计 12 QUERY 反例与命中；评分独立 ledger |

## 判据

- 每步 12 行预测/分数/排名/落选原因可重算；
- 只允许全公开标签一致 + 真实类型/解释器校验的猜想投票；
- 不针对 q-2 特制；再次命中不称独立泛化；
- 无机会 → `NO_OPPORTUNITY`，不称漏选。
