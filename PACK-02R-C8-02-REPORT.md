# PACK-02R-C8-02 报告

**任务：** PACK-02R-C8-02（自主知识复用、主动组合学习与多层知识积累）  
**状态：** REVIEW（实施完成并消费 C8-02 授权，未宣称独立验收）  
**旧学生冻结 SHA：** `4c2e9063b87660fa77d79743caec9a74231619f7`  
**C8-02 代码提交：** `f4a423b78c04ee1e1e143789b6fa5c7073545724`  
**测试：** Kotlin 325/325（B0 173 + E3 152）  
**独立闭卷：** `not_run`

---

## 1. 授权与 preflight

- 在 `repairs.json` 登记 `PACK-02R-C8-02`，审批依据为研究负责人本次消息。
- 未修改 `tasks.json`，未重置旧 authorization/patch 记录。
- 授权事件：`ev-1793956091404-f18bdd`。
- `preflight --task PACK-02R-C8-02` → `AUTHORIZED_REPAIR`。
- 旧 R/AUTH/ACTIVE/EVAL 记录保持；C4/C8/H01 状态未改。

## 2. 修改范围

- `src/main/kotlin/logic/e3/pack02/C8Study.kt`
- `src/test/kotlin/logic/e3/pack02/C8StudyTest.kt`
- `experiments/representation-evolution-e3/reports/pack02r-c8-02-result.json`
- `tools/research-console/register_c8_02.py`
- `research-console/repairs.json` / events / history

没有修改冻结学生 `ActiveLearning.kt`、B0、A1、C1、PACK-01 或原 PACK-02 数据。

## 3. 实验设计

- 先由冻结学生完成 seed 102 的 R 学习，R 已激活。
- 第二任务四属性 `a,b,c,d ∈ {0,1}`，16 点；固定种子 2026 打乱后 TRAIN 8 / HELDOUT 8。
- 第二任务真实目标由考官独立定义，不是学生 R AST 的输出：

```text
S_ref = (a==1 && c==1) OR (d==1)
```

- 学生候选库：P、Q、R；无 `preferredId` 提示。
- 用 `Pack02Knowledge.reuse`（Composer/Filter）生成、筛选候选；没有强制选择 R。
- 若 S 通过 heldout 验证与 B0 激活，才进入 T；T 目标为：

```text
T_ref = S_ref AND (b==1)
```

T 使用 P/Q/R/S 库。

## 4. R 的自主选择结果

`pack02r-c8-02-result.json`：

```text
rActive=true
S: status=AMBIGUOUS
   usesR=false
   heldoutCorrect=6/8
   activated=false
   failure=ambiguous_selection
   generated=2385
   consistent=66
T: null
```

**LEVEL 1（学生自主生成实际调用 R 的新候选）未达到。**

在没有 `preferredId` 的自主选择下，Filter 保留了 66 个训练一致候选；最终选择的 S 候选没有调用 R，且只覆盖 8 个 heldout 中的 6 个。没有 S 激活，因此 T 未开展。

这不是 R 不可用：EVAL-02 已证明，当考官提供 `preferredId` 时，全知识组选中了实际调用 R 的候选并 6/6。本实验的失败在于：**取消提示后，学生没有自主选择 R 的机制**。

## 5. 为什么没有 S

直接阻断：

1. S 目标 `R_ref OR d==1` 可被 P/Q 与 `Eq(d,1)` 表达；R 不是唯一所需；
2. 当前候选选择是 Filter 存活集的第一个/最短可用项，没有“复用成本/依赖价值/历史验证”评分；
3. S 的候选行为类仍为 AMBIGUOUS；
4. 学生没有对 S 做主动查询；`heldoutCorrect=6/8` 说明选择的候选不是正确行为类；
5. 由于 S 未通过 heldout，考官拒绝激活（这是正确行为）。

**LEVEL 2（复用辨识）、LEVEL 3（S 固化）、LEVEL 4（T）均未达到。**

## 6. S/T 轨迹

```text
R active
  ↓
S candidates 2385 generated / 66 consistent
  ↓
selected candidate: no R, 6/8 heldout
  ↓
S not activated
  ↓
T not started (blocked by inactive S)
```

没有伪造 S/T 激活，也没有把 6/8 写成成功。

## 7. FULL / NO-R / NO-S / BASE 与查询策略

本轮**没有完成**这些对照：

- 只有 FULL 的 S 候选；NO-R/NO-S/BASE 未实际运行；
- 没有 S 激活，T 对照无法开始；
- 没有 ACTIVE/FIXED/RANDOM 的 S 查询对照（S 复用调用未做主动查询）。
- 因此不能给出各档成功率、查询收益或累计学习成本。

这是实验范围/时间限制，不是阴性结果；必须如实标注。

## 8. 与既有结果的关系

- EVAL-02：R 依赖修复后，在 `preferredId` 提示下 full 组实际调用 R 并 6/6；control 也 6/6，无优势。
- C8-02：去掉提示后，学生选择了不含 R 的候选，S 失败。
- 结论：**“能调用 R”与“会自主选择 R”是两种能力**；本任务只证明了前者（在提示下），未证明后者。

## 9. 失败归因

- `CONFIRMED`：S 选择不含 R，heldout 6/8，S 未激活。
- `CONFIRMED`：没有 `preferredId`；没有硬编码目标；没有教师候选注入。
- `INFERRED`：候选选择缺乏复用价值/复杂度/历史验证评分，导致 R 路线被淹没。
- `UNKNOWN`：在更多查询/主动辨识下，是否能选中 S 的正确行为类；本轮未执行。
- `UNKNOWN`：更大搜索深度是否能发现 R 路线；本轮未调参。

## 10. 成果等级

| LEVEL | 状态 |
|---|---|
| 1 候选复用 | 未达到（自主选择未调用 R） |
| 2 复用辨识 | 未达到 |
| 3 S 固化 | 未达到 |
| 4 R/S 连续积累 | 未达到 |
| 5 可测量学习收益 | 未达到 |

## 11. 回归与状态

- Kotlin：**325/325**；B0 173，E3 152。
- PACK-02 专项 13/13；AUTH、ACTIVE、EVAL、Console gate 测试通过。
- C4/C8 仍 `PAUSED`；H01 Console 仍 `REVIEW`（与复核摘要 `ACCEPTED_LIMITED` 不一致，未擅自修改）。
- 独立闭卷 `not_run`；不启动 PACK-03。

## 12. 下一步建议

1. 在不使用 `preferredId` 的前提下，加入通用候选评分：AST 成本、依赖价值、历史验证、行为类信息增益；不得按隐藏目标加分。
2. 将 ACTIVE 扩展到四属性 S 任务，允许主动查询；只有 S 行为类唯一并验证通过才激活。
3. 在 S 激活后再做 T 与 FULL/NO-R/NO-S/BASE 对照；记录累计学习成本。
4. 若仍失败，报告阻断在 LEVEL1/2，不删除 R。
5. 不自动启动 PACK-03；等待外部监督。

## 13. 授权消费

C8-02 授权消费事件：`ev-1793956217267-eeedb8`；再次 preflight 返回 `CONSUMED`。不改变原任务状态。

