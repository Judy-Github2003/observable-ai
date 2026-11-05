# E3-P0 修复报告

```text
阶段: E3-A / P0
范围: B0_ACTIVE_QUERY 状态回退、评分执行预算、可信事件顺序
状态: complete；A9 评测基础设施功能冻结
代码/测试提交: f64337d06f33a4256a9ab311df0d36803b27c086
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 275/275（B0 173 + E3 102），失败 0
独立闭卷: not_run
```

## 1. B0_ACTIVE_QUERY 状态回退

**问题**：SOLVABLE 初始已有 `BOUNDED_IDENTIFIED`，但 active-query 分支因没有查询返回 `UNDETERMINED`。

**修复**：

- 初始诊断为 `IDENTIFIED_WITHIN_BOUNDED_SPACE` 时立即保留 `BOUNDED_IDENTIFIED`；
- 不发起查询，`totalProbeCost=0`、`action=null`；
- 保留候选程序、canonical、boundedScope、search record；
- 新增 SOLVABLE 精确回归：B0_ONLY 与 B0_ACTIVE_QUERY 的 claim/candidate/canonical 相同，active 无动作、零成本。

## 2. 独立评分执行预算

**问题**：`A9IndependentScorer` 记录冻结记录 `maxSteps`，但候选执行仍走解释器默认预算。

**修复**：

- `evaluateCandidate` 显式使用 `EvalBudget(maxSteps = record.budget.maxSteps)`；
- 评分结果记录 `evaluationMaxSteps`；
- 低预算反例：同一候选在 `maxSteps=1` 时返回 null（不能借默认预算成功），高预算时可执行。

## 3. 可信事件顺序

**修复**：

- `A9TrustedReplay` 校验 `probe < hidden_scoring`；
- `A9IndependentScorer` 进一步校验 `event.stage < record.frozenAtStage < hidden_scoring`；
- 拒绝阶段倒置、非法 evidenceId、未授权 sampleId、请求可见属性与受控观察点不匹配、重复 observationId；
- 新增反例：probe 与 freeze 同阶段、未授权 sampleId、错误 visible、晚冻结。

顺序：

```text
probe/action -> freeze candidate -> hidden_scoring
```

## 4. 信任边界（必须明确）

当前仍**不是**完整独立闭卷：

- 同一 JVM、同一进程；评测端/学生接口只靠包边界与代码调用约束。
- `A9TrustedActionEvent` 由监考/测试端构造；本轮没有密码学签名或进程间认证。
- 隐藏真值不在学生提交契约中，但开发者/监考者仍可读取。
- “可信”指评测端可重算、可核对事件与冻结记录，不代表不可伪造的硬件/进程隔离。
- 因此所有结果仍为 `implementer_run`，`independent_closed_book=not_run`。

## 5. 冻结与停止

- 不新增评分指标；
- 不实现 E3_ON_DEMAND；
- 不优化 Composer；
- 不实施主动挑战；
- 不运行 A10 批量；
- 不进入 E3-B/C；
- 不修改 B0 冻结源码。

A9 评测基础设施自本提交起功能冻结；下一批 P1 需研究负责人另行授权。
