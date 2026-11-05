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
