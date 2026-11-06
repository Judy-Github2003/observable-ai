# EH-002 报告：快速工程测试与全量回归分层

- 任务：Observable AI — Engineering Harness EH-002
- 性质：构建/测试工程改造，**不新增 AI 学习算法**
- 起始 HEAD：`f6394ad691fa58b47a15d20c933250a4cfab1b03`
- 配置提交：`192cd778ae99e4c975256922cf66876fe7a535b3`（quickTest + 文档）
- 日志提交：`c7fbd5947938bbf0eda6bb0c0dbda74aca45b140`
- 报告提交：见本轮最终提交
- `fullRegression = NOT_RUN`
- `research_formal = NOT_RUN`
- `independent_closed_book = not_run`
- C4/C8/H01 与 Research Console 状态未改变

---

## 0. 结论

新增独立 Gradle `Test` task `quickTest`：

- 默认只执行 2 个明确的工程测试类、共 **7 项测试**；
- 支持 `-PquickTests=类名,...` 指定测试类；
- 空 `-PquickTests=` **配置阶段 fail-fast**，不会退化成全量；
- 错误测试名 → `No tests found` 失败，不会假绿；
- 原 `test` 任务语义保持不变；
- 全量回归在本轮**未运行**，按任务边界记录为 `NOT_RUN`。

验收状态：**ENGINEERING_PASS**（以 L1/L2 实际日志为据；不等同于科研等级）。

---

## 1. Gate 0：侦察

- 起始 `git rev-parse HEAD`：`f6394ad`（EH-001 完成提交）。
- 工作区只有 4 个既有未跟踪文档；源码无未提交改动。
- `build.gradle.kts`：
  - Kotlin JVM `2.2.21`、JDK 21；
  - 原 `tasks.test` 通过 `doFirst` 设置 `test.runtime.classpath`；
  - 原 `test` 无筛选，会执行全部测试。
- 已确认的快速工程测试：
  - `logic.e3.pack02.EH001ReplayReuseContractTest`：3 项；
  - `logic.e3.pack02.MRX2DEvidenceReplayTest`：4 项。
- 已确认的慢/写科研产物的历史测试（源码可见）：
  - `MRXRFormalTest`：20 世界×6 条件=120 次；
  - `MRXFormalTest`：7 世界×4 条件=28 次；
  - `MRXBaselineTest`：27 世界×2 种子=54 次；
  - `MRX2DG1G5Test` 等：候选×输入预测较多。
- `test`/`check`/`build` 的依赖：`test` 依赖 `testClasses`，`check` 依赖 `test`，`build` 依赖 `check`；因此无筛选运行 `test` 会触发历史科研运行。本轮未执行它们。

---

## 2. 变更

### 2.1 `build.gradle.kts`

只新增 `quickTest` 配置，未修改原 `tasks.test`：

```kotlin
val quickTestsProperty = providers.gradleProperty("quickTests").orNull
val quickPatterns = quickTestsProperty
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf(
        "logic.e3.pack02.EH001ReplayReuseContractTest",
        "logic.e3.pack02.MRX2DEvidenceReplayTest",
    )
if (quickTestsProperty != null) {
    require(quickPatterns.isNotEmpty()) { "quickTests must not be empty" }
}

tasks.register<Test>("quickTest") {
    group = "verification"
    description = "Run explicitly selected fast engineering contract tests only"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(tasks.named("testClasses"))
    filter { quickPatterns.forEach { includeTestsMatching(it) } }
    doFirst {
        systemProperty("test.runtime.classpath", sourceSets["test"].runtimeClasspath.asPath)
    }
}
```

关键点：

- 继承 `testClassesDirs`、`classpath`、`test.runtime.classpath`；
- 依赖 `testClasses`，不会绕过编译；
- `filter` 永远至少有一个包含模式；
- 空属性在配置阶段抛 `quickTests must not be empty`。

### 2.2 文档

新增 `docs/engineering/TESTING.md`：

- L1：`quickTest` 快速契约；
- L2：显式 `test --tests` 受影响模块；
- L3：全量 `test`，仅授权后运行；
- 明确禁止日常无筛选 `test/check/build`。

### 2.3 未修改

- `src/main/kotlin/logic/e3/pack02/MRX*.kt`、`C8*.kt`、`ActiveLearning.kt`；
- 任何学生算法、冻结协议、实验 JSON；
- 原 `tasks.test`；
- Research Console 数据 / C4/C8/H01。

---

## 3. Gate 1：现有筛选命令先验证

未执行无筛选 `test`。直接使用显式 `--tests` 的 L2 命令见下；`quickTest` 见 Gate 2。

---

## 4. Gate 2/3：实际执行

### 4.1 L1 首次 `quickTest`

命令：

```powershell
.\gradlew.bat quickTest --offline --console=plain
```

结果：

- `BUILD SUCCESSFUL in 25s`
- 外层墙钟：26.1s
- 执行任务：`quickTest`（1 executed）；编译/类任务 `UP-TO-DATE`
- XML：
  - `EH001ReplayReuseContractTest`：3 tests，0 failures，0.041s
  - `MRX2DEvidenceReplayTest`：4 tests，0 failures，0.012s
- 合计 **7 tests，0 failed**

日志：`docs/engineering/logs/eh002-quicktest-1.log`

### 4.2 L1 第二次 `quickTest`（缓存行为）

- `quickTest UP-TO-DATE`
- `BUILD SUCCESSFUL in 5s`
- 外层墙钟：5.5s
- 未重新执行测试；这是 Gradle 输入未变时的正常缓存，不是“假绿”。

日志：`docs/engineering/logs/eh002-quicktest-2.log`

### 4.3 L2 显式筛选回归

命令：

```powershell
.\gradlew.bat test --offline --console=plain --tests "logic.e3.pack02.MRX2DG1G5Test"
```

结果：

- `BUILD SUCCESSFUL in 1m 18s`
- 外层墙钟：78.9s
- 实际测试：1 test，0 failed；XML time 72.852s
- 这是显式筛选的 `test`，不是无筛选全量。

日志：`docs/engineering/logs/eh002-l2.log`

### 4.4 空自定义列表

命令：

```powershell
.\gradlew.bat quickTest --offline "--console=plain" "-PquickTests="
```

结果：

- `BUILD FAILED in 4s`，exit 1
- 原因：`quickTests must not be empty`
- 未退化成全量测试。

日志：`docs/engineering/logs/eh002-empty-list.log`

### 4.5 错误测试名

命令：

```powershell
.\gradlew.bat quickTest --offline "--console=plain" "-PquickTests=logic.e3.pack02.NoSuchTest"
```

结果：

- `BUILD FAILED in 5s`，exit 1
- 原因：`No tests found for given includes: [logic.e3.pack02.NoSuchTest]`
- 没有假绿。

日志：`docs/engineering/logs/eh002-bad-name.log`

### 4.6 `test` 保留语义

- 未运行无筛选 `test`。
- 执行 `.\gradlew.bat test --dry-run --offline`：
  - 任务图包含 `:test`，原任务存在；
  - `BUILD SUCCESSFUL in 4s`；
  - dry-run 只列出任务，不执行测试。
- 源码 diff 未触碰原 `tasks.test`，因此其全量行为保持不变。

---

## 5. 成本与边界

- L1 首次：25s（编译/类任务已 UP-TO-DATE；首次执行 `quickTest`）
- L1 热缓存：5s（`UP-TO-DATE`）
- L2 单项：1m18s（`MRX2DG1G5Test` 本身 72.9s）
- 与 EH-001 的 25m30s 只作背景参考；本轮没有做同环境全量对照，**不宣称精确加速倍数**。
- `fullRegression = NOT_RUN`
- `research_formal = NOT_RUN`
- `independent_closed_book = not_run`

### 已知限制

- `quickTest` 默认只覆盖两个契约类；新增测试需要显式 `-PquickTests` 或更新默认列表。
- 未新增 `researchTest` 精确任务；本轮不登记正式科研类，避免模糊匹配误跑。
- 未新增 `fullRegression` 别名；原 `test` 保持原名，避免多一个可能被误用的全量入口。
- L2 仍使用 `test --tests`，需要调用者明确类名。

---

## 6. 验收判定

**ENGINEERING_PASS**

依据：

1. 编译/测试类任务成功（`quickTest` 依赖链）；
2. L1 实际执行 7 项、0 failed，类集与预期一致；
3. L2 显式筛选 1 项、0 failed；
4. 空列表 fail-fast，未退化全量；
5. 错误测试名失败，未假绿；
6. 原 `test` 未被修改，dry-run 任务图存在；
7. `git diff` 仅构建配置 + 文档 + 日志，无学生算法/旧 JSON 改动；
8. 全量回归未运行并明确记录。

---

## 7. Git

- 起始：`f6394ad`
- 配置：`192cd77`
- 日志：`c7fbd59`
- 报告：见本轮最终提交
- 变更：
  - `build.gradle.kts`
  - `docs/engineering/TESTING.md`
  - `docs/engineering/logs/eh002-*.log`
  - `EH-002-REPORT.md`
- `git diff --check`：通过
- 已推送：`f6394ad..` 本轮最终提交（实际 SHA 见最终答复与 `git log`）
