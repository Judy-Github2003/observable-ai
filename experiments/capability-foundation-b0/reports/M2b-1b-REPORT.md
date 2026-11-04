# B0 M2b-1b 报告

```text
子任务: M2b-1b
状态: complete
基线 SHA: 264ccd702fb618a246d86d798825ef0a9575981f
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 70/70；新增 16/16；失败 0；合计 86/86
新文件/修改文件:
  src/main/kotlin/logic/capability/TrustedVerification.kt
  src/test/kotlin/logic/capability/TrustedVerificationTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M2b-1b-REPORT.md
关键语义及接口: TrustedVerificationSuite / VerificationCase / VerificationOutcome；宿主登记表盖章 source；verdict 只由引擎计算
失败用例/未实现/已知边界: 未写库、未激活；只支持 Bool3 输出；独立闭卷 not_run
独立闭卷: not_run
下一个最小子任务: M2b-1c
```

## 做了什么

- 新增 `TrustedVerificationSuite`（`suite_id/suite_version/suite_hash` + 只读逐例列表）、`VerificationCase`（`case_id` + 类型化输入 + 预期结果）、`VerificationOutcome`（逐例结果 + 汇总 + verdict/code）。
- `suite_hash = SHA-256("verification-suite-v1\n" + 规范化 JSON)`：Map key/Record 字段排序，用例顺序保留；不用 `toString()`。
- `TrustedSuiteRegistry` 由宿主登记套件并盖章 `source`（`host_fixture` / `implementer_run`）；同一 `(suite_id, suite_version)` 内容不同 → `suite_version_conflict`，同内容重复登记幂等。
- `TrustedVerificationEngine.verify(capabilityId, capabilityVersion, suiteId, suiteVersion, budget)`：
  1. 从登记表取套件（调用方传的哈希不被采用）；
  2. `CapabilityInspector` 先检查定义：类型检查（含 `Call` 的 `(id,version,hash)` 锁定）→ 复算 `contentHash`；失败直接拒绝；
  3. 仅接受 `Bool3` 输出；
  4. 逐例经 M1 解释器执行，预算由 `EvalBudget` 限制；
  5. 判定与覆盖度见下。
- API 不存在 `passed=true` / `verdict=pass` 入参，调用方无法旁路成绩。

## 判定语义

- 实际 `Error` 一律失败，即使预期也是 `Error`；预期 `Error` 本身非法（`invalid_expectation`）。
- `Known` 与 `Known` 精确比较；`Unknown` 不当 `false`。
- 预期 `Unknown` 且实际 `Unknown` 算匹配，但**不计入**确定覆盖度。
- Bool3 覆盖度：按“规范化输入”去重的确定用例 ≥ 4，且至少 1 真 1 假；否则 `suite_inadequate`。
- 空套件 `suite_empty`；重复 `case_id` `duplicate_case_id`；任一用例不通过 `case_failed`。
- 覆盖度只取实际为 `Known(Bool)` 且与预期一致的用例；“独立”按输入去重近似，不是严格的统计独立性证明。

## 测试

| 用例 | 覆盖 |
|---|---|
| V01 | 4 个独立确定用例（2 真 2 假）通过，哈希/来源/内容哈希正确 |
| V02 | 全真 4 例全部通过仍 `suite_inadequate` |
| V03 | 只有 3 个确定用例 → 覆盖不足 |
| V04 | 缺字段 → `Unknown`，预期 false 判失败，不算 false |
| V05 | 预期 Unknown 与实际一致可匹配，但不计覆盖；全 Unknown → 覆盖不足 |
| V06 | 预算耗尽 → 实际 Error，一律失败（M1 预算生效） |
| V07 | 预期值与实际相反 → `case_failed` |
| V08 | 重复 `case_id` 被拒 |
| V09 | 相同输入重复 4 次不能凑够独立覆盖 |
| V10 | Record/Map 插入顺序不同，套件哈希稳定 |
| V11 | 用例顺序或任一预期值改变，哈希必变 |
| V12 | source 只来自宿主登记；未登记套件 `unknown_suite` |
| V13 | 非 Bool3 输出 `unsupported_output_type` |
| V14 | 空套件 `suite_empty` |
| V15 | 同 ID/版本不能换内容；同内容重复登记幂等 |
| V16 | 缺依赖 `missing_dependency`；预期 Error → `invalid_expectation` |

## 未做 / 已知边界

- 本步不写数据库、不产生 `verification_runs`、不激活；入库与事务在 M2b-1c。
- `inspect` 目前用内存检查器（类型检查 + 依赖 pin + 复算哈希）；数据库 `inspect` 适配在 M2b-1c 接入。
- 只支持 `Bool3` 输出；`Unknown` 输入无法通过用例表达（输入只能是类型化值或缺失字段）。
- 套件来源不是密码学签名，“可信”仅指宿主进程内登记，不防数据库管理员级攻击。
- 独立闭卷 `not_run`；86/86 只是实现者自测。
