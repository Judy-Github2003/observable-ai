# E3-C1 最小谓词学习与跨任务复用报告

```text
阶段: C1
范围: 第一任务谓词学习 → 验证/激活 → 第二任务复用
状态: REVIEW（implementer tests passed；独立评审未完成）
代码/测试提交: 41d778a974af70eb20ed8b9afeab71faa9fb0b12
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 297/297（B0 173 + E3 124），失败 0
独立闭卷: not_run
```

## 1. 实现

新增：

- `src/main/kotlin/logic/e3/c1/C1PredicateExperiment.kt`
- `src/test/kotlin/logic/e3/c1/C1PredicateExperimentTest.kt`

复用 B0：

- `CapabilityComposer.compose`
- `CandidateEvidenceFilter.filter`
- `CapabilityInterpreter.execute`
- `CapabilityDefinition` / `Expr.Call`
- `SqliteCapabilityRegistry.propose`
- `TrustedVerificationSuite` / `TrustedSuiteRegistry`
- `CapabilityVerificationService`
- `CapabilityActivationService`

未修改 B0、A9、A7 或 A1。

## 2. 第一任务：形成谓词 P

公开样本：`a,b ∈ {0,1,2,3}`，训练 11 条，留出 5 条。目标规则只在测试数据/评测端，不进入生产源码。

实际轨迹：

```text
B0 Composer 生成: 381
Filter 状态: IDENTIFIED
一致候选: 1
选中: Or(Eq(a,"1"), Eq(b,"1"))
独立 heldout: 5/5
propose: ok
verification: pass（5 个 definite 用例，含 true/false）
activation: ok
```

来源记录：

- `source=composer_bounded_search`
- `attribution=student_composed`
- 不是学生自主发明的搜索算法，也不是教师直接注入的 AST。

## 3. 第二任务：P_REUSE 与 NO_PREDICATE

第二任务：`a,b ∈ {0,1}`, `c ∈ {0,1,2}`；训练 9 条，留出 3 条。两组使用相同样本、相同 `maxDepth=3`、相同候选上限。

| 组 | 生成候选 | Filter 状态 | 是否调用 P | heldout | 失败原因 |
|---|---:|---|---|---|---|
| P_REUSE | 536 | IDENTIFIED | 是 | 3/3 | — |
| NO_PREDICATE | 180 | REFUSED | 否 | 未产生可评估预测 | filter_refused |

P_REUSE 选中的候选包含带版本、contentHash 的 `Expr.Call(P)`；选择依据是“第一个调用 P 的一致候选”，并记录 `selectionBasis=first_consistent_candidate_using_predicate`。NO_PREDICATE 的 `0/3` 表示没有产生可评估预测，不是 0% 准确率。

## 4. 结果解释

**工程成功**

- 谓词由 Composer 生成、Filter 筛选；
- 通过 heldout 验证和 B0 激活门禁；
- 第二任务实际调用 P，而不是人工写一个 Call 让它执行。

**受限科研成功**

- 在 `maxDepth=3` 的受限搜索空间中，P_REUSE 能表达并正确预测第二任务；NO_PREDICATE 无法生成一致候选。
- 这是“受限假设空间下的复用收益”，不是普遍学习效率优势。

**不能宣称**

- P_REUSE 生成候选更多（536 > 180），没有计算成本优势；
- P 仅是 `Or(Eq(a,1),Eq(b,1))`，不能称为复杂新概念；
- 没有证明跨世界/跨任务普遍迁移；
- 没有独立闭卷。

## 5. 失败场景

- heldout 翻转后，验证不通过，不激活。
- 错误 contentHash 的 `Expr.Call` 被 B0 类型/依赖检查拒绝（Error/Unknown）。
- 未通过验证的候选不进入正式复用。

## 6. 归因与信任边界

- Composer 搜索算法：`designer_primitive`。
- 选中表达式：`student_composed`（由搜索生成并经证据筛选）。
- B0 验证套件：宿主登记 `host_fixture`；不是独立第三方测试。
- 同一 JVM；SQLite 临时注册表；无密码学签名/进程隔离。
- `independent_closed_book=not_run`。

## 7. 结论

C1 证明了最小“生成 → 验证 → 激活 → 第二任务实际调用”工程链路，并在固定受限深度下观察到正确性收益；未证明成本优势或一般概念学习。

等待评审；补丁额度 `0/1`。
## 8. C1 唯一补丁（41d778a）

- C1-FIX-01：正式 `reuseSecondTask` 不再接受外部 `CapabilityDefinition`；P_REUSE 从 SQLite `activeCapabilities()` 读取，按 id/version/contentHash 绑定。找不到合法激活 P、版本或哈希不匹配时返回 `predicate_not_active_or_hash_mismatch`，不回退到外部定义。Composer library 与 Interpreter resolver 使用同一 active 快照。NO_PREDICATE 保持空 library。
- C1-FIX-02：`learnPredicate` 在 Filter `REFUSED` 时返回 `filter_refused`，不选候选、不注册；`AMBIGUOUS` 仅保留候选与诊断，返回 `ambiguous_not_registered`，不激活。`reuseSecondTask` 在 `REFUSED` 时返回 `filter_refused`，不把内部一致候选宣称为成功；`AMBIGUOUS` 记录 `ambiguous_selection`。
- C1-FIX-03：更正第二任务训练/留出为 9/3；NO_PREDICATE 的 `0/3` 改述为“未产生可评估预测”，不是 0% 准确率。
- 新增反例：空库/未激活、验证失败、错误版本、错误 contentHash、Filter REFUSED。
- 未修改 B0、A9、A1 或测试样本；原 P_REUSE/NO_PREDICATE 结果保留。
