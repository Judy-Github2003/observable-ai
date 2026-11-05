# E3：开放表征与学习诊断实验

> 阶段：E3-A（已完成 A0/A1/A2）。本目录放实验文档与数据；学生代码在 `src/main/kotlin/logic/e3/`。

## 当前状态

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、B0 只读边界 | 完成 |
| A1 | 公共数据契约与诊断状态（仅数据结构） | 完成；已修正 FeatureKey 与重复来源语义 |
| A2 | SOLVABLE + LATENT_ALIASING 世界生成器 | 完成 |

- B0 冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（只读）。
- 全套测试 192/192（B0 173 + E3 19）；独立闭卷 `not_run`。
- 已实现最小生成器与独立 oracle；未实现诊断算法、A3 世界族或评分器。

## 目录

- `SPEC.md`：E3-A 范围、数据契约、边界。
- `DECISIONS.md`：A0/A1/A2 的实现取舍。
- `WORKSTATE.md`：下一任务 A3、测试与 SHA。
- `reports/E3-A0-A1-REPORT.md`：A0/A1 报告。
- `reports/E3-A2-REPORT.md`：A2 报告。
- `reports/a2-sample-worlds.json`：seed=42 示例（评测端，含隐藏真值，不可交给学生）。

## 边界

- E3-A 不要求解释真实世界为何存在冲突；`x1 == x2 && y1 != y2` 只能推出“现有可见特征上的确定性函数无法同时拟合”。
- 允许 `UNDETERMINED`；不为了确定答案强行分类。
- E3-B/C 未授权。
