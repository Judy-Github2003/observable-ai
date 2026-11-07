# SD-03 E1 结果：嵌套任务上递归条件组合成功；总体不优于基线

## 0. 元数据

- 任务：`SD-03-E1-EVIDENCE-COMPOSITION-2026-11-07`
- 起始 HEAD：`ee9b707`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03EvidenceCompositionE1.kt`、`SD03EvidenceCompositionE1ContractTest.kt`、`SD03E1RepoEvidenceAdapter.kt`、`SD03E1DevelopmentIntegrationTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 1. 四组真实结果

4 个公开开发任务 × 4 arms = 16 次冻结运行。

| Arm | Challenge pass 总数 | HELDOUT 正确/84 | new Oracle | reuse |
|---|---:|---:|---:|---:|
| LEGACY_10 | 11 | 63/63 | 60 | 180 |
| R32_NESTED_12 | 9 | 63/63 | 60 | 260 |
| EVIDENCE_DEPTH_1 | 0 | 0 | 0 | 0 |
| EVIDENCE_DEPTH_4 | 3 | 62/63 | 80 | 0 |

逐任务：

| task | LEGACY | R32_NESTED | E1_DEPTH_1 | E1_DEPTH_4 |
|---|---:|---:|---:|---:|
| `mrx-base-or-r-d1` | 4/21 | 4/21 | 0 | 1/21 |
| `mrx-depth-w4` | 3/21 | 2/21 | 0 | 0 |
| `mrx-depth-r-xor-b2` | 4/21 | 3/21 | 0 | 1/21 |
| `mrx-if-nested-3` | **0** | **0** | **0** | **1/20** |

关键正面：

- `mrx-if-nested-3` 上两个基线都 0，E1_DEPTH_4 产生 1 个 Challenge-pass 来源，HELDOUT **20/21**。
- 冻结程序：19 节点、深度 4、真实解释器 TRAIN **40/40**、typecheck=true。
- 决策轨迹有 6 个条件分裂，含根分割与递归分支；`splitAttempts=6005`，`predictionReads=44150`，`memoHits=1097`，`atomPreviewInterpreterCalls=600`，`realTrainInterpreterCalls=40`。
- E1_DEPTH_1 在所有任务 0 pass，说明递归深度是必要因素，不是单层条件包装。

总体：

- E1_DEPTH_4 的 3 个 pass 少于 LEGACY 的 11、R32 的 9；
- 总 HELDOUT 62/63 略低于基线 63/63；
- 不能说“总体优于基线”。

## 2. Gate

| Gate | 判定 | 原因 |
|---|---|---|
| G0 边界 | **PASS_LIMITED** | E1 API 无 truth/world id/Challenge/HELDOUT；预测 null 保留 |
| G1 条件结构 | **PASS_LIMITED** | 6 个真实 TRAIN 条件分裂，含递归；证据 ID 可追溯 |
| G2 独立实现 | **PASS_LIMITED** | 真实解释器 TRAIN 40/40、typecheck；非仅内部 scorer |
| G3 Challenge | **PASS_LIMITED** | nested 任务 1 个 Challenge-pass；其他任务仅部分 |
| G4 收益 | **MIXED / NOT_DEMONSTRATED** | nested 上显著优于基线；总体 pass/heldout 不优于基线 |
| G5 独立迁移 | **NOT_RUN** | 公开开发任务，不是闭卷 |

## 3. 阴性/限制

- E1_DEPTH_1 全 0，说明单层条件组合不足以解决这些任务。
- E1_DEPTH_4 在 W4 仍是 0；在 base/xor 只 1 个 pass，低于基线。
- “nested 任务 1/20”是单任务单种子结果，不能泛化为稳定优势。
- E1 的 atom-partition 计数与 baseline 的 admitted candidate 计数**不是等算力单位**；不能直接宣称效率优势。
- HELDOUT 只在冻结后由考官评分；没有回流到构造器。
- 未注册/激活能力；未做独立闭卷。

## 4. 测试与边界

- E1 契约 8/8 + 集成 1/1 = **9/9，0 failed，14.494s**；`BUILD SUCCESSFUL in 24s`（集成单独）。
- **没有新的业务 RED**；代码首跑通过，未复用旧 RED。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=NOT_RUN`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。
- 证据文件含 `E1-RUN-STARTED.txt`，防止覆盖；本次为单次干净运行。

## 5. 结论

E1 在 `mrx-if-nested-3` 上首次构造出真实可执行的递归条件程序，TRAIN 40/40，Challenge 通过，HELDOUT 20/21；两个枚举基线在该任务均为 0。这是**结构学习机制**的有价值正结果。

但：

- 只在 1 个开发任务成功；
- 总体 pass/heldout 不优于基线；
- 单层 E1 全 0；
- 不能声称稳定泛化、自主学习闭环或效率优势。

因此：**递归组合在困难任务上有机制证据（G3 PASS_LIMITED）；总体收益 MIXED/NOT_DEMONSTRATED；G5 未运行。**

## 6. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03EvidenceCompositionE1ContractTest"
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03E1DevelopmentIntegrationTest"
```

本轮停止，未启动 SD-04。
