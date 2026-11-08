# E2.3 冻结重放阻断：任务 token 与冻结计划不一致

## 结论

E2.3 集成测试在**加载第一个任务**时失败，尚未进入任何更新策略的 Challenge/HELDOUT 对照：

```text
org.junit.ComparisonFailure:
E22 preregistered replay token drift
expected:<[e0734e453d890dbd]> but was:<[6894c295a0615971]>
at SD03E23DevelopmentIntegrationTest.kt:149
```

按预注册规则：FULL_REFIT 无法完成历史重放时，必须停止策略比较；本轮判定：

```text
REPLAY_FIDELITY_FAILED / FROZEN_PLAN_TOKEN_MISMATCH
```

没有产生可比较的 E2.3 四组 Challenge/HELDOUT 成绩。

## 证据

`config/e23-frozen-replay-plan.json` 与测试代码中的 `taskToken` 和实际 `sha256(world|seed).take(16)` 不一致：

| world | seed | 冻结计划 token | 实际 sha256 token | 状态 |
|---|---:|---|---|---|
| mrx-base-or-r-d1 | 20261111 | `e0734e453d890dbd` | `6894c295a0615971` | **不一致** |
| mrx-depth-w4 | 20261110 | `83a0a07d602718a9` | `83a0a07d602718a9` | 一致 |
| mrx-depth-r-xor-b2 | 20261112 | `4254cfe428af73ca` | `e0734e453d890dbd` | **不一致** |
| mrx-if-nested-3 | 20261109 | `0d3daf2627021276` | `0d3daf2627021276` | 一致 |
| mrx-base-or-r-d1 | 20261121 | `6894c295a0615971` | `4254cfe428af73ca` | **不一致** |
| mrx-depth-w4 | 20261122 | `426249206c4fbdf8` | `426249206c4fbdf8` | 一致 |
| mrx-depth-r-xor-b2 | 20261123 | `bea6135b21ee6c49` | `bea6135b21ee6c49` | 一致 |
| mrx-if-nested-3 | 20261124 | `4b1f104a970d9e8f` | `4b1f104a970d9e8f` | 一致 |

这不是随机噪声，而是三个任务的 token 发生了链式错位；随附的 `expectedE22FullRefitHash` 也与错位 token 对应，因此不能通过“只改 token”修复。

## 处理决定

- 不修改冻结的 8 个任务、11 个 QUERY IDs、种子或预期哈希；
- 不根据失败结果重新选任务；
- 不把 E2.3 写成 `NO_BENEFIT`，因为没有进入可比较实验；
- 保留 `E23-INTEGRATION-RED.log` 与 JUnit RED XML；
- 若审查者确认是冻结计划打包错误，应另行发布修正计划并重新预注册，而不是在本轮手改。

## 当前状态

- Kotlin 编译/契约：通过（`SD03E23UpdateContractTest`）。
- E2.3 开发集成：**RED / BLOCKED**。
- E2.3 四组对照：**NOT_RUN**。
- E2.3 Challenge/HELDOUT：**NOT_RUN**。
- GATE A 全量回归：见 `REGRESSION.md`（若失败，B 本身也不能宣称科研通过）。
