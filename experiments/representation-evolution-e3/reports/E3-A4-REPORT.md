# E3-A2/A3 复核修正 + A4 工作报告

```text
阶段: E3-A
范围: A2/A3 三个复核问题 + A4 可观察碰撞检测
状态: complete
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 208/208（B0 173 + E3 35），失败 0
独立闭卷: not_run
```

## 1. worldId 与运行环境隔离

- 公开 `worldId` 改为 `w-<16 hex>`：SHA-256 单向摘要，不含 seed/index，也不能直接反推。
- 新增测试：ID 匹配 `^w-[0-9a-f]{16}$`，不含 seed 十进制、`seed`、`index`。
- 设计边界：`E3WorldGenerator`/`E3AdvancedWorldGenerator`/`HiddenTruth`/`E3WorldOracle` 都在 `logic.e3.evaluator`；学生只应接收 `StudentView(public, PublicQueryOracle)`，不得把 evaluator 包放进学生运行环境。当前同 JVM 测试只证明接口隔离，**不是进程级隔离**；正式实验需独立进程/独立 classpath。
- 生成器、seed、HiddenTruth 不得进入学生输入审计流。

## 2. AMBIGUOUS_EVIDENCE 样本数

- 原文错误：报告写 5 条，实际生成 4 条。
- 修正：增加第 5 条同可见向量的重复观测（来源不同、标签相同），现为 **5 条公开观察**。
- 新增测试：A3 三族公开观察均为 5 条，避免仅靠样本数量猜家族。
- 后续对照设计：在后续实验中用 4/5/6 条、不同排列的公开样本做跨家族对照；当前 A3 固定为 5 条，仅作为最小一致格式。

## 3. SEARCH_LIMITED 的真实 Composer 低预算对照

新增 `E3WorldGeneratorTest` 中的真实 `CapabilityComposer` 对照，结果写入
`reports/a3-search-limited-composer.json`：

| maxDepth | generated | reason | candidates | 拟合公开数据 |
|---:|---:|---|---:|---:|
| 2 | 16 | depth_limit | 10 | 0 |
| 3 | 181 | depth_limit | 175 | 3 |

解释：

- 低预算实际结果已记录；depth=3 时有 3 个浅候选拟合公开数据，说明公开数据本身不足以唯一锁定三变量 XOR。
- 本轮未运行高预算对照，不能宣称“预算耗尽是唯一失败原因”，也不能宣称“已证明表示不足”。
- `ruleDepth=4` 只是评测端元数据，不作为学生可见证据。

## 4. A4 可观察碰撞检测器

文件：`src/main/kotlin/logic/e3/CollisionDetector.kt`

```text
ObservableCollisionDetector.detect(schema, observations) -> CollisionReport
CollisionFinding(featureKey, participants, scope)
CollisionParticipant(observationId, sourceId, round, label)
```

规则：

- 只按**完整且全部 Known 的可见向量**分组；
- 同一组出现不同布尔标签 → 报告冲突；
- 保留 observationId、sourceId、round、label；
- 含 `Unknown` 的观察不参与分组，列入 `excludedUnknownValueIds`；
- 未标注观察列入 `excludedUnlabeledIds`；
- 非法输入返回机器可读 `validationErrors`，不产生冲突；
- `CollisionReport` 只表达可观察冲突与适用范围，不推断隐藏属性、噪声、随机性或世界原因；
- 不读取 evaluator family/HiddenTruth/target rule（代码位于 `logic.e3`，不 import evaluator）。

测试覆盖：

- 多来源同向量冲突；
- 同来源不同轮次冲突；
- Unknown 值排除且不产生假冲突；
- 未标注排除；
- 同标签/不同向量无冲突；
- 多冲突组与稳定顺序；
- 非法输入错误；
- 编码不含 `worldKind`/`hiddenFeature`/`targetAst`/`noise`。

## 5. 测试

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon test
```

- 总计 **208 passed / 208 total，0 failed**
- B0 子集 **173/173**
- E3 子集 **35/35**

## 6. 提交与下一步

- 代码/测试提交：`ebd7268b14724fc056251784062246298dc4a6a1`。
- 未实现 A5、未修改 B0、未授权 E3-B/C。
- `independent_closed_book=not_run`；A4 只做证据检测，不是诊断原因。

下一步：`A5` B0 诊断适配器（只读调用 Composer/Filter，不重写）。
