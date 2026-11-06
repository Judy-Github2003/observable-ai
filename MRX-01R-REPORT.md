# MRX-01R 报告：证据账本、可辨识性与行动选择

- 任务：`MRX-01R`
- 研究负责人确认：任务属于本会话；另一会话误发/误撤销，已保留撤销审计并重新激活
- MRX-01 代码基线：`1f4f7f1`（MRX-01 完成状态）
- MRX-01R 代码/协议冻结提交：`5c8568a1f9155f4651c2788c0c9ee3ffde411b71`（MRXR 独立空间）
- MRX-01 冻结空间逐字节恢复：`1f3fe8860fbdb3f6215441b7dde01ae9d6dee547`
- 冻结清单提交：`5c4bc4f`
- 最终全量测试：见文末（运行中/完成后填入实际值）
- `independent_closed_book = not_run`
- C4 / C8 保持 `PAUSED`；未启动 PACK-03

---

## 0. 结论摘要

本轮把 MRX-01 的“固定顺序尝试升级”改成了**证据驱动的行动选择**：

1. 新增 `MRXRChallengeLedger`：
   - 每输入 CHALLENGE 标签只付费一次；
   - 再次使用读账本，不重复查询；
   - 区分 `UNVERIFIED / PARTIAL / PASS / COUNTEREXAMPLE / BUDGET_EXHAUSTED / ERROR`；
   - 0 次检查永远不是 PASS；
   - 重复查询异常不再被解释为“没有发现错误”；
   - HELDOUT 不进入账本。
2. 新增 H1/H2/H2b/H3/H4/H5/H6 的显式竞争假说，每个带支持/反对/未知/区分实验/成本/更新规则。
3. 新增可辨识性分析：如果 top 假说在全部可用行动上预测相同，标记为当前不可辨识，不继续浪费查询。
4. 新增 FIXED / EVIDENCE / VALUE 三种策略；行动由假说分数、区分能力、成本、历史结果共同决定。
5. 每个实验后按结果更新假说分数并重新规划；允许 `NO_ACTION`。
6. 正式 20 世界 × 6 条件 = 120 次运行：
   - C8-03：5/20 激活
   - MRX-01：10/20
   - FIXED：10/20
   - EVIDENCE：10/20
   - VALUE：10/20
   - ORACLE-OPERATOR：10/20
   - 错误激活：**0**
7. **EVIDENCE/VALUE 没有取得比 FIXED 更高的成功率；查询成本反而更高**。这是本轮最重要的阴性结果。

**等级：**

| 等级 | 判定 | 依据 |
|---|---|---|
| LEVEL 1 证据管理正确 | **达成** | 账本测试覆盖 0 检查、复用、部分、反例、重复异常、预算、HELDOUT 排除 |
| LEVEL 2 证据影响实验选择 | **部分/达成但无收益** | EVIDENCE 在缺失知识世界选择 DNF 而非 FIXED 的 KNOWLEDGE；但成功率不变、成本更高 |
| LEVEL 3 结果更新诊断并改变后续行动 | **部分** | 代码有分数更新与重新规划；正式结果没有显示“更新后获得更好行动序列”的稳定证据 |
| LEVEL 4 自主修复新结构 | **未达成** | 结构世界 depth 1/4、if 0/2，与 MRX-01 相同 |
| LEVEL 5 可重复策略优势 | **未达成** | EVIDENCE/VALUE 查询 353 次 vs FIXED 309 次，成功率相同 |

---

## 1. 授权与审计

### 1.1 误发/撤销/重新激活

MRX-01R 的首次登记来自一次跨会话误发；另一个会话在确认“不是本项目”后把授权置为 `REVOKED`。研究负责人随后明确：任务属于本会话。处理方式：

- 保留误登记、父任务修正、误撤销的审计事件；
- 追加 `repair_reactivated` 事件；
- 重新设置 `authorization_status=ACTIVE`、正确的父任务和内容哈希；
- 之后才进行 preflight 和实施。

审计事件：

- `ev-1793964140570-9642b3` 首次登记
- `ev-1793964151565-9790d9` 父任务修正
- `ev-1793964284170-d6bd77` 误撤销
- `ev-1793964367386-8807d7` 研究负责人确认后重新激活

preflight 在重新激活后返回 `AUTHORIZED_REPAIR: MRX-01R`。

### 1.2 历史保护

- MRX-01 的 `MRXSpace.kt` 已按冻结提交逐字节恢复，SHA-256 为
  `b1a24f4a2d9e35c99b87fdfff56d9ef655f9662985af95c65d4ac34152c86e8a`。
- MRX-01R 使用独立的 `MRXRSpace.kt` 副本，不覆盖 MRX-01 的候选空间代码。
- MRX-01 基线/开发/正式 JSON、C8-03 JSON 保持原样；全量回归后恢复被测试复跑改写的旧文件。
- `independent_closed_book` 仍为 `not_run`。

---

## 2. 证据账本（LEVEL 1 重点）

### 2.1 状态机

| 状态 | 含义 | 是否能算通过 |
|---|---|---|
| `UNVERIFIED` | 0 次有效检查 | 否 |
| `PARTIAL` | 有检查，但未覆盖全部 CHALLENGE 输入 | 否 |
| `PASS` | 全部 CHALLENGE 输入检查且无反例 | 是 |
| `COUNTEREXAMPLE` | 至少一个标签与候选预测冲突 | 否 |
| `BUDGET_EXHAUSTED` | 预算耗尽，未完成检查 | 否 |
| `ERROR` | 重复查询/控制器错误 | 否 |

### 2.2 行为

- 每个 `(input → label)` 只写入账本一次；后续验证/不同动作读取同一 entry，不支付 Oracle 成本。
- `validate(candidate, maxNew)` 先复用已有标签，再按需查询新输入；`PASS` 要求覆盖整个 CHALLENGE 池。
- `filter(survivors)` 用已有标签淘汰候选；只剩一个候选也不自动 PASS，必须补齐剩余标签。
- 若控制器抛出 `duplicate_query`，账本记 `ERROR`，绝不当作“无反例”。
- HELDOUT 从未传入账本；账本构造参数只有 CHALLENGE 池。

### 2.3 测试

`MRXREvidenceTest` 7 个测试全部通过：

1. 0 检查 = `UNVERIFIED`，不是 PASS；
2. 同一输入第二次读取不增加 `queriesSpent`；
3. `PARTIAL` 不是 PASS；补齐 3 个标签后才是 PASS；
4. 预测与标签冲突 = `COUNTEREXAMPLE`；
5. 控制器已使用输入导致 `duplicate_query` = `ERROR`，不是 PASS；
6. 预算耗尽 = `BUDGET_EXHAUSTED`；
7. `filter` 复用已有标签，且 HELDOUT 不出现在账本 entries。

这直接修复了 MRX-01 中“验证 0 次/异常吞掉/0 检查等于 PASS”的问题。

---

## 3. 竞争假说与可辨识性

### 3.1 假说结构

`MRXRHypothesis` 显式记录：

- `claim`：假说内容；
- `score`：当前支持程度；
- `predictions: action → success?`：如果执行该行动，预期成功/失败；
- `support/against/missing/test`：支持证据、反对证据、未知信息、区分实验。

H1/H2/H2b/H3/H4/H5/H6 不再由失败码唯一映射到行动；行动选择读取 `predictions` 与 `score`。

### 3.2 可辨识性

`analyzeIdentifiability`：

- 取分数接近最高的 top 假说；
- 对每个候选行动比较 top 假说的预测；
- 若所有行动预测都相同 → `indistinguishable=true`，不继续查询；
- 若存在区分行动 → 记录 `distinguishingActions`，供策略选实验。

正式 MRXR 条件下有 **8/20** 次运行被标记为当前不可辨识；这些主要是歧义/噪声/结构世界：候选行为在全部可用 CHALLENGE 上一致，继续查询没有区分价值。**没有把不可辨识写成失败，也没有凭空升级。**

### 3.3 策略

- **FIXED**：MRX-01 原顺序 `KNOWLEDGE → BUDGET → DNF → IF → CNF`。
- **EVIDENCE**：按支持该行动的假说分数和排序，再按成本排序。
- **VALUE**：`(1 + 区分行动数) × 支持分数 + 历史成功奖励` / 行动成本；允许 `NO_ACTION`。

每个实验后：

- 成功：预测成功的假说 `+0.15`，预测失败的 `−0.15`；
- 失败/反例：反向更新；
- 重新计算可辨识性，再选下一步（最多 3 个行动）。

---

## 4. 开发验证

开发世界（W4、歧义、缺失知识、噪声）使用已知种子；正式世界使用独立新种子。

| 世界 | C8-03 | MRX-01 | FIXED | EVIDENCE | VALUE | 说明 |
|---|---|---|---|---|---|---|
| W4 | 25/27 拒绝 | **27/27** | **27/27** | **27/27** | **27/27** | 四个新学生都修复；DNF |
| 歧义 | 26/27 | 26/27 | 26/27 | 26/27 | 26/27 | 全部不升级，正确 |
| 缺失知识 | 23/27 | **27/27** | **27/27** | **27/27** | **27/27** | FIXED=KNOWLEDGE；EVIDENCE/VALUE=DNF |
| 噪声 | 0/27 | 0/27 | 0/27 | 0/27 | 0/27 | 全部 NO_ACTION |

开发要点：

- W4：基础候选在 CHALLENGE 上出现反例；DNF 修复 27/27；证据复用（`evidenceReused > 0`）。
- 歧义：16 个 CHALLENGE 标签全部匹配，账本 `PASS`；没有无意义升级。
- 缺失知识：恢复 R 或 DNF 都能成功；EVIDENCE 选择 DNF，查询成本高于 FIXED 的 KNOWLEDGE。
- 噪声：H6 直接 `NO_ACTION`。

---

## 5. 正式六条件考试

### 5.1 设置

- 20 个正式世界（8 个新定义 + 12 个沿用 MRX-01 世界定义但使用全新种子的世界）。
- 每个世界 1 个正式种子；数据 9 TRAIN / 29 QUERY / 16 CHALLENGE / 27 HELDOUT。
- 6 条件：C8-03、MRX-01、FIXED、EVIDENCE、VALUE、ORACLE-OPERATOR。
- 共 120 次运行；运行前校验冻结 SHA。
- **未使用多随机种子**（每个世界 1 个）；这是本轮评测规模的限制。

### 5.2 总表

| 条件 | 运行 | 激活 | HELDOUT 全对 | 查询 | 生成候选 | 证据 entries/reused | 解释器调用 |
|---|---:|---:|---:|---:|---:|---:|---:|
| C8-03 | 20 | 5 | 5 | 19 | 221,032 | 0/0 | 501,853 |
| MRX-01 | 20 | 10 | 10 | 193 | 485,024 | 0/0 | 765,845 |
| FIXED | 20 | 10 | 10 | 309 | 678,711 | 240/276 | 501,903 |
| EVIDENCE | 20 | 10 | 10 | 353 | 814,741 | 241/246 | 501,946 |
| VALUE | 20 | 10 | 10 | 353 | 758,551 | 241/246 | 501,946 |
| ORACLE-OPERATOR | 20 | 10 | 10 | 211 | 428,834 | 128/148 | 501,917 |

- 错误激活：**0 / 120**。
- `EVIDENCE/VALUE` 与 `FIXED` 成功率相同（10/20），查询成本更高（353 vs 309），生成候选更多。
- `VALUE` 没有优于 `EVIDENCE`；两者在正式行动序列上相同。
- `ORACLE` 成功率也是 10/20：提供正确操作并不足以绕过候选选择/证据覆盖问题。

### 5.3 分类激活

| 类别 | 世界数 | C8-03 | MRX-01 | FIXED | EVIDENCE | VALUE | ORACLE |
|---|---:|---:|---:|---:|---:|---:|---:|
| 可表达 | 4 | 4 | 4 | 4 | 4 | 4 | 4 |
| 深度 | 4 | 0 | 1 | 1 | 1 | 1 | 1 |
| 条件结构 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| 歧义 | 3 | 0 | 0 | 0 | 0 | 0 | 0 |
| 缺失知识 | 3 | 1 | 3 | 3 | 3 | 3 | 3 |
| 搜索预算 | 2 | 0 | 2 | 2 | 2 | 2 | 2 |
| 噪声 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |

### 5.4 行动选择

| 条件 | 主要行动（20 次） | 无意义升级 | 不可辨识标记 |
|---|---|---:|---:|
| FIXED | NONE 8，NO_ACTION 6，KNOWLEDGE 3，BUDGET 2，DNF 1 | 0 | 8/20 |
| EVIDENCE | NONE 8，NO_ACTION 6，DNF 3，BUDGET 2，KNOWLEDGE 1 | 0 | 8/20 |
| VALUE | NONE 8，NO_ACTION 6，DNF 3，BUDGET 2，KNOWLEDGE 1 | 0 | 8/20 |
| ORACLE | NONE 9，NO_ACTION 4，KNOWLEDGE 3，DNF 2，BUDGET 2 | 0 | 0/20 |

- EVIDENCE 真正改变了行动：缺失知识世界从 FIXED 的 KNOWLEDGE 变为 DNF（2/3），但成功率不变、成本更高。
- 在深度/条件结构世界，8/20 被标记为不可辨识；学生选择 NO_ACTION，而不是乱加 If/DNF。
- 无意义升级为 0：歧义/噪声/可表达世界没有因为“有操作可用”就默认调用。

### 5.5 正确行动率

按“expected 集合包含该行动；expected 为空/NONE 时接受 NONE/NO_ACTION”的口径：

- FIXED：13/20
- EVIDENCE：13/20
- VALUE：13/20
- MRX-01：14/20
- ORACLE：16/20

EVIDENCE/VALUE 没有提高正确行动率；ORACLE 的提高主要来自考官指定，且仍未提高激活率（10/20）。

---

## 6. 失败归因

### 6.1 结构世界仍然失败

- 深度：4 个世界只激活 1 个。
- 条件结构：2 个世界 0 激活。
- 失败机制与 MRX-01 相同：
  - 基础候选与全部 16 个 CHALLENGE 标签一致；
  - 账本无法产生反例；
  - top 假说不可辨识或证据不足；
  - 学生选择 NO_ACTION，而不是猜测结构。
- 即使 ORACLE 强制 DNF/IF，深度/条件世界也没有提高激活；候选选择/证据覆盖仍是瓶颈。

### 6.2 简单策略已经足够

- FIXED 在缺失知识/搜索预算世界全对（3/3、2/2）。
- EVIDENCE 把 KNOWLEDGE 换成 DNF，成功但更贵。
- 这说明“证据驱动”在本轮数据上没有带来净收益；简单固定顺序在多数世界已经足够。任务要求不强制 VALUE 获胜，本轮如实记录。

### 6.3 可辨识性

- 8/20 被标记不可辨识；这些世界没有继续浪费查询。
- 但没有把“不可辨识”转化为更聪明的实验设计（例如主动寻找 CHALLENGE 未覆盖区域），因此 LEVEL 3 仍不完整。

---

## 7. 等级判定

| 等级 | 判定 | 证据 |
|---|---|---|
| LEVEL 1 证据管理正确 | **达成** | 7 个账本测试；0 检查/异常/预算/反例/部分严格分离；HELDOUT 排除 |
| LEVEL 2 证据影响实验选择 | **部分（有影响、无收益）** | EVIDENCE 在缺失知识世界选 DNF 而非 KNOWLEDGE；成功率相同、查询更多 |
| LEVEL 3 结果更新诊断并改变后续行动 | **部分** | 代码按 trial 结果更新分数并重新规划；正式没有显示更新带来更好行动序列 |
| LEVEL 4 自主修复新结构 | **未达成** | 结构世界与 MRX-01 相同；正式 10/20 |
| LEVEL 5 可重复策略优势 | **未达成** | FIXED 309 查询 vs EVIDENCE/VALUE 353，成功率相同 |

**总体：LEVEL 1 达成；LEVEL 2 部分；LEVEL 3 部分；LEVEL 4/5 未达成。**

---

## 8. 已实施 / 失败 / 未实施 / 不可辨识 / 证据不足

### 8.1 已实施并验证

- 证据账本：单次付费、复用、状态机、HELDOUT 排除。
- 7 个证据正确性测试。
- 竞争假说 H1–H6 与可辨识性分析。
- FIXED/EVIDENCE/VALUE 三种策略。
- 每实验后分数更新与重新规划（代码层）。
- 20 世界 ×6 条件正式运行；120 次；0 错误激活。
- 原始 MRX-01 代码逐字节恢复；历史结果保留。

### 8.2 已实施但失败

- EVIDENCE/VALUE 没有超过 FIXED 的成功率；查询成本更高。
- VALUE 没有超过 EVIDENCE。
- 结构世界 depth 1/4、if 0/2，未修复。
- ORACLE 强制操作也没有提高结构世界激活率。

### 8.3 尚未实施

- 每个世界多个随机种子（本轮每世界 1 个）。
- 每输入完整证据账本 JSON dump（正式 JSON 只有 entries/reused 计数；测试覆盖状态机）。
- MRX-01R 中的 T 层连续学习复测。
- 真正的独立闭卷。
- `Let` 操作。
- 基于 CHALLENGE 未覆盖区域主动设计新查询（当前只在已有 CHALLENGE 池内选择）。

### 8.4 当前不可辨识

- 8/20 正式记录被标记为不可辨识。
- 歧义/噪声世界：全部候选在 CHALLENGE 上一致，学生正确停止；HELDOUT 仍不通过。
- 结构世界：基础候选与 CHALLENGE 一致，无法合法推断需要新结构。

### 8.5 证据不足

- LEVEL 3 的“结果更新改变后续行动并提高成绩”：代码存在，正式数据没有稳定证据。
- EVIDENCE/VALUE 的成本优势：没有；反而更贵。
- 多世界统计显著性：每世界 1 个种子，不能声称总体优势。

---

## 9. 复现与产物

代码：

- `src/main/kotlin/logic/e3/pack02/MRXREvidence.kt`
- `src/main/kotlin/logic/e3/pack02/MRXRStudent.kt`
- `src/main/kotlin/logic/e3/pack02/MRXRSpace.kt`
- `src/main/kotlin/logic/e3/pack02/MRXRWorlds.kt`
- `src/main/kotlin/logic/e3/pack02/MRXRExam.kt`
- `src/test/kotlin/logic/e3/pack02/MRXREvidenceTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRXRDevTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRXRDevExamTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRXRSeedTest.kt`
- `src/test/kotlin/logic/e3/pack02/MRXRFormalTest.kt`

证据：

- `experiments/representation-evolution-e3/reports/mrx-01r-freeze.json`
- `experiments/representation-evolution-e3/reports/mrx-01r-dev.json`
- `experiments/representation-evolution-e3/reports/mrx-01r-formal.json`

运行：

```powershell
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRXREvidenceTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRXRDevTest"
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRXRFormalTest"
.\gradlew.bat test --offline
```

全量测试结果：**356/356，0 failed**（B0 173 + E3 183；含 MRX-01R 新增 13 个测试）。

---

## 10. 研究负责人审查要点

- 误发/误撤销已保留审计并重新激活；没有删除历史。
- MRX-01 原始 `MRXSpace.kt` 逐字节恢复，旧冻结测试通过。
- 证据账本没有把 0 检查/异常/预算不足当成 PASS。
- EVIDENCE/VALUE 没有获胜，报告如实记录；没有为了“让 VALUE 赢”改世界或换种子。
- 结构世界失败保留，没有用 HELDOUT 回改学生。
- 未声称 LEVEL 4/5；未声称独立闭卷。
- C4/C8 仍 `PAUSED`；PACK-03 未启动。

**最终回答：**

> 学生现在能够维护一份可复用、严格区分“未验证/部分/通过/反例/预算耗尽”的证据账本，并在竞争假说之间做出可辨识性判断；EVIDENCE/VALUE 也确实改变了行动选择。但在本轮 20 个正式世界上，这种证据驱动没有带来比 FIXED 更高的成功率或更低的查询成本；结构世界的失败仍然存在。LEVEL 1 达成，LEVEL 2–3 部分，LEVEL 4–5 未达成。

任务完成后停止，交由研究负责人审查。
