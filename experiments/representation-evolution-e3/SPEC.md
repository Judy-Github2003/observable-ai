# E3 开放表征与学习诊断实验：阶段 A 规范

> 版本：A0/A1（2026-11-05）。依据《Observable AI — E3 分阶段实施任务书 v1.0》。
> 本文件只记录当前已授权的 E3-A0/A1；E3-B/C 仅保留路线，不实施。

## 1. 研究位置

E3-A 研究：当现有规则不能可靠解释观察时，学生能否区分
**可观察特征碰撞、搜索截断、假设歧义、标签不稳定/噪声、证据不足**，
并给出可追溯证据与下一步探测建议；允许 `UNDETERMINED`。

E3-A 不要求解释真实世界为什么存在冲突，也不证明存在隐藏属性。
`x1 == x2 && y1 != y2` 只能推出“现有 x 上的确定性函数无法同时拟合”，
不能单独推出真实世界缺少某个可发现属性。

## 2. B0 边界

- B0 冻结代码：`0ca4561309aeb914df6e3f2c3877a50b080bf731`；只读。
- 不修改 `src/main/kotlin/logic/capability/`、B0 既有测试、冻结报告和考试记录。
- E3 使用独立包 `logic.e3` 与独立实验目录；必要时通过只读适配器调用 B0 Composer/Filter/Interpreter。
- B0 的 `Expr` 只是能力执行表示，不等于完整概念表示。

## 3. A0 交付

- 独立目录 `experiments/representation-evolution-e3/`：SPEC、DECISIONS、WORKSTATE。
- 记录工作树、SHA、JDK/Gradle、B0 回归结果。
- 独立闭卷保持 `not_run`；E3 自测为 `implementer_run`。

## 4. A1 数据契约（已实现）

```text
VisibleSchema(fields: Map<String, List<String>>)
ObservedValue = Known(value) | Unknown
Observation(observationId, objectId, visible, label: Boolean?, provenance, round, batchId)
EvidenceProvenance(sourceId, sourceKind, requestedByStudent, reliableDeterministicLabels: Boolean?)
FeatureKey(canonical)
DiagnosticFinding(kind, evidenceIds, scope, sourceHypotheses, observationConclusion, assumptions, alternatives)
DiagnosticReport(findings, proposedProbe, stoppedBecause, strategySource)
ProbeRequest = RepeatLabel | RequestPublicInstance | NoAction
```

- 字段缺失（key 不在 map）与 `ObservedValue.Unknown` 严格区分。
- `label=null` 表示未观察，不能当 `false`。
- 机器可读错误码：`schema_empty / schema_blank_field / schema_empty_domain /
  blank_observation_id / blank_object_id / blank_source_id / invalid_round /
  missing_field / unknown_field / invalid_value /
  duplicate_observation_id / duplicate_source_round`。
- 规范化特征键：字段排序；已知值前缀 `K:`，未知值 `U`，转义 `\ | =`；`Known("?")` 与 `Unknown` 不得碰撞。
- 重复观测：`(objectId, round, sourceId)` 唯一；不同来源可在同一对象/同一轮独立测量，同一来源重复写只算重复记录。
- JSON 编码稳定；不得包含 `worldKind`、`hiddenFeature`、`targetAst`。

## 5. A2/A3 世界生成器（已实现）

- `logic.e3.evaluator`：`SOLVABLE`、`LATENT_ALIASING`、`NOISY_LABELS`、`SEARCH_LIMITED`、`AMBIGUOUS_EVIDENCE`；固定 seed 可复现。
- `PublicWorld` 只含 schema、公开观察、公开查询池；`HiddenTruth` 含 family/规则/隐藏特征/有限域真值/`heldOut`，仅评测端持有。
- `PublicQueryOracle`/`StudentView`：学生只能查询 queryPool ID；H/D 系列或猜测 ID 返回 `query_not_allowed`，不持有完整真值。
- `LATENT_ALIASING` 用隐藏 `latent` 产生同可见向量、不同标签的公开碰撞。
- 匹配对照：`matchedPair()` 让 SOLVABLE 与 LATENT_ALIASING 共用 schema、值域、来源格式和样本数量，公开 ID 中性。
- A3 证据区别：可靠同源冲突（NOISY）；浅候选全部不拟合且无碰撞（SEARCH_LIMITED）；两个浅假设同时拟合（AMBIGUOUS）。
- 边界：碰撞只证明当前可见属性不能唯一决定标签，不证明学生能发现隐藏属性；生成器存在不等于诊断能力。

## 6. A1 本身明确未做

- 没有诊断算法、碰撞检测、噪声判断或探测选择策略。
- A1 没有世界生成器、oracle、隐藏数据或评分器（A2 才开始）。
- 没有调用 B0 Composer/Filter/Interpreter 的实验逻辑。
- 没有修改 B0。
