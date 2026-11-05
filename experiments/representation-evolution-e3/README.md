# E3：开放表征与学习诊断实验

> 阶段：E3-A（已完成 A0–A8）。学生契约在 `src/main/kotlin/logic/e3/`，评测端生成器在 `logic/e3/evaluator/`。

## 当前状态

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、B0 只读边界 | 完成 |
| A1 | 公共数据契约与诊断状态 | 完成；已修正 FeatureKey 与重复来源语义 |
| A2 | SOLVABLE + LATENT_ALIASING 生成器 | 完成；已修正隔离/查询权限/匹配对照 |
| A3 | NOISY_LABELS / SEARCH_LIMITED / AMBIGUOUS_EVIDENCE | 完成；已补 Composer 低预算对照 |
| A4 | 可观察特征碰撞检测器 | 完成；已补 objectId 区分 |
| A5 | 只读 B0 Composer/Filter 适配器 | 完成；已补输入校验与真实轨迹 |
| A6 | 搜索空间覆盖与预算诊断 | 完成；已补行为预算/候选层级/事实集 |
| A7 | 重复观测与公开探测动作 | 完成；已补执行门禁/响应校验/重复语义 |
| A8 | 多 finding 诊断整合 | 完成；已修正探测证据合并/候选状态 |
| A9 | 公平对照协议 + 累计预算/独立评分 | 协议/smoke/评分入口完成；A10 未运行 |

- B0 冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（只读）。
- 全套测试 **284/284**（B0 173 + E3 111）；独立闭卷 `not_run`。
- A8 结论受当前假设空间、预算、公开查询域和策略限制。

## 目录

- `SPEC.md`、`DECISIONS.md`、`WORKSTATE.md`、`A9-PROTOCOL.md`
- `reports/`：A0–A8 报告、A9 smoke、示例数据、A5/A6/A7/A8 真实输出。

## 边界

- 碰撞/预算/探测结果不证明真实世界原因；替代解释保留。
- 有界辨识不是全局正确；NoAction 不声称世界无可查价值。
- worldId 哈希不是安全隔离；正式考试需独立进程/classpath 与 seed 隔离。
- E3-B/C 未授权。












