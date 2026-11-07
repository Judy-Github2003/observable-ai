# SD-03 Root Cause 预注册

- 任务：`SD-03-ROOT-CAUSE-2026-11-07`
- 起始 HEAD：`3cee51a`（数据闭合）
- Console：`SD-03-ROOT-CAUSE-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 输入域 | C8 `{a,b,c,d}×{0,1,2}`，81 |
| 世界 A/B | `a=1 ∧ d=1`；`b=1 ∧ c=1` |
| split | seed 1449，40 TRAIN /20 CHALLENGE/21 HELDOUT |
| 库 | `C8TestBases.bases.library("FULL")` |
| 候选预算 | maxCandidates=30000，consistentLimit=2000 |
| A 组 | A0 PAIRWISE/10；A1 PAIRWISE/12；A2 DIVERSITY/10；A3 BASELINE/10 |
| B 组 | B0 PAIRWISE/10 校准 |
| 计量 | 每组独立 controller/ledger/cost；生成解释器调用未统一暴露 |
| GOLD | 仅考官侧验证可表达性/类型/Oracle，不进入 ranking/候选 |

## 可证伪假设

- H1：A0 前 10 原子缺少 `d=1`。
- H2：PAIRWISE 同分/字典序偏向其他原子。
- H3：TRAIN 数据不足。
- H4：30k 候选上限截断。
- H5：TRAIN_CONSISTENT/RETAINED 剪枝。
- H6：IR/typecheck/Oracle 故障。
- H7：宏 caller values 缺少 `call_` 前缀。

## 停止

- 若 ranking 重建不一致 → `DIAGNOSTIC_INVALID`。
- 若 GOLD 失败 → 先查 H6。
- 诊断后停止，不自动扩搜索器/注册宏。
