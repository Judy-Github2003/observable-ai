# SD-03 E2.1 结果：QUERY 池有 1 个反例，主动策略未命中；单标签局部修复成功但未过 Challenge

## 0. 元数据

- 任务：`SD-03-E21-COUNTEREXAMPLE-OPPORTUNITY-2026-11-07`
- 起始 HEAD：`a1b9ad2`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03E21EvidenceQuery.kt`、`SD03E21ContractTest.kt`、`SD03E21DevelopmentIntegrationTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 1. 实验 A：反例机会审计

4 个公开开发任务、12 QUERY inputs：

| task | 初始反例数 | unknown | E2 查询 | E2 命中反例 | STRICT 查询 | RANDOM 查询 |
|---|---:|---:|---:|---:|---:|---:|
| base-or-r-d1 | 0 | 0 | 0 | 0 | 0 | 0 |
| depth-w4 | 0 | 0 | 1 | 0 | 0 | 1 |
| xor-b2 | 0 | 0 | 2 | 0 | 0 | 2 |
| if-nested-3 | **1**（q-2） | 0 | 4 | **0** | 0 | 4 |

关键：

- 只有 `if-nested-3` 的 QUERY 池存在真实反例 `q-2`。
- 该任务 E2_DISAGREEMENT 发了 4 次查询，但 **没有选中 q-2**。
- STRICT_VERSIONSPACE 在该任务 0 查询（abstain）；RANDOM 4 次也未命中。
- 因此：**机会存在，但 E2 的 disagreement 选择没有命中初始反例**。
- 审计只发生在 A 冻结后：`A-EXAMINER-OPPORTUNITY-AFTER-FREEZE.jsonl`。

## 2. 实验 B：一条反例的局部修复

只有 `if-nested-3` 有 eligible 反例 q-2；其余 3 任务 `NO_POOL_COUNTEREXAMPLE`。

| Arm | Challenge | HELDOUT | 结构 |
|---|---|---|---|
| B_NO_UPDATE | COUNTEREXAMPLE | null | 原程序，含已知错误 |
| B_FULL_REFIT | COUNTEREXAMPLE | null | 全树 refit |
| B_LOCAL_ONLY | COUNTEREXAMPLE | null | `LOCAL_REPAIRED`，path=`TF` |

LOCAL_ONLY：

- `nonRootSiblingInvariant=true`
- `oldEvidenceCorrect=true`
- `allPublicCorrect=true`
- `fullRefitUsed=false`
- `ancestorAttempts=3`，`localPartitionAttempts=100`，`localPredictionReads=473`
- 结构上是真正的非根局部替换。

但三个 B arms 的 Challenge 都是 `COUNTEREXAMPLE`，无 HELDOUT。**局部修复成功不等于任务解决。**

## 3. Gate

| Gate | 判定 | 原因 |
|---|---|---|
| E2.1-G0 边界 | **PASS_LIMITED** | A 冻结后审计；B 单标签；无 truth 回流 |
| E2.1-G1 机会审计 | **PASS_LIMITED** | 真实 1 个反例，策略未命中；审计数据完整 |
| E2.1-G2 主动选择 | **NOT_DEMONSTRATED** | E2 0/1 反例命中；STRICT 0 查询 |
| E2.1-G3 局部修复 | **PASS_LIMITED** | 真实非根替换、兄弟不变、所有公开证据正确 |
| E2.1-G4 Challenge/收益 | **NO_BENEFIT** | B 三臂均 COUNTEREXAMPLE；A 无改善 |
| E2.1-G5 独立迁移 | **NOT_RUN** | 公开开发任务 |

## 4. 成本与阴性结果

- A 共 14 个实际 QUERY labels；16 个 A 冻结程序；19 个评分 arms。
- B 只有 1 个任务可做；3 个任务无 eligible 反例，不能把 0 次 local 当失败证据。
- LOCAL_ONLY 修改了 `TF` 子树，但 full refit 与 local 最终 hash 相同（`7de442...`），说明该单标签不足以解决 Challenge。
- 无 HELDOUT 结果，不填 0/21。
- 完整解释器/CPU 成本仍 `NOT_INSTRUMENTED`。

## 5. 测试与真实性

- E2.1 契约 7/7 + 集成 1/1 = **8/8，0 failed，2.02s**
- `audit_e21_results.py`：`RAW AUDIT PASS`，4 tasks、16 A freezes、3 B freezes、14 labels、19 graded arms；仅内部一致性，不是外部真值回放。
- **没有新的业务 RED**；未复用旧 RED。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=NOT_RUN`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 6. 结论

E2.1 把两个问题分开了：

1. **机会审计**：QUERY 池确实有 1 个反例；E2 的 4 次 disagreement 查询没有命中它；STRICT 版本空间直接 abstain。  
   → 主动策略的“命中反例”能力未证明。
2. **单标签局部修复**：LOCAL_ONLY 真的替换了非根 `TF` 子树，兄弟不变、旧证据保持、公开数据全对。  
   → 局部修复机制有限成立；但仍未通过 Challenge/HELDOUT。  
   → 不能把“局部修复成功”写成“任务学习成功”。

下一问题：为什么 q-2 不在 E2 disagreement 的候选中？是 rival 模型预测一致、q-2 预测 Unknown，还是排序/预算把它挤出？需要单独审计 q-2 的 rival 预测与打分，而不是扩大查询预算。

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03E21ContractTest"
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03E21DevelopmentIntegrationTest"
python tools/audit_e21_results.py docs/research/sd03-e21-development-2026-11-07
```

本轮停止，未启动 SD-04。
