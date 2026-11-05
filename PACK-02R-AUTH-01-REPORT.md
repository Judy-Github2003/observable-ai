# PACK-02R-AUTH-01 可信证据认证链修复报告

**任务：** PACK-02R-AUTH-01  
**状态：** REVIEW（实施完成并消费 AUTH 授权，未宣称研究验收）  
**代码/测试提交：** `f427cdb8399fee99d9c367cac0b48070e49edac7`  
**测试：** Kotlin 316/316（B0 173 + E3 143）；PACK-02 专项 9/9；Repair gate 7/7；旧 Console gate 5/5  
**独立闭卷：** `not_run`  
**C4/C8：** 保持 `PAUSED`；未启动 ACTIVE/EVAL/PACK-03

---

## 1. 根因与修改摘要

原认证链有三个缺口：

1. `ExecutionFactAdapter.fromResult()` 公开接受任意 `Known(true)`，并硬编码 `proposition="R"`；
2. `Fact` 是公开 data class，调用者可以直接构造 `status=VERIFIED`；
3. `ProofChecker` 接受普通 `Fact`/`Rule`，并信任 `Rule.sourceVerified` 文本字段。

修复后的核心不变量：

- VERIFIED 事实不能由普通公开 API 构造；
- 只有正式 active 执行路径可以签发 `VerifiedFact`；
- `ProofChecker` 只接受 `VerifiedFact` + `VerifiedRule`，并通过显式 `EvidenceVerifier` 检查当前 active 状态；
- 普通 `Fact`/`Rule` 不能被类型系统当作可信证明基础；
- 命题绑定使用 capability id，不再硬编码 R。

---

## 2. 实际修改文件

- `src/main/kotlin/logic/e3/pack02/Deduction.kt`
  - `Fact` 构造器私有，仅保留 `unverified`/`revoked` 公开工厂；
  - 新增 `VerifiedFact`（私有构造，绑定 ref/inputDigest/proposition/executionId）；
  - 新增 `VerifiedRule`；
  - 新增 `EvidenceVerifier`、`ActiveEvidenceVerifier`、测试专用 `TestAccept`；
  - `ProofChecker` 改为只接受认证事实/规则，默认 `RejectAll`。
- `src/main/kotlin/logic/e3/pack02/ExecutionFactAdapter.kt`
  - 删除公开 `fromResult`；
  - `fromExecution` 只返回 `VerifiedFact`；
  - 只接受 active 快照精确匹配的 ref；
  - 仅 `Known(true)` 签发；命题为 capability id。
- `src/test/kotlin/logic/e3/pack02/DeductionTest.kt`
  - H01 迁移到受控测试证据签发器；
  - 新增伪造规则/摘要负例。
- `src/test/kotlin/logic/e3/pack02/Pack02KnowledgeTest.kt`
  - 合法执行证据、未激活/错误 hash、撤销后旧凭据失效测试。

没有修改 B0、A1、C1、PACK-01、C4/C8 学习算法或原失败记录。

---

## 3. 新旧接口差异

| 旧 | 新 |
|---|---|
| `ExecutionFactAdapter.fromExecution(...): Fact?` | `...: VerifiedFact?` |
| `ExecutionFactAdapter.fromResult(...)` 公开 | 删除；无公开替代 |
| `Fact(..., status=VERIFIED)` | `Fact` 私有构造；VERIFIED 仅由受控签发器生成 |
| `ProofChecker.check(List<Fact>, List<Rule>, ...)` | `check(List<VerifiedFact>, List<VerifiedRule>, ..., EvidenceVerifier)` |
| `Rule.sourceVerified` 决定可信 | `VerifiedRule` 对象决定；普通 Rule 不可进入 checker |
| 命题硬编码 `"R"` | 命题绑定 `ref.id` |

H01 的 `DeductionEngine` 前向推理和证明步骤形状未变，仅证据构造迁移。

---

## 4. 可信边界及未覆盖威胁

**可信边界：**

- `ExecutionFactAdapter.fromExecution` 是当前唯一生产签发路径；
- `VerifiedFact`/`VerifiedRule` 构造器私有；
- `ProofChecker` 默认拒绝，必须显式注入 `ActiveEvidenceVerifier`；
- `ActiveEvidenceVerifier` 重查 active 快照，能力撤销后旧凭据失效。

**明确未覆盖：**

- 进程内 `internal` 测试签发器不是密码学边界；拥有同模块代码修改权限的人仍可绕过。
- 同进程反射/调试器、数据库管理员、仓库写权限不在防御范围。
- `VerifiedRule` 目前由受控测试签发器/开发者规则路径提供，尚无独立的规则注册表；这是后续风险。
- 没有持久 fact DB，因此本次不需要迁移历史 VERIFIED 记录；未来持久化必须重验。

---

## 5. 强制负例测试结果（9/9）

- 伪造 `Known(true)`：公开 `fromResult` 已删除；普通调用者只能得到未认证 Fact。
- 未激活能力：`fromExecution` 返回 null。
- 已撤销能力：撤销 P 后 `ActiveEvidenceVerifier` 对旧证据返回 false。
- 错误版本/contentHash：返回 null。
- 输入摘要不匹配：`VerifiedFact.issueForTest(inputDigest="bad")` 被 checker 拒绝。
- 命题不匹配：P 执行产生 `pack02.p`，不会变成 `R`；无硬编码。
- 直接构造 VERIFIED Fact：构造器私有，无法从公开 API 构造。
- 伪造 `Rule.sourceVerified`：普通 Rule 不能进入 checker；伪造规则集缺少已认证规则时证明失败。
- 无效执行凭据：missing ref/错误 hash 返回 null。
- 旧凭据撤销后重放：`ActiveEvidenceVerifier` 拒绝。
- 教师断言伪装：没有公开 API 可创建 VerifiedFact；教师文本只能作为未认证 Fact。
- 伪造基础事实通过多步证明：`ProofChecker` 只接受 VerifiedFact；H01 伪造规则测试失败。

合法 P 执行仍能生成 `VerifiedFact`，并通过 `ActiveEvidenceVerifier`；H01 三步证明在测试签发器下继续通过。

---

## 6. 全量回归

- Kotlin：**316/316**，B0 173，E3 143，失败 0。
- PACK-02 专项：**9/9**。
- Repair gate：**7/7**。
- 旧 Console gate：**5/5**。
- `check-workstate`：`ok=true`。
- AUTH preflight：`AUTHORIZED_REPAIR: PACK-02R-AUTH-01`。
- GATE 已消费；AUTH 已消费（`ev-1793895214781-f109b2`），不可重复；C4/C8 仍 PAUSED；H01 未改；独立闭卷 `not_run`。

---

## 7. H01 兼容性

- A→B→C→D 三步证明仍由 `DeductionEngine` 产生，步骤数/前提/结论测试保留。
- `ProofChecker` 现在要求 `VerifiedFact` + `VerifiedRule`；测试通过 `TestAccept` 签发器迁移，断言未放松。
- 负例仍覆盖缺前提、断链、Unknown、revoked、错误中间结论。
- 由于 `ProofChecker` 默认 `RejectAll`，生产调用必须显式注入 `ActiveEvidenceVerifier`；这是刻意的安全默认值。

**当前 Console 的 H01 状态仍是 `REVIEW`，复核摘要称 `ACCEPTED_LIMITED`；本轮没有自行改写。**

---

## 8. 剩余风险与回滚

- `VerifiedRule` 缺少独立生产规则注册表；当前 H01 规则仍是开发者实验规则。
- `internal` 测试签发器不是跨进程安全边界。
- 未来持久化 fact/proof 时需要重新验证或隔离旧记录。
- 回滚：回退 `f427cdb`；C4/C8 已 PAUSED，回滚不会恢复旧认证漏洞的“修复成功”假象。
- 不修改原 PACK-02 补丁额度或历史。

## 9. 是否建议进入 PACK-02R-ACTIVE

**不建议立即自动进入。** AUTH 已完成待审查；ACTIVE 仍需要：

1. 外部监督复核本报告的 `SharedVerifier`/`VerifiedRule` 边界；
2. 研究负责人明确批准 ACTIVE；
3. 解决 8 点输入域无法同时 query/test 的协议问题。

建议先审查 AUTH，再决定 ACTIVE。

