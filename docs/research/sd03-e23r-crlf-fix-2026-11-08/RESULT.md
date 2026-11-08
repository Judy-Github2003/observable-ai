# E2.3R CRLF 修复：冻结预检 9/11，停止于全量回归之前

## 0. 结论

```text
FREEZE_PREFLIGHT = FROZEN_SOURCE_MISMATCH
matchedSources = 9 / 11
gradleStarted = false
FULL_REGRESSION = NOT_RUN
```

按任务书，`--freeze-only` 未达到 11/11 时必须停止，**不得**运行 `--run-full`、不得改 manifest、不得重签 hash。

## 1. 安装

- 起始 HEAD：`f1d153f0299de364ad1f5be28e15cf24eb5a6451`
- 工具补丁提交：`1f480a383b7426893d6334bea058dde5ee74e6e7`
- `apply_patch.py --check`：`READY_TO_INSTALL`
- 应用：`INSTALLED`
- 二次 check：`ALREADY_INSTALLED`（幂等）
- 只改：
  - `tools/e222/isolated_full_regression.py`
  - `tools/e222/frozen_hash_preflight.py`（新增）
- 未改学习算法、历史 source、manifest。

## 2. 工程测试

`python -m unittest discover -s tests -v`：

- **4/5 通过**
- 1 个合成测试失败：`test_full_run_uses_lf_and_reports_real_junit_count`
  - 假仓库只创建了 `gradlew` shell 脚本；
  - Windows runner 选择 `gradlew.bat`，触发 `[WinError 2]`；
  - 这是合成测试的 Windows 兼容缺陷，不是实际仓库回归结果。

`py_compile`：通过。

## 3. 冻结预检

`python tools/e222/isolated_full_regression.py --repo . --freeze-only`：

| 项 | 值 |
|---|---:|
| status | `FROZEN_SOURCE_MISMATCH` |
| sourceCount | 11 |
| matchedSources | **9** |
| gradleStarted | false |
| historicalTrackedUnchanged | true |
| sandbox | `observable-e222-qwyn_rv8` |

失败 2 项：

| manifest | source | expected | actual（LF） |
|---|---|---|---|
| `mrx-01r-freeze.json` | `MRXRSpace.kt` | `f2459391...` | `61bed653...` |
| `mrx-01r-freeze.json` | `MRXRStudent.kt` | `8ebfb76f...` | `9708efd5...` |

`actual` 是 LF checkout 的字节哈希；`expected` 是历史 manifest 中的 CRLF 字节哈希。其余 9 个源文件的 expected 是 LF，因而与 LF checkout 匹配。

这证明：

> 历史 3 份 manifest 的 11 个源文件使用了**混合换行符**，不存在一个统一的 LF 或 CRLF checkout 能同时满足全部 11 个字节哈希。

- 若强制 LF：这 2 个失败；
- 若强制 CRLF：另外 9 个失败；
- 任何统一转换都会改变历史哈希含义。

## 4. 为什么不继续

任务书明确规定：`--freeze-only` 未通过时立即停止，查看 expected/actual，不得通过转换源文件、修改 manifest 或关闭校验解决；验收文档还明确“换行符仍不匹配，停止分析 Git attributes/clean/smudge filter，不得直接修改历史源码”。

因此：

- 未运行 `--run-full`；
- 未用旧 665s 结果冒充本轮 PASS；
- 未修改冻结 hash、manifest、学习算法；
- 未删除失败测试。

## 5. 归档完整性

- 主仓库历史 tracked digest 未变；
- 新 sandbox 保留用于调查；
- 本轮没有新的科研实验，`E2.3ScientificEvaluation=NOT_RUN`；
- 未启动 SD-04。

## 6. 需要的后续决定（不属于本轮）

1. 由归档所有者确认 11 个历史源文件的原始换行符，并发布一份**逐文件 EOL 清单**；或
2. 明确接受“按规范化 LF 重新计算历史 hash”的新冻结协议，并承担历史证据语义变化；
3. 在该决定前，E2.3-R 全量回归不能通过 freeze preflight。

本轮停止。
