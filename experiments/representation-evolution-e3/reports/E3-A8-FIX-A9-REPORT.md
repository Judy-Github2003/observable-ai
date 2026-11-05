# E3-A8 三项修正 + A9 协议/最小基线 工作报告

```text
阶段: E3-A
范围: A8 三项修正；A9 公平对照协议与最小可执行基线
状态: A8 fixes complete；A9 protocol + smoke baselines ready；A10 未运行
代码/测试提交: 345173a2b5feb5e177b039898cc9922a72d5757e
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 254/254（B0 173 + E3 81），失败 0
独立闭卷: not_run
```

## 1. A8 三项修正

1. **A6 截断 vs 无一致**
   - 新增/使用 `CandidateEvidenceState`。
   - 两次 `SEARCH_TRUNCATED` 且无幸存者 → `SEARCH_TRUNCATED_UNDETERMINED`，不再落入 `NO_CONSISTENT_CANDIDATE`。
   - `NO_CONSISTENT_CANDIDATE` 只允许全部 run 为 `RUN_WITH_ZERO_SURVIVORS`。
   - 反例测试：两次截断无幸存者；合成零幸存者分支可达。

2. **A8 探测证据核验**
   - `ProbeTrace.executionStatus` 必须为 EXECUTED 才合并。
   - `recordedObservationId == addedObservation.observationId`。
   - 拒绝重复 evidence ID、schema/值域非法、未标记 new 的观察。
   - 非法/重复证据不进入 after 状态；`ProbeStateChange.probeEvidenceAccepted=false`，并产生 `UNDETERMINED(probe_evidence_rejected)`。
   - 反例测试：伪造 recorded ID、重复观察。

3. **A7 决策消费顺序**
   - 先校验 decisionId 存在且内容完全一致，再消费 token。
   - 篡改同一 token 不使合法决策失效；随后原决策仍可执行。
   - 反例测试：tampered copy 被拒绝 → 原 decision 成功。

## 2. A9 协议与最小基线

协议：`A9-PROTOCOL.md`

- 基线：`B0_ONLY`、`COLLISION_ONLY`、`REPEAT_ONLY`、`E3_DIAGNOSTIC`、`ALWAYS_UNDETERMINED`。
- 同一公开证据、查询权限、成本；最多一次探测；隐藏真值只用于评测。
- 主指标 false-certainty；同时报 coverage / valid coverage / probe cost/gain / trace completeness。
- 匹配世界、4/5/6 样本数、排列对照；冻结 seeds/budgets。
- 不读取 WorldFamily/HiddenTruth 作为诊断输入。

最小可执行基线：`E3A9Baselines.kt` + `E3A9BaselineTest` + `reports/a9-baseline-smoke.json`（3 世界 × 5 基线 = 15 行，仅 smoke，不是 A10 批量结果）。

## 3. 研究意见更新

- 同意 ChatGPT 对 P3 的修订：新增通道使样本可分并稳定增益，是“新表征有用”的证据，不是表示不足的必要条件。
- 已把结论拆为 OBSERVED_COLLISION / NO_CONSISTENT_IN_BOUNDED_SPACE / NEW_FEATURE_UTILITY_VERIFIED / REPRESENTATION_INSUFFICIENCY。
- 仍保留分歧：有限观察下“表示不足”很难被证明；更保守的报告应限定为“当前表征/预算/查询域下不可辨识”。A9 必须同时报告 false-certainty 与 coverage，否则会奖励永远弃答或过度声称。

## 4. 测试

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon test
```

- **254/254 通过，0 失败**
- B0 173/173；E3 81/81

## 5. 边界

- 未运行 A10 批量实验；未进入 E3-B/C；未修改 B0。
- A9 当前只有协议与 smoke 基线，不能据此宣称综合诊断有增量价值。
- `independent_closed_book=not_run`。
