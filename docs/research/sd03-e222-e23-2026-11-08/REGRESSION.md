# E2.2.2 隔离全量回归：FULL_REGRESSION_FAIL

## 运行身份

- 源 HEAD（运行前）：`b8279e0d54df475b30938db9b830ded21529e46e`
- 隔离方式：`git worktree add --detach` 到临时目录，未在主仓库运行 `test`
- 命令：`gradlew --offline --console=plain --no-daemon test`
- 超时：1800s
- 实际耗时：**669.024s**
- 平台：Windows 11 / 24 CPU / JDK 21
- Gradle：offline，`--no-daemon`；缓存为同一机器上的 warm cache（非严格冷启动实验）

## 结果

| 指标 | 值 |
|---|---:|
| JUnit 文件 | 113 |
| tests | **507** |
| failures | **5** |
| errors | 0 |
| skipped | 0 |
| 状态 | **FAIL（exit 1）** |

历史证据完整性：

- tracked files：327
- before digest：`8b15f803...`
- after digest：`8b15f803...`
- **historicalTrackedUnchanged=true**

主仓库历史 evidence 未被覆盖；FULL 测试在隔离 worktree 中运行并清理该 worktree 内的生成文件。

## 5 个失败

| 测试 | 失败 |
|---|---|
| `C8FormalExamTest` | 冻结 source hash 不一致（`f37d...` vs `cdaa...`） |
| `MRXFormalTest` | `MRXSpace.kt` 冻结 hash 不一致 |
| `MRXRFormalTest` | `MRXREvidence.kt` 冻结 hash 不一致 |
| `SD03E1DevelopmentIntegrationTest` | `FROZEN-6894...-LEGACY_10.json` 已存在 |
| `SD03E23DevelopmentIntegrationTest` | E22 replay token drift |

## 原因分类

1. **正式考试冻结哈希过期**（3 个）：这些测试检查旧正式考试冻结时的源码 hash；当前源码已变化，因此失败。不是本轮 E2.3 造成的运行时崩溃，但也不能在未重新冻结的情况下宣称全量回归通过。禁止为了绿灯修改冻结哈希。
2. **E1 隔离清理遗漏**：`isolated_full_regression.py` 的生成文件后缀集合为 `.jsonl/.log/.xml/.csv`，**遗漏 `.json`**，因此 `FROZEN-*.json` 仍留在 sandbox，触发 `FileAlreadyExistsException`。这是 E2.2.2 工具缺陷，不是 E1 学习逻辑失败。
3. **E2.3 冻结 token 错位**：见 `E23-BLOCKED.md`；`config` 中 3 个 taskToken 与 `sha256(world|seed)` 不一致。

## 判定

```text
GATE A: FULL_REGRESSION_FAIL
GATE B: REPLAY_FIDELITY_FAILED / NOT_RUN
```

- 没有用 `quickTest` 冒充全量回归；
- 没有删除、移动或覆盖主仓库历史研究证据；
- 没有根据失败修改冻结考试哈希；
- E2.3 四组对照不应在本轮宣称科研结果。

## 修复建议（未在本轮强行执行）

1. 将 `.json` 加入 sandbox 清理后缀，并针对 E1 的 FROZEN 文件增加显式清理；需重新预注册回归工具修订，而不是偷偷改。
2. 正式考试冻结哈希需要独立的“重新冻结”任务，不应由 E2.2.2 顺带改。
3. E2.3 的 8 个 taskToken 需由包作者修正并重新预注册；本轮不手改冻结计划。
