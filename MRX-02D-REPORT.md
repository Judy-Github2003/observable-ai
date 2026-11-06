# MRX-02D 报告：证据回放、保守辨识与 G1–G5 归因

- 任务：`MRX-02D`
- 授权：Research Console 专项授权，研究负责人 2026-11-06
- 代码提交：`2ba7905a9db0eb68a367900a9cbd4a73a164a1ab`
- 基线：MRX-02C `22dba53`；旧 MRX/C8-03 结果未覆盖
- 正式 160 次考试：`formal_runs=0`（未运行）
- `independent_closed_book = not_run`
- C4/C8/H01 状态未改变

---

## 0. 结论摘要

本轮先修 MRX-02C 的 P0 证据闭环，再做 G1–G5 失败归因：

1. **证据回放**：每轮先用账本已有标签淘汰矛盾候选，再计算行为类；已有标签不重复付费。
2. **不可变预测快照**：选择查询、冻结预测使用同一份 `candidate → Known<Boolean>`；Unknown 保留，不再 `?: false`。
3. **保守状态**：行为类唯一只返回 `SINGLE_BEHAVIOR_CLASS_PROVISIONAL`；单候选且有证据返回 `ONE_SURVIVOR_WITH_EVIDENCE`；不声称 PASS/激活。
4. **错误分离**：控制器异常 `ERROR`，预算不足 `BUDGET_EXHAUSTED`，不再合并。
5. **独立通道**：QUERY 与 CHALLENGE 分账本/预算；HELDOUT 不进入任何学生通道。
6. **G1–G5 归因**：
   - W4：G1/G2 true，G3 separable，G4 true（主动辨识后 27/27），状态仍为 provisional。
   - 独立 XOR：G1/G2 **false**，G3 N/A，G4 false（12/27）。本轮把上一轮的“观察域不足”修正为更准确的归因：**正确候选根本没有生成/保留**，不是查询预算不足。

**未完成**：两次连续失败后的第三步重规划；正式 160 次考试未运行。

**等级：LEVEL 1 部分，LEVEL 2 部分，LEVEL 3 部分，LEVEL 4/5 未达成/未运行。**

---

## 1. P0 证据闭环

### 1.1 回放

`MRX2DActiveIdentifier` 每轮：

1. 遍历 QUERY/CHALLENGE 账本已有 entry；
2. 用候选真实预测与 entry.rawLabel 比较；
3. 相反 → 淘汰；Unknown → 保留并标记阻塞；
4. 统计 `replayEliminated`、`reused`；
5. 回放后才计算剩余行为类。

测试 E01：

- 预置 `true` 标签；
- 候选 A 预测 true、候选 B 预测 false；
- 回放淘汰 B，`newQueries=0`，`replayEliminated=1`；
- 结果 `ONE_SURVIVOR_WITH_EVIDENCE`。

### 1.2 冻结预测

- 每轮为所有候选×合法输入生成一次不可变快照；
- 选查询、构造 predictions、调用 ledger 均读同一快照；
- 选中查询后不再重新执行，不使用 `?: false`；
- Unknown 的候选不会被冻结成 false，而是阻塞该输入。

测试 E03：Unknown 候选 → `UNKNOWN_BLOCKED`，`selected=null`。

### 1.3 保守状态

| 状态 | 含义 |
|---|---|
| `SINGLE_BEHAVIOR_CLASS_PROVISIONAL` | 合法池上行为相同，**不是**全域正确 |
| `ONE_SURVIVOR_WITH_EVIDENCE` | 只剩一个候选，且有观察证据；仍非全域证明 |
| `CURRENTLY_UNIDENTIFIABLE` | 合法池内无可区分输入 |
| `UNKNOWN_BLOCKED` | 预测 Unknown 阻塞 |
| `ALL_REFUTED` | 全部被证据淘汰 |
| `BUDGET_EXHAUSTED` | 渠道预算耗尽 |
| `ERROR` | 控制器/查询异常 |

没有任何状态直接激活能力；激活仍只走 C8 验证器。

### 1.4 权限

- `MRX2DChannel` 分开 QUERY 与 CHALLENGE 的 pool/ledger/budget；
- HELDOUT 不在任何 channel；
- 证据账本保留输入、标签、queryIndex、来源、冻结预测、usedBy。

---

## 2. G1–G5 归因

### 2.1 W4

- 保留候选 335；
- 107 行为类 → 1 类；
- 6 次新查询；
- HELDOUT **27/27**；
- 状态 `SINGLE_BEHAVIOR_CLASS_PROVISIONAL`（谨慎，不声称全域）；
- G0=`WITNESS_FOUND`，G1=true，G2=true，G3=`WRONG_CANDIDATES_SEPARABLE`，G4=true，G5=`DECLARED_INSUFFICIENT`/ cautious provisional。

这证明主动查询能把“正确候选已保留但选错”修复为“查询后选中正确候选”。

### 2.2 独立 XOR `(a==1) XOR (d==2)`

- 保留候选 112；
- 18 行为类 → 1 类；
- 3 次新查询；
- HELDOUT **12/27**；
- G0=`UNDETERMINED`，**G1=false，G2=false**，G3=`NOT_APPLICABLE`，G4=false。

关键修正：

> 上一轮把 XOR 12/27 解释为“合法可观察域不足”。本轮考官归因显示，**正确候选根本没有生成/保留**（G1/G2 false），因此不能把失败归咎于主动查询预算；首先需要修候选空间覆盖。

这符合任务要求的 A/B/C 原因区分：XOR 属于“正确函数可表达但未生成或被剪掉”，不是“已在候选集内但查询不可辨识”。

### 2.3 G5

- 学生没有用 G1–G5 或 HELDOUT 决定行动；
- W4 只声明 provisional，不声称 PASS；
- XOR 只声明 provisional，不激活；
- 状态语义没有把“行为类唯一”升级为全域正确。

---

## 3. 精确覆盖

`MRX2CExactCover` 与穷举属性测试：

- 40 个小型随机实例；
- 当结果 `FEASIBLE_WITHIN_BUDGET` / `PROVED_IMPOSSIBLE_WITHIN_BUDGET` / `OBSERVATIONALLY_INDISTINGUISHABLE` 时，与暴力枚举一致性检查；
- 节点限制返回 `UNKNOWN_SEARCH_LIMIT`，不声称不可行。

---

## 4. 成本

| 世界 | 新 Oracle | 复用 | 解释器调用 | 备注 |
|---|---:|---:|---:|---|
| W4 | 6 | 0 | 75,600 | 含快照/行为类重复预测 |
| XOR | 3 | 0 | 22,680 | 同上 |

- `generatedCandidates` 由 `MRX2BSpace` 单独记录，未混入解释器调用；
- 但 `MRX2BSpace` 的原子/组合评估调用尚未逐项累加到 `MRX2Cost`；本轮成本只覆盖主动辨识部分。这是未完成的计量缺口。

---

## 5. 多轮连续失败（未完成）

任务要求：

- 第一次失败 → 重新规划；
- 第二次失败 → 新证据/搜索失败；
- 第三步由最新快照独立计算；
- STATIC 队列对照；
- 配对世界第二反馈改变第三步；
- UNKNOWN/预算/冲突/无实验时正确停止。

本轮只完成 P0 证据与单轮主动辨识，**没有实现两次连续失败后的真实轨迹**。因此：

- 未冻结正式协议；
- 未运行 20 世界×2 种子×4 条件=160 次；
- 不声称 LEVEL 4/5。

---

## 6. 测试

新增：

- `MRX2DEvidenceReplayTest` 4 项：回放、保守行为类、Unknown、ERROR 非预算。
- `MRX2DG1G5Test`：W4/XOR 的 G1–G5 与观测轨迹。
- `MRX2DExactCoverPropertyTest`：精确覆盖与穷举对照。

全量 Kotlin：**384/384，0 failed**（B0 173 + E3 211；MRX-02D 新增 6 个测试）。

---

## 7. 等级判定

| 等级 | 判定 | 依据 |
|---|---|---|
| LEVEL 1 证据状态正确 | **部分** | 回放/快照/Unknown/ERROR 已测；全链路成本未统一 |
| LEVEL 2 G0–G5 归因准确 | **部分** | W4/XOR 完整；未 20 世界 |
| LEVEL 3 主动辨识 | **部分** | W4 G4 修复；XOR G1/G2=false，候选未生成 |
| LEVEL 4 连续失败后重新规划 | **未达成** | 未实现两次连续失败 |
| LEVEL 5 多世界可重复增益 | **未运行** | formal_runs=0 |

**总体：LEVEL 1–3 部分，LEVEL 4/5 未达成/未运行。**

---

## 8. 已实施 / 失败 / 未实施 / 证据不足

### 8.1 已实施并验证

- 证据回放先于新查询；已有标签不重复付费。
- 同一不可变预测快照；Unknown 不转 false。
- ERROR 与 BUDGET 分离。
- QUERY/CHALLENGE 分渠道。
- W4 主动辨识后 27/27，但状态仍 provisional。
- XOR G1/G2=false 的准确归因。
- 精确覆盖属性测试。

### 8.2 已实施但未通过/失败

- XOR 正确候选未生成/保留（G1/G2 false）；不是主动查询能修复的问题。
- 全链路成本未统一。
- 两次连续失败动态未实现。

### 8.3 尚未实施

- 20 世界×2 种子×4 条件正式考试。
- G0 完整穷举证明。
- 原子丢弃/反事实计数。
- 独立闭卷。

### 8.4 证据不足

- W4 单世界主动辨识的泛化性。
- XOR 候选空间缺失的具体上游机制（top10/节点/候选上限）尚未逐项定位。
- 动态策略收益未测。

---

## 9. 复现

```powershell
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2DEvidenceReplayTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2DG1G5Test"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2DExactCoverPropertyTest"
.\gradlew.bat test --offline
```

代码：

- `src/main/kotlin/logic/e3/pack02/MRX2D.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2DEvidenceReplayTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2DG1G5Test.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2DExactCoverPropertyTest.kt`

证据：

- `experiments/representation-evolution-e3/reports/mrx-02d-g1-g5.json`
- `experiments/representation-evolution-e3/reports/mrx-02d-trace.json`
- `experiments/representation-evolution-e3/reports/mrx-02d-cost.json`

---

## 10. 研究负责人审查要点

- 已付费标签先回放；没有“查询后忽略”。
- 行为类唯一只标 provisional；没有伪造 PASS/激活。
- Unknown 不转 false；ERROR 不标预算。
- XOR 12/27 已从“观察域不足”修正为“正确候选未生成/保留（G1/G2 false）”。
- 两次连续失败未实现，因此未跑 160 次正式考试。
- 旧 MRX-01/01R/02/02B/02C 结果未覆盖。
- 未声称 LEVEL 4/5；未声称独立闭卷。

**最终回答：**

> 学生现在能先用已付费证据淘汰候选、用冻结快照选择查询，并在 W4 上把 G4 修复到 27/27；但它只声明 provisional，不把行为类唯一当全域正确。独立 XOR 的 12/27 已被准确归因为 G1/G2 失败（正确候选未生成/保留），不是主动查询不足。两次连续失败的动态重规划仍未完成，因此本轮停在第 3 关，`formal_runs=0`。LEVEL 1–3 部分，LEVEL 4/5 未达成/未运行。

任务完成后停止，等待研究负责人复核。
