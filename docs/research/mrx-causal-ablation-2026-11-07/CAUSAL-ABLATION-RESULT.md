# MRX-CAUSAL-ABLATION-2026-11-07 — 结果

## 0. 身份与权限

- 任务 / Console ID：`MRX-CAUSAL-ABLATION-2026-11-07`
- 起始 HEAD：`58e2ae1`；执行时工作区 HEAD `4252203`
- 结束 SHA：本轮提交（见最终答复）
- 起始 `git status`：仅 4 个既有未跟踪文档；未覆盖
- 授权：研究负责人“后续一律默认授权”；Console 注册后 `AUTHORIZED_REPAIR`，完成后 `CONSUMED`
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`
- 修改：`MRXLive.kt`（消融策略/语义）、`MRX2Student.kt`（LIVE 模式记录）、两个测试、`README.md`、本目录

## 1. 预注册与执行边界

- 冻结点：首次 `VALIDATE_BASE` 后的 snapshot（`LIVE_FROZEN_BELIEF`）
- `LIVE_NO_HYP`：固定中性顺序 `DNF→IF→CNF→KNOWLEDGE→BUDGET`，不读假说/teacher-prior/证据内容
- 合法动作、预算 40、合法池、seed 1449、候选配置：与既有 LIVE 相同
- 有界搜索计划 24×8；**实际只检查 2 个匿名 session**，未完成全搜索
- Student：sessionId 匿名、无 HELDOUT；Teacher：假说模板/动作先验；Examiner：truth 仅用于 oracle 构造

## 2. RED/GREEN

| 测试 | RED 业务失败原因 | GREEN | 证据 |
|---|---|---|---|
| 实时假说/冻结/去信念 | 骨架未更新/未评价；6/7 业务失败（前一轮 RED） | 10/10 契约 | `MRXLiveHypothesisContractTest` |
| 真实轨迹 | 真实 W4/XOR before/after 版本与无未来证据 | 1/1 集成 | `MRXLiveCausalAblationIntegrationTest` |
| 历史反例语义 | PASS 之前会错误反驳多个假说；新实现保留支持事实 | 断言通过 | `historical counterexample survives later pass` |
| 成本一致性 | `newQueries=0` 与 ledger 29 条不一致 | **UNRELIABLE/未修复** | 见 §5 |

RED 日志沿用 `docs/research/mrx-live-2026-11-07/RED.log`（骨架业务失败）；本轮新增语义/消融断言为 GREEN，但不把“契约通过”当科研成功。

## 3. L1 决策因果敏感性

控制：allowed/excluded/budget/teacher-prior/cost 相同；只改信念快照内容。

- Snapshot A：counterexample → H_COUNTER SUPPORTED
- Snapshot B：budget exhausted → H_BUDGET SUPPORTED
- LIVE：A 选 `IF`/DNF 类反例检验，B 选 `BUDGET`（不同）
- FROZEN：两个输入都读同一冻结 snapshot → 决策相同
- NO_HYP：两个输入都选固定 `DNF` → 决策相同、evaluations 为空

结论：**MECHANISM_ONLY_PASS**。这是合成 fixture 的 planner sensitivity，不是真实端到端学习因果。

负对照：

- 仅把 `obs-a` 改成 `obs-b`（语义相同）→ LIVE 动作不变；
- DNF/IF/CNF 预测集/成本相同 → 只允许稳定 tie-break，不能称信息增益；
- 历史 counterexample + 后续 PASS：support ID 保留，`H_COUNTER` 不被标 REFUTED；
- Unknown/预算：分别进入 H_UNKNOWN/H_BUDGET，不当 PASS/False。

## 4. L2 严格配对真实实验

| 约束 | Session A | Session B | 一致？ |
|---|---|---|---|
| 初始 student-visible 数据 | split 1449，9/29/16，匿名 | 同 | 是 |
| 第一失败 | VALIDATE_BASE COUNTEREXAMPLE | VALIDATE_BASE 非 counterexample（H_STRUCTURE 仍 UNKNOWN） | **否** |
| 第二动作前 ledger/候选 | 不同 | 不同 | 否 |
| 第二动作 | IF | IF | 是（但前缀已不同） |
| 第二真实 feedback | PASS_ON_CHALLENGE | COUNTEREXAMPLE | 预期不同，但前缀不匹配 |
| 第三步 LIVE | 停止（成功） | CNF | — |
| FROZEN / NO_HYP 第三步 | 重放 IF/DNF | 重放 CNF/DNF | 仅 replay，不是同轮执行 |

- 有界搜索上限：计划 24×8；实际 2 个匿名 session，未完成。
- 未配对原因：首步真实反馈已不同；A 只有 1 个 action，无法形成两次失败。
- mock/injection：无；trace 来自真实 `MRX2Student`/ledger。
- **结论：`REAL_PAIR_NOT_FOUND / NOT_DEMONSTRATED`**。

补充：FROZEN/NO_HYP 的对照是对真实 LIVE trace 的**决策重放**，不是两个真实执行器运行；不能升级为 L2。

## 5. L3 同轮收益与成本

| run | strategy | status | new paid oracle | reuse | generated | interpreter | wall-ms | teacher calls | UNKNOWN |
|---|---|---|---|---:|---:|---:|---:|---:|---|
| A | LIVE | REPAIRED | 0* | UNKNOWN | UNKNOWN | 90 | 1,891 | 0 | reachable |
| B | LIVE | INSUFFICIENT | 0* | UNKNOWN | UNKNOWN | 141 | 5,839 | 0 | reachable |
| A/B | FROZEN/NO_HYP | 仅重放 | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN | 0 | 未执行真实动作 |

\* `newQueries=0` 与 `MRX2Ledger` 中 29 条/16 条条目明显不一致：`MRX2Student`、`MRXQuerySource`、`MRX2Ledger` 使用多个 `MRX2Cost` 实例；当前 trace 的 `newQueries` **不能作为真实 Oracle 成本**。标记 `UNRELIABLE`，不填 0 作为结论。

- 无同轮 LIVE/FROZEN/NO_HYP/STATIC/FIXED 真实执行，不能计算成功率/成本收益。
- **结论：`INCONCLUSIVE`；无收益证据。**

## 6. 认识论语义审计

- 已修正：`PASS_ON_CHALLENGE` 不再把历史 `H_COUNTEREXAMPLE_PRESSURE`/`H_STRUCTURAL_COVERAGE` 标为 REFUTED；只追加 unknown，历史 support 保留。
- 仍不足：
  - 假说没有 `candidateId/candidateVersion/challengeScope/observationTime` 字段；只有全局 ID + evidence ID；
  - `H_STRUCTURAL_COVERAGE` 的 `NO_CANDIDATE` 仍可能被误读为“全域不可表达”；
  - `PASS_ON_CHALLENGE` 的 scope 仍由调用方字符串表达，没有结构化范围；
  - `diagnosisBefore/After` 仍复制最终诊断，不能作为变化证据。
- 这些风险在报告中保留，不伪称已完全修正。

## 7. 阴性结果 / 不允许的主张

不能成立：

- “学生在线信念导致了真实第三步不同”——没有严格配对；
- “LIVE 比 FROZEN/NO_HYP 有收益”——没有同轮真实执行；
- “PASS 证明全域正确”或“历史反例被推翻”——已明确否定；
- “`newQueries=0` 表示零成本”——计量不一致，UNRELIABLE。

## 8. 研究决策

- **建议暂停 MRX 的实时假说线路**：L1 机制敏感性存在，但 L2 无严格配对、L3 无收益，继续加人工假说/评分规则价值低。
- 下一轮优先转向**候选结构学习/表达空间扩展**（XOR 的 G1/G2 缺失），以及可构造同前缀反馈的世界生成器；不再自动新增 MRX-02F/G 线性任务。
- 对“学生自主假说学习”的证据等级：**MECHANISM_ONLY_PASS（L1）/ L2 NOT_DEMONSTRATED / L3 INCONCLUSIVE**。

## 9. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.MRXLiveHypothesisContractTest,logic.e3.pack02.MRXLiveCausalAblationIntegrationTest"
```

结果：11 tests，0 failed，8.995s；未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次正式考试。
