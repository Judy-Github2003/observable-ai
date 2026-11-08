# SD-03 E2.3R MIXED-EOL 恢复：冻结 11/11 通过，全量测试 1800s 超时

## 0. 元数据

- 任务：`SD-03-E23R-MIXED-EOL-RECOVERY-2026-11-08`
- 起始 HEAD：`a9d0d1c`；运行 HEAD：`65b56e3`（补丁后）
- Console：`SD-03-E23R-MIXED-EOL-RECOVERY-2026-11-08`，ACTIVE → CONSUMED
- 上限：1800s；`formal_runs=0`；`independent_closed_book=NOT_RUN`
- 未启动 E2.3 科研对照、SD-04

## 1. 安装与 Python 测试

安全安装器：

```text
READY_TO_INSTALL → INSTALLED → ALREADY_INSTALLED
```

只修改 `tools/e222/isolated_full_regression.py`；新增逻辑严格 allowlist：

- `MRXRSpace.kt`
- `MRXRStudent.kt`

只在隔离 worktree 中把这两个 LF 文件转为 CRLF，并逐字节校验等于历史 manifest；主仓库源文件、旧 manifest 未改。

Python 工程测试：

```text
Ran 7 tests in 7.861s
OK
```

`py_compile` 通过。

## 2. 冻结预检：PASS 11/11

`--freeze-only`：

| 项 | 值 |
|---|---:|
| status | **FROZEN_PREFLIGHT_PASS** |
| manifestCount | 3 |
| sourceCount | 11 |
| matchedSources | **11** |
| gradleStarted | false |
| historicalTrackedUnchanged | true |

恢复的两个文件：

| path | from（LF） | to（CRLF） |
|---|---|---|
| `MRXRSpace.kt` | `61bed653...` | `f2459391...` |
| `MRXRStudent.kt` | `9708efd5...` | `8ebfb76f...` |

其余 9 个 LF 源文件保持不变，全部匹配。  
`restoredArchivalCRLF` 精确等于两个 allowlist 路径。

## 3. 全量无筛选回归

命令：

```text
gradlew --offline --console=plain --no-daemon test
```

| 项 | 值 |
|---|---:|
| status | **TIMEOUT** |
| elapsedSeconds | **1800.811** |
| timeoutSeconds | 1800 |
| exitCode | null |
| gradleStarted | true |
| JUnit files | 0 |
| tests | 0 |
| failures/errors/skipped | 0/0/0 |
| historicalTrackedUnchanged | true |

Gradle 日志最后停在：

```text
> Task :test
```

没有写出 JUnit XML。说明测试进程在 1800s 上限内**没有完成**，不是通过、也不是逐项失败。

## 4. 与上一轮 665s 的区别

上一轮 665s 的 3 个 formal 测试在 **hash 校验阶段立即失败**，所以没有真正执行正式考试主体；那 665s 不是“完整功能回归”的时间。

本轮 11/11 冻结通过后，`C8FormalExamTest`、`MRXFormalTest`、`MRXRFormalTest` 开始真正运行，全套 `test` 在 1800s 内未完成，因此：

```text
FULL_REGRESSION = TIMEOUT
```

不能写成 PASS；不能把超时算作 507/507；也没有任何“全绿”JUnit 证据。

## 5. 归档完整性

- 主仓库历史 tracked digest 前后一致：`017d1450...`
- 历史 tracked files：594
- 未修改历史冻结 hash、manifest、E22 证据、学习算法
- 未删除失败测试
- 唯一改动：`tools/e222/isolated_full_regression.py`

## 6. 结论

- 混合 EOL 问题：**已修复**（sandbox 11/11，两个 allowlist 文件）
- Python 工程测试：**7/7 PASS**
- 冻结预检：**11/11 PASS**
- 全量 Gradle `test`：**TIMEOUT（1800s）**
- 完整回归 PASS：**未达成**
- E2.3 科学对照：**NOT_RUN**

## 7. 后续建议（需另行授权）

1. 需要更长的真实运行预算（>1800s），或
2. 对正式考试测试做单独的耗时基线/拆分执行，但不能因此跳过它们或声称完整回归 PASS。

本轮停止，等待独立审查；不启动 SD-04。
