# E2.3 冻结重放恢复：8 任务 / 11 查询已从 E22 原始事件重建

## 1. 恢复依据

- 输入：`docs/research/sd03-e22-development-2026-11-07/STUDENT-EVENTS-*.jsonl`
- 恢复工具：`tools/e23r/rebuild_frozen_plan.py`
- 原始 E22 证据未被编辑；`apply_recovery.py` 明确报告 `originalE22EvidenceTouched=false`。
- 恢复计划验证：

```text
E23_REPLAY_PLAN_VERIFIED
a47dceb31165bffda5c2f6a6de671a22fcb288ff70cb3b298f458dd125405c80
8 11
```

即 8 个 task-seed、11 条冻结 QUERY，计划摘要 `a47dceb...`。

## 2. 修正内容

| 文件 | 修复 |
|---|---|
| `config/e23-frozen-replay-plan.json` | 从 E22 原始事件重建 token/query/full-refit-hash 关联；加入 source event SHA |
| `SD03E23DevelopmentIntegrationTest.kt` | 不再硬编码重复任务表，读取同一 manifest，先验证 8 个 world/seed、token、11 查询、8 个最终 hash；所有任务 FULL_REFIT 通过后才评分 |
| `tools/e222/isolated_full_regression.py` | sandbox 仅删除 E1 目录下 `FROZEN-*.json` 一次性文件，不广泛删 JSON |

没有修改 `SD03E23EvidenceUpdates.kt` 学习策略。

## 3. 修正后的任务关联

| world | seed | token | queries | FULL_REFIT hash |
|---|---:|---|---|---|
| base-or-r-d1 | 20261111 | 6894... | 无 | c91ac... |
| depth-w4 | 20261110 | 83a0... | q-3 | d17dc... |
| xor-b2 | 20261112 | e073... | q-9 | 65303... |
| if-nested-3 | 20261109 | 0d3d... | q-4,q-1,q-2,q-3 | 7de44... |
| base-or-r-d1 | 20261121 | 4254... | q-0 | c91ac... |
| depth-w4 | 20261122 | 4262... | 无 | bf623... |
| xor-b2 | 20261123 | bea6... | 无 | 65303... |
| if-nested-3 | 20261124 | 4b1f... | q-4,q-2,q-11,q-1 | 38fc4... |

合计 **11** 条查询。

## 4. 重放结果

在隔离 worktree 的无筛选回归中：

- `SD03E23DevelopmentIntegrationTest`：**PASS**（JUnit `tests=1 failures=0 errors=0`）
- 所有 8 个任务的 FULL_REFIT 最终程序哈希均通过 manifest 硬校验；
- 由于完整回归有 3 个别的 formal 失败，E2.3 集成测试虽 PASS，但整体回归仍为 `FULL_REGRESSION_FAIL`。

## 5. 边界

- 本轮**没有**把 E2.3 四组作为科研对照分析；
- raw `EXAMINER-AFTER-ALL-FREEZE.jsonl` 只是回归测试的 side effect，已放 `E23-REGRESSION-SIDE-EFFECT/`，不作为 Challenge/HELDOUT 结论；
- `E23_SCIENTIFIC_ARMS_NOT_RUN`。
