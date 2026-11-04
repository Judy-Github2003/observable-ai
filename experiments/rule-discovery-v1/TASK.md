# V2 规则自主发现：给 DeepSeek 的实施任务（M1–M3）

> 本文件是执行摘要；**完整契约以同目录 `SPEC.md` 为准**。两者冲突时，以 `SPEC.md` 的约束更严者为准。
> 作者：Codex，2026-11-04。原因：把研究者批准的 V2 任务书转成可执行、可验收的最小交付。

## 目标

在**不修改 V1 行为**的前提下，新增独立的规则发现模块：学生只看得到对象属性、公开的有限假设语法和逐条教师标签，自己完成

```text
枚举候选 → 用已有正负样本筛候选 → 主动选最有区分度的对象提问
→ 吸收标签并淘汰候选 → 在可辨识时晋升为可执行规则 → 关闭教师预测新对象
```

**唯一要证明的事**：目标规则事先不在规则库里时，学生能否在有限假设空间内，靠证据把它唯一确定下来，而不是靠硬编码或教师代劳。

## 硬约束（违反即任务失败）

1. **不得硬编码目标规则或答案**：实现里不能出现“红色 AND 圆形”这类具体条件、具体颜色值或训练对象 ID 的分支。所有取值来自输入 schema。
2. **不得改旧文件**：不要修改 `BatchStudent.kt`、`BatchModel.kt`、`BatchCli.kt`、`AuditTrail.kt`、`Main.kt`、`build.gradle.kts`、`rules.json`、`curriculum_batch.py`。只新增文件；确实需要改旧文件时，在交付里说明，不要擅自改。
3. **信息隔离**：学生进程的输入、状态、日志里不能出现隐藏规则、期望标签、完整 oracle 表。标签只能通过 `discovery.observe` 一条一条进入。
4. **不偷看**：`discovery.next_query` 的决策必须在拿到该对象标签之前完成；选择依据要落盘。
5. **不伪造**：候选淘汰、冲突、不可辨识、晋升失败都要真实记录；不准只保留最终胜出者。
6. **归因**：候选生成算法、DSL、查询策略标 `designer_programmed`；本次学到的具体规则标 `student_induced`；教师标签标 `teacher_observation`。
7. **不改 V1 成绩、不重写旧报告**。

## 交付物（全部新增）

```text
src/main/kotlin/logic/discovery/
  DiscoveryModel.kt        数据类：FeatureSchema / Example / Hypothesis / Observation / LearningState / Outcome
  HypothesisGenerator.kt   受限 DSL 枚举、规范化、稳定排序、候选集合 hash
  ConsistencyEngine.kt     三值求值、样本一致性、冲突检测、等价类判定（有限域穷举）
  QueryPlanner.kt          查询前打分：候选预测分组、区分度、稳定 tie-break
  RuleInterpreter.kt       安全执行已晋升 AST（缺属性 → unknown）
  DiscoveryStore.kt        世界状态持久化（原子写）、事件追加
  DiscoveryCli.kt          独立入口 main(args)，JSONL 请求/响应
  DiscoveryAudit.kt        学习事件（见下）

src/test/kotlin/logic/discovery/
  DiscoveryScenarioTest.kt E1–E5 + 非法参数 / 重复 evidence / 重启
  DiscoveryPropertyTest.kt 有限域穷举、候选规范化、三值逻辑边界

experiments/rule-discovery-v1/
  README.md                问题定义、支持范围、运行方式
  CONTRACT.md              CLI、状态文件、事件字段、错误码
  public-fixtures/         E1–E5 的公开输入与期望（不含隐藏闭卷答案）

tools/rule_discovery_v1.py 公开演示驱动器（本地确定性 oracle，逐步打印追踪）
runs/rule-discovery-v1-20261104/DELIVERY.md  交付说明（≤80 行）
```

## CLI 契约（可等价，但必须稳定）

独立入口，不改 `Main.kt`：

```text
java -cp build/install/logic-learner/lib/* logic.discovery.DiscoveryCliKt <requests.jsonl> <state-dir>
```

- 每行一个请求：`{"task_id":"t1","world_id":"w1","op":"discovery.start","args":{...}}`
- 每行一个响应：`{"task_id":"...","world_id":"...","op":"...","status":"ok|unknown|conflict|error","result":{},"reason":null,"trace_id":"..."}`
- 所有事件追加到 `<state-dir>/events.jsonl`；状态写到 `<state-dir>/worlds/<safe-world-id>/state.json`（原子替换）。
- 重启后同一 state-dir 能继续 `hypotheses/next_query/observe/finalize/predict/status`。

操作：

| op | 关键 args | 成功 result |
|---|---|---|
| `discovery.start` | `feature_schema`, `known_examples`, `query_pool` | `world_id`, `candidate_count`, `state` |
| `discovery.hypotheses` | 无 | `candidates`（规范化表达式）、`count`、`candidate_set_hash`、`evidence_ids` |
| `discovery.next_query` | 无 | `object_id`、`features`、`score`、`groups`、`candidate_count` |
| `discovery.observe` | `object_id`, `label`（true/false）, `evidence_id` | `accepted`, `candidate_count`, `eliminated` |
| `discovery.finalize` | 无 | `status=promoted`、`rule`（AST）、`rule_id`、`evidence_ids`；否则 `unknown/conflict` + reason |
| `discovery.predict` | `object_id` 或 `features` | `label`（true/false/unknown）；规则未晋升 → `unknown/rule_not_promoted` |
| `discovery.status` | 无 | 候选数、证据数、规则状态、来源、算法版本 |

错误码至少区分：`invalid_input`、`insufficient_evidence`、`no_discriminating_query`、`conflicting_evidence`、`rule_not_promoted`、`internal_error`。

## 学习语义（必须按 SPEC 实现）

- **假设空间**：单条件 `field == value`；双条件 `(f1==v1) AND (f2==v2)`（不同字段）。不支持 OR / 否定 / 嵌套 / 数值阈值。
- **枚举范围**：只来自 `feature_schema` 的字段与值；去重、规范化（子句排序、字段名排序）、稳定排序、集合 hash。
- **三值逻辑**：缺字段 → 单条件 `unknown`；AND 中任一 `false` → `false`；否则含 `unknown` → `unknown`。
- **筛选**：与任一已观察标签不一致的候选淘汰，并记录是哪条 evidence；同一对象同一标签出现正反证据 → `conflict`，保留双方，禁止晋升。
- **等价类**：在 `feature_schema` 的完整笛卡尔积上比较候选预测；预测完全一致视为等价（可给等价证明），否则视为不同候选。
- **晋升条件**（全部满足）：无未解决冲突；至少一条 true 和一条 false 观察；剩余候选恰为一个等价类；记录 evidence_ids、淘汰历史、scope、规则版本。
- **查询策略**：对每个可查询对象，计算每个可行候选的预测，按“能分出的组数/最大组大小”等确定性分数排序；同分按对象 ID 升序。没有对象能减少歧义时返回 `no_discriminating_query`，并说明需要什么类型的反例。
- **预测**：只用晋升后的 AST，不读教师、不读训练对象映射；缺属性 → `unknown`。

## 事件（写入 events.jsonl）

`observation_received`、`hypotheses_generated`、`hypothesis_eliminated`、`query_selected`、`rule_promoted`、`rule_executed`、`rule_abstained`，并带：

`world_id`、`task_id`、`trace_id`、`rule_id`（适用时）、`evidence_ids`、`candidate_set_hash`、`source`、`algorithm_version`、`timestamp`。

`hypothesis_eliminated` 必须写明被哪条 evidence 淘汰；`query_selected` 必须写查询前的候选分组与分数。

## 测试要求（公开开发测试）

`DiscoveryScenarioTest` 至少覆盖：

- **E1**：A=true、B=false，C/D 未询问；此时候选 ≥3 且非等价；`next_query` 选中 C 或 D；喂入后候选收缩；finalize 晋升 `红色 AND 圆形`；predict 新对象正确。
- **E2**：查询池为空或全部不可区分 → finalize 返回 `insufficient_evidence`，列出剩余候选，不晋升。
- **E3**：同一对象先 true 后 false → `conflict`，不晋升，两条证据都保留。
- **E4**：换世界（新 ID、新颜色/形状值、第三个无关属性、样本顺序变化）→ 仍能学到新世界的规则；无关属性不得进入最终规则。
- **E5**：先给让错误候选合理的样本，再给反例 → 淘汰记录引用正确 evidence，历史可查。
- **边界**：非法 schema、重复 evidence_id、未知对象、缺字段、重启后状态一致、日志事件齐全。

`DiscoveryPropertyTest`：有限域穷举验证“候选等价判定”和“三值求值”与独立实现一致。

测试不得调用网络、不得读取隐藏文件；用固定种子/固定 fixture。

## 交付说明

在 `runs/rule-discovery-v1-20261104/DELIVERY.md` 写：

1. 新增文件清单与各自职责；
2. CLI 与状态目录示例；
3. 你自行推演过的关键分支（不用声称跑过测试）；
4. 尚未解决的问题与风险；
5. 明确声明：**未运行构建/测试**（沙箱限制），由宿主代跑。

## 不要做

- 不运行 shell / 构建 / 测试（沙箱会失败，宿主代跑）。
- 不写 HTML 报告、不做闭卷考试、不生成 30 个世界（那是审查者的 M4）。
- 不修改旧 V1 代码与成绩。
- 不把 SPEC 里的示例答案写进实现。
