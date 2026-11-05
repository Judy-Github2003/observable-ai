# E3-A9 公平对照协议（v1，冻结前草案）

> 状态：协议草案 + 最小可执行基线；**未批量运行 A10**。
> 原则：先冻结指标、预算、世界、信息权限，再运行；隐藏真值只用于评测，不进入任何基线诊断。

## 1. 目标

比较综合诊断相对单点基线的**增量价值**，重点不是最高准确率，而是：

- 是否降低错误确定性（false certainty）；
- 是否在有效覆盖（coverage）下保持可追溯；
- 探测收益是否值得查询成本；
- 是否诚实保留 UNDETERMINED / 替代解释。

## 2. 基线（同一公开证据、同一查询权限/成本）

| 基线 | 允许的诊断原语 | 输出 |
|---|---|---|
| `B0_ONLY` | A5 适配器（Composer/Filter） | 有界 IDENTIFIED 或 UNDETERMINED |
| `COLLISION_ONLY` | A4 碰撞检测 | 观察冲突或 UNDETERMINED；不搜索 |
| `REPEAT_ONLY` | A4 + A7 RepeatLabel | 重复测量/NoAction；不搜索 |
| `E3_DIAGNOSTIC` | A4+A5+A6+A7+A8 | 多 finding 报告 + 可选一次探测 |
| `ALWAYS_UNDETERMINED` | 无 | 永远弃答（覆盖率下界对照） |

- 每个基线使用同一组公开 observations、schema、constants、queryDomain、gateway 权限。
- 查询成本统一为 1/次；最多 1 次探测（A9 最小版）；不得额外查询隐藏/未授权点。
- 基线不得接收 WorldFamily、HiddenTruth、target rule、ruleDepth、seed。
- `B0_ONLY` 只能看公开样本/查询域；`COLLISION_ONLY` 只能看 A4 输入；`REPEAT_ONLY` 只允许重复测量；不得越权。

## 3. 世界与预算冻结

- 世界族：SOLVABLE、LATENT_ALIASING、NOISY_LABELS、SEARCH_LIMITED、AMBIGUOUS_EVIDENCE。
- 每族至少两个规则模板/两个 seed；世界与 seed 在运行前冻结并记哈希。
- 样本数/排列：至少 4/5/6 条公开样本的匹配对照；顺序打乱版本单独评价。
- 预算：low=`rounds=2,nodes=32,candidates=3000,steps=10000`；high=`rounds=3`；同基线同预算。
- 每个案例最多一次 A7 探测；成本 1；查询池与隐藏集不重叠。

## 4. 评测指标

### 主指标：错误确定性（false certainty）

统计以下任一：

1. 声称 `BOUNDED_IDENTIFIED`，但在冻结隐藏输入上预测错误；
2. 声称全局规则/真实世界原因（禁止；任何 `claimedGlobal=true` 即错误）；
3. 声称 “noise” 或 “representation gap” 而缺乏协议要求的证明；
4. 将观察冲突直接归因为隐藏属性。

`false_certainty_rate = false_certainty_cases / cases_with_non_UNDETERMINED_claim`；同时报告绝对案例数。

### 覆盖与有效性

- `coverage = 非 UNDETERMINED 输出数 / 总案例`（ALWAYS_UNDETERMINED 为 0，防止“永远弃答”得分）。
- `valid_coverage = 有界辨识且隐藏预测正确 / 总案例`。
- `abstention_accuracy`：应弃答案例中实际弃答比例（由评测端真值定义，不进入基线）。
- `probe_cost`：总查询成本/案例。
- `probe_gain`：探测前后隐藏预测正确数变化（仅有界辨识案例）。
- `trace_completeness`：finding 是否有 evidenceIds/scope/替代解释/停止原因。

### 报告要求

- 同时报告 false certainty、coverage、valid coverage、成本；不得只报准确率。
- 阴性/失败案例全部保留，单独列 `failure_cases.jsonl`。
- 任何“综合诊断更好”的结论必须同时满足：同预算、同成本、盲评、false certainty 不升且 valid coverage 不显著降。

## 5. 允许的原语与信息边界

- 学生/基线输入：`PublicWorld`、`PublicConstants`、`queryPool`、受控 `PublicQueryOracle`。
- 评测端：hidden labels、WorldFamily、target rule、`ruleDepth` 只用于评分与分层，绝不回传。
- 禁止用 sample count / worldId / 来源格式推断 family；A9 要做计数/排列/命名对照。
- 任何“新观察通道”实验属于 E3-B，不在 A9。

## 6. 运行与停止

- 先跑最小 smoke（5 基线 × 固定案例），确认输出 schema/成本/无泄漏。
- 协议冻结后才可跑 A10 批量；本轮不跑 A10。
- 失败案例、异常、NOT_RUN 必须记录，不得丢弃。
- 结论只对冻结世界/预算/查询域有效。

## 7. 最小可执行基线

代码：`src/main/kotlin/logic/e3/E3A9Baselines.kt`
真实 smoke 输出：`reports/a9-baseline-smoke.json`
测试：`E3A9BaselineTest`（只验证基线与边界，不做批量实验）。
