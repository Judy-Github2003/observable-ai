# B0 M2b-2b 报告

```text
子任务: M2b-2b
状态: complete
基线 SHA: 0e04f672612fc4a39e20ebdc318a6db3708913f3（M2b-2a 提交后更新为实际 SHA）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 104/104；M2b-2a 9/9；M2b-2b 新增 11/11；失败 0；合计 115/115
新文件/修改文件:
  src/main/kotlin/logic/capability/CapabilityExecutionService.kt
  src/main/kotlin/logic/capability/ActivationGate.kt
  src/main/kotlin/logic/capability/CapabilityActivationService.kt
  src/test/kotlin/logic/capability/CapabilityExecutionServiceTest.kt
  experiments/capability-foundation-b0/reports/M2b-REPORT.md
关键语义及接口: executeActive(id,version,args,budget)；每次执行重新核对激活与传递依赖
失败用例/未实现/已知边界: 执行结果未单独持久化为运行日志；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M2c-1a
```

## 正式入口

`CapabilityExecutionService.executeActive` 每次执行都重新检查：

1. `inspect` 成功（body/签名/依赖表/IR 语义版本）；
2. `activations` 存在且 `content_hash` 与当前定义一致；
3. 激活引用的 `verification_runs` 仍是 `verdict=pass` 且绑定同一 `(id,version,hash)`；
4. `ActivationGate` 递归核对全部传递依赖的激活记录与哈希；
5. 通过后才调用 M1 解释器，预算由 `EvalBudget` 控制。

`CapabilityRegistry.resolve` / `CapabilityInterpreter` 保留为开发与验证接口；正式应用路由必须走 `executeActive`。同进程内这并不是对恶意调用者的硬隔离，权限沙箱留后续阶段。

## 结果语义

- `ok=true` 只表示“门禁通过且确实执行了”；
- `result` 可能是 `Known / Unknown / Error`，`resultKind` 明确区分；
- `Unknown` 不是 `false`，`Error` 不是成功预测；
- `ok=false` 时 `code` 给出拒绝原因（`not_active`、`activation_mismatch`、`verification_failed`、`body_hash_mismatch`、`dependency_not_activated` 等）。

## 测试

| 用例 | 覆盖 |
|---|---|
| Y01 | 仅 candidate / 仅验证未激活 → `not_active`；内部 `resolve` 仍可用（开发接口区别） |
| Y02 | 激活后执行得到 Known true/false |
| Y03 | 激活后改 body → `body_hash_mismatch` |
| Y04 | 手工写入调用方激活但依赖未激活 → `dependency_not_activated`；补齐依赖后放行 |
| Y05 | 依赖 body 被改 → 调用方执行被拒 |
| Y06 | 关闭重开数据库后激活仍在、结果一致；未激活的 v2 仍拒绝 |
| Y07 | 篡改激活记录内容哈希 → `activation_mismatch` |
| Y08 | 篡改验证 verdict → `verification_failed` |
| Y09 | 篡改 IR 语义版本 → `unsupported_ir_semantics_version` |
| Y10 | 预算耗尽/缺字段/输入类型错误分别返回 Error/Unknown/Error，均不冒充成功 |
| Y11 | 三层链重启后可执行；篡改最内层 body 后调用方被拒 |

## 已知边界

- 执行结果目前作为返回值交给调用方，**没有新增执行日志表**（schema 契约已固定为 6 张表）；若后续需要逐次执行留痕，应单独设计迁移，不能复用验证记录冒充执行证据。
- 不把 `Unknown`/`Error` 写入 activations 或 verification_runs 作为成功。
- 独立闭卷 `not_run`；115/115 是实现者自测。
