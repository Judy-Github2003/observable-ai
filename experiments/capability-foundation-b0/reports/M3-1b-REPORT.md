# B0 M3-1b 报告

```text
子任务: M3-1b
状态: complete
基线 SHA: d9d7c8e705865b935baaf07c3a1b5998bfe20f0a（M3-1a）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 139/139；新增 8/8；失败 0；合计 147/147
新文件/修改文件:
  src/main/kotlin/logic/capability/CandidateEvidenceFilter.kt
  src/test/kotlin/logic/capability/CandidateEvidenceFilterTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M3-1b-REPORT.md
关键语义及接口: CandidateEvidenceFilter.filter(inputs,candidates,examples,queryValues,budget,searchExhausted)
失败用例/未实现/已知边界: 注册与留出集在 M3-1c；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M3-1c
```

## 做了什么

- `CandidateEvidenceFilter`：对每个候选执行全部公开样本，`Known` 精确比较；`Unknown` 不算命中；`Error` 直接淘汰。
- 输入校验：样本为空、同输入不同标签（`conflicting_evidence`）、schema 外字段（`unknown_attribute`）、值类型不符或参数不全（`invalid_example`）。
- 搜索被候选上限截断时，调用方传 `searchExhausted=true` → `search_budget_exhausted`，不拿残缺候选声称辨识完成。
- 幸存候选在调用方给出的公开查询域上做**等价分组**：同一输出签名视为一个等价类；只有一个等价类才返回 `IDENTIFIED`，代表取节点最少、canonical 最小者。
- 多个等价类 → `AMBIGUOUS`，最多给出 3 个能区分它们的查询输入；没有查询域且幸存者多于一个时给 `insufficient_query_domain`。
- 没有一致候选 → `REFUSED` + `no_consistent_candidate`；候选为空 → `hypothesis_space_exhausted`。

## 语义

| 情形 | 结果 |
|---|---|
| 单条正例，多个候选都解释样本 | `AMBIGUOUS`，给出区分性查询 |
| 补充负例后只剩一个公开域等价类 | `IDENTIFIED` |
| 预期 false，实际 `Unknown` | 不算命中，候选被淘汰 |
| 实际 `Error` | 候选淘汰，不晋升 |
| 同输入标签冲突 | `REFUSED / conflicting_evidence` |
| 样本出现未声明字段 | `REFUSED / unknown_attribute` |
| 目标超出候选空间（如 3 变量多数函数） | `REFUSED / no_consistent_candidate` |
| 搜索撞候选上限 | `REFUSED / search_budget_exhausted` |

## 测试

| 用例 | 覆盖 |
|---|---|
| P01 | 只有一条正例 → 多个可区分候选，返回歧义与查询建议 |
| P02 | 补足三条负例后收敛到 `color==red AND shape==circle` |
| P03 | 缺字段导致 `Unknown`，不当 false，候选不通过 |
| P04 | 同输入正负冲突先于执行被拒 |
| P05 | 未声明属性 `texture` → `unknown_attribute` |
| P06 | 3 变量多数函数超出 `maxDepth=3` 候选空间 → 拒答 |
| P07 | 搜索被候选上限截断 → `search_budget_exhausted` |
| P08 | 双否定与合取在公开域上同签名 → 归为一个等价类；筛选可复现 |

## 未做 / 已知边界

- 不做注册、验证、激活；`student_composed` 归因与留出集检验在 M3-1c。
- 等价只在调用方给定的公开查询域上判定，不是全局语义等价证明；报告必须写 `bounded_equivalence`。
- 公开域最多 256 点，超过则视为“无域”，不冒充可辨识。
- 未实现自动生成区分性查询值；只从调用方给出的查询域中挑选。
- 独立闭卷 `not_run`；147/147 是实现者自测。
