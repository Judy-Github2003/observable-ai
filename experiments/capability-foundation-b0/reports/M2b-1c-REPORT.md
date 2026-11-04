# B0 M2b-1c 报告

```text
子任务: M2b-1c
状态: complete
基线 SHA: a3147761aa661731a14a6f3f270705a667f15c04
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 86/86；新增 9/9；失败 0；合计 95/95
新文件/修改文件:
  src/main/kotlin/logic/capability/CapabilityVerificationService.kt
  src/main/kotlin/logic/capability/CapabilityRegistry.kt
  src/test/kotlin/logic/capability/CapabilityVerificationServiceTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M2b-1c-REPORT.md
关键语义及接口: CapabilityVerificationService.verify(id,version,suiteId,suiteVersion) → 执行 + run/event 同事务追加
失败用例/未实现/已知边界: 激活门禁与正式执行入口未实现；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M2b-2a
```

## 做了什么

- `CapabilityVerificationService`：用数据库 `inspect` 作为可信检查器（`RegistryInspector`），执行内存验证引擎，然后把一条 `verification_runs` 与一条 `events(kind='verified')` 同事务写入。
- `verification_source=implementer_run`（本轮所有验证均由实现者执行）；库内 `source` 字段来自宿主登记（`host_fixture` / `implementer_run`），不是能力自己声明。
- 事件 JSON 保存汇总与逐例 `case_id / status / counted_as_definite / expected / actual`，失败用例不被“通过”一句话覆盖；不使用离线 trace 文件，因此不存在文件与事务的原子性问题。
- 前置拒绝不写库：套件未登记、库内能力损坏/依赖断裂、未执行任何用例、trace 超过 1 MiB 都直接返回错误码，不产生无法复核的记录。
- 已执行的运行（`pass`、`fail`、`suite_inadequate`）全部保留；每次 `run_id` 新生成，只追加不更新。

## 事务与拒绝边界

| 场景 | 行为 |
|---|---|
| run 插入成功、event 插入失败（触发器注入） | 整笔回滚，`transaction_failed`，不新增任何行 |
| 库内 body 被篡改 | `inspect` 返回 `body_hash_mismatch`，拒绝写“通过记录” |
| 传递依赖被篡改 | 依赖方 `inspect` 失败，拒绝写入 |
| 套件未登记 | `unknown_suite`，不写库 |
| 套件覆盖不足 | 写 `verdict=fail`，`code=suite_inadequate`（保留失败事实） |

## 测试

| 用例 | 覆盖 |
|---|---|
| W01 | 通过运行落库：run 字段、事件逐例证据、run_id 关联一致 |
| W02 | 失败运行保留，`passed=3/failed=1`，事件含 `WRONG_VALUE` |
| W03 | 覆盖不足写 fail 而非 pass（4 条全真） |
| W04 | 事件写入失败触发器 → run 与 event 双回滚 |
| W05 | body 被篡改 → `body_hash_mismatch`，0 条 run |
| W06 | 两次验证两个 run_id，事件 3 条，历史只追加 |
| W07 | 未登记套件 → `unknown_suite`，0 条 run |
| W08 | 依赖 body 被篡改 → 依赖方无法产生新验证记录 |
| W09 | `source` 来自宿主登记并写入事件 |

## 已知边界

- `pass` 只表示**该套件**通过，不代表开放世界正确；`suite_hash` 只证明套件内容一致，不证明测试集来源独立。
- 验证套件仍由实现者以 Kotlin 夹具登记，不是外部独立测试；独立闭卷 `not_run`。
- 激活、正式执行入口（M2b-2a/2b）未实现；`capabilities.status` 仍固定为 `candidate`。
