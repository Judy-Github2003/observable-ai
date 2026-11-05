# E3-A9 公平预算/记录/评分修正工作报告

```text
阶段: E3-A（A9 协议与最小评分；A10 未运行）
状态: complete for this handoff
代码/测试提交: 39580816d1e2c4ebb26536667b028993e6f644cc
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 259/259（B0 173 + E3 86），失败 0
独立闭卷: not_run
```

## 1. 累计计算预算

新增 `E3UsageMeter` / `E3UsageSnapshot`：

- composerCalls
- generatedCandidates
- filterCandidateEvaluations（候选×公开样本）
- interpreterExecutions（行为等价类等）
- publicQueries / queryCost
- elapsedNanos

适配器、A6、A7、A8 都接受可选 meter；A9 每个基线单独计量。现在可以明确显示：**相同单次 maxComposeRounds ≠ 相同累计预算**。

## 2. 指标拆分

- `bounded_claim_validity`：只对有界辨识结论，按声明空间/查询域判定。
- `heldout_prediction_error`：独立指标；有界结论后隐藏预测错误不等于 epistemic overclaim。
- `epistemic_overclaim_rate`：只统计 `claimedGlobal` / 越权归因；不把合法有界结论的隐藏错误算作 overclaim。
- 覆盖：`diagnostic_coverage` 只统计 BOUNDED_IDENTIFIED；`collision_coverage` 单列；重复测量/NoAction 不增加诊断覆盖。
- ALWAYS_UNDETERMINED 的 diagnostic coverage 为 0。

## 3. 结论与动作分离

`A9BaselineResult` 现在分别含：

- `diagnosticClaim`：BOUNDED_IDENTIFIED / OBSERVATION_COLLISION / UNDETERMINED；
- `action`：REQUEST_PUBLIC_INSTANCE / REPEAT_LABEL / NO_ACTION。

`A9ActionRecord` 记录：

```text
actionKind
requestedSampleId
repeatedObjectId
recordedObservationId
measurementKind
independentEvidence
actualCost
actualClaim
```

E3_DIAGNOSTIC 不再用 recordedObservationId 冒充 requestedSampleId；REPEAT 与 REQUEST 类型明确区分。

## 4. 真实输出

- `reports/a9-baseline-smoke.json`：15 行（3 世界 × 5 基线），含累计预算字段与动作记录。
- `reports/a9-scoring-example.json`：示例评分，验证：
  - 有界有效 + 隐藏错误 → `epistemicOverclaim=false`；
  - 全局声称 → overclaim=true；
  - ALWAYS_UNDETERMINED → coverage=false。

## 5. 针对性测试

- 同单次预算、不同调用次数 → 累计 composer/generated/filter 不同。
- 有界有效但隐藏错误 → 不是 overclaim。
- RepeatLabel → diagnostic coverage 不增加，且 actionKind=REPEAT_LABEL、requestedSampleId=null。
- ALWAYS_UNDETERMINED → coverage=0。
- 原始 15 行 smoke 可复现，字段准确。

## 6. 独立研究意见：计算更多但诊断更好，如何定义增量价值

我的立场：**不能只报“诊断更好”，也不能只用单次预算宣称公平；必须报价值-计算曲线。**

建议：

1. **成本匹配比较**：按累计 generatedCandidates / composerCalls 分层；在同一累计成本桶内比较 false-certainty、bounded validity、coverage。
2. **边际价值**：`Δfalse-certainty per +1000 candidates`、`Δvalid coverage per extra composerCall`、达到同等 coverage 所需成本。
3. **Pareto 判定**：若 E3 在同成本下不劣且至少一项显著更好 → 有增量价值；若只有更高成本才更好 → 报告“用更多计算换取的增益”，不能称为免费改进。
4. **预注册阈值**：A9 冻结前规定最小有意义增益（例如 overclaim 降低 ≥X 且 coverage 不降 >Y），避免事后挑选。
5. **反例实验**：固定 B0_ONLY 预算，逐步增加 E3 预算；若在匹配累计 generated 时 E3 不优于 B0，则“综合诊断增量”不成立，优势只是搜索更多。

我不同意“单次 maxComposeRounds 相同即公平”，也不同意把合法有界结论的隐藏错误直接算作认识论越权；这两点已按上述拆分修正。

## 7. 边界

- 未跑 A10 批量；未进入 E3-B/C；未修改 B0。
- 当前 smoke 只验证记录/评分契约，不足以证明 E3_DIAGNOSTIC 有增量价值。
- `independent_closed_book=not_run`。
