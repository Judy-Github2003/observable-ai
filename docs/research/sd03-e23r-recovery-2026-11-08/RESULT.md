# SD-03 E2.3R 恢复结果：计划已恢复、E23 重放 PASS；全量回归仍因 CRLF 冻结冲突 FAIL

## 0. 元数据

- 任务：`SD-03-E23R-REPLAY-RECOVERY-2026-11-08`
- 起始 HEAD：`76fdd2d`；修复 HEAD：`891700c`
- Console：本轮独立授权，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=NOT_RUN`；未启动 SD-04

## 1. 冻结计划恢复

- 从 E22 原始 `STUDENT-EVENTS-*.jsonl` 重建，不依赖 E23 结果；
- `E23_REPLAY_PLAN_VERIFIED a47dceb... 8 11`；
- 8 个 task-seed、11 条冻结 QUERY；
- 原始 E22 证据 0 修改；
- 修正原先 3 个 token 链式错位：
  - base-or-r-d1/20261111 → `6894...`
  - xor-b2/20261112 → `e073...`
  - base-or-r-d1/20261121 → `4254...`

## 2. E2.3 重放

在隔离全量回归中：

- `SD03E23DevelopmentIntegrationTest`：**PASS**
- `tests=1 failures=0 errors=0`
- 8 个任务的 FULL_REFIT 最终哈希全部与重建 manifest 一致；
- 这是 `REPLAY_PASS` / `E23_REPLAY_PLAN_RECOVERED`。

本轮**没有**将四组作为科研对照分析，raw 数据仅是回归 side effect：

```text
E23_SCIENTIFIC_ARMS_NOT_RUN
```

`E23-REGRESSION-SIDE-EFFECT/` 中的数据不作为 Challenge/HELDOUT 结论。

## 3. 全量回归

完整报告：[REGRESSION.md](REGRESSION.md)

| 指标 | 值 |
|---|---:|
| 隔离 | detached worktree |
| 命令 | `gradlew --offline --console=plain --no-daemon test` |
| 实际耗时 | **665.268s** |
| tests | **507** |
| failures | **3** |
| errors | 0 |
| skipped | 0 |
| 状态 | **FULL_REGRESSION_FAIL** |
| 历史 tracked | 453，前后 digest 相同 |

3 个失败全部是历史 formal source hash：

- `C8FormalExamTest`
- `MRXFormalTest`
- `MRXRFormalTest`

独立只读审计：

```text
status: ARCHIVE_AND_HEAD_COMPARED_NOT_RESIGNED
historicalManifestCount: 3
checkedSources: 11
mismatchedSources: 0
```

主工作树 11 个源文件全部匹配旧 manifest。隔离 worktree 因 `core.autocrlf=true` 产生 CRLF，测试按原始文本 hash 比较才失败；不是源文件漂移。

没有修改旧清单、没有重签 hash、没有删除失败测试、没有伪报 PASS。

## 4. 修复效果

| 项 | 修复前 | 修复后 |
|---|---|---|
| E1 FROZEN `.json` 碰撞 | FAIL | **PASS** |
| E2.3 token 错位 | FAIL | **PASS**（重放通过） |
| 其他 current failures | 2 | 0 |
| historical formal hash | 3 FAIL | 3 FAIL（CRLF 环境） |

因此：

- **E2.3 科研可执行性已恢复**；
- **完整回归仍为 FAIL**，原因是隔离 checkout 的换行符，不是学习代码；
- 不能写成“全量回归 PASS”或“504/504 全绿”。

## 5. 测试

- `tools/e23r` Python 契约：**7/7 OK**
- `SD03E23UpdateContractTest`：PASS
- 全量回归：507/3 FAIL
- 未运行 E2.3 策略比较；未运行 160 次正式考试

## 6. 产物

- [REPLAY-RECOVERY.md](REPLAY-RECOVERY.md)
- [REGRESSION.md](REGRESSION.md)
- `FREEZE-AUDIT.json`（11/11 match，0 mismatch）
- `FULL-REGRESSION.json/.log` / `FULL-REGRESSION-JUNIT/`（113 XML）
- `REGRESSION-CLASSIFICATION.json` / `FULL-REGRESSION-FAILURES.json`
- `PYTHON-TESTS.log`、`E23-CONTRACT-GREEN.log`
- `E23-REGRESSION-SIDE-EFFECT/`（仅回归 side effect，不作科研结论）

## 7. 结论

- 冻结计划恢复：**PASS**
- E2.3 FULL_REFIT 重放：**PASS**
- 全量回归：**FAIL（3 个 CRLF 历史 formal 冲突）**
- E2.3 四组科学对照：**NOT_RUN**
- 不启动 SD-04，等待独立审查。
