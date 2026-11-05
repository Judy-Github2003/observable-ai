# E3：开放表征与学习诊断实验

> 阶段：E3-A（只完成 A0/A1）。本目录只放实验文档与后续数据；学生代码在 `src/main/kotlin/logic/e3/`。

## 当前状态

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、B0 只读边界 | 完成 |
| A1 | 公共数据契约与诊断状态（仅数据结构） | 完成 |
| A2 | SOLVABLE + LATENT_ALIASING 世界生成器 | 未开始 |

- B0 冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（只读）。
- 全套测试 182/182（B0 173 + E3 9）；独立闭卷 `not_run`。
- 未实现诊断算法、世界生成器、oracle、隐藏数据或评分器。

## 目录

- `SPEC.md`：E3-A 范围、数据契约、边界。
- `DECISIONS.md`：A0/A1 的实现取舍。
- `WORKSTATE.md`：下一任务 A2、测试与 SHA。
- `reports/E3-A0-A1-REPORT.md`：本轮工作报告。
- 规划中（未创建数据）：`fixtures/`、`evaluator/`、`runs/`、`reports/`。

## 边界

- E3-A 不要求解释真实世界为何存在冲突；`x1 == x2 && y1 != y2` 只能推出“现有可见特征上的确定性函数无法同时拟合”。
- 允许 `UNDETERMINED`；不为了确定答案强行分类。
- E3-B/C 未授权。
