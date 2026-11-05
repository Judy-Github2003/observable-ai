# B0 M4-1c 报告

```text
子任务: M4-1c
状态: complete
基线 SHA: d06c72a95b491f052989b8aa939b3c99079f3650（M4-1b）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 165/165；新增 5/5；失败 0；合计 170/170
新文件/修改文件:
  src/main/kotlin/logic/capability/SubtreeRewriting.kt
  src/test/kotlin/logic/capability/SubtreeRewritingTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M4-1c-REPORT.md
  experiments/capability-foundation-b0/reports/M4-REPORT.md
关键语义及接口: SubtreeRewriter.rewrite(tasks,candidate,domain,domainKind) → RewriteOutcome
失败用例/未实现/已知边界: L3 参数化抽象 not_implemented；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M5-1a
```

## 做了什么

- `SubtreeRewriter`：按 `(taskId, path)` 在原位置把公共子树替换成锁定 helper 的 `Call`，不移动子树到短路/分支之外。
- 只替换最外层出现，避免嵌套重复替换；重写版本使用 `version+1`，原版本保留。
- 类型检查：重写后的定义必须通过 `CapabilityTypeChecker`，helper 已在 resolver 中。
- 等价检查：对调用方给出的有限域逐点执行原程序与重写程序，精确比较
  `Known`（含值）、`Unknown`（含原因）、`Error`（含错误码）。
  `DomainKind.EXHAUSTIVE` 表示声明有限域；`BOUNDED` 明确写 `bounded_verified`。
- 净收益：`after = Σ重写任务 + helper 正文 + helper 签名 + λ`，`before = Σ原任务`；
  只有 `equivalence.verified && after < before` 才 `accepted`。
- 未通过时返回 `rejected_candidate` 原因（`behavior_mismatch` / `no_net_benefit`），不自动晋升。

## 发布顺序（原子性策略）

1. 先发布原程序（version 1）；
2. 发布 helper（candidate）→ 验证 → 激活；
3. 发布重写版本（version 2，钉住 helper 哈希）→ 逐个验证 → 激活。

激活门禁要求依赖已激活，因此不存在“重写版本已 active、helper 未 active”的半发布状态；任一步失败时下游保持未激活，helper 即使已激活也只是未被使用的库项，可撤销。本轮未新增跨能力批量事务。

## 测试

| 用例 | 覆盖 |
|---|---|
| U01 | 3 个任务共享 `condition`：重写接受，成本下降，3 个版本 +1，全部钉住 helper 哈希 |
| U02 | 只共享小 `isRed`：行为等价但 helper 成本高于节省 → `no_net_benefit` 拒绝 |
| U03 | 扩展域含缺字段 `Unknown` 与错误类型 `Error`，逐点比较完全一致，标记 `bounded_verified` |
| U04 | 没有公共子树的任务对不产生候选（负对照） |
| U05 | 按发布顺序注册 helper 与重写版本、验证、激活、执行；重启后可执行；撤销 helper 后下游 `dependency_revoked`，旧版本仍可 `inspect` |

## 已知边界

- L3 参数化抽象 `not_implemented`；本轮不声称完成 L3。
- 等价只在给定有限域/受控测试集上成立，不是全局语义等价证明。
- 成本用冻结静态权重；没有用运行耗时比较收益。
- 重写只处理 M1 纯函数；不处理副作用、外部工具或动态字段。
- 独立闭卷 `not_run`；170/170 是实现者自测。
