# SD-03 R3.1 预注册：枚举预算因果拆解

- 任务：`SD-03-R31-ENUMERATION-CAUSAL-2026-11-07`
- 起始 HEAD：`b7580e7`（R3）
- Console：`SD-03-R31-ENUMERATION-CAUSAL-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 任务 | `mrx-if-nested-3`，seed 20261109 |
| 分割 | C8 `all().shuffled(Random(20261109))`：40 TRAIN /20 CHALLENGE/21 HELDOUT |
| 库/排序 | FULL，PAIRWISE，Top-12 原子 |
| 上限 | maxCandidates=30,000，consistentLimit=2,000 |
| 组 | LEGACY_12；QUOTA_IF12；QUOTA_IF24 |
| 配额 | LITERAL=256, AND=1024, OR=23000, IF=5720（总 30,000） |
| 判据 | IF attempted/admitted、globalCapRejected/familyQuotaRejected、trainConsistent、Challenge/HELDOUT、nestedIf AST 计数 |
| 停止 | 不根据成绩调阈值；无来源则 NO_BENEFIT；不把诊断当修复 |

## 解释边界

- LEGACY 与 QUOTA 是“调度+配额”的组合干预，不能单独归因。
- IF12 vs IF24 只变 If 可见字面量范围。
- 若只看到直接 If 无嵌套 If，只能结论 `DIRECT_NESTED_IF_TEMPLATE_ABSENT`，不能称语义不可表达。
