# E3：开放表征与学习诊断实验

> 阶段：E3-A（已完成 A0–A4）。学生契约在 `src/main/kotlin/logic/e3/`，评测端生成器在 `logic/e3/evaluator/`。

## 当前状态

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、B0 只读边界 | 完成 |
| A1 | 公共数据契约与诊断状态 | 完成；已修正 FeatureKey 与重复来源语义 |
| A2 | SOLVABLE + LATENT_ALIASING 生成器 | 完成；已修正隔离/查询权限/匹配对照 |
| A3 | NOISY_LABELS / SEARCH_LIMITED / AMBIGUOUS_EVIDENCE | 完成；已补 Composer 低预算对照 |
| A4 | 可观察特征碰撞检测器 | 完成；只检测标签冲突，不归因 |

- B0 冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（只读）。
- 全套测试 **208/208**（B0 173 + E3 35）；独立闭卷 `not_run`。
- 未实现 A5 诊断适配器、原因归因或评分器。

## 目录

- `SPEC.md`：E3-A 范围、数据契约、边界。
- `DECISIONS.md`：A0–A4 的实现取舍。
- `WORKSTATE.md`：下一任务 A5、测试与 SHA。
- `reports/`：A0/A1、A2、A2+A3、A4 报告；seed=42 示例数据；Composer 低预算对照。
- `a2-sample-worlds.json`：评测端示例，含隐藏真值，不可交给学生。

## 边界

- 碰撞只证明当前可见属性不能唯一决定标签，不证明隐藏属性存在，也不证明噪声/世界原因。
- 允许 `UNDETERMINED`；不为了确定答案强行分类。
- 生成器/seed/HiddenTruth 不得进入学生运行环境；当前同 JVM 测试仅证明接口隔离。
- E3-B/C 未授权。
