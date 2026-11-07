# MRX-LIVE-HYP-2026-11-07 — 实验结果

> 失败与阴性结果保留；不得用事后分析或固定反馈映射补成 PASS。

## 0. 基线与授权

- 起始 HEAD：`425220376349e0741386f442ee04c70e696d1afd`（README 同步后）
- 结束 commit：本轮提交
- 原有未提交文件：`Observable_AI_B0_...`、`Observable_AI_E3_...`、`PACK-02R-DIAG-01.md`、`PACK-02R-PRE-01.md`（未触碰）
- Console 门禁：初次 `REFUSED: unknown task`；研究负责人回复“后续一律默认授权”后注册 `MRX-LIVE-HYP-2026-11-07`，preflight `AUTHORIZED_REPAIR`
- 专项授权 ID：`MRX-LIVE-HYP-2026-11-07`
- `formal_runs = 0`
- `independent_closed_book = not_run`
- `fullRegression = NOT_RUN`

## 1. 真实代码变更与 RED/GREEN

| 文件 | 修改必要性 | RED log | GREEN log |
|---|---|---|---|
| `src/main/kotlin/logic/e3/pack02/MRXLive.kt` | 新增在线假说状态、前后快照、通用行动评价、teacher-prior 动作模型、trace writer | `RED.log`（6/7 业务失败） | `GREEN.log` |
| `src/main/kotlin/logic/e3/pack02/MRX2Student.kt` | 新增可选 `LIVE_HYPOTHESIS`；保留 FIXED/STATIC/DYNAMIC | 同上 | 同上 |
| `src/test/kotlin/logic/e3/pack02/MRXLiveHypothesisContractTest.kt` | 时间隔离、状态更新、Unknown、行动评价、合法性 | 6 failed | 7/7 |
| `src/test/kotlin/logic/e3/pack02/MRXLiveIntegrationTest.kt` | 真实学生 before/after 时间线 | — | 1/1 |
| `docs/research/mrx-live-2026-11-07/TRACE-session-*.jsonl` | 真实在线轨迹 | — | 2 条 |

RED 摘要：

```text
7 tests completed, 6 failed
real counterexample observation updates only after it exists FAILED
evaluation reads hypothesis discrimination not feedback code FAILED
budget exhaustion updates its own hypothesis only FAILED
```

失败来自骨架未更新/未评价的业务断言，不是编译失败。GREEN：

```text
MRXLiveHypothesisContractTest + MRXLiveIntegrationTest
8 tests, 0 failures, 8.756s
BUILD SUCCESSFUL in 17s
```

JUnit：`junit-summary.json`；日志：`RED.log`、`GREEN.log`。

## 2. Teacher/Student/Examiner 信息边界

- **教师提供的先验**：
  - 四个通用失败假说模板（结构覆盖、反例压力、预算、Unknown/执行）；
  - 动作-假说可区分性模型：`KNOWLEDGE` → 结构；`DNF/IF/CNF` → 结构+反例；`BUDGET` → 预算。
  - 这些全部标记 `BUILTIN_BASELINE` / `teacher-prior:*`，**不是学生原创**。
- **学生在线维护/使用**：
  - `MRXLiveSnapshot` 版本、证据摘要、每个假说支持/反驳/未知 ID；
  - 决策前冻结 `snapshotBefore`；真实结果后 `snapshotAfter`；
  - 决策读取 `predictedDiscrimination` 与当前状态；没有世界 ID/反馈码→动作表。
- **考官独立持有**：
  - HELDOUT/目标真值仅用于测试中 `StudyQueryController` 的 oracle 构造，不进入 `MRX2Student`、trace、snapshot 或 planner。
- **泄漏检查**：
  - 学生 sessionId 为 `session-a` / `session-b`，不含 W4/XOR/target family；
  - trace 中无 HELDOUT/heldout 字段；
  - 训练/查询池来自既有 split；未将考官后验 G1–G5 回填。

## 3. 单条真实在线链路

### Session A（匿名；历史 W4 类案例）

| 阶段 | before snapshot | 可见 evidence IDs | 动作与理由 | 实际反馈 | after snapshot |
|---|---|---|---|---|---|
| step 0 | v0 初始四假说 UNKNOWN | — | VALIDATE_BASE（旧基线） | COUNTEREXAMPLE | v1：H_COUNTER SUPPORTED，其余 UNKNOWN/REFUTED 依赖 base |
| step 1 | v1，`H_COUNTER=SUPPORTED`，`H_STRUCTURE=UNKNOWN` | `real-step-1`（决策前尚未产生）；决策使用 `step:0`/ledger | `IF`，`DISCRIMINATE=H_COUNTEREXAMPLE_PRESSURE`，predicted={H_COUNTER,H_STRUCTURE}，cost=2 | PASS_ON_CHALLENGE | v2：H_STRUCTURE REFUTED、H_COUNTER CONFLICTED，其余 REFUTED |
| STOP | — | — | 成功即停止 | — | — |

- 只有 1 个可执行 action；没有两次失败。
- `snapshotBefore` 不包含 `real-step-1`；`snapshotAfter` 才包含，时间顺序通过测试。
- 这是真实执行器/账本反馈，不是注入。

### Session B（匿名；独立 XOR 类案例）

| 阶段 | before snapshot | 动作与理由 | 实际反馈 | after snapshot |
|---|---|---|---|---|
| 1 | v1：H_COUNTER SUPPORTED，其余 UNKNOWN | `IF`，discriminate H_COUNTER | COUNTEREXAMPLE | v2：同状态，新增 evidence |
| 2 | v2 | `DNF`，同一预测集，因 IF 已试且无新区分 | COUNTEREXAMPLE | v3 |
| 3 | v3 | `CNF`，同一预测集 | COUNTEREXAMPLE | v4 |
| 结束 | — | `INSUFFICIENT_EVIDENCE` | 无 PASS | — |

- 三次真实失败；动作不再重复 IF（`excludeActions` 生效）。
- 但 H_STRUCTURE 始终 UNKNOWN，H_COUNTER 始终 SUPPORTED；动作差异来自“避免重复”而非新假说区分。
- 没有 `LIVE_FROZEN_BELIEF`/`LIVE_NO_HYP` 消融。

## 4. 实验等级

| Gate | 判定 | 原始 trace 和原因 |
|---|---|---|
| C1 时间真实 | **PASS_LIMITED** | `TRACE-session-a/b`：before 版本、after 版本、observationId 只在 after；但 step0 的 base 诊断仍来自旧路径，`diagnosisBefore/After` 仍是重复最终值（旧字段局限） |
| C2 在线归因/决策使用 | **PASS_LIMITED** | 4 假说、SUPPORTED/REFUTED/CONFLICTED/UNKNOWN 分化；决策 rationale 引用 `DISCRIMINATE=...`；但 teacher-prior 动作模型是人工先验，且 only H_COUNTER 被真正区分 |
| C3 严格配对因果 | **NOT_DEMONSTRATED** | 只有 A/B 两个不同 session；base 反馈不同、前缀不同；A 只有 1 action；无同前缀不同第二反馈；无 frozen/no-hyp 消融 |
| C4 成本/成功收益 | **INCONCLUSIVE / NO_BENEFIT** | A 成功但仅 1 action；B 失败；无 STATIC/RULE 同轮配对；样本 2 个开发案例 |

## 5. 严格配对尝试

- 冻结策略/接口后，检查 A/B 两个真实 session。
- 相同点：同 seed/split、同 TRAIN、同合法池、同预算 40；第一步均为 `VALIDATE_BASE`。
- 不同点：第一步真实反馈 A=`COUNTEREXAMPLE`；B 的基础验证反馈不是同一类（trace v1 中 H_STRUCTURE 仍 UNKNOWN，未形成 counterexample 支持），因此**首个可见证据已不同**，不是“同前缀”；A 第二步 `IF` 成功，B 第二步 `IF` 反例。
- 未能在本轮构造“同初始、同第一失败、同第二动作、第二反馈不同”的合法配对。
- 尝试数：2 个预注册候选 session、1 个 seed；未做 24×8 有界搜索（时间/构造复杂度限制），因此不能声称穷尽。
- 结论：**END_TO_END_NOT_DEMONSTRATED**。
- `LIVE_FROZEN_BELIEF`/`LIVE_NO_HYP`：**NOT_IMPLEMENTED / NOT_RUN**；因此即使未来有配对，也尚未排除“最后反馈直接查表”的伪因果。

## 6. 真实策略对照与成本

| Session | Strategy | Success | Actions | New Queries | Reused Labels | Generated | Interpreter Calls | Wall-ms | Teacher Calls |
|---|---|---|---|---:|---|---:|---:|---:|---:|
| A | LIVE_HYPOTHESIS | REPAIRED | VALIDATE_BASE > IF | 0 | UNKNOWN | UNKNOWN | 90 | 1,891 | 0 |
| A | STATIC（上一轮同 split） | REPAIRED | VALIDATE_BASE > DNF | 29 | 0 | UNKNOWN | 74 | 3,574 | 0 |
| A | RULE/FIXED（上一轮） | REPAIRED | 4 步 | 29 | 0 | UNKNOWN | 145 | 6,130 | 0 |
| B | LIVE_HYPOTHESIS | INSUFFICIENT_EVIDENCE | VALIDATE_BASE > IF > DNF > CNF | 0 | UNKNOWN | UNKNOWN | 141 | 5,839 | 0 |
| B | STATIC（上一轮） | INSUFFICIENT_EVIDENCE | DNF > IF > CNF | 16 | 0 | UNKNOWN | 141 | 6,227 | 0 |
| B | RULE/FIXED（上一轮） | INSUFFICIENT_EVIDENCE | 4 步 | 29 | 0 | UNKNOWN | 119 | 6,465 | 0 |

说明：

- LIVE 的 `New Queries=0` 是因为动作通过已冻结/已付费的 challenge 账本执行；不能与 STATIC/RULE 的 29/16 直接相加比较。
- `Generated`/`Reused Labels` 在当前结果中未可靠取得，标 `UNKNOWN`，不倒填 0。
- 上一轮 STATIC/RULE 来自 `docs/research/mrx-next-2026-11-07/COMPARISON.md`，不是同一次进程运行；只能作背景，不能当严格同轮对照。
- 总成本无明显收益；A 的 LIVE 比 STATIC 少 1 步但解释器调用更多（90 vs 74），B 同失败且调用相同。

## 7. 失败与下一轮问题

- **成功机制与边界**：真实 before/after 假说链、匿名 session、行动评价接口已成立；但 teacher-prior 动作模型、单 H_COUNTER 区分、无严格配对，不能称“自主假说学习”。
- **阴性结果**：
  - W4 类 A 只有一次 action，无法观察两次失败后的变化；
  - XOR 类 B 三次失败但状态不分化，动作差异来自排除已试动作；
  - 无 `LIVE_FROZEN_BELIEF`/`LIVE_NO_HYP` 对照。
- **人工映射/事后归因风险**：动作模型是教师先验；`MRXNextAnalyzer` 未被用于决策；旧 `diagnosisBefore/After` 仍不能作为变化证据。未发现把事后分析回填为决策。
- **最重要的未解决问题**：
  1. 如何构造同前缀、第二真实反馈不同的合法配对世界，同时保持候选空间/预算一致？
  2. 通用操作符与假说之间是否存在可检验的预测模型，而不是教师先验表？
  3. 候选空间覆盖不足时，是否应先扩展表达而不是继续选择查询/动作？
- 明确停止，等待研究负责人审查；不自动进入下一任务。

## 8. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.MRXLiveHypothesisContractTest,logic.e3.pack02.MRXLiveIntegrationTest"
```

未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次正式考试或独立闭卷。
