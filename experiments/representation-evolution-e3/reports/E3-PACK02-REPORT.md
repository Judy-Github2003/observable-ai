# PACK-02 联合研究报告（补丁后真实结果）

```text
研究包: PACK-02
子任务: C4 / RES-H01 / C8
状态: PAUSED（C4/C8）；REVIEW（RES-H01）
代码/测试提交: 60c48d14f3e2a1c3e8dd997b250505d21dc3def5
测试: 315/315（B0 173 + E3 142），失败 0
独立闭卷: not_run
```

## 1. 结论

补丁修复了三个管理/门禁问题，但没有把 C4/C8 从真实失败中“修成成功”：

- **C4 在原训练/留出样本上仍 AMBIGUOUS，未激活 → PAUSED。**
- **C8 因 R 未激活而不能复用 → PAUSED。**
- **RES-H01 的受限多步演绎与独立检查通过 → REVIEW。**

这不是测试失败；这是科研结果。没有修改原训练样本、留出样本或目标规则来制造成功。

## 2. C4：真实失败

P/Q 基础能力：生成 10，IDENTIFIED，激活，heldout 4/4。

R（`maxDepth=3`, `maxCandidates=20000`）：

```text
generated = 1084
composeReason = depth_limit
searchExhausted = false
Filter = AMBIGUOUS
active = false
failure = filter_not_identified
```

因此没有 R 的定义/contentHash，也没有 R 注册/验证/激活。

此前扩展样本上曾得到 1525/IDENTIFIED，但那不是原始训练/留出配置，不能作为正式结果；已废弃该成功声明。原首次实施的 20006/IDENTIFIED 记录也只保留为历史搜索门禁缺陷证据，不作为有效结果。

## 3. RES-H01：通过（受限）

- A→B→C→D 实际 3 步；
- 每步记录前提、规则、结论；
- `ProofChecker` 独立核验通过；
- 缺前提、断链、Unknown、revoked、错误中间结论、未验证规则均失败；
- 不宣称通用定理证明。

## 4. C8：因前置失败暂停

R 未激活，因此正式复用入口对 R 引用返回 `inactive_or_hash_mismatch`；不能建立“使用 R”的新任务结果。

无 R 对照组可合法读取激活的 P/Q，但不能替代 C8 联合目标。C8 记录为 `PAUSED`，不把无预测写成 0% 准确率。

## 5. 补丁实际修复

### FIX-01 激活门禁

- 正式 learn/reuse 只接受 `dbPath + KnowledgeRef(id/version/contentHash)`。
- 从 `activeCapabilities()` 取得同一快照；未激活/撤销/错误版本/哈希拒绝。
- Composer、Filter、Interpreter 使用同一快照。
- 直接构造 `CapabilityDefinition` 的正式入口已移除。

### FIX-02 搜索截断

- `compose.reason == "candidate_limit"` 时传 `searchExhausted=true`。
- 截断时 `search_budget_exhausted`，不选/不注册/不激活。
- 强制低预算测试通过；未截断但未辨识时也不激活（C4 即此情况）。

### FIX-03 执行事实

- 新增 `ExecutionFactAdapter`：只有合法 active 能力且 `Known(true)` 才生成 VERIFIED Fact。
- false/Unknown/Error/未激活/撤销均不产生肯定事实。
- Fact 保留 capabilityRef、inputDigest、执行 source。
- C4 未激活，因此没有可用于联合证明的 R 事实；不伪造。

## 6. 测试

- 315/315 通过；B0 173 + E3 142。
- PACK02 专项 8/8：P/Q 激活、C4 原样本不激活、搜索截断不激活、正式入口拒绝错误引用、执行事实拒绝非 true/未激活、H01 5 项。
- C4/C8 的测试断言是“不能激活/不能复用”，不是成功断言。

## 7. 限制与下一步

- C4 在原假设空间/预算下未辨识；扩大样本不是本次授权允许的修复。
- C8 无法在 R 未激活时进行；保持 PAUSED。
- H01 仅受限前向演绎；无通用量词/定理证明。
- 未证明跨任务复用；未完成 B0 激活知识替换闭环。
- `independent_closed_book=not_run`。
- PACK-02 普通补丁 1/1 已耗尽，不再第二轮修补；不启动 PACK-03。
