# MRX-CAUSAL-ABLATION-2026-11-07 — 预注册

- 起始 HEAD：`58e2ae1`（随后有 README 提交 `4252203` 和本轮实验提交）
- Console 授权：`MRX-CAUSAL-ABLATION-2026-11-07`，ACTIVE → 完成后 CONSUMED
- 正式考试：`formal_runs=0`
- `independent_closed_book=not_run`
- `fullRegression=NOT_RUN`

## 固定策略

| 策略 | 是否读信念 | 是否在线更新 | 冻结时刻 | 动作/预算 |
|---|---|---|---|---|
| LIVE_HYPOTHESIS | 是 | 是 | 无 | 共享 |
| LIVE_FROZEN_BELIEF | 是 | 否 | 首次 VALIDATE_BASE 后 | 共享 |
| LIVE_NO_HYP | 否 | 否 | 不适用 | 固定中性顺序 DNF→IF→CNF→KNOWLEDGE→BUDGET；共享已试动作/预算/停止 |

`LIVE_NO_HYP` 不读取假说 ID、teacher-prior 映射或证据状态；只按预先冻结的顺序取第一个未试动作。

## 指标与判定

- **L1（必须）**：在 allowed/excluded/budget/teacher-prior/cost 相同、仅信念内容变化的条件下，LIVE 决策因语义不同而不同；FROZEN/NO_HYP 不产生同样的信念依赖。合成 fixture 只算 `MECHANISM_ONLY_PASS`。
- **L2（努力）**：真实同前缀、同第一失败、同第二动作，第二真实反馈不同；LIVE 第三步不同，FROZEN/NO_HYP 无相同差异。找不到则 `NOT_DEMONSTRATED`。
- **L3（探索）**：同轮 LIVE/FROZEN/NO_HYP/STATIC/FIXED 的成功率与成本；样本小则 `INCONCLUSIVE/NO_BENEFIT`。

## 负对照

1. 仅证据 ID/版本变化，不改变假说语义，不应改变动作。
2. DNF/IF/CNF 预测区分集/成本相同，只报告 tie-break，不算信息增益。
3. 历史反例在后续候选 PASS 后仍保留；PASS 只限定当前候选/检查范围。
4. Unknown、预算耗尽、ERROR 不记为 PASS/反驳。

## 有界配对计划

- 目标：现有 `MRXWorlds` 至多 24 对候选 × 8 split。
- 实际本轮只做 2 个匿名 session（A/B）的开发检查；未做 24×8 全搜索，原因是当前 `MRX2Student` 首步反馈/候选状态已不同，且时间有限。
- 若无法构造严格同前缀，记录 `REAL_PAIR_NOT_FOUND / NOT_DEMONSTRATED`，不伪造反馈。

## 明确不做

- 不运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次正式考试。
- 不改旧冻结 JSON、Research Console 成绩、历史报告。
- 不把教师先验动作模型包装成学生自主发现。
