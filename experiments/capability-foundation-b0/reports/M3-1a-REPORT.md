# B0 M3-1a 报告

```text
子任务: M3-1a
状态: complete
基线 SHA: f832d6a4cb167cee6b739ebe5307f3d8b5910f73（M2c-1b）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 130/130；新增 9/9；失败 0；合计 139/139
新文件/修改文件:
  src/main/kotlin/logic/capability/CapabilityComposer.kt
  src/main/kotlin/logic/capability/CapabilityRegistry.kt
  src/main/kotlin/logic/capability/CapabilityActivationService.kt
  src/test/kotlin/logic/capability/CapabilityComposerTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M3-1a-REPORT.md
关键语义及接口: CapabilityComposer.compose(ComposeRequest) → 候选列表 + 预算原因
失败用例/未实现/已知边界: 公开样本筛选、歧义判定、候选注册在 M3-1b/1c；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M3-1b
```

## 做了什么

- `CapabilityComposer.compose(ComposeRequest)`：从输入参数、Record 顶层字段、公开常量、已激活能力生成候选。
- 逐层组合：`not / and / or / eq / call`；不做 `if/let/ref`，不做交换律归一化，不做语义等价合并。
- 每个候选先按 canonical AST 哈希去重，再用 `CapabilityTypeChecker` 做完整类型检查（含 call 的版本/哈希/参数锁定）。
- 预算：`maxDepth`、`maxNodes`、`maxCandidates`、`maxCallsPerCapability`；返回 `reason`（`candidate_limit` / `depth_limit`）与 `nodeLimitSkipped`。
- 输出按 `(节点数, canonical)` 稳定排序；相同输入产生相同顺序与哈希。
- `CapabilityRegistry.activeCapabilities()`：只返回“已激活 + 验证 pass + 未撤销 + 依赖链健康”的能力快照，供组合器输入。

## 输入契约

- 目标输入/输出签名；
- 公开可见常量列表（如颜色、形状值）；
- 已激活能力快照（由宿主从注册表取得）；
- 搜索预算。

**不包含** hidden oracle、目标 AST、留出测试答案；组合器无法接触考试真值。

## 测试

| 用例 | 覆盖 |
|---|---|
| N01 | 从字段与公开常量生成 `color == red`，并生成 AND 组合；空库不出现 call |
| N02 | 同一请求两次运行：候选哈希、顺序、生成数一致 |
| N03 | 所有候选通过类型检查，节点数不超限 |
| N04 | 候选按哈希唯一 |
| N05 | `maxCandidates=1` → 1 个候选、`candidate_limit`、exhausted |
| N06 | `maxNodes` 过小 → 丢弃超限组合但正常返回 |
| N07 | 已激活能力生成 pinned call，依赖锁定 `(id,version,contentHash)` |
| N08 | 同 ID 两个版本分别被锁定，不默认取最新 |
| N09 | 撤销后 `activeCapabilities()` 为空，候选不再引用该能力 |

## 未做 / 已知边界

- 不评估公开样本、不判歧义、不拒答；这些是 M3-1b。
- 不注册候选、不验证、不激活；这些是 M3-1c。
- 组合器只做“按类型重建”，不实现 SMT/e-graph/搜索剪枝优化；候选空间由预算主动限制。
- `if/let/ref` 未纳入候选生成；后续如需扩展，必须先证明不会改变短路/错误优先级。
- 来源只标 `composer_bounded_search`；是否可归因为 `student_composed` 在 M3-1c 按实际注册与验证流程判定。
- 独立闭卷 `not_run`；139/139 是实现者自测。
