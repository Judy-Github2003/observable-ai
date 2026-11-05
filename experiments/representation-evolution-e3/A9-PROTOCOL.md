# E3-A9 公平对照协议（v2，冻结前草案）

> 状态：协议草案 + 最小可执行基线；**未批量运行 A10**。
> 原则：先冻结指标、预算、世界、信息权限，再运行；隐藏真值只用于评测，不进入任何基线诊断。

## 1. 目标

比较综合诊断相对单点基线的**增量价值**，重点不是最高准确率，而是：

- 有界结论是否在其声明范围内成立；
- 隐藏预测错误率；
- 是否越权声称全局规律/隐藏属性/噪声；
- 覆盖、弃答、探测收益与**累计计算成本**。

## 2. 基线（同一公开证据、同一查询权限/成本）

| 基线 | 允许的诊断原语 | 输出 |
|---|---|---|
| `B0_ONLY` | A5 适配器 | 有界 IDENTIFIED / UNDETERMINED |
| `COLLISION_ONLY` | A4 碰撞检测 | 观察冲突 / UNDETERMINED；不搜索 |
| `REPEAT_ONLY` | A4 + A7 RepeatLabel | 动作记录；诊断结论保持 UNDETERMINED |
| `E3_DIAGNOSTIC` | A4+A5+A6+A7+A8 | 多 finding + 可选一次探测 |
| `ALWAYS_UNDETERMINED` | 无 | 永远弃答（覆盖率下界） |

- 同一公开 observations/schema/constants/queryDomain/gateway。
- 最多一次探测；查询成本 1/次；不得查询隐藏/未授权点。
- 基线不得接收 WorldFamily、HiddenTruth、target rule、ruleDepth、seed。

## 3. 总计算预算（关键修正）

单次 `maxComposeRounds` 相同**不等于**总预算相同；E3_DIAGNOSTIC 会在 plan/execute/before/after 多次调用 Composer/Filter。

统一记录 `E3UsageSnapshot`：

- `composerCalls`：Composer 调用次数；
- `generatedCandidates`：所有调用的 `generated` 总和；
- `filterCandidateEvaluations`：Filter 候选×公开样本执行量；
- `interpreterExecutions`：行为等价类/额外解释执行的次数；
- `publicQueries` / `queryCost`；
- `elapsedNanos`：实际墙钟。

公平方案：

1. 每个基线报告累计成本，不只报告单次预算；
2. 主分析同时给出**原始成本**与**成本匹配**结果：按 composerCalls / generatedCandidates 分层或配额比较；
3. 不允许用“单次预算相同”声称公平；
4. 若 E3_DIAGNOSTIC 需要更多调用，必须展示每增加一次调用带来的 false-certainty/coverage 增量，而非只报绝对分数；
5. 预算冻结：low=`rounds=2,nodes=32,candidates=3000,steps=10000`；high=`rounds=3`；同基线同预算；查询成本单独计。

## 4. 评测指标（分开，不合并）

### 4.1 bounded_claim_validity

当诊断为 `BOUNDED_IDENTIFIED` 时，检查该结论在**声明的假设空间 + 查询域**内是否成立（由评测端按冻结规则判定）。只对 bounded claim 定义；UNDETERMINED/COLLISION 为 null。

### 4.2 heldout_prediction_error

该 bounded 候选在冻结隐藏样本上的预测错误率。**这是独立指标**：有界结论后来隐藏预测错误，不等于认识论越权。

### 4.3 epistemic_overclaim_rate

只统计越权声称：

- `claimedGlobal=true`；
- 声称真实世界隐藏属性/噪声/确定原因而缺乏协议证明；
- 将观察冲突直接归因为隐藏属性。

不得把“有界辨识 + 隐藏预测错误”自动算作 overclaim。

### 4.4 覆盖/弃答/探测

- `diagnostic_coverage = BOUNDED_IDENTIFIED 数 / 总案例`；动作（Repeat/NoAction）**不增加**覆盖率。
- `collision_coverage = OBSERVATION_COLLISION 数 / 总案例`，与辨识覆盖分开。
- `abstention_rate`；ALWAYS_UNDETERMINED 的 diagnostic coverage 必须为 0。
- `probe_gain`：探测前后隐藏预测正确数变化；`probe_cost` 单列。
- `trace_completeness`：finding 是否有 evidenceIds/scope/替代解释/停止原因。

### 4.5 报告

同时报告三项主指标 + coverage/abstention + 累计成本；禁止只报准确率。任何“综合诊断更好”必须满足：同成本或成本匹配下，overclaim 不升、bounded validity 不降、coverage 不显著降。

## 5. 结论与动作分离

`A9BaselineResult` 分开：

- `diagnosticClaim`：BOUNDED_IDENTIFIED / OBSERVATION_COLLISION / UNDETERMINED；
- `action`：REQUEST_PUBLIC_INSTANCE / REPEAT_LABEL / NO_ACTION 的独立记录。

`A9ActionRecord` 字段：

```text
actionKind
requestedSampleId（若存在）
repeatedObjectId（若存在）
recordedObservationId
measurementKind
independentEvidence
actualCost
actualClaim（动作执行状态）
```

- 动作已执行 ≠ 新知识；REPLAYED_LABEL 不得当独立测量。
- 重复测量/NoAction 不得提高 diagnostic coverage。
- E3_DIAGNOSTIC 不得用 recordedObservationId 冒充 requestedSampleId。

## 6. 世界与冻结

- 世界族：SOLVABLE、LATENT_ALIASING、NOISY_LABELS、SEARCH_LIMITED、AMBIGUOUS_EVIDENCE。
- 每族至少两个规则模板/两个 seed；世界与 seed 运行前冻结并记哈希。
- 样本数/排列：4/5/6 条公开样本对照；顺序打乱版本单独评价。
- 查询池与隐藏集不重叠；source/reliability 声明冻结。

## 7. 运行与停止

- 先跑 smoke（5 基线 × 固定案例），确认 schema/成本/动作/无泄漏。
- 协议冻结后才可跑 A10；本轮**不跑 A10**。
- 失败、异常、NOT_RUN 全部保留，不得丢弃。
- 结论只对冻结世界/预算/查询域有效。

## 8. 最小实现

代码：`src/main/kotlin/logic/e3/E3A9Baselines.kt`、`E3UsageMeter.kt`
真实 smoke：`reports/a9-baseline-smoke.json`
测试：`E3A9BaselineTest`（15 行 smoke + 预算/指标/动作反例）。
