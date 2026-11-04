# 规则发现契约 v1（discovery-v1）

> 实现：DeepSeek Harness（本会话），2026-11-04。本文件描述**当前实现**的稳定接口；与 `SPEC.md` 冲突时以更严者为准。

## 入口

```text
java -cp "build/install/logic-learner/lib/*" logic.discovery.DiscoveryCliKt <requests.jsonl|-> <state-dir>
```

- 第一个参数为 `-` 时从 stdin 逐行读取，便于交互式驱动；每读一行立即输出一行响应并 flush。
- `state-dir` 由调用方指定；不同世界用不同 `world_id`，互不覆盖。

## 请求 / 响应

请求（每行一个）：

```json
{"task_id":"t1","world_id":"w1","op":"discovery.start","args":{}}
```

响应：

```json
{"task_id":"t1","world_id":"w1","op":"discovery.start","status":"ok","result":{},"reason":null,"trace_id":"trace-..."}
```

`status`：`ok | unknown | conflict | error`。未知信息用 `unknown` + `reason`，参数错误用 `error` + `invalid_input`，证据冲突用 `conflict`。

## 操作

| op | args | 成功 result |
|---|---|---|
| `discovery.start` | `feature_schema`、`known_examples`、`query_pool` | `state`、`candidate_count`、`surviving_count`、`object_count`、`query_pool_size`、`candidate_set_hash` |
| `discovery.hypotheses` | 无 | `count`、`candidates[{hash,text,rule}]`、`candidate_set_hash`、`evidence_ids` |
| `discovery.next_query` | 无 | `object_id`、`features`、`score`、`groups`、`candidate_count` |
| `discovery.observe` | `object_id`、`label`、`evidence_id` | `accepted`、`candidate_count`、`eliminated[hash]`、`candidate_set_hash` |
| `discovery.finalize` | 无 | `status:"promoted"`、`rule_id`、`rule`、`evidence_ids`、`equivalent_candidates`、`domain_size` |
| `discovery.predict` | `features` 或 `object_id` | `label:"true/false/unknown"`、`rule_id` |
| `discovery.status` | 无 | `candidate_count`、`evidence_count`、`conflict`、`promoted`、`rule_id?`、`algorithm_version` |

### 输入校验

- schema 字段非空、值域非空且不重复；
- 对象 ID 唯一；features 的字段必须在 schema 内、值在该字段值域内；
- `evidence_id` 全局唯一；同一对象同一标签不能重复观察；
- 同一对象出现 true 与 false → `conflict`，两条证据都保留。

### 晋升条件

全部满足才写 `promoted`：

1. 没有未解决的冲突；
2. 至少一条 `true` 和一条 `false` 观察；
3. 剩余候选非空；
4. 在 schema 的完整笛卡尔积上，剩余候选恰好一个语义等价类；
5. 记录 `evidence_ids`、`domain_size`、`rule_id`、`acquisition_method=student_induced`。

不满足时返回 `unknown/insufficient_evidence` 或 `conflict/conflicting_evidence`，绝不产生已晋升规则。

## 状态与审计

```text
<state-dir>/
  events.jsonl
  worlds/<sha256(world_id)[:32]>/state.json
```

- `state.json` 先写临时文件再原子替换；失败不会留下半条规则。
- `events.jsonl` 追加写入，事件类型至少包括：
  `hypotheses_generated`、`observation_received`、`hypothesis_eliminated`、`query_selected`、`rule_promoted`、`rule_executed`、`rule_abstained`。
- 每条事件带 `world_id`、`task_id`、`trace_id`、`candidate_set_hash`、`evidence_ids`、`source`、`algorithm_version`、`timestamp`。
- `query_selected` 在拿到标签前写入，包含对象、分数与候选分组。

## 错误码

| reason | 含义 |
|---|---|
| `invalid_input` | 参数、schema、对象或证据不合法 |
| `insufficient_evidence` | 存在多个非等价候选，或缺少正/负观察 |
| `no_discriminating_query` | 查询池无法再区分候选 |
| `conflicting_evidence` | 同一对象出现互斥标签 |
| `contradictory_evidence` | 所有候选都被证据淘汰（世界自相矛盾） |
| `rule_not_promoted` | 尚未晋升就调用 predict |
| `missing_attribute` | 预测输入缺少规则需要的属性 |
| `unknown_op` / `invalid_json` | 协议错误 |

## 已知限制

- 只支持单条件与双条件 AND；不支持 OR、否定、嵌套、数值阈值；
- 只在声明的有限 schema 内判定等价，不声称开放世界唯一；
- 查询策略按“预测分组数”启发式，不保证信息增益最优；
- 公开测试不是独立闭卷；闭卷状态在报告里固定为 `not_run`。
