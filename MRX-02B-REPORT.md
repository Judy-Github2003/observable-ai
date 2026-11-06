# MRX-02B 报告：候选生命周期、A/B/C 搜索对照与 G0–G4 归因

- 任务：`MRX-02B`
- 授权：Research Console 专项授权，研究负责人 2026-11-06
- 代码提交：`4c314ca992fb037fd3a92a7acab065fcabf2b7fc`
- 基线：MRX-02 停在第 3 关；MRX-01/01R、C8-03 历史结果未覆盖
- 正式 160 次考试：**未运行**（第三阶段未完成）
- `independent_closed_book = not_run`
- C4/C8/H01 状态未改变；未启动 PACK-03

---

## 0. 结论摘要

本轮把 MRX-02 未完成的第三关推进到**可审计的候选生命周期 + A/B/C 开发对照**，但没有完成“两次连续失败后重新规划”的动态测试，因此按任务书停在正式考试之前。

| 阶段 | 结果 | 说明 |
|---|---|---|
| 候选生命周期审计 | **已完成** | `MRX2BTrace` 记录 GENERATED / TRAIN_CONSISTENT / RETAINED / SELECTED，并记录剪枝原因 |
| G0–G4 考官归因 | **已实现/开发验证** | 完整 81 点真值只在 `auditAfterExam` 使用；G0 未穷举时返回 `UNDETERMINED` |
| A/B/C 搜索对照 | **部分成功** | PAIRWISE 在 W4 上保留旧策略没有生成的正确候选（G1/G2 从 false 变 true），但最终选择仍错（G4 false） |
| 多轮动态（两次连续失败） | **未完成** | 只有 MRX-02 的单次反馈配对；MRX-02B 要求的两次连续反馈未实现 |
| 正式 20×2×4=160 | **未运行** | 第三阶段未完成，按任务书停止 |

**等级判定：LEVEL 1 达成；LEVEL 2 部分；LEVEL 3 部分；LEVEL 4/5 未达成/未运行。**

---

## 1. 候选生命周期

### 1.1 跟踪结构

`MRX2BTrace` 记录：

- `GENERATED`：通过类型检查、节点限制、canonical 去重后进入候选池；
- `TRAIN_CONSISTENT`：在 TRAIN 上全部匹配；
- `RETAINED`：通过 2,000 个一致候选上限后保留；
- `SELECTED`：最终选中的候选；
- `prunes`：每个被淘汰候选的 stage 与原因（`train_mismatch`、`consistent_cap_2000` 等）。

`MRX2BSpace.generate()` 的顺序：

1. 原子产生（属性 Eq + 已激活能力调用）；
2. 原子排序与 top 10 截断；
3. 字面量/合取项/析取候选生成；
4. 类型检查；
5. 训练一致性筛选；
6. 一致候选上限截断；
7. 选择；
8. 交给审计。

### 1.2 生命周期测试

`MRX2BLifecycleTest`：

- 将 `consistentLimit` 设为 1，强制触发截断；
- 结果：`GENERATED=8000`、`TRAIN_CONSISTENT=2`、`RETAINED=1`；
- `prunes=7999`，原因至少包含 `train_mismatch` 与 `consistent_cap_1`；
- 审计在没有任何正确 witness 时返回 `G0=UNDETERMINED`，不是 `NOT_REPRESENTABLE_IN_BOUND`。

这满足 LEVEL 1：候选产生、保留、剪枝、选择可追踪，且不把“没找到”说成“不可表达”。

---

## 2. G0–G4 归因

`MRX2BAuditor.auditAfterExam()` 只在考试结束后运行：

- **G0**：`WITNESS_FOUND` / `NOT_REPRESENTABLE_IN_BOUND`（仅当 `grammarExhaustive=true`）/ `UNDETERMINED`；
- **G1**：正确候选是否实际生成；
- **G2**：正确候选是否保留到筛选阶段；
- **G3**：
  - `WRONG_CANDIDATES_SEPARABLE`：每个错误保留候选都有合法输入与 witness 不同；
  - `SOME_CANDIDATES_INDISTINGUISHABLE`：存在无法区分的错误候选；
  - `INSUFFICIENT_QUERY_BUDGET`：可区分但有限查询预算不足；
  - `NOT_APPLICABLE`：没有 witness；
- **G4**：最终选择是否在完整 81 点上等于 truth；
- `correctPrunes`：正确候选在哪个阶段被剪掉。

G3 还做了贪心联合查询预算检查（选择能淘汰最多错误候选的合法输入），不只看逐候选反例存在性。

学生侧没有 `truth` 或完整域；`MRX2BSpace` 只接收 TRAIN 标签。

---

## 3. A/B/C 搜索策略

### 3.1 策略

- **A BASELINE**：单原子训练准确率排序，top 10。
- **B DIVERSITY**：按 family（`eq:attr`、`call:id`）分组，组间轮转，避免相似原子占满 top 10。
- **C PAIRWISE**：在 TRAIN 上对原子两两做 AND/OR/XOR/XNOR 预评估，按“单原子准确率 + 最大组合增益”排序，允许单独弱、组合强的原子进入 top 10。

所有策略共用：

- 同一类型检查器、解释器、`maxCandidates=30,000`、`consistentLimit=2,000`；
- 同一 TRAIN 集；
- 同一 DNF/IF 生成逻辑；
- 同一选择规则（estimatedSteps/nodeCount）。

PAIRWISE 只用 TRAIN 标签，不使用 HELDOUT/目标函数。

### 3.2 开发结果

`mrx-02b-search.json` 原始记录：

| 世界 | 策略 | generated | consistent | retained | pair eval | G0 | G1 | G2 | G3 | G4 |
|---|---|---:|---:|---:|---:|---|---|---|---|---|
| xor-d1-d2 | BASELINE | 28,523 | 244 | 244 | 0 | WITNESS | true | true | SEPARABLE | true |
| xor-d1-d2 | DIVERSITY | 28,523 | 139 | 139 | 0 | WITNESS | true | true | SEPARABLE | true |
| xor-d1-d2 | PAIRWISE | 28,523 | 244 | 244 | 420 | WITNESS | true | true | SEPARABLE | true |
| w4-if | BASELINE | 28,523 | 5 | 5 | 0 | UNDETERMINED | false | false | N/A | false |
| w4-if | DIVERSITY | 28,523 | 5 | 5 | 0 | UNDETERMINED | false | false | N/A | false |
| w4-if | PAIRWISE | 28,523 | 10 | 10 | 420 | WITNESS | **true** | **true** | SEPARABLE | false |

关键发现：

- **PAIRWISE 在 W4 上保留了正确候选**，而 BASELINE/DIVERSITY 没有生成正确候选（G1/G2 false → true）。这直接证明“旧 top10 单原子排序会漏掉组合正确结构，二阶组合价值可以补足”。
- 但 PAIRWISE 的 **G4 仍是 false**：正确候选虽保留，最终按成本选中的是错误候选。下一步需要把 G1/G2 的覆盖优势接到选择/验证阶段，而不是只看候选数量。
- XOR 世界上三种策略都能找到正确候选；PAIRWISE 产生 420 个 pair 评估，但没有带来更好结果。**单个成功世界不代表泛化。**

### 3.3 剪枝归因

- `MRX2BLifecycleTest` 显示 2,000 上限会明确记录 `consistent_cap_1`（测试用 1 代替 2,000）；
- W4 的 BASELINE/DIVERSITY `G1=false`，说明失败发生在**正确候选未生成**，不是被 2,000 上限剪掉；
- PAIRWISE `G1/G2=true`、`G4=false`，说明失败转移到**选择/验证**，不是候选空间覆盖。

这符合任务要求：区分“未生成”“被剪枝”“生成但选错”“证据不足”，而不是只报最终准确率。

---

## 4. 多轮动态测试（未完成）

MRX-02 已有两个配对实验：

- `VALIDATE_BASE` → `COUNTEREXAMPLE` → DNF；
- `VALIDATE_BASE` → `PASS_ON_CHALLENGE` → STOP。

但 MRX-02B 要求：

- 第一次实验失败后重新规划；
- **第二次实验再次失败后重新规划**；
- Unknown、预算耗尽、恢复知识成功、扩展结构成功、无合法区分实验而停止。

本轮只完成了候选搜索主线，没有实现“两次连续失败”的实际世界与轨迹。因此：

- 没有声称已经完成 MRX-02B 的动态多轮要求；
- 没有为动态测试运行正式 160 次；
- `diagnosisBefore/After` 的真实快照差异也仍未在 MRX-02B 中补齐。

这是本轮的主要未完成项。

---

## 5. 成本与计量

- `MRX2BSpace` 记录 `generated/consistent/retained`，PAIRWISE 额外记录 420 个 pair 评估；
- 每个策略的墙钟：由测试输出与 JSON 的 `generated/retained` 对照；未把 pair 评估数当作解释器调用数；
- 候选生成数（28,523）不等于实际解释器调用；PAIRWISE 的 420 是**训练集上的组合评分次数**，不是 Oracle 查询；
- Oracle 查询成本仍由 MRX-02 的 `MRX2Cost` 分开记录；MRX-02B 没有新增 Oracle 查询路径。

局限：MRX-02B 的搜索对照没有把“解释器调用次数”逐候选累加，`MRX2BSpace` 目前只记录候选/配对计数；完整成本审计仍需在正式考试前补齐。

---

## 6. 等级判定

| 等级 | 判定 | 依据 |
|---|---|---|
| LEVEL 1 候选生命周期可追踪 | **达成** | GENERATED/TRAIN_CONSISTENT/RETAINED/SELECTED + prune 原因；生命周期测试 |
| LEVEL 2 G0–G4 归因准确 | **部分** | 审计实现并在两个世界验证；G3 有联合预算检查；尚未在 20 世界/多种子验证 |
| LEVEL 3 新搜索补足旧缺陷 | **部分** | W4：PAIRWISE G1/G2 从 false 变 true；但 G4 仍 false，未转化为最终学习成功 |
| LEVEL 4 连续失败后重新规划 | **未达成** | 没有两次连续失败的实际轨迹 |
| LEVEL 5 多世界可重复增益 | **未运行** | 未冻结正式考试 |

**总体：LEVEL 1 达成；LEVEL 2–3 部分；LEVEL 4–5 未达成/未运行。**

---

## 7. 已实施 / 失败 / 未实施 / 证据不足

### 7.1 已实施并验证

- `MRX2BTrace` 生命周期与剪枝原因。
- `MRX2BAuditor` G0–G4；完整真值只在考官端。
- A/B/C 三种原子策略；PAIRWISE 组合评分。
- 两个开发世界的原始 JSON。
- 生命周期测试：2,000 上限剪枝可追踪；无 witness 时 G0=UNDETERMINED。

### 7.2 已实施但失败/部分

- PAIRWISE 在 W4 上保留正确候选（G1/G2 true），但最终选择仍错（G4 false）。
- XOR 世界上 A/B/C 都成功，PAIRWISE 没有优势。
- DIVERSITY 在 XOR 上 consistent 更少（139 vs 244），没有证明优势。

### 7.3 尚未实施

- 两次连续失败后的动态重规划（MRX-02B 核心缺口）。
- 20 世界×2 种子×4 条件=160 次正式考试。
- G0 完整穷举证明（当前没有 `grammarExhaustive=true` 的可信来源）。
- 全候选解释器调用逐项计量。
- 独立闭卷。

### 7.4 证据不足

- PAIRWISE 的跨世界泛化：只有一个 W4 世界显示 G1/G2 改善，且 G4 仍失败。
- G0–G4 在 20 世界上的准确性：未运行。
- 动态策略收益：未完成两次连续失败测试。

---

## 8. 复现

```powershell
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2BLifecycleTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2BSearchTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2BDevelopmentTest"
.\gradlew.bat test --offline
```

代码：

- `src/main/kotlin/logic/e3/pack02/MRX2BSpace.kt`
- `src/main/kotlin/logic/e3/pack02/MRX2BAudit.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2BLifecycleTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2BSearchTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2BDevelopmentTest.kt`

原始 JSON：`experiments/representation-evolution-e3/reports/mrx-02b-search.json`

全量测试：**372/372，0 failed**（B0 173 + E3 199；MRX-02B 新增 5 个测试）。

---

## 9. 研究负责人审查要点

- 没有把“候选未生成”和“被 2,000 上限剪掉”混为一谈；生命周期/prune 原因可追踪。
- G0 未穷举时只返回 `UNDETERMINED`，没有声称不可表达。
- PAIRWISE 的改善是 G1/G2 覆盖，不是最终学习成功；G4 false 如实保留。
- 两次连续失败动态测试未完成，因此没有启动 160 次正式考试。
- 没有覆盖 MRX-01/01R/02、C8-03 旧结果。
- 未声称 LEVEL 3/4/5 全部达成；未声称独立闭卷。
- C4/C8 仍 `PAUSED`；PACK-03 未启动。

**最终回答：**

> 学生现在能追踪候选从生成、训练一致、保留到选择的完整生命周期，并区分“未生成/被剪枝/生成但选错/证据不足”；PAIRWISE 在 W4 上补足了 BASELINE/DIVERSITY 漏掉的正确候选（G1/G2 改善），但最终选择仍错（G4 false），且两次连续失败的动态重规划未完成。因此本轮停在第 3 关，未运行 160 次正式考试。LEVEL 1 达成，LEVEL 2–3 部分，LEVEL 4–5 未达成/未运行。

任务完成后停止，等待研究负责人复核。
