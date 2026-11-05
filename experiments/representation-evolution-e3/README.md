# E3：开放表征与学习诊断实验

> 阶段：E3-A（已完成 A0–A5）。学生契约在 `src/main/kotlin/logic/e3/`，评测端生成器在 `logic/e3/evaluator/`。

## 当前状态

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、B0 只读边界 | 完成 |
| A1 | 公共数据契约与诊断状态 | 完成；已修正 FeatureKey 与重复来源语义 |
| A2 | SOLVABLE + LATENT_ALIASING 生成器 | 完成；已修正隔离/查询权限/匹配对照 |
| A3 | NOISY_LABELS / SEARCH_LIMITED / AMBIGUOUS_EVIDENCE | 完成；已补 Composer 低预算对照 |
| A4 | 可观察特征碰撞检测器 | 完成；已补 objectId 区分 |
| A5 | 只读 B0 Composer/Filter 诊断适配器 | 完成 |

- B0 冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（只读）。
- 全套测试 **217/217**（B0 173 + E3 44）；独立闭卷 `not_run`。
- A5 只采集信号，不实现 A6 之后综合诊断。

## 目录

- `SPEC.md`：范围、契约、边界。
- `DECISIONS.md`：A0–A5 取舍与 worldId 风险。
- `WORKSTATE.md`：下一任务 A6、测试与 SHA。
- `reports/`：A0–A5 报告、示例数据、Composer 对照、A5 诊断信号。

## 边界

- 碰撞只证明当前可见属性不能唯一决定标签，不证明隐藏属性/噪声/世界原因。
- `depth_limit` 不证明规律不在假设空间；`IDENTIFIED` 仅限有界空间与查询域。
- worldId 哈希不是安全隔离；正式考试需独立进程/classpath 与 seed 隔离。
- E3-B/C 未授权。
