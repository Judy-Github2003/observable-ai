# B0 M3-1c 报告

```text
子任务: M3-1c
状态: complete
基线 SHA: ea002c30a4eae1d523298c559b48284d3d75b619（M3-1b）
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 147/147；新增 7/7；失败 0；合计 154/154
新文件/修改文件:
  src/main/kotlin/logic/capability/StudentComposition.kt
  src/test/kotlin/logic/capability/StudentCompositionTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/README.md
  experiments/capability-foundation-b0/reports/M3-1c-REPORT.md
  experiments/capability-foundation-b0/reports/M3-REPORT.md
关键语义及接口: composeAndPropose → PROPOSED；verifyAndActivate → ACTIVATED；HeldOutEvaluator 只在激活后评估
失败用例/未实现/已知边界: 留出集不参与学习；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M4-1a
```

## 做了什么

- `StudentCompositionService.composeAndPropose`：
  1. 从注册表取 `activeCapabilities()`（已激活、验证 pass、未撤销、依赖健康）；
  2. 有界搜索 + 公开样本筛选；
  3. 仅 `IDENTIFIED` 的候选提交为 `candidate`，provenance 写 `acquisition_method=student_composed`、`teacher_calls=0`、搜索候选数、预算原因、公开样本数；
  4. 搜索本身不写 `verified` / `active`。
- `verifyAndActivate`：必须由宿主登记的可信套件验证通过，再走 M2b 激活门禁；任何一步失败返回 `REJECTED`。
- `HeldOutEvaluator`：只在组合、验证、激活完成后对留出样本执行，结果只用于报告，不回流到搜索。
- `CompositionResult` 记录阶段、候选、证据、验证、激活、搜索候选数、预算原因与耗时（耗时仅信息，不参与判定）。

## 两个新世界（同一套 IR 原语，空能力库）

| 世界 | 字段 | 目标规则 | 公开样本 | 结果 |
|---|---|---|---|---|
| 设备兼容 | `power/port` | `power == usb AND port == c` | 4 组全组合 | `PROPOSED → ACTIVATED`，留出集 2/2 |
| 访问资格 | `role/level` | `role == admin AND level == 3` | 4 组全组合 | `PROPOSED → ACTIVATED`，执行正确 |

两例都未使用任何手写目标 AST；候选由搜索器在公开字段/常量上枚举，由证据筛选确定。

## 退化与拒绝

- **删掉关键库能力**：目标 `isRed AND shape==circle` 在有 `core.is_red` 时可组合并锁定依赖；撤销该能力后 `activeCapabilities()` 变空，搜索从 `PROPOSED` 退化为 `REFUSED / no_consistent_candidate`。
- **未验证不能激活**：候选写入后直接用不存在的 run_id 激活 → `verification_not_found`；正式执行 → `not_active`。
- **证据不足**：只给一条正例 → `AMBIGUOUS`，给出区分性查询，不注册。
- **超出假设空间**：3 变量多数函数在 `maxDepth=3` 下 → `REFUSED / no_consistent_candidate`，不硬猜。

## 测试

| 用例 | 覆盖 |
|---|---|
| C01 | 设备世界：组合→注册→验证→激活→执行；留出集 2/2；provenance 含 `student_composed` |
| C02 | 访问世界：同一套原语、不同规则；组合与激活正确 |
| C03 | 撤销关键库能力后搜索可观测退化；有库时依赖被 pin 到 `core.is_red@1` |
| C04 | 未验证候选不能激活、不能正式执行 |
| C05 | 证据不足 → `AMBIGUOUS` 且不注册 |
| C06 | 超出假设空间 → `REFUSED` 且不注册 |
| C07 | provenance 记录搜索规模与 `teacher_calls=0` |

## 归因与边界

- **可以写**：这两个具体候选由组合算法在公开信息上生成、由公开样本筛选、由独立套件验证后激活，来源记 `student_composed`。
- **不能写**：搜索算法、IR、验证/激活机制是开发者预置的；没有“学生发明归纳算法”“自主获得概念”或“超越 LLM”的证据。
- 留出集由测试代码持有，组合与筛选接口不接收它；但这不是独立闭卷，留出集仍由实现者保存。
- `student_composed` 只描述本次候选来源，不代表能力已泛化到开放世界。
- 耗时字段受机器负载影响，报告只把它当参考，不用来比较算法优劣。
- 独立闭卷 `not_run`；154/154 是实现者自测。
