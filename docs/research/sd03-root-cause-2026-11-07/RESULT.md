# SD-03 Root Cause 结果：A0 的 top-10 原子截断是直接原因，A1 同时触发候选上限

## 0. 元数据

- 任务：`SD-03-ROOT-CAUSE-2026-11-07`
- 起始 HEAD：`3cee51a`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`SD03RootCauseMatrixTest.kt`、`SD03MacroBindingRegressionTest.kt`、`SD03.kt`（caller key 修复）、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 根因矩阵

`ROOT-CAUSE-MATRIX.jsonl`，seed 1449，81 输入，40/20/21：

| 世界/arm | generated | train-consistent | retained | Challenge pass | 关键原子 | 候选上限 |
|---|---:|---:|---:|---:|---|---|
| A0 PAIRWISE/10 | 28,523 | **0** | 0 | **0** | `lhs=true`，`rhs=false` | 否 |
| A1 PAIRWISE/12 | 30,000 | 81 | 81 | **4** | lhs/rhs true | **是（30k）** |
| A2 DIVERSITY/10 | 28,523 | 59 | 59 | **4** | lhs/rhs true | 否 |
| A3 BASELINE/10 | 28,523 | 71 | 71 | **4** | lhs/rhs true | 否 |
| B0 PAIRWISE/10 | 28,523 | 75 | 75 | **4** | lhs/rhs true | 否 |

- A0 的 `d=="1"` 原子未进入前 10，`rhsAtomRetained=false`；GOLD 候选也没有在 GENERATED 中出现。
- A0 失败发生在 **原子选择/候选生成**，不是 TRAIN_CONSISTENT 或 RETAINED 剪枝。
- A1 把 `rhs` 原子纳入后恢复 4 个 challenge-pass 来源，但同时撞到 30,000 候选上限；因此 A1 的恢复不能单独归因于 atomLimit，H4 也成立。
- A2/A3 也能恢复；但 A2/A3 是策略对照，不是严格单变量 atomLimit 实验。
- A1/A2/A3/B0 的 ledger：20 new / 60 reuse / 80 interpreter calls；A0 为 0（无候选可验）。

## 2. GOLD 与假说

GOLD `And(Eq(a,1), Eq(d,1))`：

- typecheck=true；
- TRAIN 40/40，CHALLENGE 20/20，HELDOUT 21/21；
- 独立 ledger `PASS_ON_CHALLENGE`，20 new queries。

结论：

| 假说 | 判定 | 证据 |
|---|---|---|
| H1 前 10 原子漏 `d=1` | **支持** | A0 `rhsAtomRetained=false`，train-consistent=0；A1/A2/A3 保留后恢复 |
| H2 PAIRWISE 同分/字典序 | **未被证明为主要原因** | ranking 重建与生产 `trace.atoms` 一致；主要缺口是 top-10 |
| H3 TRAIN 数据不足 | **排除** | A TRAIN 有 5 正/35 负，存在区分 `d` 的相反标签对 |
| H4 30k 候选上限 | **支持（A1 同时）** | A1 generated=30,000，`candidateCapPossiblyHit=true` |
| H5 剪枝 | **排除** | A0 没有 gold 进入 GENERATED；不是 retained 剪枝 |
| H6 IR/type/Oracle | **排除** | GOLD typecheck + 40/20/21 + oracle PASS |
| H7 宏 caller key | **支持（已修复）** | RED `input_argument_mismatch`；补丁后 GREEN |

## 3. 宏绑定 RED/GREEN

- 修复前：caller `values` 的键是 `params` 原名，缺 `call_` 前缀；真实 RED：
  `Error(input_argument_mismatch)`。
- 修复：`values = params.map { "call_$id" to ... }`。
- GREEN：`SD03MacroBindingRegressionTest` 1/1，`Known(Bool(false))` 精确断言通过。
- 这是 synthetic engineering fixture，不是学生发现/注册/迁移证据。

## 4. 成本

- 生成解释器调用未统一暴露，标 `NOT_INSTRUMENTED`；不能记 0。
- 验证侧：A1/A2/A3/B0 各 20 new / 60 reuse / 80 interpreter calls；A0 0（无候选）。
- GOLD 独立 20 new。
- 未做注册/迁移/ENABLED-DISABLED。

## 5. 测试与门禁

- `SD03RootCauseMatrixTest` + `SD03MacroBindingRegressionTest`：**2/2，0 failed，9.918s**，`BUILD SUCCESSFUL in 19s`。
- RED 为宏绑定回归的真实业务失败；根因矩阵是诊断测试，首跑通过，不伪造 RED。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=not_run`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 6. 结论

- A0 的直接根因是 **PAIRWISE top-10 原子排序漏掉 `d=="1"`**，导致 0 个 train-consistent 候选。
- 把 atomLimit 提到 12 可恢复，但同时触发 30k 候选上限；生产默认不应在本轮被修改。
- 数据有效性修复后 A/B 都有真实来源；但本轮没有继续注册/迁移，仍 `NOT_RUN`。
- 不宣称“学生自主结构学习闭环”完成；只完成根因诊断与宏绑定修复。

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03RootCauseMatrixTest,logic.e3.pack02.SD03MacroBindingRegressionTest"
```

本轮停止，未启动 SD-04。
