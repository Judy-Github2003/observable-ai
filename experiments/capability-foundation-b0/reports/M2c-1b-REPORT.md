# B0 M2c-1b 报告

```text
子任务: M2c-1b
状态: complete
基线 SHA: 15e244be349147a88fc88edacd382c3cdef4fc05
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 124/124；新增 6/6；失败 0；合计 130/130
新文件/修改文件:
  src/main/kotlin/logic/capability/ActivationGate.kt
  src/main/kotlin/logic/capability/CapabilityRegistry.kt
  src/main/kotlin/logic/capability/CapabilityActivationService.kt
  src/main/kotlin/logic/capability/CapabilityExecutionService.kt
  src/test/kotlin/logic/capability/CapabilityInvalidationTest.kt
  src/test/kotlin/logic/capability/CapabilityExecutionServiceTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M2c-1b-REPORT.md
  experiments/capability-foundation-b0/reports/M2c-REPORT.md
关键语义及接口: ActivationGate.check / locateDependencyFailure；失败码 revoked / dependency_revoked / dependency_invalid / dependency_not_activated / dependency_hash_mismatch
失败用例/未实现/已知边界: 撤销的证据撤回语义未实现；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M3-1a
```

## 做了什么

- `ActivationGate.check` 递归核对整条依赖链：撤销 → 激活记录 → 哈希 → 引用验证仍为 `pass` 且绑定一致 → 继续向下 → 最后检查该依赖自身 `inspect`。
- 先递归、后自检，使错误路径指向真正的根因：内层 body 损坏时报告停在内层，而不是笼统地报中间层无效。
- `ActivationGate.locateDependencyFailure`：根能力整链 `inspect` 失败时，先判断本行是否完好（`inspectSelf`），再沿依赖向下定位坏点，返回 `dependency_invalid` + 完整路径。
- `Registry.inspect` 拆成 `inspectSelf`（本行）与完整版（本行 + 依赖），保证“根自身损坏”和“依赖损坏”能区分。
- 激活与正式执行都使用同一门禁；`ActivationResult` / `ActiveExecutionResult` 增加 `detail` 字段给出依赖路径。

## 语义与错误码

| 情形 | 结果 |
|---|---|
| 根能力被撤销 | `revoked` |
| 任一传递依赖被撤销 | `dependency_revoked`，detail 为 `a@1 -> b@1 -> c@1` |
| 依赖激活记录缺失 | `dependency_not_activated` |
| 依赖激活哈希/依赖表哈希不一致 | `dependency_hash_mismatch` |
| 依赖验证记录缺失、非 pass 或绑定不一致 | `dependency_invalid` |
| 依赖 body/签名/语义版本损坏 | `dependency_invalid`（先定位到最深层） |
| 根自身 body 损坏 | 保留 `inspect` 具体错误码，如 `body_hash_mismatch` |

`Unknown` / `Error` 不参与这些门禁码；门禁拒绝发生在执行之前，历史 `inspect` 与旧验证/激活记录保留。

## 恢复语义

- 旧调用方钉住旧版本：依赖被撤销或损坏后，旧调用方永久不可用。
- 新版本（如 C2）可独立注册、验证、激活；旧 A/B 不会自动恢复。
- 需要恢复能力时，必须新建依赖新版本的调用方（如 B2/A2），重新验证、重新激活。

## 测试

| 用例 | 覆盖 |
|---|---|
| R01 | A→B→C 全部激活；撤销 C 后 A/B 被拒，路径分别为 `b@1 -> c@1`、`c@1`；C 自身 `revoked` |
| R02 | 新版本 C2 可执行，但旧 A/B 仍 `dependency_revoked`；旧 C 仍 `revoked` |
| R03 | 新建钉住 C2 的 B2/A2 可激活执行；旧 A 仍被拒 |
| R04 | 中间层 body 被改坏 → `dependency_invalid`，路径停在中间层 |
| R05 | 依赖已撤销时，新调用方即使验证通过也不能激活（`dependency_revoked`） |
| R06 | 重启后失效状态与路径一致；新版本可继续注册，旧链不恢复 |
| Y11 | 最内层损坏 → `dependency_invalid`，detail 为 `chain.b@1 -> chain.c@1` |

## 已知边界

- 没有缓存依赖健康状态；每次执行都重新读库核对，成本可接受但未做性能优化。
- 门禁深度上限 32；M1 调用深度上限 16，因此正常能力图先被 M1 约束，深图病态主要靠该上限兜底。
- “证据撤回导致撤销”的复杂语义未实现；当前撤销以人工原因码 + 证据引用记录。
- 独立闭卷 `not_run`；130/130 是实现者自测。
