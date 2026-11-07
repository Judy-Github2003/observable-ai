# SD-03 E2.1 预注册：反例机会与单标签局部修复

- 任务：`SD-03-E21-COUNTEREXAMPLE-OPPORTUNITY-2026-11-07`
- 起始 HEAD：`a1b9ad2`（E2）
- Console：`SD-03-E21-COUNTEREXAMPLE-OPPORTUNITY-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 任务 | base-or-r-d1/20261111；w4/20261110；xor-b2/20261112；if-nested-3/20261109 |
| 分割 | 32 TRAIN /12 QUERY/16 CHALLENGE/21 HELDOUT |
| A arms | STATIC；E2_DISAGREEMENT；STRICT_VERSIONSPACE；RANDOM_MATCHED |
| B arms | NO_UPDATE；FULL_REFIT；LOCAL_ONLY |
| B 选择 | 按 q-ID 字典序第一个已知错误初始预测；不按修复成功率 |
| 评分 | A/B 冻结后独立 Challenge；HELDOUT 仅 pass |

## 判据

- A：审计 12 QUERY 中初始反例数、各策略命中数；E2 不接收 truth。
- B：只揭示一条标签；local 只改非根子树，兄弟结构不变；不得 root refit。
- 无 B 反例 → `NO_POOL_COUNTEREXAMPLE`，0 次 local 不作为失败证据。
