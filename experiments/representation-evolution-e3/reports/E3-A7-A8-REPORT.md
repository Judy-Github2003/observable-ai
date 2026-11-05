# E3-A7 复核修正 + A8 工作报告

```text
阶段: E3-A
范围: A7 执行门禁/响应校验/重复语义/认识论边界 + A8 诊断整合
状态: complete
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 251/251（B0 173 + E3 78），失败 0
独立闭卷: not_run
```

## 1. A7 修正

### 执行门禁

- `plan()` 签发 `decisionId`（UUID），登记在会话内。
- `execute()` 独立校验：
  - 决策必须来自当前会话且未消费；
  - `maxActions` 独立检查，不只依赖 plan；
  - 查询 ID 必须在 gateway `allowedQueryIds`；
  - 重复对象必须在当前证据中；
  - cost 必须与动作类型一致。
- 伪造、过期、已消费、超预算决策返回 `REJECTED_STALE_OR_FORGED_DECISION` / `REJECTED_ACTION_BUDGET` / `REJECTED_INVALID_ACTION`，不进入证据集。

### 查询结果验证

更新 `currentObservations` 前检查：

- 返回 `sampleId` 与请求一致；
- `visible` 与 gateway 公开点一致；
- 字段集合与 schema 一致、值在公开域；
- 标签存在、objectId 非空；
- RepeatLabel 的 objectId/visible 与原对象一致。

无效响应返回 `REJECTED_INVALID_RESPONSE`，不新增观察。

### 重复测量语义

- `MeasurementKind = REPLAYED_LABEL | FRESH_MEASUREMENT | PUBLIC_INSTANCE`。
- `newObservationRecorded` 与 `independentEvidence` 分开：
  - REPLAYED_LABEL 可新增记录，但 `independentEvidence=false`；
  - FRESH_MEASUREMENT 也不自动宣称独立，只记录为重复来源；
  - 只有合法新公开实例才 `independentEvidence=true`。
- 当前测试主要使用回放模拟；不声称已具备独立重复测量能力。

### 认识论边界

- 已辨识后的 NoAction 理由限定为 `no_action_within_current_bounded_space_and_policy`。
- trace 明确 `no_claim_that_no_valuable_query_exists_in_world`。
- `ProbeDecision.strategySource=designer_primitive`；运行时选择归因 `attribution=student_selected_public_probe`，两者分开。

## 2. A8 诊断整合

文件：`src/main/kotlin/logic/e3/E3DiagnosticIntegrator.kt`

- 输入：真实 A6 `BudgetDiagnosis` + A7 `ProbeTrace`（可选）。
- 输出：`DiagnosticReport`，可同时多个 finding。
- 每个 finding 含 evidenceIds、EvidenceScope、sourceHypotheses、observationConclusion、assumptions、alternativeExplanations。
- 整合信号：
  - A4 观察冲突 → `OBSERVED_LABEL_COLLISION` + `UNDETERMINED`（原因未定）；
  - A6 截断/预算敏感/多候选 → `SEARCH_TRUNCATED` / `BUDGET_SENSITIVE` / `MULTIPLE_HYPOTHESES`；
  - 有界辨识 → `SUPPORTED_WITHIN_BOUNDED_H`，明确仅当前 bounds/query domain；
  - A7 执行 → `PROBE_EXECUTED`；NoAction → `NO_ACTION_TAKEN`，替代解释保留“策略外可能有价值查询”；
  - 证据不足 → `UNDETERMINED`。
- 不使用 WorldFamily/target rule/HiddenTruth；归因 `designer_primitive`。

## 3. 真实输出

`reports/a8-diagnostic-report.json`：

| case | verdict | findings（节选） |
|---|---|---|
| solvable | BUDGET_SENSITIVE_CANDIDATE_SET | SEARCH_TRUNCATED, BUDGET_SENSITIVE, SUPPORTED_WITHIN_BOUNDED_H, NO_ACTION_TAKEN |
| latent | OBSERVATION_COLLISION | OBSERVED_LABEL_COLLISION, UNDETERMINED, PROBE_EXECUTED |
| search_limited | BUDGET_SENSITIVE_CANDIDATE_SET | SEARCH_TRUNCATED, BUDGET_SENSITIVE, MULTIPLE_HYPOTHESES, PROBE_EXECUTED |
| ambiguous | MULTIPLE_CANDIDATES_REMAIN | SEARCH_TRUNCATED, BUDGET_SENSITIVE, MULTIPLE_HYPOTHESES, PROBE_EXECUTED |

- latent 冲突的 alternative explanations 明确包含 noise / hidden attribute / context / data error，没有选一个当结论。
- 有界辨识的 conclusion 含“within current bounds”。
- NoAction finding 保留“策略外可能有价值查询”的替代解释。

## 4. 测试

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon test
```

- **251/251 通过，0 失败**
- B0 173/173；E3 78/78
- 覆盖：执行门禁伪造/过期/超预算、无效网关响应、重复测量语义、NoAction 边界、A8 多 finding/证据追溯/UNDETERMINED/无 family。

## 5. 边界与下一步

- 未修改 B0；未实现 A9 对照基线；E3-B/C 未授权。
- A8 结论均受当前假设空间、预算、公开查询域和策略限制；不证明真实世界规则。
- 下一步 A9。

## 6. 提交

- 代码/测试：24c29af1df966a85893ba675a6786a17daafdd97

## 7. A8 复核修正（2026-11-05）

- ProbeTrace.addedObservation 保存探测新增观察；E3DiagnosticIntegrator 先对原始 observations 跑 beforeBudget，再合并新增证据跑 afterBudget；报告使用 after 状态，同时暴露 ProbeStateChange(beforeStatus, afterStatus, addedEvidenceIds, changed)。
- A8 输出新增 before/after verdict、status、addedEvidenceIds；探测前/后不再混为一个状态。
- A6 增加 CandidateEvidenceState：NOT_RUN / RUN_WITH_ZERO_SURVIVORS / SEARCH_TRUNCATED / RUN_WITH_SURVIVORS。
- facts.noConsistentCandidate 仅在 all runs 为 RUN_WITH_ZERO_SURVIVORS 时为 true；观察冲突（未搜索）不再被误判。
- 反例测试：LATENT 冲突为 NOT_RUN 且 noConsistent=false；SEARCH_LIMITED 低预算为 SEARCH_TRUNCATED；A8 探测后 addedEvidenceIds/状态变化被记录。


