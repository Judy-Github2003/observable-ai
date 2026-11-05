# E3-A 决策记录

## A0（2026-11-05）

- E3 代码放在新包 `logic.e3`，实验数据/文档放在 `experiments/representation-evolution-e3/`；不复用 B0 的内部状态。
- B0 冻结代码 `0ca4561309aeb914df6e3f2c3877a50b080bf731` 只读；本阶段不新建能力、不改注册表。
- 独立闭卷 `not_run`；E3 结果只能标 `implementer_run`。

## A1（2026-11-05）

- `ObservedValue` 用 sealed：`Known(value)` / `Unknown`；字段缺失由 Map 缺少 key 表示，与 Unknown 分开校验。
- `label: Boolean?`：`null` 表示未观察，绝不映射为 false。
- `FeatureKey` 使用排序字段 + 转义（`\`、`|`、`=`）；未知值用 `?`，避免与任何字符串值混淆。
- 校验采用返回错误列表而非抛异常，错误码机器可读；schema 无效时仍记录 observation ID/round 等结构错误。
- `DiagnosticFinding` 把 `sourceHypotheses`、`observationConclusion`、`EvidenceScope`（hypothesisSpace/observationDomain/queryDomain）分字段表达；允许同一条 finding 附假设与替代解释。
- `DiagnosticReport` 允许 findings 多标签共存与 `UNDETERMINED`；`strategySource` 固定 `designer_primitive`，具体世界诊断才归 `student_diagnosed`。
- `ProbeRequest` 只允许 `RepeatLabel`、`RequestPublicInstance`、`NoAction`；不包含新传感器/新谓词（E3-B/C）。
- A1 不实现诊断算法；A2 才建 SOLVABLE + LATENT_ALIASING 世界生成器与隐藏真值分层。
