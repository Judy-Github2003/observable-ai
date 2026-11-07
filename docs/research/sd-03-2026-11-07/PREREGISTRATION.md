# SD-03 预注册：真实学生来源 → 参数化结构闭环

- 任务：`SD-03-STUDENT-ABSTRACTION-LOOP-2026-11-07`
- 起始 HEAD：`4603f05`（SD-02 Console 完成）；实际工作树 `6ed6e62` 后无新代码
- Console：`SD-03-STUDENT-ABSTRACTION-LOOP-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 固定变量

| 项 | 冻结值 |
|---|---|
| 开发任务 | 两个匿名布尔合取任务 `a=1 ∧ d=1` 与 `b=3 ∧ c=3` |
| 学生搜索 | 现有 `MRX2BSpace` PAIRWISE（固定枚举器，教师工程先验） |
| 数据 | 40 TRAIN、41 CHALLENGE；HELDOUT 不参与 |
| Oracle | `StudyQueryController` + `MRX2Ledger.validate`，`PASS_ON_CHALLENGE` 才选源 |
| 来源要求 | ≥2 个不同 canonical、真实 challenge 通过、证据可回链 |
| 归纳 | 现有 `SD02AntiUnify`，恰好 2 来源；拒绝退化/类型冲突 |
| 注册/迁移/消融 | 本轮未实现；G2–G4 不预注册为可完成 |
| 失败判据 | 无两个合规来源 → `NO_VERIFIED_PAIR_IN_BOUND`；LGG 退化 → `G1_NOT_DEMONSTRATED` |

## 负对照

- 相同来源/复制 → 拒绝；
- 只有 root hole/零参数 → degenerate；
- 类型冲突/未知 AST → reject；
- 不将 `InMemoryResolver` 调用冒充注册/激活；
- 不将无宏/0 调用当 DISABLED 对照。

## 停止条件

- 两个真实源找到但 LGG 退化 → `G1_NOT_DEMONSTRATED`，不再为通过而扩充搜索/手工补结构；
- 找不到两个源 → `G0_NOT_DEMONSTRATED`；
- 不运行全量/正式考试。
