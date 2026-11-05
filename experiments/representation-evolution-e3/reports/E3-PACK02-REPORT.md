# PACK-02 联合研究报告

```text
研究包: PACK-02
子任务: C4 / RES-H01 / C8
状态: REVIEW（实施完成，未验收）
代码/测试提交: 3d01e69301729a5ab44289e757e0ac5400fe2df2
测试: 315/315（B0 173 + E3 142），失败 0
独立闭卷: not_run
```

## 1. 授权与边界

- PACK-01 已归档；PACK-02 白名单仅 `C4`、`RES-H01`、`C8`。
- 三项任务从 EXPLORATORY 通过控制台授权进入 IN_PROGRESS。
- 未修改 B0、A1、C1、PACK-01 学习算法、A9 或实验世界。
- 未启动 PACK-03 或其他任务。

## 2. C4：组合两个已激活基础谓词

实际流程：

- 基础 P：输入 `a,b`，从公开样本经 Composer/Filter 学习，heldout 4/4，B0 验证/激活。
- 基础 Q：输入 `b,c`，同样学习、验证、激活。
- 复合 R：输入 `a,b,c`，library 只含真实激活的 P、Q；Composer 生成候选；Filter 筛选；heldout 4/4；B0 propose/verify/activate。

实际记录（`reports/pack02-run.json`）：

| 能力 | 生成候选 | Filter | 激活 | heldout |
|---|---:|---|---|---|
| P | 10 | IDENTIFIED | 是 | 4/4 |
| Q | 10 | IDENTIFIED | 是 | 4/4 |
| R | 1525 | IDENTIFIED | 是 | 4/4 |

R 的 `usesIds = pack02.p,pack02.q`；依赖来自真实激活的能力定义，不是开发者直接注入的 AST。R 的 Composer reason 为 `depth_limit`，不是 `candidate_limit`。

原首次实施记录 20006/IDENTIFIED 已保留为历史，但当时未把 `candidate_limit` 传给 Filter；该记录不能作为有效搜索门禁证据。补丁后按 `maxDepth=3` 重新搜索得到 1525，未见 candidate_limit。

## 3. RES-H01：多步演绎

`DeductionEngine` 通用前向演绎；不使用硬编码 A/B/C/D 链。

测试链：

```text
A -> B -> C -> D
```

从事实 A 得到 3 个实际步骤，`ProofChecker` 独立核验每个前提/规则/结论。

已覆盖负例：

- 缺少前提 → failure；
- 规则不能连接 → failure；
- Unknown 事实 → 不使用；
- revoked 事实 → 拒绝；
- 错误中间结论 → 独立检查失败；
- 未验证规则 → 不使用。

## 4. C8：跨任务复用与对照

新任务目标：`R(a,b,c) OR (d=="1")`，训练/留出与前面分离。

| 组 | 生成 | Filter | 使用旧知识 | heldout |
|---|---:|---|---|---|
| 全部已激活知识 | 2385 | AMBIGUOUS | 是，含 `pack02.r` | 2/2 |
| 无复合 R（仅 P,Q） | 1525 | REFUSED | 否 | 未产生可评估预测 |

全部知识组选中候选确实调用 `pack02.r`，但 Filter 仍为 AMBIGUOUS；因此记录 `ambiguous_selection`，不宣称唯一规则。无 R 组未产生可评估预测，不记作 0% 准确率。

## 5. 联合实验

C4 的 R 在某输入上执行得到 `true`，将该结果作为事实 R，使用通用规则：

```text
R -> S -> T
```

两步演绎得到 T，并通过独立 `ProofChecker`。这证明同一会话中：

- 基础知识 P/Q 已激活；
- 复合 R 由 P/Q 构造并激活；
- R 的实际执行结果进入多步推理；
- C8 在新任务中再次调用 R；
- 全过程保留来源、版本、哈希、证据和证明步骤。

## 6. 失败与限制

- C8 全知识组仍有歧义，不能声称唯一复合概念。
- 没有固定查询策略/无复用策略的完整统计对照；noComposite 组只显示未得到可评估候选。
- R 候选数接近上限，不能声称穷尽搜索。
- 未证明普遍自主科学发现或跨世界稳定性。
- 同 JVM；`independent_closed_book=not_run`。

## 7. 归因

- 类型系统、Composer、Filter、Interpreter、注册/验证/激活：开发者/宿主；
- 基础谓词：学生由公开样本组合并经 B0 验证；
- 复合 R：学生由已激活 P/Q 组合、Filter 筛选并验证；
- 演绎规则与引擎：开发者提供的通用操作；具体证明链由事实/规则选择产生；
- 独立检查器只检查证明有效性，不证明现实世界前提为真。

## 8. 状态

三项任务实施完成，进入 `REVIEW`；不自行标记 ACCEPTED。等待研究负责人/ChatGPT 审查。
## 9. PACK-02 唯一集中补丁

### FIX-01：激活门禁

- 正式 learn/reuse 只接受 `dbPath + KnowledgeRef(id/version/contentHash)`。
- 使用 `activeCapabilities()` 取得合法快照；未激活、撤销、版本/哈希不匹配均拒绝。
- Composer、Filter、Interpreter 使用同一快照。
- C8 全知识组和无 R 组都从同一注册表读取；无 R 只排除 R，不引入未激活定义。
- 直接构造 `CapabilityDefinition` 的正式入口已移除。

### FIX-02：搜索截断

- `compose.reason == "candidate_limit"` 时向 Filter 传 `searchExhausted=true`。
- 强制截断返回 `search_budget_exhausted`，不选、不注册、不激活。
- 补丁后 C4 采用 `maxDepth=3`，R 生成 1525，未触发 candidate_limit。
- 原 20006/IDENTIFIED 记录保留为历史缺陷证据，不作为补丁后成功证据。

### FIX-03：R 执行事实

- 新增 `ExecutionFactAdapter`：只有合法 active R 且执行结果 `Known(true)` 才生成 VERIFIED Fact。
- false/Unknown/Error/未激活/撤销均不得生成肯定事实。
- Fact 保留 capabilityRef、inputDigest、执行 source。
- 联合推导 R→S→T 的两条规则仍是开发者提供的实验规则，不宣称学生发现。

### 补丁测试

- 激活 P/Q/R 正常复用；
- 未激活、错误版本、错误哈希、撤销 R 拒绝；
- 直接定义不能进入正式入口；
- 强制候选截断不激活；
- R true→Fact→两步证明；false/Unknown/Error→无事实；
- C8 无 R 组仍合法使用激活 P/Q。

### 仍未验证

- C8 仍 AMBIGUOUS；
- 未形成唯一复合规则；
- 未完成 B0 已激活知识替换闭环；
- 无固定策略/无复用策略的完整统计对照；
- `independent_closed_book=not_run`。

