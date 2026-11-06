# EH-003 报告：确认性证据与候选淘汰解耦

- 任务：Observable AI — Engineering Harness EH-003
- 性质：证据来源/状态语义修复，不是 MRX-02E，不是科研正式考试
- 起始 HEAD：`46ed45b08301404d2e63ae4356ee66023b0e3412`
- 目标文件：`src/main/kotlin/logic/e3/pack02/MRX2D.kt`
- 目标文件 Git blob：`243ea4a4eb905e67be0b92e134e7880b0cfe205f`（与包 manifest 一致）
- `fullRegression = NOT_RUN`
- `researchFormal = NOT_RUN`
- `independentClosedBook = NOT_RUN`
- C4/C8/H01 与 Research Console 状态未改变

---

## 0. 结论

EH-003 修复了两个语义缺陷：

1. **零淘汰的回放标签没有进入 observation**；
2. **`ONE_SURVIVOR_WITH_EVIDENCE` 借用了“曾发生过淘汰/曾有任意 observation”，而不是最终幸存者自己拥有 Known 匹配证据**。

修复后：

- 每个已付费 `(channel, entry)` 回放都产生可追踪 observation，`eliminated=0` 也记录；
- 只有最终幸存者自己拥有 Known 且匹配的标签，才返回 `ONE_SURVIVOR_WITH_EVIDENCE`；
- 幸存者全 Unknown、或证据只属于已淘汰候选 → 仍是 `SINGLE_BEHAVIOR_CLASS_PROVISIONAL`；
- 新查询揭示的标签也只确认当前留下且预测匹配的候选，不回溯继承给已淘汰候选；
- EH-001 的 `reusedLabels` 口径保持不变。

验收状态：**ENGINEERING_PASS**。

---

## 1. Gate 0：基线核对

- `git rev-parse HEAD`：`46ed45b`
- `git hash-object src/main/kotlin/logic/e3/pack02/MRX2D.kt`：
  `243ea4a4eb905e67be0b92e134e7880b0cfe205f`，与包一致。
- 检查真实签名：
  - `StudyCandidate`、`MRX2DChannel`、`MRX2Ledger.entries()/newQueries()/query()`；
  - `MRX2DObservation`、`MRX2DResult`；
  - `MRX2DActiveIdentifier.identify()`。
- 参考补丁与当前代码上下文一致；仍按源码最小修改，不直接覆盖。

---

## 2. Gate 1：RED

复制 `EH003EvidenceProvenanceContractTest.kt` 到 `src/test/kotlin/logic/e3/pack02/`，未改生产代码运行：

```powershell
.\gradlew.bat quickTest --offline --console=plain "-PquickTests=logic.e3.pack02.EH003EvidenceProvenanceContractTest"
```

结果：

- `7 tests completed, 5 failed`
- `BUILD FAILED in 7s`
- 业务断言失败，不是编译失败：

| 失败 | 旧值 | 期望 |
|---|---:|---:|
| 零淘汰确认唯一候选 | `SINGLE_BEHAVIOR_CLASS_PROVISIONAL` | `ONE_SURVIVOR_WITH_EVIDENCE` |
| 零淘汰 observation 数 | 0 | 1 |
| Unknown 唯一候选 observation 数 | 0 | 1 |
| 两 channel 同 key observation 数 | 0 | 2 |
| 多候选同确认 observation 数 | 0 | 1 |

日志：`docs/engineering/logs/eh003-red.log`

---

## 3. Gate 2：最小修复

只改 `MRX2D.kt`：

1. 新增 `confirmedCandidateIds`；
2. 回放时，`prediction == entry.rawLabel` 的候选加入 confirmed；Unknown 不加入；
3. 每个 entry 无条件追加 `MRX2DObservation`（不再仅在淘汰时记录）；
4. 单幸存者状态改为：
   `candidates.size == 1 && selected.canonical in confirmedCandidateIds`；
5. 新查询揭示标签后，把当前留下且匹配的候选加入 confirmed，不继承给已淘汰候选。

未改：

- `MRX2Ledger` / `StudyQueryController`；
- `MRX2Student`、候选搜索、预算、停止条件；
- EH-001 的 `reusedLabels += 1`；
- 旧 JSON、冻结结果、Research Console。

`git diff --stat`：

```text
build.gradle.kts                         |  1 +
docs/engineering/TESTING.md              |  1 +
src/main/kotlin/logic/e3/pack02/MRX2D.kt | 28 +++++++++++++++++++---------
```

---

## 4. Gate 3：GREEN

命令：

```powershell
.\gradlew.bat quickTest --offline --console=plain --rerun-tasks `
  "-PquickTests=logic.e3.pack02.EH003EvidenceProvenanceContractTest,logic.e3.pack02.EH001ReplayReuseContractTest,logic.e3.pack02.MRX2DEvidenceReplayTest"
```

结果：

- `BUILD SUCCESSFUL in 59s`
- `EXIT=0`
- 测试：
  - EH003：7/7
  - EH001：3/3
  - MRX2DEvidence：4/4
  - 合计 **14/14，0 failed**

日志：`docs/engineering/logs/eh003-green.log`  
JUnit 汇总：`docs/engineering/logs/eh003-junit-summary.json`

默认 `quickTest` 也已加入 EH003：

```powershell
.\gradlew.bat quickTest --offline
```

结果：

- `BUILD SUCCESSFUL in 1m 7s`
- 外层墙钟 67.6s（含 build.gradle 变更后的编译）
- 14 tests，0 failed

日志：`docs/engineering/logs/eh003-default-quicktest.log`

---

## 5. 不变量核验

- 回放不增加 Oracle：`newQueries=0`，`ledger.newQueries()` 前后不变；
- 一条标签淘汰两个候选：`reusedLabels=1`、`replayEliminated=2`，不混淆；
- 零淘汰确认：`reusedLabels=1`、`replayEliminated=0`、observation 1 条；
- 全 Unknown：observation 记录，但状态仍是 provisional；
- A 曾确认但被淘汰、B 全 Unknown 幸存：B 仍是 provisional，不继承 A 的证据；
- 两 channel 同 input key：observation 2 条，来源分别 QUERY/CHALLENGE；
- 单候选无任何标签：provisional，observation 为空；
- 新查询确认只作用于当前留下候选。

---

## 6. 未运行项与边界

- **未运行**无筛选 `gradlew test`、`gradlew build`、`gradlew check`；
- **未运行** `MRXRFormalTest`、`MRXFormalTest`、`MRXBaselineTest`、160 次科研考试；
- `fullRegression = NOT_RUN`
- `researchFormal = NOT_RUN`
- `independentClosedBook = NOT_RUN`
- 不声称科研 LEVEL 提升；不修改旧研究成绩。

---

## 7. Git

- 起始：`46ed45b`
- 生产修复：`src/main/kotlin/logic/e3/pack02/MRX2D.kt`
- 新测试：`src/test/kotlin/logic/e3/pack02/EH003EvidenceProvenanceContractTest.kt`
- 构建/文档：`build.gradle.kts`（默认 quickTest 加 EH003）、`docs/engineering/TESTING.md`
- 日志：`docs/engineering/logs/eh003-*.log`、`eh003-junit-summary.json`
- 最终提交 SHA：见本轮提交（`git log`）
- `git diff --check`：通过

验收：**ENGINEERING_PASS**。
