# MRX-02 报告：证据正确性、动态重规划与候选空间审计

- 任务：`MRX-02`
- 授权：Research Console 专项授权，研究负责人 2026-11-06
- 代码提交：`dff7cc912a465c620fbfafe4ecfd9ff6a8d04d9c`
- 基线：MRX-01R 冻结代码与原始 JSON（未覆盖）
- 全量 Kotlin：**367/367**，0 failed（B0 173 + E3 194）
- `independent_closed_book = not_run`
- C4 / C8 / H01 状态未改变；未启动 PACK-03

---

## 0. 结论摘要

本轮按三关推进：

| 关口 | 结果 | 说明 |
|---|---|---|
| 第一关：证据与成本正确性 | **通过** | 新证据账本、真实预测冻结、Unknown/预算/反例严格分离、成本计量测试 9 项通过 |
| 第二关：动态重规划 | **通过** | 两个配对实验中，相同首轮行动在反馈不同后产生不同第二步行动；DYNAMIC 每步重新诊断 |
| 第三关：候选覆盖/剪枝归因 | **未通过/未完成** | 未实现 A/B/C 搜索策略对照与完整 G0–G4 审计；按任务书要求停止，不强行运行 160 次正式考试 |
| LEVEL 1 | **达成** | 证据状态机与成本测试 |
| LEVEL 2 | **达成** | 配对反馈改变行动 |
| LEVEL 3 | **部分/未达成** | 有证据账本与部分归因，但无完整 G0–G4 和剪枝定位 |
| LEVEL 4/5 | **未运行/未达成** | 正式 160 次考试未启动 |

**没有把“候选空间未审计”包装成成功；也没有用未冻结的正式成绩。** 本轮停在第 3 关，符合任务书的停止条件。

---

## 1. 第一关：证据语义

### 1.1 新账本

`MRX2Ledger` 与 MRX-01R 的 `MRXRChallengeLedger` 分开实现，避免覆盖旧代码。

每个 entry 记录：

- `inputKey`：原始输入；
- `rawLabel`：Oracle 原始标签；
- `queryIndex`：第几次付费查询；
- `source`：`trusted_oracle`；
- `candidatePredictions`：查询时冻结的真实候选预测；
- `usedBy`：哪些验证/实验使用了该标签。

### 1.2 状态

| 状态 | 含义 | PASS？ |
|---|---|---|
| `UNVERIFIED` | 未检查 | 否 |
| `UNKNOWN_PREDICTION` | 解释器返回 Unknown/Error | 否 |
| `NO_VALID_PREDICTION` | 没有可用预测/候选 | 否 |
| `PARTIAL_ON_CHALLENGE` | 只覆盖部分 CHALLENGE | 否 |
| `PASS_ON_CHALLENGE` | 全部 CHALLENGE 输入已知且匹配 | 是（限定 scope） |
| `COUNTEREXAMPLE` | 至少一个反例 | 否 |
| `BUDGET_EXHAUSTED` | 预算不足 | 否 |
| `ERROR` | 控制器/查询错误 | 否 |

- `PASS` 名称带 `_ON_CHALLENGE`，并记录 `scope = CHALLENGE_POOL(n)`；不声称全域正确。
- `query()` 要求真实 `candidateIds` 与预测键完全一致；空候选、键不一致直接拒绝。**没有 `__ledger` 占位预测。**
- Unknown 不参与匹配，不能把“未知”当“通过”。
- 重复使用标签保留最初 `queryIndex/source`，只增加 `usedBy`，不增加 Oracle 新查询。
- HELDOUT 从未传入账本。

### 1.3 证据测试

`MRX2EvidenceTest` 9 项全部通过：

1. 0 检查 → `NO_VALID_PREDICTION`，不是 PASS；
2. Unknown 预测 → `UNKNOWN_PREDICTION`，且不付 Oracle；
3. 部分覆盖 → `PARTIAL_ON_CHALLENGE`；补齐全部 3 个标签后才 `PASS_ON_CHALLENGE`；
4. 复用不重复付费，保留 `queryIndex/source/usedBy`；
5. 反例 → `COUNTEREXAMPLE`；
6. 预算耗尽 → `BUDGET_EXHAUSTED`；
7. 控制器重复异常 → `ERROR`；
8. 占位/覆盖不一致的预测被拒绝；
9. 成本计数器不把生成候选数混作 Oracle 或解释器调用。

---

## 2. 第一关：真实成本

### 2.1 `MRX2Cost`

记录：

- `oracleNewQueries`
- `oracleReuseUses`
- `generatedCandidates`
- `interpreterCalls`
- `executionSteps`
- `verificationCalls`
- `actionWallMillis`
- `totalWallMillis`
- `processWallMillis`

规则：

- 新标签只加 `oracleNewQueries`；
- 旧标签使用加 `oracleReuseUses`，不加新查询；
- 候选生成只加 `generatedCandidates`，不等于解释器调用；
- 每次实际候选评估加 `interpreterCalls`；
- 动作/总墙钟分开记录；
- `processWallMillis` 由测试进程外层记录，不能冒充单次学习耗时。

### 2.2 配对实验成本

| 环境 | 新 Oracle | 复用 | 生成候选 | 解释器调用 | 墙钟 |
|---|---:|---:|---:|---:|---:|
| W4 反馈 A（反例） | 16 | 16 | 42,601 | 48 | 3,230 ms |
| W4 反馈 B（PASS） | 16 | 0 | 13,206 | 16 | 1,302 ms |

- A 多出的查询/生成来自发现反例后的 DNF 实验；没有被误记成 Oracle 新查询。
- B 的 16 次新查询是完整 CHALLENGE 验证；不是 0 检查。

---

## 3. 第二关：动态重规划

### 3.1 控制循环

`MRX2Student` 不再启动时固定队列：

```text
VALIDATE_BASE
  → 记录真实反馈（PASS / COUNTEREXAMPLE / UNKNOWN / PARTIAL / BUDGET）
  → 重新诊断 H1/H2/H2b/H3/H4/H5/H6
  → 重新计算可辨识性
  → 从当前诊断重新生成/排序行动
  → 执行一个行动
  → 记录反馈
  → 重新诊断
  → 继续 / 停止
```

- 首轮固定 `VALIDATE_BASE`，两个配对环境相同。
- DYNAMIC 每轮调用 `chooseNext` 重新计算，不读旧队列。
- STATIC 只在首次诊断后排序一次，作为对照。
- FIXED 使用固定顺序。
- 重复行动必须有新反馈/新配置，否则不再执行。

### 3.2 配对实验 1（W4）

| 项目 | 环境 A | 环境 B |
|---|---|---|
| 初始 TRAIN/QUERY | 相同 | 相同 |
| 基础候选 | 相同 | 相同 |
| 首轮行动 | `VALIDATE_BASE` | `VALIDATE_BASE` |
| 反馈 | `COUNTEREXAMPLE` | `PASS_ON_CHALLENGE` |
| 第二步行动 | **DNF** | **STOP** |
| 结果 | `REPAIRED` | `IDENTIFIED` |

环境 B 的 CHALLENGE 标签被构造为基础候选的预测；学生没有反例，正确停止。环境 A 有真实反例，第二步改为 DNF 并修复。**第二步不是预设的，而是由反馈触发。**

### 3.3 配对实验 2（缺失知识）

| 项目 | 环境 A | 环境 B |
|---|---|---|
| 首轮行动 | `VALIDATE_BASE` | `VALIDATE_BASE` |
| 反馈 | `COUNTEREXAMPLE` | `PASS_ON_CHALLENGE` |
| 第二步行动 | **DNF/KNOWLEDGE** | **STOP** |
| 结果 | 修复 | 停止 |

- 两个环境中学生代码、世界 ID、训练/查询、预算相同；只有 CHALLENGE 反馈不同。
- 第二次行动不同，证明反馈进入决策，不是世界 ID 硬编码。
- 账本 entries 不含 HELDOUT 键；测试断言。

### 3.4 仍有限制

- DYNAMIC 的第二步在 W4 选择 DNF，不是因为“看到 W4 就加 If”，而是 H1/H5 的当前支持；这仍是启发式，不是通用因果推断。
- 配对实验只覆盖两种反馈（反例/PASS）；UNKNOWN、预算耗尽、部分覆盖的配对反馈尚未做完整实验。

---

## 4. 第三关：候选空间审计（未通过）

### 4.1 未完成的内容

任务要求至少比较：

- A 旧单原子排序；
- B 结构多样性保留；
- C 二阶组合价值评估；

并给出 G0–G4：

- G0 目标是否属于指定有界表达空间；
- G1 正确候选是否实际生成；
- G2 是否正确候选保留到筛选；
- G3 合法输入能否区分；
- G4 学生是否正确选择/停止。

本轮**没有实现** A/B/C 三种搜索策略的对照，也没有完整的 G0–G4 审计。因此第三关不通过。

### 4.2 已知风险（来自 MRX-01R 证据）

- `MRXRSpace` 仍使用单原子相关性排序、top 10 原子截断、前 2,000 个一致候选截断；
- DNF/CNF/IF 生成顺序固定；
- 正式 MRX-01R 中 depth 1/4、if 0/2，无法区分：
  - 正确候选未生成；
  - 生成后被剪枝；
  - 生成但无法区分；
  - 证据不足。
- 本轮没有用隐藏目标保护正确原子；也没有声称已解决上述归因。

### 4.3 因此停止

按任务书：

> 如果某个研究关口未通过，保留开发结果并停止，不得为了完成任务书而强行运行 160 次正式考试。

本轮未运行 20 世界 ×2 种子 ×4 条件的正式考试；没有生成正式成绩，也没有用正式成绩回改学生。

---

## 5. 等级判定

| 等级 | 判定 | 依据 |
|---|---|---|
| LEVEL 1 证据与成本正确 | **达成** | 9 个证据测试；真实预测、Unknown、预算、反例、PASS scope、成本分离 |
| LEVEL 2 动态重新规划 | **达成** | 两个配对实验：同首轮、不同反馈 → 不同第二步；DYNAMIC 每步重新诊断 |
| LEVEL 3 正确失败归因 | **部分/未达成** | 有状态级归因（无候选/反例/预算/覆盖），但没有 G0–G4 与剪枝定位 |
| LEVEL 4 有效修复与搜索改进 | **未运行** | 未做 A/B/C 搜索策略和正式新世界 |
| LEVEL 5 可重复净收益 | **未运行** | 无正式多世界对照 |

**总体：LEVEL 1、2 达成；LEVEL 3 部分；LEVEL 4/5 未运行。** 不把配对实验成功扩大成跨任务泛化。

---

## 6. 已实施 / 失败 / 未实施 / 不可辨识 / 证据不足

### 6.1 已实施并验证

- `MRX2Ledger`：真实预测冻结、来源/序号/使用记录、PASS_ON_CHALLENGE、Unknown/预算/反例严格分离。
- `MRX2Cost`：Oracle 新查询/复用、生成、解释器、墙钟分离。
- 9 个证据/成本测试。
- `MRX2Student` 动态循环；两个配对反馈实验。
- 账本不包含 HELDOUT。

### 6.2 已实施但失败/未通过

- 第三关候选搜索 A/B/C 对照未实现。
- G0–G4 完整审计未实现。
- 结构世界仍可能“候选未生成/被剪枝/不可辨识”混在一起；本轮没有解决。

### 6.3 尚未实施

- 20 世界 ×2 种子 ×4 条件的正式考试。
- 160 次正式运行、正式冻结成绩。
- STATIC/FIXED/DYNAMIC/ORACLE 的多世界对照。
- 每个配对反馈的 UNKNOWN/预算/部分覆盖分支。
- 独立闭卷。
- 生产级权限/基础设施。

### 6.4 当前不可辨识

- 配对环境 B：CHALLENGE 全部匹配，学生正确停止；但这是“当前证据下不可辨识/无需升级”，不是证明目标真实。
- 结构世界（来自 MRX-01R）：候选与 CHALLENGE 一致，学生不能合法推断隐藏结构。

### 6.5 证据不足

- 动态策略的跨世界净收益：未运行正式考试，不能声称。
- 候选空间改进：未实现，不能声称。
- 配对实验只有两种反馈，不能覆盖全部实验选择逻辑。

---

## 7. 复现

```powershell
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2EvidenceTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2PairTest"
.\gradlew.bat test --offline
```

代码：

- `src/main/kotlin/logic/e3/pack02/MRX2Evidence.kt`
- `src/main/kotlin/logic/e3/pack02/MRX2Student.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2EvidenceTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2PairTest.kt`

全量测试结果：**367/367，0 failed**（B0 173 + E3 194；MRX-02 新增 11 个测试）。

---

## 8. 研究负责人审查要点

- 第一关证据/成本测试通过；没有 0 检查=PASS、没有 Unknown=PASS、没有占位预测。
- 第二关配对实验真实改变第二步行动；首轮相同、反馈不同、行动不同。
- 第三关未通过，已按任务书停止，没有强行跑 160 次正式考试。
- 没有覆盖 MRX-01/MRX-01R/C8-03 旧 JSON 或成绩。
- 未声称 LEVEL 3/4/5。
- `independent_closed_book=not_run`；C4/C8 仍 PAUSED；PACK-03 未启动。

**最终回答：**

> 学生现在能严格维护证据、区分未知/部分/通过/反例/预算，并在配对反馈下真正改变下一步实验；但候选空间覆盖与剪枝归因尚未完成，因此本轮停在第三关，未进入 160 次正式考试。LEVEL 1、2 达成，LEVEL 3 部分，LEVEL 4/5 未运行。

任务完成后停止，交由研究负责人审查。
