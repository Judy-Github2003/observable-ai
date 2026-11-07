# SD-03 E2.2 结果：一致猜想命中 2/5 反例，但无学习收益且出现负作用

## 0. 元数据

- 任务：`SD-03-E22-CONSISTENT-CONJECTURES-2026-11-07`
- 起始 HEAD：`5636a2c`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03E22ConsistentConjectures.kt`、`SD03E22ContractTest.kt`、`SD03E22DevelopmentIntegrationTest.kt`、`tools/audit_e22_results.py`、本目录、README
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 1. 结构审计

`tools/audit_e22_results.py`：

- task-seeds：8
- student final freezes：40
- matrix snapshots：76
- revealed query labels：56
- examiner opportunity rows：8
- examiner grade rows：40
- 结果：`E22 RAW STRUCTURAL AUDIT PASS`（仅内部一致性）

每步完整矩阵（12 QUERY × 所有候选的预测/分数/排名/落选原因）已写入 `STUDENT-EVENTS-*.jsonl`。

## 2. 初始反例机会

| task-seed | 初始反例 | q-IDs |
|---|---|---|
| base-or-r-d1/20261111 | 0 | — |
| depth-w4/20261110 | 0 | — |
| xor-b2/20261112 | 0 | — |
| if-nested-3/20261109 | 1 | q-2 |
| base-or-r-d1/20261121 | 0 | — |
| depth-w4/20261122 | 1 | q-4 |
| xor-b2/20261123 | 0 | — |
| if-nested-3/20261124 | 3 | q-6, q-10, q-11 |

共 **3 个 task-seed、5 个初始反例**；其余 5 个 `NO_OPPORTUNITY`。

## 3. 查询命中

| Arm | 总查询 | 命中初始反例 |
|---|---:|---:|
| STATIC | 0 | 0 |
| RANDOM_FIXED_4 | 32 | 1（q-2） |
| E2_LEGACY | 13 | 0 |
| E21_STRICT | 0 | 0 |
| E22_CONJECTURE | 11 | **2**（q-2、q-6） |

E22：

- `if-nested-3/20261109` 命中 q-2；
- `if-nested-3/20261124` 命中 q-6，但漏掉 q-10/q-11；
- `depth-w4/20261122` 0 查询，漏掉 q-4。
- E22 比 E2_LEGACY 命中更多，但仍只覆盖 2/5，且未覆盖第二个 w4 反例。

## 4. Challenge/HELDOUT

| Arm | Challenge pass | HELDOUT 正确/147 | 备注 |
|---|---:|---:|---|
| STATIC | 6 | 119 | 基线 |
| RANDOM_FIXED_4 | 6 | 119 | 与 STATIC 相同 |
| E2_LEGACY | 5 | 103 | 比 STATIC 差 |
| E21_STRICT | 6 | 119 | 0 查询，等同 STATIC |
| E22_CONJECTURE | **5** | **103** | 比 STATIC 差 |

E22 的具体负作用：

- `if-nested-3/20261124` 初始 STATIC **PASS（16/21）**；
- E22 查询后变为 **COUNTEREXAMPLE**，HELDOUT 变为 null；
- 命中 q-6 没有转化为整体成功，反而破坏了原程序。
- `if-nested-3/20261109` 仍为 COUNTEREXAMPLE。

## 5. Gate

| Gate | 判定 | 原因 |
|---|---|---|
| E2.2-G0 边界 | **PASS_LIMITED** | 矩阵/决策冻结先于审计；考官评分独立；无 truth 回流 |
| E2.2-G1 完整矩阵 | **PASS_LIMITED** | 76 snapshots，12 行/步，落选原因可审计 |
| E2.2-G2 一致猜想 | **PASS_LIMITED** | 猜想经公开标签+类型/解释器校验；数量/多样性有记录 |
| E2.2-G3 查询命中 | **PASS_LIMITED** | 2/5 初始反例命中，优于 E2_LEGACY 的 0/5 |
| E2.2-G4 学习收益 | **NO_BENEFIT（有负作用）** | 5 pass/103 vs STATIC 6/119；4b1f 由 pass 变 fail |
| E2.2-G5 独立迁移 | **NOT_RUN** | 公开开发任务，含已见 q-2 |

## 6. 关键阴性结果

- 5 个 task-seed 无初始反例，不能算漏选。
- E22 命中 2 个反例，但没有带来 Challenge/HELDOUT 改善；反而在 4b1f 上把 PASS 变成失败。
- E21_STRICT 在所有任务 0 查询，等同 STATIC；严格版本空间没有可用分歧。
- 再次命中 q-2 不是独立泛化；q-2 是已公开开发案例。
- 不能声称 E22 优于 RANDOM/E2；命中率略高但最终得分更差。
- 成本：76 矩阵快照、56 labels、E22 11 查询；完整 CPU 成本 `UNKNOWN`。

## 7. 测试与真实性

- E2.2 契约 10/10 + 集成 1/1 = **11/11，0 failed，3.493s**
- **没有新的业务 RED**；未复用旧 RED。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=NOT_RUN`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 8. 结论

E2.2 成功建立了完整可审计的查询矩阵和一致猜想机制，并把 E2.1 的“机会审计”推进到“每步全矩阵 + 命中率”：

- 猜想选择确实命中 2 个真实初始反例；
- 但命中反例不等于修复任务；
- E22 的最终 Challenge/HELDOUT **不优于 STATIC**，且在 4b1f 上有负作用；
- E21_STRICT 0 查询，说明严格一致性过滤直接扼杀了分歧。

因此：**机制可审计性成立；查询命中率有限成立；学习收益未证明，且有负作用。**

下一问题：为什么 q-6 命中后反而使 4b1f 从 PASS 变 FAIL？需要审计 E22 在每次 reveal 后的 refit 是否丢失了原程序的有效结构，而不是继续增加猜想数量。

## 9. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03E22ContractTest"
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03E22DevelopmentIntegrationTest"
python tools/audit_e22_results.py docs/research/sd03-e22-development-2026-11-07
```

本轮停止，未启动 SD-04。
