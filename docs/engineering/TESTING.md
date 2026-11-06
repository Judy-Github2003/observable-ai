# 工程测试分层（EH-002）

本项目有两类完全不同的测试：

- **工程契约测试**：验证代码接口/计量/回归，通常秒级；
- **科研实验测试**：`MRXRFormalTest`、`MRXFormalTest`、`MRXBaselineTest` 等，会运行大量世界、种子和正式/开发实验，耗时可达数十分钟，并写研究 JSON。

`gradlew test` 是**全量科研回归**，不是日常改代码的安全检查。日常工程修改请使用分层入口。

## L1：针对性快速测试

```powershell
.\gradlew.bat quickTest --offline
```

默认只运行：

- `logic.e3.pack02.EH001ReplayReuseContractTest`（3 项）
- `logic.e3.pack02.MRX2DEvidenceReplayTest`（4 项）

指定测试类：

```powershell
.\gradlew.bat quickTest --offline "-PquickTests=logic.e3.pack02.EH001ReplayReuseContractTest,logic.e3.pack02.MRX2DEvidenceReplayTest"
```

- 只接受类名/测试匹配模式，不传空列表；
- `-PquickTests=` 会在配置阶段失败，不会退化成全量；
- 错误的测试名会让任务失败，不会假绿；
- 第二次运行若输入未变，Gradle 可能显示 `UP-TO-DATE`；需要强制重跑时加 `--rerun-tasks`。

## L2：受影响模块回归

在知道改动影响哪个测试类时，显式传入多个 `--tests`：

```powershell
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRX2DG1G5Test"
```

这是**筛选后的 test**，不是无筛选全量。用于工程改动跨出 L1 类集、但仍局限在明确模块时。

## L3：全量回归 / 科研验收

```powershell
.\gradlew.bat test --offline
```

只在研究负责人明确授权、或正式科研验收时运行。它会重新执行 MRXRFormal 120 次、MRXFormal 28 次、MRXBaseline 54 次等历史实验；EH-001 记录约 25m30s，不应作为日常检查。

## 不要执行

- `gradlew test`（无筛选）
- `gradlew check`
- `gradlew build`
- 任何正式科研考试，除非单独授权

`quickTest` 保留原 `test` 任务不变；它只新增一个独立的 `Test` task，不修改学生算法、冻结协议、旧 JSON 或 Research Console 状态。
