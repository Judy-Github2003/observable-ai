# B0 M2b-2a 报告

```text
子任务: M2b-2a
状态: complete
基线 SHA: 0e04f672612fc4a39e20ebdc318a6db3708913f3
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 104/104；新增 9/9；失败 0；合计 113/113（M2b-2b 后总数为 115）
新文件/修改文件:
  src/main/kotlin/logic/capability/CapabilityActivationService.kt
  src/main/kotlin/logic/capability/ActivationGate.kt
  src/main/kotlin/logic/capability/CapabilityRegistry.kt
  src/test/kotlin/logic/capability/CapabilityActivationServiceTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M2b-2a-REPORT.md
关键语义及接口: activate(id,version,verificationRunId)；追加 activations 记录，不修改 capabilities.status
失败用例/未实现/已知边界: 正式执行入口在 M2b-2b；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M2b-2b
```

## 门禁顺序

1. `inspect(id, version)` 成功，拿到当前 `contentHash`；
2. `verification_runs` 存在，且 `(capability_id, version, content_hash)` 与当前定义完全一致；
3. `verdict='pass'`；
4. 套件在宿主登记表中，`suite_hash` 一致；库内 `source` 必须是合法枚举，且与该套件登记来源一致；
5. 每个传递依赖都有激活记录，版本/哈希一致，且当前仍可 `inspect`（`ActivationGate` 递归）；
6. 幂等：同 `(id, version)` 已有同哈希激活记录 → `already_active`，不追加事件；哈希不同 → `activation_conflict`；
7. `activations` + `events(kind='activated')` 同事务追加。

## 测试

| 用例 | 覆盖 |
|---|---|
| X01 | 通过验证后激活成功；`activations=1`、事件 3 条；`capabilities.status` 仍是 `candidate` |
| X02 | 伪造 run_id 直接激活 → `verification_not_found` |
| X03 | 失败验证 → `verification_failed`，不产生激活 |
| X04 | 用另一能力的 run → `verification_mismatch`；篡改 run 内容哈希同样拒绝 |
| X05 | 依赖未激活 → `dependency_not_activated`；补齐后调用方激活成功 |
| X06 | 事件写入触发器失败 → 激活记录回滚，`transaction_failed` |
| X07 | 重复激活幂等：`already_active`，仍只有 1 条激活、1 条 activated 事件 |
| X08 | 三层链 C←B←A：未激活内层时拒绝 A；按 C→B→A 顺序全部成功 |
| X09 | 篡改库内 `source` → `source_mismatch`；未登记套件 → `suite_not_registered` |

## 说明

- 激活是**追加记录推导出的逻辑状态**，不是 `candidate → verified → active` 的数据库状态字段变化；`capabilities.status` 始终是 `candidate`。
- 本轮“可信”= 宿主进程内登记表 + 记录核对，不承诺对抗恶意数据库管理员。
- 正式执行入口（`executeActive`）在 M2b-2b 才闭环；本步完成前 M2b 不算 complete。
