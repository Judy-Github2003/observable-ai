# GNJV-01 Phase-A 结果：参考学习器整合、公开合同 5/5、学生源码冻结

> 执行者：DeepSeek Harness，2026-11-08。本文件只记录可见的执行、输入、命令与结果；
> 不记录模型内部推理。验收裁定属于研究负责人/ChatGPT 审查，本文件不写 ACCEPTED。

## 0. 来源与诚实声明

- 交付包：`Observable-AI-GNJV-01-PHASE-A-STUDENT-FREEZE-2026-11-08.zip`
  - SHA-256：`ec366e4d4947996fe6f510dead5d4e6d09f1b45cc911335d165b2be88cee5025`
  - 包内 9 个文件按 `SHA256SUMS.txt` 校验：**全部 OK**
- `GNJV01Kernel.kt` 与 `GNJV01PublicContractTest.kt` 是 **ChatGPT 提供的参考实现与公开合同**，
  不是学生自主发现，也不是本实现的原创算法。本实现只做整合、核对、冻结。
- 只有三个预设有向无环候选结构；这是受限程序化版本空间消除 + minimax 实验选择，
  不等于一般因果发现、元学习、自主表征发明或智能涌现。
- 未读取封存考官源码，未运行 Phase B，未根据成绩调参。

## 1. 起点与门禁

| 项 | 值 |
|---|---|
| START HEAD | `b7da2cb8ad828c5853ebcda17dd0ddc64519957e` |
| 起始工作区 | 仅 4 个原有未跟踪研究文档，无已跟踪改动 |
| 授权记录 | `research-console/repairs.json` → `GNJV-01-PHASE-A-FREEZE-2026-11-08`（`research_owner`，来源=研究负责人交付上述 ZIP） |
| 门禁 | `console_cli.py preflight --task GNJV-01-PHASE-A-FREEZE-2026-11-08` → `AUTHORIZED_REPAIR`，exit 0 |
| WORKSTATE | `ok: true`（`experiments/representation-evolution-e3/WORKSTATE.md`） |

授权范围：整合 + 公开合同定向测试 + 一次性冻结 + 提交。不含 Phase B、考官源码、历史全量回归、SD-04。

## 2. 整合内容

复制到仓库（未改语义，字节与包内一致）：

- `src/main/kotlin/logic/e3/gnjv/GNJV01Kernel.kt`
- `src/test/kotlin/logic/e3/gnjv/GNJV01PublicContractTest.kt`
- `tools/gnjv/GNJV01Smoke.kt`、`tools/gnjv/freeze_learner.py`、`tools/gnjv/verify_public_smoke.py`
- `tools/research-console/register_gnjv_01.py`（授权登记脚本）

静态核对（未改动代码）：`do` 干预按结构方程计算后代、禁止干预 `U`、三候选被动观测同为 `X=Y`、
证据不一致时保留候选并拒绝给出唯一结构、`predict` 对未观测干预返回 `null` 而不是 `false`。
未发现需要修改的语义缺陷；因此没有产生 RED，也没有“修复后 GREEN”的记录。

## 3. 公开合同真实结果

命令（仓库内，offline，JDK 21 `C:\Users\j1437\.jdks\jbr-21.0.11`）：

```powershell
.\gradlew.bat quickTest --offline --console=plain "-PquickTests=logic.e3.gnjv.GNJV01PublicContractTest"
```

| 项 | 值 |
|---|---|
| 结果 | **BUILD SUCCESSFUL**，5 tests / 0 skipped / 0 failures / 0 errors |
| JUnit XML | `build/test-results/quickTest/TEST-logic.e3.gnjv.GNJV01PublicContractTest.xml`，`time=0.022` |
| 构建耗时 | 30s（首次编译全量 main+test 源码） |
| 日志 | `docs/research/gnjv-01/QUICK-TEST-PUBLIC-CONTRACT.log` |

5 个用例：`threeDistinctHypothesesWithIdenticalPassiveObservation`、
`activeChoosesDiscriminativeIntervention`、`causalOutcomeFiltersWithoutWorldId`、
`incompatibleEvidenceNeverCreatesFalseCertainty`、`unknownIsNotCoercedToFalse`。

可选非 Gradle smoke：**NOT_RUN**（本机无 `kotlinc`，`java` 也不在 PATH）。
不得用 Gradle 结果冒充 smoke，反之亦然。

## 4. 冻结

`freeze_learner.py --repo . --examiner-sha256 aa6838...c57c` **只执行一次**，
写入 `docs/research/gnjv-01/FREEZE.json`：

| 项 | 值 |
|---|---|
| 协议 | `GNJV01-PHASE-A-v1` |
| `gitHeadBeforeFreezeCommit` | `b7da2cb8ad828c5853ebcda17dd0ddc64519957e` |
| 学生源 SHA-256 | `0380f3300f503ecc42c171a2a4be45821864e9b07a17d2e6815d3d8c756af3d3` |
| 公开测试 SHA-256 | `4228f76043a5c9a8ee3c7e844e47cc79111acb6ad88fb7a7c24f8bef7f3a20c0` |
| 封存考官 ZIP SHA-256 | `aa6838728aac4c3c4c3cfd35170b6961ce6b4809abc0bd69f13f39789761c57c`（由 README 提供，未取得 ZIP） |
| 独立闭卷 | `NOT_RUN` |
| 历史全量回归 | `NOT_CLAIMED` |
| 科学考试 | `NOT_RUN` |

`FREEZE.json` 的 `workingTreeStatusBeforeFreeze` 逐行记录了冻结瞬间的工作区状态，
包含 4 个原有未跟踪文档——这是事实记录，不代表它们被纳入版本控制。

**Freeze commit：`6d62905c908be443378af98b79af927f2e5e13dc`**

提交后核对：`git cat-file blob HEAD:<path>` 的 SHA-256 与 FREEZE.json 及工作区三方一致，均为 LF。

## 5. 已知限制与风险

1. 三个候选结构本来就由设计者给定且可辨识；本实验证明的是受限闭环。

   `HELDOUT` 是另一外生状态（`U'=¬U`），不是新结构；只能称“未见干预状态预测”。

2. 简单世界中 `FIXED` 通常很强；不能预注册 `ACTIVE` 必胜 `FIXED`。机制证据与策略增益是两个结论。

3. 同组 4 个 seeds 只改变外生状态/随机基线，不是 4 种不同世界结构，统计推断须谨慎。

4. **换行风险**：本仓库 `core.autocrlf=true` 且 `.gitattributes` 为 `* text=auto`。
   当前工作区与 blob 都是 LF，哈希三方一致；但若在别处重新 checkout，可能得到 CRLF 并破坏
   FREEZE 哈希（E2.3V 已因此耗过时间）。Phase B 运行前必须重新核对工作区文件哈希，
   或在 `core.autocrlf=false` 的环境下运行，不得用重新 checkout 后的文件直接开考。

5. 历史全量回归仍未 PASS（最后一次为 1800s TIMEOUT）；本轮未运行，也不得写成 PASS。

6. 独立闭卷 `NOT_RUN`：若实施者后来看到封存考官源码，本实验只能称“冻结学习器后延迟揭示的开发实验”。

## 6. 交付物

- 冻结源码 2 份 + 工具 3 份 + FREEZE.json
- 公开合同测试日志与 JUnit XML
- Console 授权事件（`research-console/history/ev-1794151822937-1ef4d9.json`）与 `repairs.json` 记录

## 7. 本轮停止点

已交回 **freeze commit `6d62905`**。按协议此处停止：不请求、不下载、不推测封存考官内容；
等待研究负责人确认后再揭示 Phase-B ZIP。
