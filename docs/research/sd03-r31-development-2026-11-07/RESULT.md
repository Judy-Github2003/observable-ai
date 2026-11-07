# SD-03 R3.1 结果：IF 被 OR 挤出已证实，恢复 IF 仍无 TRAIN 来源

## 0. 元数据

- 任务：`SD-03-R31-ENUMERATION-CAUSAL-2026-11-07`
- 起始 HEAD：`b7580e7`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`MRX2BR31Budget.kt`、`MRX2BSpace.kt`（guarded patch，LEGACY 默认）、两个 R3.1 测试、`scripts/apply_r31.py`、本目录、README
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 1. 三组真实结果

固定 `mrx-if-nested-3`、seed 20261109、FULL、PAIRWISE Top-12、30k：

| Arm | generated | TRAIN consistent | IF attempted/admitted | IF globalCapRejected | OR admitted | Challenge pass | HELDOUT |
|---|---:|---:|---|---:|---:|---:|---:|
| LEGACY_12 | 30,000 | 0 | 1,728 / **0** | **1,728** | 29,676 | 0 | none |
| QUOTA_IF12 | 25,052 | 0 | 1,728 / **1,728** | 0 | 23,000 | 0 | none |
| QUOTA_IF24 | 29,044 | 0 | 13,824 / **5,720** | 0 | 23,000 | 0 | none |

- LEGACY：OR 先接纳 29,676，填满 30k；IF 的 1,728 次尝试全部被全局 cap 拒绝。
- QUOTA_IF12：IF 全部接纳；OR 达到 23,000 配额后拒绝 29,650 次；空余配额没有借给其他 family，generated 只有 25,052。
- QUOTA_IF24：If 字面量范围扩到 24，IF 接纳 5,720；仍然 **0 TRAIN-consistent**。
- 三组 `nestedIfAstCount=0`；直接 If 有了，但候选语法里没有嵌套 If 模板。
- 三组 Challenge pass 均为 0，无 HELDOUT 正确率。

## 2. 成本

| Arm | search interpreter calls | generation wall |
|---|---:|---:|
| LEGACY_12 | 54,198 | 1,475 ms |
| QUOTA_IF12 | 43,947 | 1,118 ms |
| QUOTA_IF24 | 51,181 | 1,232 ms |

- 这是生成搜索阶段的解释器次数；候选生成完整 CPU 成本仍 `UNKNOWN`。
- 三组无 Oracle 新查询/复用（0 来源，无验证）。

## 3. 结论

**已证实：**

1. LEGACY 的 IF `admitted=0` 不是语法不存在，而是 OR 先耗尽全局 30k 候选预算（1,728 次 IF 被 `globalCapRejected`）。
2. 配额调度 `QUOTA_IF12` 能让 IF 被接纳；这是**枚举预算竞争**的直接证据。
3. `QUOTA_IF24` 扩 If 可见字面量后仍 0 TRAIN-consistent；所以“补回 IF”不等于任务可解。
4. 三组 `nestedIfAstCount=0`，只能说明**直接嵌套 If 模板缺失**，不能说明语义不可表达。

**未证明：**

- 没有 TRAIN-consistent/Challenge/HELDOUT 成功；
- 没有学生自主修复；配额改动是外部实验干预，不是学生学习；
- 没有证明 `If` 输入覆盖不足与嵌套语法缺失哪一个单独导致失败；
- 不能把 `AUDIT`/调度结果写成学习收益。

## 4. Gate 判定

| Gate | 判定 | 原因 |
|---|---|---|
| G0 数据/证据 | **PASS_LIMITED** | 固定数据/原子/预算；先冻结后评分；成本部分 UNKNOWN |
| G1 预算因果 | **PASS_LIMITED** | LEGACY IF admitted=0 可复现；QUOTA_IF12 恢复 IF |
| G2 结构/覆盖区分 | **PASS_LIMITED** | IF12/24 均 0 consistent；nestedIf=0；只到诊断边界 |
| G3 学习修复 | **NOT_DEMONSTRATED** | 无 TRAIN 来源/Challenge/HELDOUT |
| G4 收益 | **NO_BENEFIT** | 三组 0 来源；无 HELDOUT |
| G5 跨任务 | **NOT_DEMONSTRATED** | 单开发任务 |

## 5. 测试与真实性

- `SD03R31EnumerationContractTest` 2/2；`SD03R31EnumerationIntegrationTest` 1/1；合计 **3/3，5.072s**，`BUILD SUCCESSFUL in 15s`。
- 第一次 integration 写入证据后再次运行被 `R3.1 evidence already exists` 拒绝；已归档首次证据到 `ATTEMPTS/`，最终使用一次干净运行。
- **没有新的业务 RED**；契约/集成首跑通过，未复用旧 RED。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=NOT_RUN`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 6. 下一问题

在保持 Top-12、30k 的前提下，下一步应单独测试：

1. **IF 输入覆盖**：If 条件/分支可用的字面量集合，而非只扩 12→24；
2. **嵌套 If 模板**：当前生成器只枚举单层 `If(c,a,b)`，`nestedIfAstCount=0` 是模板缺失的直接证据；
3. **预算再分配**：OR 23k 配额是否过多/IF 5.7k 是否足够，需另有预注册对照。

不能把“配额恢复 IF”当任务修复；也不能把“nested If 模板缺失”直接等同语义不可表达。

## 7. 运行命令

```powershell
python scripts/apply_r31.py --check
python scripts/apply_r31.py --apply
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03R31EnumerationContractTest,logic.e3.pack02.SD03R31EnumerationIntegrationTest"
```

本轮停止，未启动 SD-04。
