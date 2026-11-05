# E3：开放表征与学习诊断实验

> 阶段：E3-A（已完成 A0–A3）。本目录放实验文档与数据；学生契约在 `src/main/kotlin/logic/e3/`，评测端生成器在 `logic/e3/evaluator/`。

## 当前状态

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、B0 只读边界 | 完成 |
| A1 | 公共数据契约与诊断状态 | 完成；已修正 FeatureKey 与重复来源语义 |
| A2 | SOLVABLE + LATENT_ALIASING 世界生成器 | 完成；已修正 heldOut、查询权限、匹配对照 |
| A3 | NOISY_LABELS / SEARCH_LIMITED / AMBIGUOUS_EVIDENCE | 完成 |

- B0 冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（只读）。
- 全套测试 **197/197**（B0 173 + E3 24）；独立闭卷 `not_run`。
- 已实现五个生成器家族与受控 oracle；未实现诊断算法、A4 碰撞检测或评分器。

## 目录

- `SPEC.md`：E3-A 范围、数据契约、边界。
- `DECISIONS.md`：A0–A3 的实现取舍。
- `WORKSTATE.md`：下一任务 A4、测试与 SHA。
- `reports/E3-A0-A1-REPORT.md`：A0/A1 报告。
- `reports/E3-A2-REPORT.md`：A2 初版报告。
- `reports/E3-A2-A3-REPORT.md`：A2 修正 + A3 报告。
- `reports/a2-sample-worlds.json`：seed=42 示例（评测端，含隐藏真值，不可交给学生）。

## 边界

- E3-A 不要求解释真实世界为何存在冲突；`x1 == x2 && y1 != y2` 只能推出“现有可见特征上的确定性函数无法同时拟合”。
- 允许 `UNDETERMINED`；不为了确定答案强行分类。
- 生成器存在不等于诊断能力；A4 才做碰撞检测。
- E3-B/C 未授权。
