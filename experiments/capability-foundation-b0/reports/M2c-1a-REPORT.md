# B0 M2c-1a 报告

```text
子任务: M2c-1a
状态: complete
基线 SHA: 87f01e0e6ab8bc4d2173e91869046253706fee1c（研究北极星文档提交后）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 115/115；新增 8/8；schema 迁移补 1 项并改为 v3；失败 0；合计 124/124
新文件/修改文件:
  src/main/kotlin/logic/capability/CapabilityRevocationService.kt
  src/main/kotlin/logic/capability/CapabilityRegistry.kt
  src/main/kotlin/logic/capability/CapabilityActivationService.kt
  src/main/kotlin/logic/capability/CapabilityExecutionService.kt
  src/test/kotlin/logic/capability/CapabilityRevocationServiceTest.kt
  src/test/kotlin/logic/capability/CapabilitySchemaMigrationTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M2c-1a-REPORT.md
关键语义及接口: revoke(id,version,reason,evidence)；追加 revocations + revoked 事件；撤销优先于激活与执行
失败用例/未实现/已知边界: 传递依赖失效与恢复在 M2c-1b；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M2c-1b
```

## 做了什么

- schema v3：`0→3` 一次建全、`1→3` / `2→3` 单事务补 `revocations` 表、`3` 原样打开、其他版本拒绝；迁移失败整笔回滚。
- `revocations(revoke_id, capability_id, version, content_hash, reason_code, evidence_ref, created_at)`，外键指向 `capabilities(capability_id, version)`；只追加。
- `CapabilityRevocationService.revoke(id, version, reasonCode, evidenceRef)`：
  - 原因为空、证据引用为空或超长 → `invalid_revocation_reason` / `invalid_evidence_ref`，不写库；
  - 能力不存在或库内定义损坏 → 返回 `inspect` 的具体错误码；
  - 同版本重复撤销且哈希一致 → `already_revoked`，幂等，不追加事件；哈希不一致 → `revocation_conflict`；
  - 撤销记录 + `events(kind='revoked')` 同事务提交。
- 正式入口 `executeActive` 撤销优先：被撤销版本即使有旧验证、旧激活记录也返回 `revoked`；激活服务同样拒绝已撤销版本。
- `inspect` 仍可读取旧版本，用于历史审计；`capabilities.status` 仍是 `candidate`。

## 设计取舍

- **不提供原地恢复**：修正必须发布新版本、重新验证、重新激活；旧版本永久可追溯且不可正式运行。
- **重复撤销幂等**：同一 `(id, version)` 同内容哈希返回原记录，避免重复事件污染历史；哈希不一致视为异常并拒绝。
- 本步只拦根能力撤销；调用方依赖被撤销时的传递失效在 M2c-1b 实现，避免把两步语义混在一次提交里。

## 测试

| 用例 | 覆盖 |
|---|---|
| M01–M06 | v1→v3、空库→v3、重复重启、迁移失败回滚、未知版本拒绝、v2→v3 补表且旧行不变 |
| Z01 | 撤销落库 + 事件；重启后仍可审计；能力/验证/激活记录保留，status 仍是 candidate |
| Z02 | 撤销不存在的版本 → `not_found`，0 行 |
| Z03 | 空白原因、空白证据、超长原因 → 拒绝，0 行 |
| Z04 | 重复撤销幂等：一条记录、一条事件 |
| Z05 | 事件写入失败 → `transaction_failed`，撤销回滚 |
| Z06 | 已撤销版本不能重新激活、不能正式执行；`inspect` 仍可用 |
| Z07 | 同 ID 新版本可独立验证、激活、执行；旧版本仍不可执行 |
| Z08 | 撤销前后 capabilities/bodies/verification_runs/activations 逐行快照不变 |

## 已知边界

- 传递依赖撤销后的失效传播、`revoked / dependency_revoked / dependency_invalid / not_active` 区分在 M2c-1b。
- 撤销的“证据撤回”语义未实现；本步只记录原因码与证据引用文本。
- 独立闭卷 `not_run`；124/124 是实现者自测。
