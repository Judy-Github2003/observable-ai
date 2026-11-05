# PACK-02R-ACTIVE-01 主动辨识与自主学习实验报告

**任务：** PACK-02R-ACTIVE-01  
**性质：** 探索性主动学习实验  
**状态：** REVIEW（实施完成并消费 ACTIVE 授权，未宣称独立验收）  
**代码/测试提交：** `bedc1486834377d2ef83a65a57971cb8886d7788`  
**测试：** Kotlin 320/320（B0 173 + E3 147）；PACK-02 专项 13/13  
**独立闭卷：** `not_run`

---

## 1. 正式专项授权与 preflight

- 使用既有 `research-console/repairs.json` 专项命名空间登记 `PACK-02R-ACTIVE-01`，未修改 `tasks.json`，未重置 PACK-02 补丁额度。
- 授权事件：`ev-1793896470423-6a21a7`。
- 正式门禁：

```text
preflight --task PACK-02R-ACTIVE-01
AUTHORIZED_REPAIR: PACK-02R-ACTIVE-01
```

- 原 GATE/AUTH 授权历史保留；AUTH 仍是 `CONSUMED`。
- 完成后消费 ACTIVE 授权：`ev-1793896595870-1b09a4`；再次 preflight 返回 `CONSUMED`（见第 13 节）。

## 2. 修改文件

- `src/main/kotlin/logic/e3/pack02/ActiveLearning.kt`
  - 27 点世界生成器（a,b,c ∈ {0,1,2}）。
  - 候选保存、行为类、区分查询、冻结、更新、停止、B0 激活。
  - 固定/随机查询基线。
- `src/test/kotlin/logic/e3/pack02/ActiveLearningTest.kt`
  - 隔离、候选更新、激活、负例、基线测试。
- `tools/research-console/register_active.py`
  - 一次性专项授权登记。
- `research-console/repairs.json` / `events.jsonl` / `history/`
  - 授权审计。
- 实验 trace：
  - `experiments/representation-evolution-e3/reports/pack02r-active-run.json`
  - `pack02r-active-baseline.json`
  - `pack02r-active-c8.json`
  - `pack02r-active-statuses.json`

未修改 C4/C8/H01 状态、原 PACK-02 样本或 B0/A1/C1/PACK-01。

## 3. 新实验世界与数据隔离

- 输入域：`a,b,c ∈ {0,1,2}`，27 个组合。
- `ActiveWorlds.generate(seed, family)` 用种子机械打乱后划分：
  - TRAIN 6 条带标签；
  - HELDOUT 6 条，学习结束前不给学生会话；
  - QUERY 15 条，只给输入、不给标签。
- 三组按 `(a,b,c)` 严格互斥；测试断言无交集。
- 不同 seed 产生不同实例（seed 7 vs 8 至少一组不同）。
- 目标函数机械计算；学生 API 不暴露 target AST/标签/世界类型。
- 原始 PACK-02 的 a,b,c∈{0,1} 8 点数据和状态完全未改。

## 4. 真实候选生成

主实验：`world-7-and_ac`。

- `CapabilityComposer` 生成 **1525** 个表达式，`reason=depth_limit`（未触发 candidate_limit）。
- 经过训练一致性和声明家族过滤后：**81 个候选**，**4 个行为类**。
- 候选只使用已激活 P/Q 调用与布尔组合；不注入目标 AST；不使用 heldout 标签。
- 初始候选预测签名覆盖 QUERY 池。

## 5. 主动查询策略

- 对每个未使用 QUERY 输入，按存活候选的预测分组；
- 优先最小预测组更大的输入（更均衡）；并列时用分组数和固定键排序；
- 查询预算固定为 3；
- 只有冻结候选、输入、预测、证据版本后才调用 Oracle；
- 每轮记录候选 canonical、输入、预测、标签来源。

## 6. 完整多轮学习轨迹

| 轮次 | 输入 | Oracle 标签 | 查询前候选 | 查询后候选 |
|---|---|---|---:|---:|
| 0 | (1,1,0) | false | 81 | 49 |
| 1 | (1,0,1) | true | 49 | 8 |

- 第 1 次查询排除与 `(1,1,0)=false` 矛盾的候选；
- 第 2 次查询在剩余候选中产生最大预测分裂，标签 true；
- 两轮后从 4 个行为类降到 **1 个**，剩余 8 个行为等价候选。

查询输入不属于 TRAIN 或 HELDOUT；没有把 HELDOUT 标签作为查询反馈。

## 7. 候选更新前后

```text
初始：81 candidates / 4 classes
Q0 后：49 candidates / ? classes
Q1 后：8 candidates / 1 class
```

最终行为类唯一；在声明的候选家族与 QUERY 域内辨识完成。  
8 个候选是同一行为类内的语法不同/等价表达，不能声称唯一 AST。

## 8. 最终状态分布

| 场景 | 状态 | 激活 |
|---|---|---|
| 主动 and_ac（seed 7, budget 3） | `IDENTIFIED` | 是 |
| 固定查询基线（同一世界） | `BUDGET_EXHAUSTED` | 否 |
| 候选家族外 `outside_b2` | `NO_CANDIDATE` | 否 |
| 搜索上限 1 | `SEARCH_LIMITED` | 否 |
| 预算 1 | `BUDGET_EXHAUSTED` | 否 |

`[CONFIRMED]` 由 `pack02r-active-run.json`、`pack02r-active-baseline.json`、`pack02r-active-statuses.json` 记录。

## 9. 新能力验证与激活

- 选定候选 body 来自实际生成候选，不是开发者注入。
- B0：propose → heldout 验证套件（6 条）→ activate。
- `ActiveActivation(active=true)`；定义 id `pack02r.active.r`；heldout 6/6。
- 来源标为 `active_session` / `student_composed`。
- 没有把“有限域唯一”夸大为开放世界普遍正确。

## 10. C8 开发预实验

- 原 C8 保持 `PAUSED`，没有改状态。
- 仅做开发用 heldout 复用检查：新 seed 11、同 `and_ac` 家族，使用已激活 R 的 body；
- 结果：**6/6** correct；记录在 `pack02r-active-c8.json`。
- 这不是原 C8 的正式跨任务迁移验收；没有第二任务结构/禁止 R 对照的完整比较，不能宣称 C8 完成。

## 11. 与基线比较

同一世界、同预算 3、同候选集：

| 策略 | 轮次 | 候选 | 行为类 | 最终状态 | 激活 |
|---|---:|---:|---:|---|---|
| ACTIVE（均衡分裂） | 2 | 81→8 | 4→1 | IDENTIFIED | 是 |
| FIXED（固定顺序） | 3 | 81→77 | 4→3 | BUDGET_EXHAUSTED | 否 |

在本次单世界上，主动策略用更少查询达到唯一行为类并激活；固定基线耗尽预算仍未辨识。该结果不能外推为普遍优势。

## 12. 测试结果

- 新增 ACTIVE 测试 4/4：
  - 世界不重叠/种子可复现；
  - 冻结与候选更新；
  - 合法激活；
  - 家族外/搜索截断/短预算不伪造激活；
  - 固定基线。
- PACK-02 专项：**13/13**。
- Kotlin 全量：**320/320**，B0 173，E3 147，失败 0。
- AUTH 认证链测试、PACK-01、Research Console 测试仍通过（无生产 B0 修改）。

## 13. 授权消费与状态

- ACTIVE 授权完成消费后写入独立 repair 记录；不改变 C4/C8/H01/PACK-02 任务状态。
- C4/C8：仍 `PAUSED`。
- H01：Console 实际为 `REVIEW`；复核摘要称 `ACCEPTED_LIMITED`，未擅自修改。
- PACK-02：`PAUSED`，普通补丁 `1/1` 未变。
- 独立闭卷：`not_run`。
- 未启动 EVAL 或 PACK-03。

## 14. 失败、限制与建议

**限制：**

- 只实验了一个成功家族（and_ac）和一个 seed；不足以证明通用主动学习。
- 候选家族过滤掉了 raw Eq，避免 C4 的旧语法膨胀；这是有界家族，不是完整 IR。
- Oracle 是进程内测试 Oracle，不是独立外部服务。
- 8 个剩余候选行为等价，不能声称唯一表达式。
- C8 只是开发用 heldout 检查，不是正式迁移。
- 没有独立闭卷、没有第二任务结构对照、没有真实模型训练。
- H01 状态不一致仍需外部裁决。

**建议：**

- 进入审查，不自动 EVAL。
- 下一步应验证多 seed/多家族、独立 Oracle 和 C8 第二任务；本报告不宣称这些已完成。

---

**报告状态：** 实施完成，待审查；不是正式独立验收。  
**停止条件：** 不启动 ACTIVE 后续、EVAL 或 PACK-03。


