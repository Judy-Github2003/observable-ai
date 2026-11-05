# E3-A0/A1 工作报告

```text
阶段: E3-A
范围: A0（冻结基线/隔离目录）+ A1（公共数据契约）
状态: complete
代码提交: f903f82714bc18cc8ff6f1da937d033f1d872113
工作状态提交: 0eb54e99ff8522fdcc39b80ffddf9a82d98ce35e
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 182/182（B0 173 + E3 9），失败 0
独立闭卷: not_run
```

## A0：基线冻结与隔离

- 读取 `AGENTS.md`、`docs/RESEARCH-NORTH-STAR.md`、B0 `WORKSTATE.md`、C 专项考试结论。
- 新增实验目录 `experiments/representation-evolution-e3/`（SPEC/DECISIONS/WORKSTATE）。
- 新增独立包 `src/main/kotlin/logic/e3/`，不修改 B0 生产代码、测试、冻结报告或考试记录。
- 基线回归：B0 173/173 通过（`--rerun-tasks`，2026-11-05）。
- 记录 JDK 21.0.11、Gradle 8.14.6；`independent_closed_book=not_run`。

## A1：公共数据契约

实现：

| 文件 | 内容 |
|---|---|
| `Observation.kt` | `VisibleSchema`、`ObservedValue`、`Observation`、`EvidenceProvenance`、`FeatureKey` |
| `Diagnosis.kt` | `FindingKind`、`EvidenceScope`、`DiagnosticFinding`、`DiagnosticReport` |
| `Probes.kt` | `ProbeRequest`（重复标签 / 公开实例 / 不动作） |
| `Validation.kt` | 校验错误码、严格字段/值域检查、稳定 JSON 编码 |

语义要点：

- 字段缺失与 `ObservedValue.Unknown` 严格区分；`label=null` 表示未观察，不能当 false。
- 规范化特征键按字段排序，未知值编码 `?`，转义 `\ | =`。
- `DiagnosticFinding` 把 `sourceHypotheses`、`observationConclusion`、`EvidenceScope`（假设空间/观察域/查询域）分字段表达；允许多 finding 与 `UNDETERMINED`。
- `strategySource=designer_primitive`；具体世界诊断才是 `student_diagnosed`。
- JSON 编码稳定，不含 `worldKind`、`hiddenFeature`、`targetAst`。

机器可读错误码：

```text
schema_empty / schema_blank_field / schema_empty_domain
blank_observation_id / blank_object_id / blank_source_id / invalid_round
missing_field / unknown_field / invalid_value
duplicate_observation_id / duplicate_object_round
```

## 测试

- `E3ContractTest`：9/9；全套 **182/182**，0 失败。
- 命令：`JAVA_HOME=C:\Users\j1437\.jdks\jbr-21.0.11; .\gradlew.bat --offline --no-daemon test`
- 未实现诊断算法；A1 不运行 Composer/Filter/Interpreter。

## 未做 / 边界

- 没有世界生成器、oracle、隐藏数据、评分器（A2 起）。
- 没有碰撞检测、噪声判断或探测选择策略。
- E3-B/C 未授权；本轮只 A0/A1。
- 独立闭卷 `not_run`；E3 结果为 `implementer_run`。

## 下一步

`A2`：独立世界生成器最小版（SOLVABLE + LATENT_ALIASING），带随机种子、公开出题/隐藏真值分层；完成后停止等待“继续”。

## 归因

- 契约与校验实现：DeepSeek Harness，2026-11-05。
- 诊断算法、教师、学生判断均未参与 A0/A1。
- B0 原实现与冻结版本保持原归属不变。
