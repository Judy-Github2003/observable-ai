# SD-01 结果：组合候选可验证，但可复用结构未实现

## 0. 元数据

- 任务编号：`SD-01-STRUCTURE-DISCOVERY-2026-11-07`
- 起始 SHA：`f4b0d14e80112c861eb2933dfc7686609c438652`
- 结束 SHA：本轮提交（见最终答复）
- PREREGISTRATION：`docs/research/sd-01-2026-11-07/PREREGISTRATION.md`
- Console：`SD-01-STRUCTURE-DISCOVERY-2026-11-07`，ACTIVE → CONSUMED
- 未提交文件：4 个既有未跟踪文档，未触碰
- 变更：`SD01.kt`、两个测试、本目录、README

## 1. 目标与假说

- RQ1：从通用原语生成多个候选，用 TRAIN/CHALLENGE 筛选 → **部分成立**。
- RQ2：提炼参数化可调用结构，并在独立任务真实调用 → **未成立**。
- RQ3：相对基础搜索有成本/成功率收益 → **未证明**。
- 原语/预算：现有 Typed IR、`MRX2BSpace` PAIRWISE/BASELINE；maxCandidates 30,000、consistentLimit 2,000。
- 任务：W4 类 + 独立 XOR；seed 1449，9/29/16/27；HELDOUT 未进入学生。

## 2. 真实观察与候选溯源

| 案例 | generated | retained | CHALLENGE 通过 | 结构 |
|---|---:|---:|---|---|
| W4 | 28,523 | 335 | 有，16/16 | `sd01-96ca5f8d`，PROGRAM_INSTANCE |
| XOR | 28,523 | 112 | 无 | null |

- 候选均来自现有 `MRX2BSpace` 固定有界枚举器；`origin=STUDENT_DERIVED` 仅表示“由学生侧生成器产生”，**不代表新的发现算法**。
- TRAIN 证据、CHALLENGE 证据 ID 分开保存；未读取 HELDOUT/目标真值进入生成器。
- 教师贡献：复用既有 Typed IR/枚举器/解释器；未提供 W4/XOR 目标程序或世界特判。

## 3. 新结构固化及迁移

- `structureId=sd01-96ca5f8d@1`，类别 **PROGRAM_INSTANCE**，参数列表为空。
- 它只是一个封闭程序实例，不是 `PARAMETRIC_MACRO`，不能对未见任务提供参数化复用。
- 迁移：用该 body 在 XOR 的 9 个 TRAIN cases 上检查，**5/9 正确**；不是真实能力调用，只是 body 预测。
- 禁用消融：`structure=null` 时 checks=0，无法形成“真实禁用调用”的因果对照；**结构性缺失**。
- 结论：**SD1 NOT_DEMONSTRATED**；不能把 5/9 或“知识库多一条记录”当可复用抽象。

## 4. 对照与成本

| 组别 | 任务 | 状态 | 候选数 | 实付 Oracle | 解释器调用 | 墙钟 | 新结构调用 |
|---|---|---|---:|---:|---:|---:|---:|
| BASE_SEARCH | W4 | 也找到候选 | 28,523 | UNKNOWN | UNKNOWN | UNKNOWN | 0 |
| STRUCTURE_DISCOVERY | W4 | 候选通过 CHALLENGE | 28,523 | UNKNOWN | UNKNOWN | UNKNOWN | 0 |
| STRUCTURE_DISABLED_TRANSFER | XOR | 无结构可禁用 | — | UNKNOWN | UNKNOWN | UNKNOWN | 0 |

- BASE_SEARCH 与 STRUCTURE_DISCOVERY 使用同一固定枚举器；没有搜索算法差异。
- 没有真实 new structure 调用；`newQueries`/解释器调用未做跨实例统一计量，标 `UNKNOWN`，不填 0 冒充零成本。
- 样本仅 2 个开发案例；`INCONCLUSIVE`，无收益结论。

## 5. RED/GREEN 与门禁

- RED：`SD01DiscoveryContractTest` **3 failed**（骨架返回空结构/空候选；业务断言失败，非编译失败）。
- GREEN：`SD01DiscoveryContractTest` 3/3 + `SD01TransferTest` 1/1 = **4/4，10.739s**，`BUILD SUCCESSFUL in 19s`。
- 默认 `quickTest`：14/14（未包含 SD01 专项，按需显式）。
- `fullRegression=NOT_RUN`；`formal_runs=0`；`independent_closed_book=not_run`。
- 未运行无筛选 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## 6. 科学判定

| 等级 | 判定 | 证据 |
|---|---|---|
| SD0 真实组合归纳 | **SD0_PASS_LIMITED** | W4 生成 335 保留候选，1 个 CHALLENGE 16/16 通过；但使用既有固定枚举器，不是新搜索算法 |
| SD1 结构复用与消融 | **NOT_DEMONSTRATED** | 只有 PROGRAM_INSTANCE，无参数；无真实调用链；无有效禁用对照；迁移 5/9 不是能力复用 |
| SD2 自主更新搜索语法 | **NOT_TESTED** | 未实现生成规则学习 |
| 成本收益 | **INCONCLUSIVE / NO_BENEFIT** | 同枚举器；成本未统一；无真实调用 |

## 7. 阴性结果与下一问题

- XOR 在预算内没有 CHALLENGE 通过的候选（112 retained，structure=null）；这是候选空间/覆盖不足的阴性结果，不是“不可表达”证明。
- W4 的成功可直接由既有枚举器得到；不能声称“学生自主扩展表达语法”。
- 教师先验（枚举器、Typed IR、AND/OR/NOT/IF 原语）仍是主要能力来源。
- 下一阶段最值得验证的**一个**假说：
  > 从两个以上已验证候选做**参数化反统一（anti-unification）**，能否产生真正可调用的 `PARAMETRIC_MACRO`，并在独立嵌套任务上通过真实调用/禁用消融证明收益？

## 8. Git 与交付

- 代码/测试/报告：本轮提交
- 测试：`SD01DiscoveryContractTest`、`SD01TransferTest`
- 未运行：全量回归、正式 160、独立闭卷
- 已停止，未自动启动 SD-02 或 MRX 后续任务
