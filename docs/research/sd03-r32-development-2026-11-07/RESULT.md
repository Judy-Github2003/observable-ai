# SD-03 R3.2 结果：顺序公平改善角色覆盖，但未产生 TRAIN 来源

## 0. 元数据

- 任务：`SD-03-R32-ENUMERATION-FAIRNESS-2026-11-07`
- 起始 HEAD：`0fe392a`；结束 SHA：本轮提交
- Console：ACTIVE → CONSUMED
- 变更：`MRX2BR32Enumeration.kt`、`MRX2BSpace.kt`（R3.2 可选 policy）、两个 R3.2 测试、`scripts/apply_r32.py`、本目录、README
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；`fullRegression=NOT_RUN`

## 1. 三组真实结果

固定 `mrx-if-nested-3`、seed 20261109、FULL、PAIRWISE Top-12、30k：

| Arm | generated | TRAIN consistent | IF attempted/admitted | direct role kinds (cond/true/false) | nested admitted | Challenge |
|---|---:|---:|---|---|---:|---:|
| LEX_DIRECT24 | 29,044 | 0 | 13,824 / 5,720 | 24 / **10** / 24 | 0 | 0 |
| ROTATING_DIRECT24 | 29,044 | 0 | 13,824 / 5,720 | **24 / 24 / 24** | 0 | 0 |
| ROTATING_NESTED24 | 29,044 | 0 | 16,684 / 5,720 | 24 / 24 / 24 | **2,860** | 0 |

- LEX 的 `whenTrue` 只覆盖 10 种字面量；ROTATING 在相同 quota、相同 atom pool、相同直接语法下把三角色覆盖都提升到 24。
- 但三组 TRAIN-consistent 均 **0**；Challenge pass 0；无 HELDOUT 正确率。
- NESTED 实际接纳 2,860 个 `If(c0, If(c1,a,b), If(c2,c,d))`；仍 0 TRAIN-consistent。
- NESTED 的 IF attempted=16,684、familyQuotaRejected=10,964；是**语法+配额再分配**的联合干预，不是纯嵌套语法对照。

## 2. 成本

| Arm | search interpreter calls | wall ms |
|---|---:|---:|
| LEX_DIRECT24 | 51,181 | 1,300 |
| ROTATING_DIRECT24 | 51,377 | 1,152 |
| ROTATING_NESTED24 | 51,931 | 1,267 |

- 仅生成搜索解释器次数；完整 CPU/候选图成本仍 `UNKNOWN`。
- 三组无 Oracle 新查询/复用（0 来源，无验证）。

## 3. 解释

**已证实：**

- 枚举顺序确实造成覆盖差异：LEX 的 `whenTrue` 角色只覆盖 10/24，ROTATING 覆盖 24/24；这是受控顺序对照。
- NESTED 生成器能实际接纳嵌套 If，`nestedIfAccepted=2860`。
- 但角色覆盖/嵌套接纳增加，没有转化为 TRAIN-consistent。

**未证明：**

- 不能把 NESTED 的差异单独归因于“嵌套语法”；它同时替换了 2,860 个 direct 槽位。
- 不能把“无 TRAIN 来源”直接等同“语义不可表达”。
- 没有学生自主修复/学习成功。

## 4. Gate

| Gate | 判定 | 原因 |
|---|---|---|
| G1 公平机制 | **PASS_LIMITED** | 顺序改变扩大 direct IF 角色覆盖 |
| G2 有用覆盖 | **NOT_DEMONSTRATED** | 覆盖扩大但 0 TRAIN consistent |
| G3 嵌套模板 | **PASS_LIMITED（联合干预）** | 实际接纳 2,860 nested，但非纯语法归因 |
| G4 学习/收益 | **NO_BENEFIT** | 0 来源/0 Challenge/无 HELDOUT |
| G5 盲测/迁移 | **NOT_DEMONSTRATED** | 单开发任务 |

## 5. 测试与边界

- R3.2 契约 5/5 + 集成 1/1 = **6/6，4.996s**，`BUILD SUCCESSFUL in 14s`
- LEX 与 R3.1 QUOTA_IF24 兼容性：generated 29,044、IF 5,720、TRAIN 0 一致；未出现 stop condition
- 首次证据归档 `ATTEMPTS/`，最终使用一次干净运行
- **没有新的业务 RED**；未复用旧 RED
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=NOT_RUN`
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试

## 6. 结论

R3.2 成功把“枚举顺序造成角色覆盖不均”这一层拆开：LEX 的 whenTrue 只覆盖 10/24，ROTATING 覆盖 24/24；NESTED 也确实进入了嵌套 If。

但三组都 **0 TRAIN-consistent、0 Challenge、无 HELDOUT**。因此：

- 顺序公平有机制证据；
- 嵌套模板有接纳证据；
- **学习成功/收益仍未证明**；
- NESTED 不能单独归因于语法，因为同时改变了 direct 槽位配额。

下一问题：在保持同一 grammar 的前提下，如何把“角色覆盖”与“候选组合/语义筛选”分开；以及嵌套模板需要怎样的输入覆盖，才能产生 TRAIN-consistent，而不是仅增加接纳数。

## 7. 运行命令

```powershell
python scripts/apply_r32.py --check
python scripts/apply_r32.py --apply
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03R32EnumerationContractTest,logic.e3.pack02.SD03R32IntegrationTest"
```

本轮停止，未启动 SD-04。
