# E3-A 决策记录

## A0（2026-11-05）

- E3 代码放在新包 `logic.e3`，实验数据/文档放在 `experiments/representation-evolution-e3/`；不复用 B0 的内部状态。
- B0 冻结代码 `0ca4561309aeb914df6e3f2c3877a50b080bf731` 只读；本阶段不新建能力、不改注册表。
- 独立闭卷 `not_run`；E3 结果只能标 `implementer_run`。

## A1（2026-11-05）

- `ObservedValue` 用 sealed：`Known(value)` / `Unknown`；字段缺失由 Map 缺少 key 表示，与 Unknown 分开校验。
- `label: Boolean?`：`null` 表示未观察，绝不映射为 false。
- 校验采用返回错误列表而非抛异常，错误码机器可读；schema 无效时仍记录 observation ID/round 等结构错误。
- `DiagnosticFinding` 把 `sourceHypotheses`、`observationConclusion`、`EvidenceScope`（hypothesisSpace/observationDomain/queryDomain）分字段表达；允许同一条 finding 附假设与替代解释。
- `DiagnosticReport` 允许 findings 多标签共存与 `UNDETERMINED`；`strategySource` 固定 `designer_primitive`，具体世界诊断才归 `student_diagnosed`。
- `ProbeRequest` 只允许 `RepeatLabel`、`RequestPublicInstance`、`NoAction`；不包含新传感器/新谓词（E3-B/C）。
- A1 不实现诊断算法；A2 才建 SOLVABLE + LATENT_ALIASING 世界生成器与隐藏真值分层。

## A1 修正（2026-11-05，审查后）

- **FeatureKey**：已知值前缀 `K:`，未知值 `U`，不再用共同的 `?`；新增问号、反斜杠、竖线、等号、`U`、空串的碰撞测试。理由：`Known("?")` 与 `Unknown` 必须语义可分。
- **重复观测**：唯一键由 `(objectId, round)` 改为 `(objectId, round, sourceId)`，错误码 `duplicate_source_round`。理由：不同来源可在同一轮独立测量同一对象；同一来源同轮重复写则视为重复记录，不得冒充新证据。
- 该唯一键只防“来源自报重复”；来源是否真正独立由评测端另行审查，不能用 sourceId 字符串本身证明独立性。

## A2（2026-11-05）

- 生成器放在 `logic.e3.evaluator`，与学生契约包分离；`PublicWorld` 不携带 `WorldFamily`，公开 `worldId` 也不含家族名，避免通过 ID 泄漏 `worldKind`。
- 两个家族：
  - `SOLVABLE`：三字段值域上的单字段/AND/OR 规则，公开 5 条（至少一真一假）、查询池 2、隐藏 2；规则可被现有 IR 表达。
  - `LATENT_ALIASING`：可见 `kind/shape`，隐藏 `latent`；规则 `kind==k1 XOR latent`，公开含同一可见向量、不同来源、不同标签的碰撞；隐藏 2 个未见可见向量。
- 固定种子用 `java.util.Random(seed*1_000_003 + family.ordinal*97 + index)`；同一 seed 完全复现，不同 seed 至少改变采样/规则。
- oracle 只按 `sampleId` 返回标签；`HiddenTruth.fullDomain` 供评测端全枚举，不进入公开数据。
- 边界：LATENT_ALIASING 只证明**当前可见属性不能唯一决定标签**，不证明学生能发现隐藏属性，也不证明真实世界存在该隐藏属性。

## A2 审查修正（2026-11-05）

- `HiddenTruth.heldOut` 显式保存 held-out 点；测试直接比较 public/query/heldOut 的可见向量，不再用 D 系列 ID。
- 种子变化测试只比较 schema/观察/查询/真值内容，移除 worldId、sampleId 等 seed 派生字段。
- `PublicQueryOracle`/`StudentView` 只允许 queryPool ID；H/D 或猜测 ID 返回 `query_not_allowed`，学生视图不携带完整 `HiddenTruth`。
- `E3AdvancedWorldGenerator.matchedPair()` 提供同 schema、同值域、同来源格式和样本数的 SOLVABLE/LATENT 对照；公开 ID 中性。
- 保留 `E3WorldOracle` 作为评测端完整真值接口；它不进入学生视图。

## A3（2026-11-05）

- `NOISY_LABELS`：同一可靠来源、同一对象、不同轮标签相反；证据层是“同源可靠冲突”，不是隐藏特征推断。
- `SEARCH_LIMITED`：三变量 XOR，`ruleDepth=4`；公开数据无碰撞，单字段及浅层 AND/OR 候选均不拟合。`ruleDepth` 仅评测端持有。
- `AMBIGUOUS_EVIDENCE`：`a==1` 与 `b==1` 同时拟合公开数据；隐藏输入用于后续区分。
- 三个 A3 家族统一使用三字段 schema、来源格式和 5/1/2 公开/查询/隐藏分层；不靠 family 字段区分。
- 仍未实现诊断算法；A4 才做可观察碰撞检测。证据模式存在不等于学生能正确诊断。

## A2/A3 复核修正（2026-11-05）

- 公开 `worldId` 改为 SHA-256 摘要 `w-<16 hex>`，不含 seed/index；正式实验要求 evaluator/seed/HiddenTruth 在独立进程或 classpath。
- `AMBIGUOUS_EVIDENCE` 初版实际只有 4 条公开观察（报告误写 5）；现补第 5 条同标签重复观测，A3 三族统一为 5 条，并加计数测试。
- `SEARCH_LIMITED` 增加真实 Composer 低预算对照（depth 2/3），结果见 `reports/a3-search-limited-composer.json`；未运行高预算，不宣称预算唯一原因。
- 后续对照计划：用 4/5/6 条公开样本和不同排列，检查样本数量/顺序是否成为类别线索。

## A4（2026-11-05）

- 新增 `ObservableCollisionDetector`，只按完整 Known 可见向量分组；同组不同布尔标签报告冲突。
- 报告保留 observationId/sourceId/round/label 和证据范围；Unknown/未标注分别排除，非法输入返回机器错误。
- 明确不做原因归因：不推断隐藏属性、噪声、随机性或世界类别；不读取 evaluator family/HiddenTruth。
- 无冲突、多来源、不同轮次、Unknown、多冲突组和编码字段均有测试。

## A4 修正 / A5（2026-11-05）

- `CollisionParticipant` 增加 `objectId`；同一对象重复测量与不同对象碰撞可由 objectId 区分，来源/轮次仍保留。
- worldId 的 SHA-256 只做伪匿名，不构成安全隔离；低熵 seed 可被枚举。正式考试要求独立评测进程/classpath 与 seed 隔离，不能依赖哈希隐藏参数。
- A5 新增 `E3B0DiagnosticAdapter`：只读调用冻结 Composer/Filter；完整 Known 观察转 B0 输入，Unknown/未标注显式排除；冲突时复用 A4 报告并跳过搜索。
- A5 记录 `SearchTrace`/`FilterTrace`/有界范围；区分 observation collision、no complete observations、depth_limit、candidate_limit、ambiguous、refused。
- `depth_limit` 不解释为“规律不在假设空间”；`IDENTIFIED` 仅限当前假设空间与查询域。
- 不读取 evaluator family/HiddenTruth/target rule/ruleDepth；A6 才做搜索空间覆盖诊断。

## A5 修正 / A6（2026-11-05）

- PublicConstants(values, source)；常量必须在公开 schema 值域，来源非空。
- queryDomain 校验缺失/额外/非法值/重复/超过 B0 256 点上限；非法输入统一 INVALID_INPUT，不抛异常。
- SearchTrace 用 maxComposeRounds 并记录 maxNodes/maxCandidates/maxSteps/observedAstMaxDepth；组合轮数不等于 AST 深度。
- FilterTrace.domainSize 取真实 EvidenceResult.domainSize；空域标注为非完整验证。
- A6 新增 E3SearchBudgetDiagnoser：低/高预算真实搜索对照，记录候选集、幸存者集、行为等价类和预算变化；verdict 覆盖冲突、预算敏感、多个候选、截断、无一致候选、UNDETERMINED。
- 高预算新候选不当作真实规则；高预算失败不当作表示不足；A6 只诊断公开证据与预算。

## A6 修正 / A7（2026-11-05）

- 行为等价类重执行使用对应 BudgetRun 的 EvalBudget.maxSteps，而不是默认预算。
- 保留四层候选统计：Composer 生成、公开一致（全部 evaluation.consistent）、行为等价类（全部一致候选）、Filter 代表；不把代表当全部程序。
- 增加 BudgetFacts：预算敏感、多行为类、无一致候选、搜索截断、原因未定、观察冲突同时记录；主 verdict 保持兼容。
- NO_CONSISTENT_CANDIDATE 分支通过独立分类器单元测试可达（合成 BudgetRun），不伪造真实数据。
- 空 queryDomain → TRAINING_ONLY_CONSISTENCY；只声明训练观察一致性，不称独立查询域辨识。
- A7 新增 E3ProbeSession：冲突选 RepeatLabel（不宣布噪声），歧义选合法公开区分查询，预算/无合法/已辨识选 NoAction。
- 动作记录成本、观察、诊断前后状态、归因；RepeatLabel 与重复查询同一 ID 均不算新独立证据。

## A7 修正 / A8（2026-11-05）

- `execute()` 独立门禁：plan 签发 decisionId；拒绝伪造/过期/已消费/超 maxActions/非法查询/cost 不匹配的决策。
- 网关响应在入证据前校验 sampleId/visible/字段/值域/标签；无效响应拒绝并记录。
- 重复测量区分 REPLAYED_LABEL / FRESH_MEASUREMENT；`newObservationRecorded` 与 `independentEvidence` 分开，回放不称独立证据。
- 已辨识 NoAction 仅限当前有界空间/策略；保留“策略外可能有价值查询”的替代解释；strategySource 与 attribution 分开。
- A8 `E3DiagnosticIntegrator` 整合 A4/A6/A7；多 finding、证据 ID/scope、替代解释、UNDETERMINED；冲突/预算/辨识均不越权归因。

