# E3：开放表征与学习诊断实验

> 阶段：E3-A（已完成 A0–A6）。学生契约在 `src/main/kotlin/logic/e3/`，评测端生成器在 `logic/e3/evaluator/`。

## 当前状态

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、B0 只读边界 | 完成 |
| A1 | 公共数据契约与诊断状态 | 完成；已修正 FeatureKey 与重复来源语义 |
| A2 | SOLVABLE + LATENT_ALIASING 生成器 | 完成；已修正隔离/查询权限/匹配对照 |
| A3 | NOISY_LABELS / SEARCH_LIMITED / AMBIGUOUS_EVIDENCE | 完成；已补 Composer 低预算对照 |
| A4 | 可观察特征碰撞检测器 | 完成；已补 objectId 区分 |
| A5 | 只读 B0 Composer/Filter 适配器 | 完成；已补输入校验与真实轨迹 |
| A6 | 搜索空间覆盖与预算诊断 | 完成 |

- B0 冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（只读）。
- 全套测试 **226/226**（B0 173 + E3 53）；独立闭卷 `not_run`。
- A6 只诊断公开证据与预算，不证明真实世界规则。

## 目录

- `SPEC.md`：范围、契约、边界。
- `DECISIONS.md`：A0–A6 取舍与 worldId 风险。
- `WORKSTATE.md`：下一任务 A7、测试与 SHA。
- `reports/`：A0–A6 报告、示例数据、Composer 对照、A5/A6 真实输出。

## 边界

- 碰撞只证明当前可见属性不能唯一决定标签，不证明隐藏属性/噪声/世界原因。
- 组合轮数截断不证明规律不在假设空间；`IDENTIFIED` 仅限有界空间与查询域。
- 高预算新候选不当作真实规则；高预算失败不当作表示不足。
- worldId 哈希不是安全隔离；正式考试需独立进程/classpath 与 seed 隔离。
- E3-B/C 未授权。
