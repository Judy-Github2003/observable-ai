# PACK-02R-EVAL-02 报告

**任务：** PACK-02R-EVAL-02（最小实验协议修正与补充评测）  
**状态：** REVIEW（实施完成并消费 EVAL-02 授权，未宣称独立验收）  
**STUDENT_FREEZE_SHA：** `4c2e9063b87660fa77d79743caec9a74231619f7`  
**EVAL-02 代码/结果提交：** `6853b50f156e529fb5870bdb8dfb88d67b2e0bf0`  
**测试：** Kotlin 324/324（B0 173 + E3 151）  
**独立闭卷：** `not_run`

---

## 1. 授权与 preflight

- 在 `repairs.json` 登记 `PACK-02R-EVAL-02`，审批依据为研究负责人本次消息；未改 `tasks.json`，未重置旧授权或 PACK-02 补丁。
- 授权事件：`ev-1793955186739-6928c0`。
- `preflight --task PACK-02R-EVAL-02` → `AUTHORIZED_REPAIR`。
- 学生仍冻结在 `4c2e906`；本轮只修改考官侧生成/验证/C8 配置/评测。
- 完成后消费授权（见第 18 节）。

## 2. eval-101 的确定失败码

`pack02r-eval101-diagnostic.json`：

```text
status=IDENTIFIED
selected=true
outcomeCode=suite_inadequate
verdict=FAIL
caseCount=6
passedCount=6
definiteCount=6
trueCount=0
falseCount=6
recorded=true
```

**结论（CONFIRMED）：** 6 个 heldout 全部是反例，预测 6/6 仍然通过逐例，但 B0 要求至少一个正例和一个反例；`suite_inadequate` 是最终码，不是学生预测错误。

**修正：** 新增预注册 heldout 覆盖检查 `EvalExam2.choose`：正例和反例都必须存在；不合格 seed 只按预注册的 alternate seed 顺序替换，不看学生成绩。EVAL-01 原始结果仍保存在 `pack02r-eval01-*`，未覆盖。

## 3. C8 无法复用的根因

**直接原因（CONFIRMED）：**

- 全知识组第一次把 **R 单独**放入复用 library；
- 候选 `Call(R)` 求值时，R 的 AST 依赖 P/Q；`InMemoryResolver` 没有 P/Q，依赖无法解析；
- 所有 `Call(R)` 候选不一致，`fullFailure=no_candidate`。

这不是“R AST 不存在”或“学生不会查询”。修复方式是考官侧把 P/Q 依赖与 R 一起提供给求值器，同时仍要求最终选中的 AST 实际包含 `Call(R)`。

## 4. 修正后的 C8 配置

- 训练/验证按固定种子 `Random(2026)` 打乱后 8/8 划分，不再使用 `all.take(8)`（原划分所有训练 a=0、测试 a=1）。
- 第二任务目标由独立考官规则定义：
  - `R_ref(a,b,c)=a==1 && c==1`
  - `S=R_ref OR d==1`
  不使用学生当前 R 输出定义标签。
- 全知识组 library：P、Q、R；对照组：P、Q。
- 同训练、同 heldout、同 `maxDepth=3`、`maxCandidates=20000`、同验证标准。
- 只有选中 AST 的 `usesIds` 实际含 R，才计复用成功。

## 5. C8 结果

`pack02r-eval-c8.json`：

```text
fullActivated=true
fullUsesR=true
fullHeldout=6
controlUsesR=false
controlHeldout=6
fullStatus=AMBIGUOUS
controlStatus=AMBIGUOUS
fullGenerated=2385
fullConsistent=66
rHeldoutAccuracy=8
```

- R 自身在第二任务 heldout 8/8，与独立 `R_ref` 一致。
- 全知识组选中 AST 实际调用 R，heldout 6/6。
- 禁止 R 组 heldout 6/6。
- 两组都仍 AMBIGUOUS；**复用已验证，但没有准确率优势**。不能声称“跨任务迁移带来性能提升”。

## 6. 补充冻结考试配置

`EvalExam2` 预注册 6 个世界：

| 家族 | 预注册 seeds | 实际覆盖 seed | 覆盖 |
|---|---|---|---|
| and_ac | 1101/1201/1301 | 1101 | 1 正 5 反 |
| and_ac | 1102/1202/1302 | 1102 | 1 正 5 反 |
| or_ac | 2101/2201/2301 | 2101 | 5 正 1 反 |
| or_ac | 2102/2202/2302 | 2102 | 4 正 2 反 |
| outside_b2 | 3101/3201/3301 | 3101 | 2 正 4 反 |
| and_ac（budget1） | 4101/4201/4301 | 4101 | 1 正 5 反 |

- 覆盖选择只用 heldout 正/反例，不使用学生成绩。
- 6 世界 × 5 会话 = 30 次；仍是实施者组织的冻结考试，不是独立闭卷。
- 全部 TRAIN/QUERY/HELDOUT 互斥；StudentView 不暴露 family/heldout/target。

## 7. 补充考试原始状态表

| 世界 | ACTIVE | FIXED | RANDOM-11/12/13 |
|---|---|---|---|
| eval-1101 | IDENTIFIED, 3q, act=true | BUDGET, 3q | IDENT/BUDGET/BUDGET |
| eval-1102 | IDENTIFIED, 1q, true | BUDGET, 3q | IDENT/IDENT/IDENT |
| eval-2101 | IDENTIFIED, 1q, true | IDENTIFIED, 3q, true | BUDGET/IDENT/IDENT |
| eval-2102 | IDENTIFIED, 2q, true | IDENTIFIED, 2q, true | BUDGET/IDENT/IDENT |
| eval-3101 | NO_CANDIDATE | NO_CANDIDATE | NO_CANDIDATE ×3 |
| eval-4101 | BUDGET, 1q | BUDGET, 1q | IDENT/BUDGET/BUDGET |

原始逐行记录：`pack02r-eval02-runs.json`。

## 8. 查询策略对照（补充考试）

| 策略 | 运行 | IDENTIFIED | 激活 | 平均查询 |
|---|---:|---:|---:|---:|
| ACTIVE | 6 | 4 | 4 | 1.33 |
| FIXED | 6 | 2 | 2 | 2.00 |
| RANDOM（3 seeds） | 18 | 9 | 9 | 1.50 |

- ACTIVE 在 4/6 补充世界完成辨识并激活；固定 2/6；随机 9/18。
- 无查询需求的世界未在本补充集出现：所有有效世界中 ACTIVE 的初始行为类均 >1（4/2/2/3/0/4）。因此本报告**没有**把“无需查询”与“需要查询”混为单一成功率；这是覆盖缺口，不是成功。
- outside 世界全部 `NO_CANDIDATE`，没有强制激活。
- budget1 世界 active/fixed 均耗尽，random 有 1 次侥幸 identified；保留原始结果，不据此调参。

## 9. 失败和限制

- 补充考试只有 6 世界，未达到建议 18；没有 no-query 成功世界、没有干扰/大分布世界。
- C8 全知识组与对照组都是 6/6，复用没有准确率优势；表达式深度/依赖压缩收益不能冒充迁移优势。
- `fullStatus/controlStatus` 仍 AMBIGUOUS；只证明“实际调用了 R 且 heldout 通过”。
- eval-101 原始考卷保留为不合格历史；新覆盖规则不修改 EVAL-01 成绩。
- 未实现行为类级信息增益与候选数量级信息增益的逐轮比较；报告 `UNKNOWN`。
- 未做外部独立闭卷。

## 10. 回归

- Kotlin：**324/324**；B0 173，E3 151。
- PACK-02 专项 13/13；隔离、AUTH、PACK-01、Research Console gate 测试通过。
- 学生源码仍为 `4c2e906`；EVAL-02 未修改 `ActiveLearning.kt`/学生测试。
- 原 EVAL-01 结果：`pack02r-eval01-runs.json`、`pack02r-eval01-c8.json` 保留。

## 11. 归因

- eval-101：B0 覆盖要求 + 不合格 heldout 设计，非学生预测错误。
- C8 首次失败：考官侧 library 缺少 R 的 P/Q 依赖；修复后复用成功但无优势。
- 新考试：学生代码冻结；考官负责世界、覆盖、标签、验证与统计。
- 仍不是外部独立闭卷；`independent_closed_book=not_run`。

## 12. 结论与建议

- **不建议把 C8 记为正式迁移成功**：没有优于对照组。
- **不建议恢复原 C4/C8 状态**：原任务仍 PAUSED。
- 建议补充 no-query、干扰世界和更大样本，再讨论 ACTIVE 受限验收。
- 不自动启动 PACK-03；不修改 H01 状态。

## 13. 授权消费与状态

- EVAL-02 消费事件：`ev-1793955570186-28a79f`；再次 preflight 返回 `CONSUMED`。
- C4/C8 仍 `PAUSED`；H01 Console 仍 `REVIEW`（与复核摘要 `ACCEPTED_LIMITED` 不一致，未擅自修改）。
- PACK-02 普通补丁 `1/1` 未变。
- `independent_closed_book=not_run`。

## 14. 证据路径

- `experiments/representation-evolution-e3/reports/pack02r-eval101-diagnostic.json`
- `pack02r-eval01-runs.json`
- `pack02r-eval01-c8.json`
- `pack02r-eval02-runs.json`
- `pack02r-eval-c8.json`
- `research-console/repairs.json`、`events.jsonl`、`history/`

