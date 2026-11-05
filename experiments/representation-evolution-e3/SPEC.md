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
- 公开 `worldId` 为 `w-<hash>`，不暴露 seed/index；正式学生实验需将 evaluator 包、seed 和 HiddenTruth 放在独立进程/classpath。
- `PublicQueryOracle`/`StudentView`：学生只能查询 queryPool ID；H/D 系列或猜测 ID 返回 `query_not_allowed`，不持有完整真值。
- `LATENT_ALIASING` 用隐藏 `latent` 产生同可见向量、不同标签的公开碰撞。
- 匹配对照：`matchedPair()` 让 SOLVABLE 与 LATENT_ALIASING 共用 schema、值域、来源格式和样本数量，公开 ID 中性。
- A3 证据区别：可靠同源冲突（NOISY）；浅候选全部不拟合且无碰撞（SEARCH_LIMITED）；两个浅假设同时拟合（AMBIGUOUS）。
- A3 三族公开样本统一为 5 条；后续用 4/5/6 条与不同排列做对照，避免样本数量成为类别线索。
- SEARCH_LIMITED 已记录真实 Composer 低预算对照（depth 2/3）：`reports/a3-search-limited-composer.json`；未运行高预算，不能宣称预算唯一原因。
- 边界：碰撞只证明当前可见属性不能唯一决定标签，不证明学生能发现隐藏属性；生成器存在不等于诊断能力。

## 6. A4 可观察碰撞检测（已实现）

- `ObservableCollisionDetector.detect(schema, observations)` 只按完整 Known 可见向量分组；同组不同布尔标签才是冲突。
- 报告含 featureKey、observationId、objectId、sourceId、round、label、范围；Unknown/未标注分别排除；非法输入返回机器错误。
- `objectId` 用于区分同一对象重复测量与不同对象碰撞；来源/轮次保留。
- 不推断隐藏属性、噪声、随机性或世界原因；不 import evaluator/family/HiddenTruth。
- 无冲突、多来源、不同轮次、Unknown 值均有测试。

### worldId 安全边界

- 当前 `w-<SHA256(seed/index/family) 前 8 字节>` 只是伪匿名，不是安全隔离；低熵 seed 可被枚举比对。
- 正式考试必须把生成器、seed、HiddenTruth 放进独立进程/classpath；学生只接收 `StudentView`。不得依赖该哈希隐藏生成参数。本轮不实现复杂身份系统。

## 7. A5 只读 B0 诊断适配器（已实现）

- `E3B0DiagnosticAdapter.diagnose` 将完整 Known 有标签观察转为 B0 `PublicExample`；Unknown/未标注显式排除，不字符串化。
- 复用 A4 碰撞报告；有冲突则返回 `OBSERVATION_COLLISION` 并跳过 Composer/Filter，不假装搜索能解决矛盾。
- 真实调用 Composer/Filter，记录 `SearchTrace`、`FilterTrace`、有界 `EvidenceScope`；区分 depth_limit、candidate_limit、歧义、冲突、无完整观察。
- `IDENTIFIED` 只表示当前有界假设空间与公开 query domain 内的辨识。
- 不 import evaluator；不读取 family/HiddenTruth/target rule/ruleDepth。
- 输入校验：queryDomain 字段完整性/额外字段/值域/重复点/256 上限；constants 必须来自公开 schema 值域并记录来源。
- `FilterTrace.domainSize` 取真实 `EvidenceResult.domainSize`；`SearchTrace` 记录 maxComposeRounds/maxNodes/maxCandidates/maxSteps 与 observedAstMaxDepth；空域不等于完整验证。

## 8. A6 搜索空间覆盖与预算诊断（已实现）

- 同一公开证据跑低/有限提高预算；记录真实 Composer/Filter 轨迹、候选 hash 集、幸存者 hash 集、行为等价类、预算变化。
- 判定：冲突、无完整观察、预算敏感候选集、多个候选、搜索截断、当前无一致候选、稳定有界辨识、UNDETERMINED。
- 高预算新候选不声称真实规则；高预算失败不声称表示不足；`depth_limit` 不等于规律不在假设空间。
- 不 import evaluator；不读取 family/HiddenTruth/target AST/ruleDepth。
- 真实输出：`reports/a6-budget-diagnosis.json`。
- A6 修正：行为等价类使用对应 run 的 `EvalBudget(maxSteps)`；保留 Composer 生成/公开一致/行为等价类/Filter 代表四层；`BudgetFacts` 记录同时成立的事实；空 queryDomain 返回 `TRAINING_ONLY_CONSISTENCY`，不称独立辨识。

## 9. A7 重复观测与公开探测（已实现）

- `E3ProbeSession` 支持 `RepeatLabel`、`RequestPublicInstance`、`NoAction`。
- 冲突且原因不确定时只选择重复测量，不宣布噪声；多个假设可区分时选 Filter 建议且在 gateway 允许的公开查询；预算截断/无合法动作/已辨识时 NoAction。
- 查询只经 `PublicProbeGateway`（测试由受控 `PublicQueryOracle` 包装）；不读取 HiddenTruth/family/target。
- 记录动作、成本、结果、诊断前后状态、来源归因；重复测量与重复查询同一 ID 不算新独立证据。
- 真实轨迹：`reports/a7-action-traces.json`。

## 10. A1 本身明确未做

- 没有诊断算法、碰撞检测、噪声判断或探测选择策略。
- A1 没有世界生成器、oracle、隐藏数据或评分器（A2 才开始）。
- 没有调用 B0 Composer/Filter/Interpreter 的实验逻辑。
- 没有修改 B0。
