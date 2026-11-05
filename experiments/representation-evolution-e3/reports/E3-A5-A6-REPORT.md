# E3-A5 复核修正 + A6 工作报告

```text
阶段: E3-A
范围: A5 输入校验/轨迹修正 + A6 搜索空间覆盖与预算诊断
状态: complete
代码/测试提交: c8bde14bb8b1abe917acfe7d7d1d8b3974dbab98
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 226/226（B0 173 + E3 53），失败 0
独立闭卷: not_run
```

## 1. A5 修正

### 输入校验

- 新增 `PublicConstants(values, source)`；常量必须属于 schema 公开值域，来源不得为空。
- queryDomain 校验：字段缺失、额外字段、值域外、重复点、超过 B0 的 256 点上限。
- 非法输入统一返回 `INVALID_INPUT`，notes 带机器可读错误码（`query_missing_field`、`query_unknown_field`、`query_invalid_value`、`duplicate_query_point`、`query_domain_too_large`、`constant_not_in_public_domain`、`blank_constants_source`）。
- 不再可能因 queryDomain 异常抛出未处理异常。

### 轨迹修正

- `FilterTrace.domainSize` 直接取真实 `EvidenceResult.domainSize`，不再用传入列表长度。
- 空域（`domainSize=0`）附注 `filter_domain_empty_or_unavailable; not a complete domain verification`；>256 直接 INVALID_INPUT。
- `SearchTrace` 增加 `maxNodes/maxCandidates/maxSteps`；`maxDepth` 改名为 `maxComposeRounds`，并记录 `observedAstMaxDepth`，明确组合轮数 ≠ AST 嵌套深度。
- 保留原始 `reason/exhausted/candidateCount` 与过滤状态。

### 测试断言

- 固定种子明确断言：SOLVABLE=`IDENTIFIED_WITHIN_BOUNDED_SPACE`；LATENT=`OBSERVATION_COLLISION`；SEARCH_LIMITED=`AMBIGUOUS_WITHIN_BOUNDED_SPACE`；AMBIGUOUS=`AMBIGUOUS_WITHIN_BOUNDED_SPACE`。
- 覆盖缺失/额外/非法/重复/超过 256 点、非法常量、空来源、`maxCandidates=1` 触顶、真实 domainSize。

## 2. A6 搜索空间覆盖与预算诊断

文件：`src/main/kotlin/logic/e3/E3SearchBudgetDiagnoser.kt`

```text
E3SearchBudgetDiagnoser.diagnose(schema, observations, constants, queryDomain, low, high)
  -> BudgetDiagnosis(verdict, runs, budgetChanges, scope, notes)
```

判定：

- `OBSERVATION_COLLISION`：A4 已发现同可见向量标签冲突；不搜索。
- `INVALID_INPUT` / `NO_COMPLETE_OBSERVATIONS`。
- `BUDGET_SENSITIVE_CANDIDATE_SET`：低/高预算的候选集合变化（含低预算无幸存者、高预算出现候选）。
- `MULTIPLE_CANDIDATES_REMAIN`：多个行为等价类/歧义仍在。
- `SEARCH_TRUNCATED_UNDETERMINED`：搜索被截断且无法确认。
- `NO_CONSISTENT_CANDIDATE`：当前预算/查询域无一致候选，但不等于表示不足。
- `STABLE_IDENTIFIED_BOUNDED`：仅表示当前有界空间/查询域内稳定。
- `UNDETERMINED`：其余无法区分情形。

每个 `BudgetRun` 记录：

- 真实 `DiagnosticSignal`（search/filter/status/notes）；
- Composer 候选 hash 集、Filter 幸存者 hash 集；
- 在公开 queryDomain 上的行为等价类（输出签名分组）。

## 3. 真实输出（seed 101/107/109/103）

`reports/a6-budget-diagnosis.json`：

| case | verdict | low rounds=2 | high rounds=3 |
|---|---|---|---|
| solvable | BUDGET_SENSITIVE_CANDIDATE_SET | REFUSED, gen 37, 28 cand, 0 survivors | IDENTIFIED, gen 1255, 1246 cand, 1 survivor |
| search_limited | BUDGET_SENSITIVE_CANDIDATE_SET | REFUSED, gen 16, 10 cand, 0 survivors | AMBIGUOUS, gen 181, 175 cand, 6 survivors, 2 classes |
| ambiguous | MULTIPLE_CANDIDATES_REMAIN | AMBIGUOUS, 2 survivors, 2 classes | AMBIGUOUS, 20 survivors, 2 classes |
| latent | OBSERVATION_COLLISION | 不搜索 | 不搜索 |

说明：

- 高预算出现新候选只记为预算敏感，不声称找到真实世界规则。
- 高预算仍歧义/失败不声称表示必然不足。
- `search_limited` 的 `depth_limit` 只说明组合轮数截断；公开数据/查询域仍限制结论。
- 低预算 `domainSize=0` 已显式标注为空域/非完整验证。

## 4. 测试

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon test
```

- **226/226 通过，0 失败**
- B0 173/173；E3 53/53
- A6 固定种子断言：solvable/search_limited 为 BUDGET_SENSITIVE；ambiguous 为 MULTIPLE；latent 为 COLLISION。

## 5. 边界与下一步

- 未修改 B0；未读取 WorldFamily/HiddenTruth/target AST/ruleDepth。
- 未实现 A7 重复观测/探测动作，未启动 E3-B/C。
- `independent_closed_book=not_run`；A6 只诊断当前公开证据与预算，不证明真实规律。
- 下一步：A7 重复观测与公开探测动作。
