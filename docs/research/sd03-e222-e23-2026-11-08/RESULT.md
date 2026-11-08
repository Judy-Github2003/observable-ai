# SD-03 E2.2.2 + E2.3 合并轮结果：GATE A 失败，GATE B 被冻结 token 阻断

## 0. 元数据

- 任务：`SD-03-E222-E23-COMBINED-2026-11-07`
- 起始 HEAD：`19960fe`；代码提交：`b8279e0`
- Console：`SD-03-E222-E23-COMBINED-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；未启动 SD-04

## 1. GATE A：E2.2.2 全量隔离回归

完整报告：[REGRESSION.md](REGRESSION.md)

| 指标 | 值 |
|---|---:|
| 隔离 | detached Git worktree |
| 命令 | `gradlew --offline --console=plain --no-daemon test` |
| 实际耗时 | **669.024s** |
| tests | **507** |
| failures | **5** |
| errors | 0 |
| skipped | 0 |
| 状态 | **FAIL** |
| 历史 tracked 文件 | 327，前后 digest 相同 |

5 个失败：

1. `C8FormalExamTest`：正式 source hash 过期；
2. `MRXFormalTest`：`MRXSpace.kt` 冻结 hash 过期；
3. `MRXRFormalTest`：`MRXREvidence.kt` 冻结 hash 过期；
4. `SD03E1DevelopmentIntegrationTest`：sandbox 中 `FROZEN-*.json` 已存在；
5. `SD03E23DevelopmentIntegrationTest`：E22 replay token drift。

原因：

- 3 个正式考试失败源于源码已变化、冻结 hash 未重签；不能在回归任务里擅自改冻结哈希。
- E1 失败源于 E2.2.2 工具的清理后缀遗漏 `.json`（只清 `.jsonl/.log/.xml/.csv`）。
- E23 失败源于冻结计划 taskToken 错位。

**GATE A = FULL_REGRESSION_FAIL。** 没有用 `quickTest` 冒充全量回归。

## 2. GATE B：E2.3 冻结重放

完整阻断说明：[E23-BLOCKED.md](E23-BLOCKED.md)

集成测试在第一个任务即失败：

```text
expected:<[e0734e453d890dbd]> but was:<[6894c295a0615971]>
```

`config/e23-frozen-replay-plan.json` 中 3 个 taskToken 与 `sha256(world|seed).take(16)` 不一致：

| world/seed | plan token | actual token |
|---|---|---|
| base-or-r-d1/20261111 | e0734... | **6894...** |
| xor-b2/20261112 | 4254... | **e0734...** |
| base-or-r-d1/20261121 | 6894... | **4254...** |

这是链式错位，且随附 `expectedE22FullRefitHash` 也按错位 token 排列；不能只改 token，也不能手改冻结计划。

判定：

```text
REPLAY_FIDELITY_FAILED / FROZEN_PLAN_TOKEN_MISMATCH
```

四组对照：

| Arm | Challenge | HELDOUT | 状态 |
|---|---|---|---|
| FULL_REFIT | NOT_RUN | NOT_RUN | replay 未建立 |
| KEEP_IF_AGREE | NOT_RUN | NOT_RUN | 不比较 |
| LOCAL_FIRST | NOT_RUN | NOT_RUN | 不比较 |
| STATIC | NOT_RUN | NOT_RUN | 不比较 |

没有 E2.3 Challenge/HELDOUT 结果，也没有 32 个最终冻结；不能写 `NO_BENEFIT`，只能写 `NOT_RUN / REPLAY_FIDELITY_FAILED`。

## 3. 工程测试

- `SD03E23UpdateContractTest`：通过（合成契约）。
- `SD03E23DevelopmentIntegrationTest`：**RED**，JUnit 与日志已保留。
- `tools/e223/tests`：通过（审计工具单元测试）。
- 全量回归：**FAIL**，见上。

没有新的业务 RED 被伪造；E2.3 的 RED 是真实冻结配置不一致。

## 4. 结论

- E2.2.2 的隔离 worktree 机制能保护主仓库历史证据（digest 前后一致），但清理器遗漏 `.json`，导致 E1 集成测试在 sandbox 内仍碰撞。
- 3 个正式考试冻结 hash 失败说明当前源码与历史正式冻结不一致；本轮不能宣称全量回归通过。
- E2.3 因冻结计划 token 错位，FULL_REFIT 历史重放未建立，四组学习对照不得执行或宣称结果。
- 因此：**GATE A FAIL，GATE B BLOCKED，科研验收 STOP。**

## 5. 产物

- [REGRESSION.md](REGRESSION.md)
- [E23-BLOCKED.md](E23-BLOCKED.md)
- [PREREGISTRATION.md](PREREGISTRATION.md)
- `FULL-REGRESSION.json` / `.log` / `FULL-REGRESSION-JUNIT/`
- `E23-INTEGRATION-RED.log` / `JUNIT-E23-INTEGRATION-RED.xml`
- `e23-frozen-replay-plan.json`

本轮停止，等待独立审查；不启动 SD-04。
