# MRX-02E 报告：两次真实失败后的第三步重规划

- 任务：Observable AI — MRX-02E
- 性质：研究开发阶段（Engineering Harness 实施），不是 160 次正式科研考试
- 起始 HEAD：`88b65741dfd3e1025ae29a28f0020642349513c4`（EH-003）
- 正式考试：`formal_runs = 0`
- `fullRegression = NOT_RUN`
- `researchFormal = NOT_RUN`
- `independent_closed_book = not_run`
- C4/C8/H01 与 Research Console 状态未改变

---

## 0. 验收结论

**ENGINEERING_PASS / LEVEL4_PARTIAL**

已完成：

- 纯策略层 `MRX2EPlanner`：DYNAMIC 对第二次失败反馈敏感，STATIC 使用冻结队列；
- RED：scaffold 在 U01 行为断言失败；
- GREEN：planner 6/6；
- 真实 `MRX2Student` 端到端：一个公开合成开发世界上产生 **两次真实失败**，第三步由 planner 新快照重算（DNF→IF→CNF）；
- 与 STATIC 对照：相同初始预算/合法动作；STATIC 只消费冻结队列。

未达到：

- **配对世界第二反馈不同 → 第三步不同** 未实现；
- 真实双失败配对中 DYNAMIC 与 STATIC 第三步相同（都是 CNF）；
- `diagnosisBefore/After` 仍是复制最终诊断，未改成独立快照；
- 160 次正式考试未运行。

因此不能标记 `LEVEL4_END_TO_END_DEMONSTRATED`，只能 `LEVEL4_PARTIAL`。

---

## 1. Gate 0：侦察

- 起始 HEAD：`88b6574`
- 相关文件：
  - `MRX2Student.kt`（真实学生循环、executor/ledger）
  - `MRX2Evidence.kt`（账本）
  - `MRX2D.kt`（EH-003 修复保留）
  - `MRXRSpace.kt`（候选空间）
- 旧缺陷确认：
  - `chooseNext()` 的 DYNAMIC 只看固定预测表评分，失败后可能重复同一动作；
  - `diagnosisBefore/After` 都填最终诊断；
  - 没有真实双失败快照。

---

## 2. Gate 1：纯策略 RED/GREEN

### 2.1 RED

将 `MRX2EPlannerContractTest` 放入项目，临时使用 scaffold 行为（DYNAMIC 返回 STOP）：

```text
6 tests completed, 1 failed
MRX2EPlannerContractTest > two real-shape failed feedbacks should change third DYNAMIC choice FAILED
```

失败原因：DYNAMIC 对 `NO_CANDIDATE` 与 `COUNTEREXAMPLE` 两种第二次失败都返回 STOP，未产生合法第三动作。属于业务断言失败，不是编译失败。

日志：`docs/engineering/logs/mrx02e-red.log`

### 2.2 GREEN

实现 `MRX2EPlanner`：

- `STATIC`：只消费 `frozenInitialQueue.drop(actionHistory.size)`，不因新反馈重排；
- `DYNAMIC`：
  - `COUNTEREXAMPLE` → 优先 IF（条件结构）
  - `NO_CANDIDATE/ALL_REFUTED` → 优先 CNF（不同组合语法）
  - `UNKNOWN/ERROR` → STOP
  - `BUDGET_EXHAUSTED` → 只选不耗 Oracle 的动作，否则 STOP
  - 同一动作无新证据/候选摘要变化时避免重复
- `evidenceVersionUsed` 来自输入快照，不读世界 ID。

结果：

```text
MRX2EPlannerContractTest 6/6，0 failed
BUILD SUCCESSFUL in 11s
```

日志：`docs/engineering/logs/mrx02e-planner-green.log`

---

## 3. Gate 2：真实学生端到端

### 3.1 世界

自定义公开合成开发世界（不是 HELDOUT，不读取考官真值）：

```text
parity over four attributes: count(a,b,c,d == "1") % 2 == 1
```

- 9 TRAIN / 29 QUERY / 16 CHALLENGE / 27 HELDOUT；
- 学生只使用 TRAIN/QUERY/CHALLENGE 与真实 `MRX2Ledger`；
- 目标在 depth=3 的候选空间中无解，预期连续失败。

### 3.2 真实轨迹

`MRX2Student` DYNAMIC：

| 步骤 | 行动 | 反馈 | 下一步 |
|---:|---|---|---|
| 0 | VALIDATE_BASE | UNVERIFIED | — |
| 1 | DNF | COUNTEREXAMPLE | IF |
| 2 | IF | COUNTEREXAMPLE | CNF |
| 3 | CNF | COUNTEREXAMPLE | DNF/STOP |

- 两轮真实失败：DNF 与 IF 均实际执行并通过 ledger 返回 `COUNTEREXAMPLE`；
- 第三步 `CNF` 由 planner 的 `DYNAMIC` 分支在第二次失败后重新计算；
- 没有测试代码注入反馈；`executeSpace()` + planner 真实调用；
- 最终 `INSUFFICIENT_EVIDENCE`，没有假 PASS/激活。

STATIC 对照：

| 步骤 | 行动 | 反馈 | 下一步 |
|---:|---|---|---|
| 0 | VALIDATE_BASE | UNVERIFIED | — |
| 1 | DNF | COUNTEREXAMPLE | IF |
| 2 | IF | COUNTEREXAMPLE | CNF |
| 3 | CNF | COUNTEREXAMPLE | STOP |

- STATIC 队列在首败前冻结；第三步与 DYNAMIC 相同（均为 CNF）。
- 因此本轮没有证明“第二反馈不同 → 第三步不同”；只证明“两次真实失败后第三步由新快照计算，且不再重复 DNF”。

测试结果：

```text
MRX2EIntegrationTest 1/1，0 failed
MRX2EPlannerContractTest 6/6，0 failed
合计 7/7
BUILD SUCCESSFUL in 1m 19s（含编译；测试总 45.65s）
```

JUnit 汇总：`docs/engineering/logs/mrx02e-junit-summary.json`  
日志：`docs/engineering/logs/mrx02e-integration-green.log`

---

## 4. 改动

- `src/main/kotlin/logic/e3/pack02/MRX2E.kt`：planner DTO / 决策；
- `src/main/kotlin/logic/e3/pack02/MRX2Student.kt`：
  - DYNAMIC 在每次真实行动后调用 `planDynamicNext()`；
  - 不再只按固定评分表 `chooseNext()`；
  - STATIC 路径保持冻结队列；
- `src/test/kotlin/logic/e3/pack02/MRX2EPlannerContractTest.kt`；
- `src/test/kotlin/logic/e3/pack02/MRX2EIntegrationTest.kt`；
- `docs/engineering/logs/mrx02e-*.log/json`。

未改：

- MRX2Ledger、StudyQueryController、MRX2D（EH-003 修复保留）；
- 旧学生算法/冻结协议/正式 JSON/Research Console；
- 没有运行全量 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

---

## 5. 未完成与限制

1. **配对第二反馈不同**未实现：真实双失败轨迹只使用一个世界，第二次失败与第一次同属 COUNTEREXAMPLE；DYNAMIC/STATIC 第三步相同（CNF）。
2. `diagnosisBefore` / `diagnosisAfter` 仍都是最终诊断；没有独立时间点快照。
3. 真实 E2E 是自定义合成开发世界，不是现有公开世界；只用于机制验证，不构成泛化。
4. 端到端约 56.7s/1m19（含候选生成），比契约测试慢；未加入默认 `quickTest`，只在显式 `-PquickTests` 中运行。
5. 没有正式 160 次考试；`formal_runs=0`。
6. 没有全量回归；`fullRegression=NOT_RUN`。

---

## 6. 验收状态

- **ENGINEERING_PASS**：planner 契约、RED/GREEN、真实双失败轨迹、STATIC 对照、日志/JSON 完整。
- **LEVEL4_PARTIAL**：有真实两次失败后第三步重算，但缺少“第二反馈不同→第三步不同”的配对证据，且诊断快照不独立。
- 不标记 `LEVEL4_END_TO_END_DEMONSTRATED`。

---

## 7. Git

- 起始：`88b6574`
- 最终提交：见本轮提交（`git log`）
- 正式考试：`formal_runs = 0`
- `independent_closed_book = not_run`
- `fullRegression = NOT_RUN`
