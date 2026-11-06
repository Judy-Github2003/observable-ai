# EH-001 工程报告：旧标签复用计量修复

- 任务：Observable AI — Engineering Harness v0.1 / `EH-001`
- 性质：工程契约修复，**不是 MRX-02E，不是科研正式考试**
- 包基线：`2ba7905a9db0eb68a367900a9cbd4a73a164a1ab`
- 实际执行 HEAD：`025298c21afe8dfe0c81993cb050f147fadadb37`
- 目标文件：`src/main/kotlin/logic/e3/pack02/MRX2D.kt`
- 目标文件 Git blob：`88a6a3d8e7ef5434b9d8242bb9bf1e3a28b443d1`（与包 manifest 一致）
- 生产变更：1 个计数表达式
- 正式研究考试：**未运行**（EH-001 不授权、不需要）

---

## 1. Gate 0：侦察与基线确认

- `git rev-parse HEAD`：`025298c`（MRX-02D 完成后的工程状态；包基线 `2ba7905` 是 MRX-02D 代码提交，目标文件在此后未变）。
- `git hash-object src/main/kotlin/logic/e3/pack02/MRX2D.kt`：
  `88a6a3d8e7ef5434b9d8242bb9bf1e3a28b443d1`，与 `PACKAGE-MANIFEST.json` 一致。
- 工作区在复制测试前只有 4 个既有未跟踪文档，没有已修改的源码。
- 阅读：
  - `MRX2D.kt` 的 `MRX2DActiveIdentifier.identify()`；
  - `MRX2DEvidenceReplayTest`；
  - `MRX2Evidence.kt` / `MRX2Ledger` 的 `newQueries()`、`entries()`；
  - 包内契约、计划、RUNBOOK、参考补丁。

诊断：

- 旧代码 `reused += before - candidates.size` 统计的是**被回放淘汰的候选数**；
- 契约要求 `reusedLabels` 统计**实际处理的已有 `(channel, ledger entry)` 条数**；
- 一条旧标签确认一个候选（不淘汰）时旧值 0；一条旧标签淘汰两个候选时旧值 2；两者都违背契约。

---

## 2. Gate 1：RED

复制 `EH001ReplayReuseContractTest.kt` 到 `src/test/kotlin/logic/e3/pack02/`，未修改生产代码运行：

```text
3 tests completed, 3 failed
```

失败断言：

| 测试 | 期望 | 实际 |
|---|---:|---:|
| 一条确认标签，无淘汰 | `reusedLabels=1` | **0** |
| 两条确认标签，无淘汰 | `reusedLabels=2` | **0** |
| 一条标签淘汰两个候选 | `reusedLabels=1` | **2** |

这是真实 RED：编译成功，失败原因是 `reusedLabels` 契约不一致，不是缺类/语法错误。

---

## 3. Gate 2：最小修复

生产代码只改一处：

```diff
-                reused += before - candidates.size
+                // EH-001: 复用计数按“已购买的 (channel, entry)”计，而不是按被淘汰候选数计。
+                reused += 1
                 replayEliminated += before - candidates.size
```

未改：

- `MRX2Ledger` / `StudyQueryController`；
- 候选筛选、查询预算、停止条件；
- `replayEliminated` 的候选淘汰计算；
- 状态语义；
- 旧 JSON / 历史 manifest。

`git diff --check` 通过；生产 diff 仅 `MRX2D.kt` 上述两行。

---

## 4. Gate 3：GREEN 与专项回归

```text
EH001ReplayReuseContractTest  3/3 通过
MRX2DEvidenceReplayTest       4/4 通过
```

不变量验证：

- 回放不新增 Oracle：`result.newQueries=0`，`ledger.newQueries()` 前后不变；
- 一条标签淘汰两个候选：`reusedLabels=1`、`replayEliminated=2`；
- 两条确认标签：`reusedLabels=2`、`replayEliminated=0`；
- Unknown 测试继续通过（Unknown 不匹配、不矛盾）；
- 状态仍为 `SINGLE_BEHAVIOR_CLASS_PROVISIONAL` / `ONE_SURVIVOR_WITH_EVIDENCE`，没有把计量修复包装成能力提升。

全量 Gradle：

```text
TOTAL tests=387 failures=0 errors=0 skipped=0
BUILD SUCCESSFUL in 25m 30s
```

全量测试 387/387（B0 173 + E3 214；EH-001 新增 3 项契约测试）。慢的原因是全量套件重新执行 MRXRFormal 120 次、MRXFormal 28 次、MRXBaseline 54 次等历史科研运行，不是 EH-001 失败。

---

## 5. 工程 Harness 试点评估

1. **能否只通过架构索引找到正确文件？**
   能。`ARCHITECTURE.md` 指向 `MRX2D.kt`，实际 blob 与 manifest 一致；无需在 `MRX2Evidence.kt`/`MRX2C.kt` 中猜测。
2. **是否一次看懂真实 Kotlin 接口？**
   是。`MRX2DChannel`、`MRX2Ledger.entries()`、`newQueries()`、`MRX2Cost` 的签名与包内描述一致；没有编译错误。
3. **红灯与故障是否准确对应？**
   是。三个失败断言正好对应契约表的三行，期望/实际数值与旧表达式一致。
4. **最小改动下 GREEN + 专项回归？**
   是。只改一行计数表达式；契约测试与既有证据测试通过。
5. **契约哪里可以更精确？**
   - `reusedLabels` 是否应在“所有候选对该 entry 都是 Unknown”时仍计 1？契约写“无论是否淘汰，读取一条算一次”，本文按此实现；建议下一版明确写“即使全 Unknown 也计 1”。
   - 同一 channel 同一 entry 在一个 `identify()` 内只会被循环一次；跨 channel 同 inputKey 分别计数，建议在契约中给一个双 channel 例子。
   - `MRX2Cost.oracleReuseUses` 与 `reusedLabels` 的口径不同，建议在报告模板中固定一行对照，避免后续再次混用。

---

## 6. 未完成 / 风险

- 全量回归结果待填；若出现非 EH-001 相关失败，按实际环境/已有问题报告，不改写为本修复失败。
- 未运行 160 次正式研究考试；EH-001 不涉及科研等级。
- 未修改 `MRX2CExactCover`、`MRX2Student` 或候选空间；这些属于后续任务。
- `independent_closed_book=not_run`；C4/C8/H01 状态不变。

---

## 7. 交付清单

- 生产修复：`src/main/kotlin/logic/e3/pack02/MRX2D.kt`
- 契约测试：`src/test/kotlin/logic/e3/pack02/EH001ReplayReuseContractTest.kt`
- 本报告：`EH-001-REPORT.md`
- Git diff：仅一行计数表达式 + 注释
- 提交 SHA：见本轮最终提交
- 全量测试：387/387，0 failed（B0 173 + E3 214）
