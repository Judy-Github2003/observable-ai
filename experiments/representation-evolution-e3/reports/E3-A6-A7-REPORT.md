# E3-A6 复核修正 + A7 工作报告

```text
阶段: E3-A
范围: A6 行为预算/候选层级/事实集/空域修正 + A7 重复观测与公开探测
状态: complete
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 237/237（B0 173 + E3 64），失败 0
独立闭卷: not_run
```

## 1. A6 修正

### 行为等价类预算

- `behaviorClasses` 重新执行一致候选时使用对应 `BudgetRun.budget.maxSteps`：
  `interpreter.execute(..., EvalBudget(maxSteps = run.budget.maxSteps))`。
- `BudgetRun.behaviorEvalMaxSteps` 显式记录；测试断言等于该 run 的 maxSteps。
- 组合搜索预算（rounds/nodes/candidates）与解释执行预算（maxSteps）分开记录。

### 候选层级

`CandidateLayers` 同时保留：

- `composerGenerated`：Composer 生成的全部候选；
- `consistentWithEvidence`：Filter 评估中 `consistent=true` 的全部候选（不是代表）；
- `behaviorClasses`：一致候选在公开 queryDomain 上的真实输出签名等价类；
- `filterRepresentatives`：Filter 返回的代表数。

测试断言 `consistent >= representatives`，不把单个代表解释为唯一程序。

### 事实集与主状态

`BudgetDiagnosis` 保留原 `verdict`，新增可同时成立的 `BudgetFacts`：

- `budgetSensitive`
- `multipleBehaviorClasses`
- `noConsistentCandidate`
- `searchTruncated`
- `reasonUndetermined`
- `observationCollision`

`NO_CONSISTENT_CANDIDATE` 分支通过独立分类器单元测试可达（构造 `BudgetRun`，不是伪造真实 B0 结果）。

### 空 queryDomain

- 空 queryDomain 时，即使 Filter 返回 IDENTIFIED，也返回 `TRAINING_ONLY_CONSISTENCY`。
- notes：`query_domain_empty_training_only; no independent query-domain identification`。
- scope 明确写 `training observations only; no independent query domain`。
- 反例测试：同样 SOLVABLE 数据，空域不得报 `IDENTIFIED_WITHIN_BOUNDED_SPACE`。

## 2. A6 真实输出

`reports/a6-budget-diagnosis.json`：

| case | verdict | low (rounds=2) | high (rounds=3) |
|---|---|---|---|
| solvable | BUDGET_SENSITIVE_CANDIDATE_SET | REFUSED, 28 cand, 0 consistent | IDENTIFIED, 1246 cand, 1 consistent |
| search_limited | BUDGET_SENSITIVE_CANDIDATE_SET | REFUSED, 10 cand, 0 consistent | AMBIGUOUS, 175 cand, 6 consistent, 2 classes |
| ambiguous | MULTIPLE_CANDIDATES_REMAIN | AMBIGUOUS, 2 consistent, 2 classes | AMBIGUOUS, 20 consistent, 2 classes |
| latent | OBSERVATION_COLLISION | 不搜索 | 不搜索 |

- 高预算新候选不声称真实规则；高预算失败不声称表示不足。
- 空域/低预算 `domainSize=0` 明确标注非完整验证。

## 3. A7 公开探测

文件：`src/main/kotlin/logic/e3/E3ProbeSession.kt`

动作：

- `RepeatLabel(objectId, sourceId)`
- `RequestPublicInstance(sampleId)`
- `NoAction`

策略：

1. 完整 Known 标签冲突 → `RepeatLabel`，理由 `collision_cause_uncertain_repeat_measurement_not_noise_claim`；不宣布噪声。
2. `AMBIGUOUS` 且有 Filter 建议、gateway 允许、未用过的公开查询 → 选第一个 `RequestPublicInstance`。
3. 已辨识/训练域一致、预算截断、无合法查询、无可用动作 → `NoAction`。

安全与记录：

- 查询只通过 `PublicProbeGateway`；测试由受控 `PublicQueryOracle` 包装。策略不持有 HiddenTruth。
- 不读取 WorldFamily/target rule/隐藏标签。
- 记录动作、成本、观察结果、诊断前后 status、来源归因。
- `RepeatLabel` 的 `independentEvidence=false`；同一 query id 重复执行不计新证据，成本 0，notes 明确。
- 单步最小策略，无强化学习。

## 4. A7 真实行动轨迹

`reports/a7-action-traces.json`：

| case | action | before → after | cost | independent |
|---|---|---|---:|---|
| solvable | NoAction | IDENTIFIED → IDENTIFIED | 0 | false |
| latent | RepeatLabel(obj-0a) | COLLISION → COLLISION | 1 | false |
| noisy | RepeatLabel(obj-0) | COLLISION → COLLISION | 1 | false |
| ambiguous | RequestPublicInstance(Q1) | AMBIGUOUS → IDENTIFIED | 1 | true |
| no_legal_query | NoAction | AMBIGUOUS → AMBIGUOUS | 0 | false |

- latent/noisy 的重复测量没有解决冲突，也没有被写成噪声。
- ambiguous 的新公开实例使诊断从歧义变为有界辨识；仍不声称全局正确。

## 5. 测试

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon test
```

- **237/237 通过，0 失败**
- B0 173/173；E3 64/64
- 覆盖：行为预算、候选四层、facts、无一致分支、空域反例、SOLVABLE/LATENT/NOISY/AMBIGUOUS 动作、无合法查询、重复查询、成本/归因。

## 6. 边界与下一步

- 未修改 B0；未实现 A8 综合诊断策略；E3-B/C 未授权。
- 探测动作只改变公开观察，不证明真实原因；重复测量不等于独立证据。
- `independent_closed_book=not_run`；下一步 A8。

## 7. 提交

- 代码/测试：058a34fc0c938fa73334ff4196b28558405d732f

