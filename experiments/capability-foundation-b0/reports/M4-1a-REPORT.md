# B0 M4-1a 报告

```text
子任务: M4-1a
状态: complete
基线 SHA: a8fe5f6b94c24b8536de59b3d4af5907f2e6863e（M3-1c）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 154/154；新增 6/6；失败 0；合计 160/160
新文件/修改文件:
  src/main/kotlin/logic/capability/LibraryCost.kt
  src/main/kotlin/logic/capability/CapabilityRegistry.kt
  src/test/kotlin/logic/capability/LibraryCostTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M4-1a-REPORT.md
关键语义及接口: CapabilityCost（冻结权重）+ L1LibraryAudit（正文共享验收与成本）
失败用例/未实现/已知边界: L2 子树抽取在 M4-1b；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M4-1b
```

## 做了什么

- `CapabilityCost`：冻结节点、Call、签名、接口权重；`program()`、`signature()`、`canonicalBytes()`。
- 成本公式：`L_total = Σ 唯一正文成本 + Σ 签名成本 + λ × 能力接口数`，`λ=1`。
- `L1LibraryAudit`：读取全部能力（完整 `inspect`，含依赖校验）→ 按 canonical AST 哈希归并正文 → 统计共享、正文字节、各部分成本。
- 不新增“正文去重引擎”：落盘共享由 M2a 的 `bodies(body_hash PRIMARY KEY)` 承担，本步只做验收与统计。

## 冻结权重（2026-11-05）

| 节点/项 | 成本 |
|---|---|
| `arg` / `const` / `ref` | 1 |
| `field` | 2 + 记录表达式 |
| `eq` / `and` / `or` | 2 + 左右子表达式 |
| `not` | 1 + 子表达式 |
| `if` | 2 + 三个子表达式 |
| `let` | 2 + value + body |
| `call` | 3 + 1×实参数 + 各实参表达式 |
| 签名 | 1 + 输出类型 + Σ(1 + 输入类型) |
| 接口开销 | 每个能力 1，λ=1 |

类型成本：`Bool3/String/Int64` 各 1；`Record` = 1 + 各字段类型成本。

## 测试

| 用例 | 覆盖 |
|---|---|
| L01 | 手算冻结值：正文 6、签名 6、接口 1、总计 13；正文字节与 canonical JSON 一致 |
| L02 | 两个能力共享同一正文：`bodies=1`，正文成本只计一次，签名与接口逐个计，总成本 20 |
| L03 | Call 成本含基座、实参数与实参表达式自身成本 |
| L04 | 正文被篡改 → 审计整体 `body_hash_mismatch`，不返回残缺成本 |
| L05 | 关闭重开后成本一致 |
| L06 | 更丰富的 Record 签名成本更高 |

## 已知边界

- 成本是静态长度代理，不是运行耗时；不得用它宣称“学习更快”。
- `L_total` 中的接口开销采用“每个能力 1”的简化口径，已在 DECISIONS 冻结；改动必须重跑 M4/M5。
- JSON 字节数只作工程指标，依赖编码格式。
- L2 公共子树抽取与净收益判定在 M4-1b/1c，本步未实现。
- 独立闭卷 `not_run`；160/160 是实现者自测。
