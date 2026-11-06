# PACK-02R-EVAL-01 实施报告

**任务：** PACK-02R-EVAL-01（EVAL-A 隔离 + EVAL-B 多世界考试 + C8 开发对照）  
**状态：** REVIEW（实施完成并消费 EVAL 授权，未宣称独立验收）  
**STUDENT_FREEZE_SHA：** `4c2e9063b87660fa77d79743caec9a74231619f7`  
**EVAL 代码/结果提交：** `e979a0717145e561b07eb2792c8933264ca8a657`  
**测试：** Kotlin 322/322（B0 173 + E3 149）  
**独立闭卷：** `not_run`

---

## 1. 授权与 preflight

- 使用既有 `repairs.json` 专项命名空间登记 `PACK-02R-EVAL-01`；未修改 `tasks.json`，未重置 PACK-02 普通补丁。
- 授权事件：`ev-1793954228042-9dbaaa`。
- 正式 preflight：`AUTHORIZED_REPAIR: PACK-02R-EVAL-01`。
- GATE/AUTH/ACTIVE 的授权历史保持 `CONSUMED`；未恢复、未重复消费。
- EVAL 完成后消费本次授权（见第 13 节）。

## 2. 隔离前的漏洞和修复

**修复前：** `ActiveSession` 持有完整 `ActiveWorld`，World 内含 `family`、`heldout` 标签、`oracle`、含家族名的 world id。学生算法虽未读取，但正常 Kotlin API 可直接访问。

**修复后：**

- `ActiveSession` 只持有 `StudentView` + `QueryController`，不再接收 `ActiveWorld`。
- `StudentView` 仅含公开训练、无标签 QUERY 池、P/Q 引用、预算，匿名 `session-<seed>`；无 family/target/heldout。
- `QueryController` 私有持有目标标签；`freeze` 后才允许 `reveal`；检查授权池、预算、重复、冻结对象身份。
- `ExamEvaluator` 考官侧持有 heldout/target，完成验证与 B0 激活；学生不能读取 heldout 明细。
- `FrozenQuery` 无公开构造器/`copy`，Controller 以对象身份验证。

## 3. 隔离测试

`ActiveLearningTest` 覆盖：

- StudentView 字段不包含 family/heldout/target，`worldId` 为 `session-7`，不含 `and_ac`；
- 未冻结不能 reveal；外部 Controller 的 FrozenQuery 被拒绝；
- 非法输入、重复输入、heldout 输入、超预算均拒绝；
- 冻结后预测不被改写；
- 学生 `ActiveSession` 只产生候选结果，激活只能由 `ExamEvaluator` 完成；
- 原成功案例仍可运行（seed 7：IDENTIFIED，考官激活 true）。

## 4. STUDENT_FREEZE_SHA

`4c2e9063b87660fa77d79743caec9a74231619f7`（`eval: isolate student view and freeze ACTIVE learner`）。

该提交包含隔离后的 `ActiveLearning.kt`、隔离测试、冻结时回归结果。考试期间没有修改学生候选生成、筛选、查询选择、停止或验证申请算法。

## 5. 考试配置及种子

考试在冻结后执行，配置预先固定在 `EvalExam.specs`：

| 世界 | seed | 家族（考官侧） | 预算 |
|---|---:|---|---:|
| eval-101 | 101 | and_ac | 3 |
| eval-102 | 102 | and_ac | 3 |
| eval-201 | 201 | or_ac | 3 |
| eval-202 | 202 | or_ac | 3 |
| eval-301 | 301 | outside_b2 | 3 |
| eval-401 | 401 | and_ac | 1 |

策略：ACTIVE 1 次；FIXED 1 次；RANDOM 3 个预登记种子（1/2/3）。共 6 世界 × 5 会话 = 30 次运行。

实际成本低于建议的 18 世界；未在看到成绩后增删世界或挑选种子。26/27 点世界未全部覆盖是限制，不是成功声明。

## 6. TRAIN/QUERY/HELDOUT 隔离证据

- 27 个输入预先划分为 TRAIN 6、HELDOUT 6、QUERY 15，按完整 `(a,b,c)` 键严格互斥。
- StudentView 只含 TRAIN 和 QUERY；不含 HELDOUT。
- QueryController 只允许 QUERY 键；heldout 输入会被拒绝。
- 世界 `family`/target/heldout 仅在考官对象中；学生视图匿名为 `session-<seed>`。
- 原 PACK-02 的 8 点训练/留出数据完全未改。

## 7. 各世界完整状态表

| 世界 | ACTIVE | FIXED | RANDOM-1 | RANDOM-2 | RANDOM-3 |
|---|---|---|---|---|---|
| eval-101 | IDENTIFIED, 0q, act=false | IDENTIFIED, 0q, false | IDENTIFIED | IDENTIFIED | IDENTIFIED |
| eval-102 | IDENTIFIED, 1q, act=true | BUDGET, 3q | BUDGET | BUDGET | IDENTIFIED, 2q, true |
| eval-201 | IDENTIFIED, 0q, true | IDENTIFIED, true | IDENTIFIED | IDENTIFIED | IDENTIFIED |
| eval-202 | IDENTIFIED, 0q, true | IDENTIFIED, true | IDENTIFIED | IDENTIFIED | IDENTIFIED |
| eval-301 | IDENTIFIED, 0q, act=false (5/6) | same | same | same | same |
| eval-401 | IDENTIFIED, 1q, true | BUDGET, 1q | BUDGET | BUDGET | BUDGET |

`act=false` 表示考官验证/激活失败，不是学生自行宣称成功。`eval-101` 的激活失败是 B0 验证层拒绝，尽管预测 heldout 6/6；该不一致保留为失败记录。

## 8. ACTIVE/FIXED/RANDOM 汇总

| 策略 | 运行 | IDENTIFIED | 激活 | 平均查询 |
|---|---:|---:|---:|---:|
| ACTIVE | 6 | 6 | 4 | 0.33 |
| FIXED | 6 | 4 | 2 | 0.67 |
| RANDOM（3 seeds） | 18 | 13 | 7 | 0.61 |

ACTIVE 在 6/6 世界完成行为类归并；FIXED 2 个世界预算耗尽；RANDOM 5 个世界预算耗尽。该样本小，不能外推为普遍优势。

## 9. 成功辨识率与错误激活

- ACTIVE 辨识：6/6；激活：4/6。
- FIXED 辨识：4/6；激活：2/6。
- RANDOM 辨识：13/18；激活：7/18。
- **错误激活（激活成功但 heldout 不完美）：0 次。**
- `eval-301`（家族外 `b==2`）被学生判为 IDENTIFIED，但 heldout 5/6，考官拒绝激活；这是错误辨识，不是错误激活。
- `eval-101` 预测 6/6 但 B0 验证失败，保留为验证层问题/限制。

## 10. 查询效率

- ACTIVE 平均 0.33 次/世界；FIXED 0.67；RANDOM 0.61。
- ACTIVE 在 eval-102 用 1 次查询完成；FIXED/RANDOM-1/2 用 3 次仍预算耗尽。
- 查询数少并不自动代表更好：部分世界初始候选只有一个行为类（0 查询），不能归功于主动选择。

## 11. 候选数量 vs 行为类分裂

**UNKNOWN/未完成。** 考试 trace 保存了初始/剩余候选数和初始/剩余类数，但没有逐轮保存“按候选数量”与“按行为类”两种分裂收益。因此不能声称二者一致或不一致；这是后续分析缺口。

## 12. C8 第二任务设计与结果

设计：Task1 学习 `R = P AND Q`；Task2 使用 R 与新增 d 组合：

```text
S = R(a,b,c) OR (d == 1)
```

全知识组候选库只给已激活 R；对照组只给 P/Q。相同训练/heldout/预算。

实际 `pack02r-eval-c8.json`：

```text
fullActivated=true
fullUsesR=false
fullHeldout=0
controlUsesR=false
controlHeldout=6
fullStatus=AMBIGUOUS
controlStatus=AMBIGUOUS
fullFailure=no_candidate
```

最终实际记录为 `fullStatus=AMBIGUOUS`、`fullFailure=no_candidate`；full 组未产生使用 R 的候选，control 组 6/6 但为 AMBIGUOUS。**C8 未验证**；不能把 ACTIVE-01 的同家族 6/6 当作替代。原 C8 保持 PAUSED。

## 13. 原始 trace 与授权消费

- `experiments/representation-evolution-e3/reports/pack02r-eval-runs.json`
- `experiments/representation-evolution-e3/reports/pack02r-eval-c8.json`
- `experiments/representation-evolution-e3/reports/pack02r-active-run.json`（ACTIVE 开发轨迹）
- `research-console/repairs.json`、`events.jsonl`、`history/`

EVAL 授权消费事件：`ev-1793954725702-4ebd7f`；再次 preflight 返回 `CONSUMED`。不改变 C4/C8/H01/PACK-02 任务状态。

## 14. 完整回归

- Kotlin：**322/322**；B0 173，E3 149。
- PACK-02 专项：13/13（含 ACTIVE 开发测试）。
- AUTH 认证链、PACK-01、Research Console gate 测试通过。
- 工作树/Workstate 同步：`ok=true`。
- 独立闭卷：`not_run`。

## 15. 归因

- 隔离/评测器：实施者（DeepSeek）完成。
- 学生算法：冻结于 `4c2e906`；考试期间未修改。
- 基础能力、Composer/Filter、B0 验证/激活：宿主/开发者已有设施。
- 考试标签、heldout、激活判定：考官/评测器；不是学生自主发现。
- 外部独立审查：尚未进行。

## 16. 失败案例

1. C8 full 未选出使用 R 的候选；control 6/6，但 C8 未验证。
2. eval-301 家族外目标被误判 IDENTIFIED（heldout 5/6）；考官拒绝激活。
3. eval-101 预测 6/6 但 B0 验证失败；原因未定位。
4. 只覆盖 6 世界，OR/large 家族覆盖不足，不能声称通用。
5. 逐轮候选数/行为类信息增益未记录。

## 17. 是否建议 ACTIVE 受限验收

**不建议直接正式验收。** 可进入外部监督审查；需要补充世界数、修复 C8、解释 eval-101 验证失败，并保留 `independent_closed_book=not_run`。

## 18. 是否建议恢复原 C4/C8

**不建议立即恢复。** 原 C4/C8 仍 PAUSED；本轮 C8 未验证，不能推动原状态变更。可以恢复“研究设计”讨论，不恢复任务执行授权。

## 19. 后续研究问题

- 为什么 eval-101 的 6/6 候选被 B0 验证拒绝？
- 为什么 C8 的全知识组没有选中 R？
- 18 世界、更多 OR/干扰世界下的策略差异。
- 行为类级信息增益与候选数量级信息增益的偏差。
- 独立外部闭卷/外部 Oracle。


