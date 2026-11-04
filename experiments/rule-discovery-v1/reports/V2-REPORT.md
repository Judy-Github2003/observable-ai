# V2 规则自主发现：开发报告

- 日期：2026-11-04
- 实现：DeepSeek Harness（本会话直接实现）
- 任务书：[SPEC.md](../SPEC.md)
- 状态：**M1–M3 完成；公开开发测试通过；独立闭卷 `not_run`**

## 1. 交付摘要

| 项 | 结果 |
|---|---|
| 新增内核 | `src/main/kotlin/logic/discovery/` 8 个文件 |
| 新增测试 | 15 项（E1–E5、边界、性质），全套 **26/26 通过** |
| V1 回归 | 原 11 项测试全过，未修改 V1 源码 |
| 公开多世界 | 30 个世界，**30/30 晋升且与隐藏规则等价**，新对象 **200/200** |
| 平均查询 | 1.47（最多 3） |
| 拒答场景 | E2 `insufficient_evidence`；E3 `conflict`，均未晋升 |
| 教师调用 | 0（全程本地 oracle） |
| 独立闭卷 | **not_run**（等待外部审查者） |

## 2. 实现要点

1. **候选枚举**：只从 `feature_schema` 生成单条件/双条件 AND；规范化后按 hash 去重、稳定排序。
2. **三值一致性**：缺属性 `unknown`；AND 中已知 `false` 优先；未知预测不能解释已观察标签。
3. **主动查询**：对每个未问对象计算候选预测分组，按分组数打分，同分按对象 ID；决策在任何标签进入前写入 `query_selected`。
4. **证据与冲突**：每条观察带 `evidence_id`；同对象正反标签 → `conflict`，两条都保留，禁止晋升。
5. **等价类晋升**：在 schema 的完整笛卡尔积上比较候选预测，只有一个等价类才晋升；否则 `insufficient_evidence`。
6. **持久化**：`state.json` 临时文件 + 原子替换；`events.jsonl` 追加；重启后规则、候选、淘汰历史可恢复。
7. **解释执行**：晋升后的 AST 由 Kotlin 解释器执行，缺属性返回 `unknown`。

## 3. E1 全程追踪

见 `e1-trace.jsonl` 与 `e1-events.jsonl`；摘要：

```text
start A=true,B=false        → 8 候选，剩 3（红 / 圆 / 红且圆）
next_query                  → C（红,方），true:1 / false:2
observe C=false             → 淘汰 红，剩 2
next_query                  → D（蓝,圆），true:1 / false:1
observe D=false             → 淘汰 圆，剩 1
finalize                    → promoted: (颜色==红色) AND (形状==圆形)
predict 红圆 / 蓝方 / 红方 / 蓝圆 → true / false / false / false
```

## 4. 多世界结果

| 指标 | 值 | 说明 |
|---|---|---|
| 世界数 | 30 | seed 20261104，2–3 个字段、1–2 个条件 |
| 晋升率 | 30/30 | 有限假设空间内 |
| 规则等价 | 30/30 | 外部 Python oracle 在完整域上比较 |
| 新对象预测 | 200/200 | 训练中未出现的对象 ID |
| 平均查询 | 1.47 | 主动选择区分性对象 |
| ID 记忆基线 | 0.0 | 未见 ID 只能 unknown |
| 多数类基线 | 0.745 | 全答 false；说明只看 accuracy 会被负例比例抬高 |

原始数据：`public-30.json`。

## 5. 失败与拒答（不是只报成功）

| 场景 | 结果 | 意义 |
|---|---|---|
| E2 查询池为空 | `unknown / insufficient_evidence`，列出多个等价类 | 不因“选最简单”而假装学会 |
| E3 同对象先 true 后 false | `observe=conflict`，`finalize=conflict`，不晋升 | 保留双方证据，不取最后写入 |
| 非法 schema / 重复 evidence / 未知对象 | `error / invalid_input` | 参数错误与未知信息分开 |
| 未晋升就 predict | `unknown / rule_not_promoted` | 不偷用未固化规则 |
| 缺属性 predict | `unknown / missing_attribute` | 拒答而非猜 false |

## 6. 归因（本轮最重要）

| 能力 | 归属 |
|---|---|
| DSL、枚举、查询打分、晋升判定、存储与审计 | `designer_programmed` |
| 本次每个世界学到的具体规则 | `student_induced` |
| 教师提供的逐条标签 | `teacher_observation` |
| 独立闭卷 | `external_audit`，**not_run** |

**不能声称**：学生发明了归纳算法、能开放世界学习、能理解自然语言、已通过独立验证。

## 7. 已知风险与未完成

1. **世界偏容易**：查询池覆盖完整有限域，主动查询几乎总能快速唯一化；尚未测“部分池 + 噪声标签 + 超纲规则”。
2. **同模型审查**：实现与公开演示由同一模型完成；真正独立闭卷必须由外部审查者用仓库外世界与独立 oracle 执行。
3. **等价判定是有限域穷举**：字段/值域变大时组合爆炸；当前只适合小规模受控世界。
4. **冲突恢复未实现**：E3 只验证“冲突不晋升”，没有证据撤回后的恢复流程。
5. **查询策略是启发式**：按分组数，不保证最优信息增益。
6. **V1 遗留**：`plan.run` 的原子性问题未修。

## 8. 给独立审查者的接口

- 入口：`java -cp "build/install/logic-learner/lib/*" logic.discovery.DiscoveryCliKt - <state-dir>`
- 契约：[CONTRACT.md](../CONTRACT.md)
- 建议闭卷维度：新字段/新值、三字段含无关属性、部分查询池、冲突与撤回、超纲规则（应判 `out_of_hypothesis_class`）、重启后预测、事件完整性。
- 闭卷结果出来前，本报告所有成绩只能标为“公开开发测试”。
