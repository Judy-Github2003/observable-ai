# MRX-02C 报告：正确候选的主动辨识与精确覆盖审计

- 任务：`MRX-02C`
- 授权：Research Console 专项授权，研究负责人 2026-11-06
- 代码提交：`22dba536a94849c51942de4b5651d32870b29c53`
- 基线：MRX-02B `4c314ca`；旧 MRX/C8-03 结果未覆盖
- 正式 160 次考试：**未运行**（第三关未完成）
- `independent_closed_book = not_run`
- C4/C8/H01 状态未改变

---

## 0. 结论摘要

本轮把 MRX-02B 的“候选已保留但最后选错”接到真正的主动查询闭环上：

- 用 RETAINED 候选在合法 QUERY+CHALLENGE 池上计算行为签名；
- 只根据候选预测分歧选择查询；
- 冻结真实候选预测后查询标签，按观察淘汰；
- 复用已有标签，不重复付费；
- 区分 `IDENTIFIED_ON_OBSERVABLE_POOL / CURRENTLY_UNIDENTIFIABLE / BUDGET_EXHAUSTED / UNKNOWN_BLOCKED`；
- 考官端使用**精确有界覆盖**，超限返回 UNKNOWN，不再把贪心失败当不可辨识。

**关键结果：**

| 世界 | 保留候选 | 行为类 | 查询 | 可观察结论 | HELDOUT | G4 |
|---|---:|---:|---:|---|---:|---|
| W4 `if R then d1 else d0` | 335 | 107→1 | 6 | `IDENTIFIED_ON_OBSERVABLE_POOL` | **27/27** | **true** |
| 独立 XOR `(a==1) XOR (d==2)` | 112 | 18→1 | 3 | `IDENTIFIED_ON_OBSERVABLE_POOL` | **12/27** | **false** |

- **W4 的 G4 从 MRX-02B 的 false 变为 true**：主动查询在合法 QUERY+CHALLENGE 上淘汰了错误候选，选中全域正确候选。
- **独立 XOR 暴露可观察域边界**：合法池上 18 个行为类被压到 1 个，表面上“唯一”，但 HELDOUT 仍有反例（12/27）。这说明 `IDENTIFIED_ON_OBSERVABLE_POOL` 不是全域正确；必须保留为失败。
- 精确覆盖测试通过：预算 1 不可行、预算 2 可行、同行为不可辨识、超节点限制返回 UNKNOWN。

**等级：LEVEL 1/2 部分，LEVEL 3 部分，LEVEL 4/5 未达成/未运行。**

---

## 1. 第一关：生命周期与 G0–G4

### 1.1 已有

- `MRX2BTrace` 记录 GENERATED / TRAIN_CONSISTENT / RETAINED / SELECTED；
- `MRX2BAuditor` 使用完整 81 点真值做 G0–G4；
- G0 无 witness 且未穷举返回 `UNDETERMINED`；
- G3 有贪心联合预算检查。

### 1.2 本轮新增

- `MRX2CExactCover`：考官端在合法输入上用位掩码 DFS 精确搜索 ≤budget 的区分集合；
  - `FEASIBLE_WITHIN_BUDGET`
  - `PROVED_IMPOSSIBLE_WITHIN_BUDGET`
  - `OBSERVATIONALLY_INDISTINGUISHABLE`
  - `UNKNOWN_SEARCH_LIMIT`（节点上限，禁止声称不可行）
- 明确合法池 = QUERY+CHALLENGE；HELDOUT 不参与学生查询，也不参与 G3 的合法输入集。

### 1.3 未完成

- 原子丢弃计数、top-K 反事实替换、节点/类型/候选上限的逐项聚合尚未加入 `MRX2BTrace`。
- G0 仍没有可信的完整穷举来源；只能 `WITNESS_FOUND` 或 `UNDETERMINED`。
- G3 精确覆盖只在小型合成测试中验证；未在 20 世界全量运行。

因此第一关**部分完成**。

---

## 2. 第二关：主动辨识闭环

### 2.1 机制

`MRX2CActiveIdentifier`：

1. 输入 RETAINED 候选；
2. 在合法池上计算每个候选的预测签名；Unknown 单独标记 `?`；
3. 若所有候选同一行为类 → `IDENTIFIED_ON_OBSERVABLE_POOL`；
4. 否则选真实/假分组最平衡的未查询输入；
5. 冻结全部候选预测，调用 `MRX2Ledger.query` 获取标签；
6. 按标签淘汰候选；已有标签复用，不重复付费；
7. 无预算 → `BUDGET_EXHAUSTED`；无可区分输入 → `CURRENTLY_UNIDENTIFIABLE`；Unknown 阻塞 → `UNKNOWN_BLOCKED`。

### 2.2 W4 协议分割

- 数据：9 TRAIN / 29 QUERY / 16 CHALLENGE / 27 HELDOUT；合法池=45。
- PAIRWISE 保留 335 个候选，初始 107 个行为类。
- 6 次查询后压到 1 类；选中的候选在 27 点 HELDOUT 上 **27/27**。
- 结论：**MRX-02B 的 G4 失败在 W4 上被主动查询修复**。

### 2.3 独立 XOR

- 目标：`(a==1) XOR (d==2)`，两个独立属性。
- PAIRWISE 保留 112 个候选，初始 18 类。
- 3 次查询后压到 1 类，`IDENTIFIED_ON_OBSERVABLE_POOL`。
- 但该候选 HELDOUT 只有 **12/27**；合法 QUERY+CHALLENGE 上的唯一行为类仍不等于全域目标。
- 这保留了“可观察域可辨识 ≠ 全域正确”的反例，不能宣称成功。

### 2.4 与旧成本最小选择对比

- MRX-02B：PAIRWISE 保留正确候选，但按 estimatedSteps/nodeCount 直接选，W4 G4=false。
- MRX-02C：不再直接选；先查询再选，W4 G4=true。
- 这证明“候选保留”与“候选辨识”是不同阶段；不能把 G1/G2 改善当成学习成功。

---

## 3. 第三关：多轮动态反馈（未完成）

MRX-02C 要求至少两次连续失败后的第三步重新规划，并记录：

- `diagnosisBefore` / `diagnosisAfter` 深拷贝；
- 证据列表；
- action/config；
- 预算差额；
- 选择原因；
- 实际结果。

本轮专注主动辨识，没有新增两次连续失败的真实世界与轨迹。已有 MRX-02 的两个单次配对（COUNTEREXAMPLE→DNF、PASS→STOP）不足以满足第三关。

因此：

- 未冻结正式协议；
- 未运行 20 世界×2 种子×4 条件=160 次；
- 未声称 LEVEL 4/5。

---

## 4. 测试

- `MRX2CActiveTest`：W4 与独立 XOR 的主动辨识（非协议分割，开发对照）。
- `MRX2CProtocolTest`：协议 9/29/16/27 分割；W4 27/27；独立 XOR 12/27 保留为失败。
- `MRX2CExactCoverTest`：预算 1 不可行、预算 2 可行、同行为不可辨识、节点上限 UNKNOWN。

全量 Kotlin：**378/378，0 failed**（B0 173 + E3 205；MRX-02C 新增 6 个测试）。

---

## 5. 等级判定

| 等级 | 判定 | 依据 |
|---|---|---|
| LEVEL 1 生命周期可追踪 | **部分** | 生成/一致/保留/选择已有；原子丢弃与反事实计数未加 |
| LEVEL 2 G0–G4 归因准确 | **部分** | 精确覆盖与合法池实现；未全量 20 世界验证 |
| LEVEL 3 新搜索补足旧缺陷 | **部分** | W4 G4 修复；独立 XOR 暴露可观察域不足，未泛化 |
| LEVEL 4 连续失败后重新规划 | **未达成** | 两次连续失败未实现 |
| LEVEL 5 多世界可重复增益 | **未运行** | 未冻结正式考试 |

**总体：LEVEL 1–3 部分，LEVEL 4/5 未达成/未运行。**

---

## 6. 已实施 / 失败 / 未实施 / 证据不足

### 6.1 已实施并验证

- `MRX2CActiveIdentifier` 主动查询闭环；W4 G4 修复 27/27。
- 精确有界覆盖；超限 UNKNOWN。
- 合法池 QUERY+CHALLENGE，HELDOUT 排除。
- 独立 XOR 的“可观察唯一但 HELDOUT 失败”反例。

### 6.2 已实施但失败/未完成

- 独立 XOR：合法池 18→1 类，但 HELDOUT 12/27；不能宣称正确辨识。
- 原子丢弃/反事实计算未加入生命周期。
- 两次连续失败动态未实现。

### 6.3 尚未实施

- 20 世界×2 种子×4 条件正式考试。
- G0 完整穷举证明。
- 逐项解释器调用/内存峰值统一计量。
- 独立闭卷。

### 6.4 证据不足

- W4 单世界 G4 修复的泛化性。
- 精确覆盖在 20 世界上的规模/节点限制。
- 主动查询策略相对成本最小选择的净收益（只有 W4）。

---

## 7. 复现

```powershell
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2CActiveTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2CProtocolTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2CExactCoverTest"
.\gradlew.bat test --offline
```

代码：

- `src/main/kotlin/logic/e3/pack02/MRX2C.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2CActiveTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2CProtocolTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRX2CExactCoverTest.kt`

JSON：`experiments/representation-evolution-e3/reports/mrx-02c-active.json`

---

## 8. 研究负责人审查要点

- W4 G4 修复是真实查询后的结果，不是按成本直接选。
- 独立 XOR 的 HELDOUT 12/27 保留为失败；没有把 `IDENTIFIED_ON_OBSERVABLE_POOL` 写成全域正确。
- 精确覆盖超限返回 UNKNOWN，没有把搜索失败当不可行。
- 没有启动 160 次正式考试；没有覆盖旧结果。
- 未声称 LEVEL 3/4/5 全部达成；未声称独立闭卷。

**最终回答：**

> 当正确候选已经保留时，学生现在能自主选择合法 QUERY/CHALLENGE 查询并在 W4 上修复 G4（27/27）；但独立 XOR 显示，合法可观察池上的“唯一行为类”仍可能不等于全域目标（12/27）。原子丢失计数和两次连续失败的动态重规划未完成，因此本轮停在正式考试之前。LEVEL 1–3 部分，LEVEL 4/5 未达成/未运行。

任务完成后停止，等待研究负责人复核。
