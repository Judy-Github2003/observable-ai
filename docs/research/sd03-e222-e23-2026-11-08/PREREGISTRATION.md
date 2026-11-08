# SD-03 E2.2.2 + E2.3 预注册（合并轮）

- 任务：`SD-03-E222-E23-COMBINED-2026-11-07`
- 起始 HEAD：`19960fe`；本轮代码提交：`b8279e0`
- Console：`SD-03-E222-E23-COMBINED-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`

## GATE A：E2.2.2 隔离回归

- 使用 `tools/e222/isolated_full_regression.py` 在独立 Git worktree 执行无筛选 `gradlew test`；
- 不在主仓库运行全量 `test`；不删除、移动主仓库历史证据；
- 超时 1800 秒；记录实际耗时、机器/JDK/Gradle、JUnit 计数、失败/跳过；
- 失败或超时 → `FULL_REGRESSION_FAIL/BLOCKED/TIMEOUT`，不得用 `quickTest` 冒充。

## GATE B：E2.3 冻结重放

- 8 个公开开发任务 × 4 组（FULL_REFIT / KEEP_IF_AGREE / LOCAL_FIRST / STATIC）= 32 个最终冻结；
- 三种更新策略使用完全相同的 11 个冻结 QUERY IDs；
- FULL_REFIT 最终程序哈希必须与历史 E2.2 一致，否则 `REPLAY_FIDELITY_FAILED` 并停止对照；
- 学生只接收 TRAIN + 实际揭示的 QUERY；Challenge/HELDOUT 只在冻结后考官评分；
- 不根据结果调整任务、QUERY、种子、阈值。
