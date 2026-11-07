# SD-03 E1 预注册：证据驱动条件组合

- 任务：`SD-03-E1-EVIDENCE-COMPOSITION-2026-11-07`
- 起始 HEAD：`ee9b707`（R3.2）
- Console：`SD-03-E1-EVIDENCE-COMPOSITION-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 任务 | `mrx-base-or-r-d1@20261111`、`mrx-depth-w4@20261110`、`mrx-depth-r-xor-b2@20261112`、`mrx-if-nested-3@20261109` |
| 分割 | C8 `all().shuffled(Random(seed))`：40 TRAIN /20 CHALLENGE/21 HELDOUT |
| Arms | LEGACY_10；R32_NESTED_12；EVIDENCE_DEPTH_1；EVIDENCE_DEPTH_4 |
| E1 配置 | beam=5，maxTreeNodes=63，maxPartitionAttempts=30000，minExamplesPerChild=1 |
| 评价 | TRAIN 由真实解释器独立执行；Challenge 独立 ledger；HELDOUT 仅在冻结后考官评分 |
| 停止 | Unknown 保留 null；冲突标签停止；budget cap 标注；不按结果调参 |

## 边界

- 公开开发任务，不是独立闭卷。
- 候选为 proposal；TRAIN 一致不等于语义正确。
- E1 计数是 atom-partition tests，baseline 是 admitted programs，不可当等算力比较。
