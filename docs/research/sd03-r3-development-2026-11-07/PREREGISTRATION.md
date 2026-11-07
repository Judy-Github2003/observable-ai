# SD-03 Sequential Attribution R3 预注册

- 任务：`SD-03-SEQUENTIAL-ATTRIBUTION-R3-2026-11-07`
- 起始 HEAD：`38dcba7`（盲归因 R2）
- Console：`SD-03-SEQUENTIAL-ATTRIBUTION-R3-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 冻结流程

| 阶段 | 规则 |
|---|---|
| 第一次决策 | 只读 R2 TRAIN-only snapshot；输出 `PROBE_*` 或 `ABSTAIN` |
| 决策封存 | 先 append+fsync `DECISION_0`，再真实搜索 |
| 第二次快照 | 由真实 `MRX2BSpace` 重跑生成，atom universe/TRAIN 不变 |
| 第二次决策 | `STOP_FOR_VERIFICATION` / `AUDIT_GENERATION_CAP` / `AUDIT_COMPOSITION_COVERAGE` / 不同 `PROBE_*` / `ABSTAIN` |
| 评分 | Challenge/HELDOUT 只在决策后考官侧 |

## 开发任务

- `edb6ed...`（`mrx-if-nested-3`，seed 20261109）
- `068096...`（`mrx-depth-w4`，seed 20261110）
- 公开开发回归，**不是独立盲测**。

## 判定

- G0：快照/决策时间顺序、无 truth/HELDOUT。
- G1：真实第二次 TRAIN-only snapshot。
- G2：多假说更新，Unknown 保留。
- G3：证据驱动第二步行动；诊断请求不等于修复。
- G4：与 CONTROL/GENERIC 对比 HELDOUT/成本；阴性保留。
- G5：跨任务/盲测；本轮不宣称。

## 禁止

- 不用 Challenge 标签决定 R3 动作；
- 不伪造 RED；
- 不把 `AUDIT_*` 诊断当修复；
- 不把开发任务称独立闭卷。
