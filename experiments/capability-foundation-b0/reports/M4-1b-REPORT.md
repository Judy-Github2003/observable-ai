# B0 M4-1b 报告

```text
子任务: M4-1b
状态: complete
基线 SHA: fdad6b5d3d1ce680aaaa525c62f9a41dc4d86470（M4-1a）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 160/160；新增 5/5；失败 0；合计 165/165
新文件/修改文件:
  src/main/kotlin/logic/capability/SubtreeExtraction.kt
  src/test/kotlin/logic/capability/SubtreeExtractionTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M4-1b-REPORT.md
关键语义及接口: SubtreeExtractor.extract(tasks) → ExtractHelperCandidate（helper 签名 / 来源路径 / 依赖 / 原位替换 Call）
失败用例/未实现/已知边界: 重写、等价验证、净收益在 M4-1c；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M4-1c
```

## 做了什么

- `SubtreeExtractor`：遍历至少两个任务正文的所有非根节点，按 canonical AST 哈希 + 输出类型分组。
- 只有跨 ≥2 个不同 `taskId`、且节点数达到下限的子树才成为候选；同一任务内重复出现不算独立证据。
- 安全约束：
  - 含 `Ref` 的子树一律跳过，避免捕获 `let` 局部变量；
  - 子树中的 `Arg` 作为 helper 形参显式传入；
  - 只生成“原位替换”的 `Call`，不把子树移出短路/分支位置；
  - 根节点不抽取，避免把整个任务包装成 helper。
- 候选包含：helper 定义（签名 + 正文 + contentHash）、来源 `(taskId, path)` 列表、依赖 pin、替换用 `Call`。
- 原任务定义保持不变；本步不写注册表、不改写程序。

## 测试

| 用例 | 覆盖 |
|---|---|
| S01 | 两个任务共享 `isRed(item)` → 一个候选、2 个来源、签名 `item: Record → Bool3`、替换 Call 哈希一致 |
| S02 | `And(red,circle)` 与 `And(circle,red)` 不合并；只有 red/circle 各自成候选 |
| S03 | `let` 作用域内的子树不产生含 `Ref` 的 helper；`let` 值里的公共子树仍可抽取 |
| S04 | 相同输入两次抽取的候选顺序与哈希一致 |
| S05 | 两个任务正文完全相同时，不把整段正文当作 helper，但内部公共子树仍被提出 |

## 已知边界

- 只识别 canonical AST **完全相同**的子树；语义等价但 AST 不同不合并。
- 仅覆盖 M1 的确定性节点；`List/Optional` 未实现。
- `minNodes` 默认 3，过滤掉叶子和单字段访问；这是工程阈值，不是理论结论。
- 未做类型重写、行为等价、净收益或事务发布；这些是 M4-1c，未通过前候选不得激活。
- 独立闭卷 `not_run`；165/165 是实现者自测。
