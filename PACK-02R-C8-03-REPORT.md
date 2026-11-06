# PACK-02R-C8-03 报告：四属性主动组合学习与 R→S→T 连续链

- 任务：`PACK-02R-C8-03`
- 授权：Research Console 专项修复授权，研究负责人 2026-11-06
- 学生代码冻结提交：`a6ab1a51f1beefcb0e1a1cd9d0c3180c2d01f76e`
- 冻结清单提交：`7f78fc6561ffdbf3abd104011fc42d85a528a44c`
- 正式结果提交：`575b182522275cc857cedd75b282894a9bf0a501`
- 最终测试：Kotlin **335/335**（0 failed；B0 173 + E3 162）
- `independent_closed_book = not_run`
- C4 / C8 状态保持 `PAUSED`；未启动 PACK-03

---

## 0. 结论摘要

本轮不是 C8-02 的重复被动筛选。学生端新增了：

1. 四属性三值世界（81 个输入）上的冻结 TRAIN / QUERY / HELDOUT；
2. 候选生成、训练一致性筛选、行为等价类统计；
3. 两种通用查询策略（行为分歧 minimax、成本/复用加权期望分歧）；
4. 先冻结候选预测、再向受控 Oracle 查询、按反例淘汰候选的完整主动循环；
5. 主动停止条件（唯一行为类、预算耗尽、无区分查询、全被反驳）；
6. 基于结构复杂度、依赖复用和估算执行成本的自主候选选择，不使用 `preferredId`；
7. S 激活后作为 T 的真实依赖，继续主动学习 T；
8. FULL / NO-R / BASE / NO-S 对照，以及 ACTIVE / FIXED / RANDOM 查询对照；
9. 查询轨迹、候选依赖、成本、激活和全输入审计的 JSON 证据。

**结果：**

- 6 个正式世界中，**5 个完成了 R→S→T**（W1/W2/W3 使用 R；W5/W6 自主判断不需要 R，直接用原语）。
- W4 未完成：冻结的 B0 组合器不枚举 `If`，S 目标不在假设空间；学生选出的 3 个候选在公开数据上一致，但 HELDOUT 只有 **24/27**，考官验证拒绝激活，T 未运行。
- 正式考试共 29 个阶段、19 次激活尝试、**14 次激活成功**、**0 次错误激活**；14 个已激活能力在全 81 输入上共执行 **1,134 次，1,134 次正确**。
- T 的 NO-S 对照 6/6 均为 `NO_CANDIDATE`，没有一个在缺少 S 时激活。
- 自主查询：W1 S 从 4 个行为类经 2 次查询降到 1 类，T 从 5 类经 2 次查询降到 1 类；开发集上 FIXED/RANDOM 在 S 上各需 3 次，ACTIVE（utility/divergence）只需 2 次，正确率相同。
- W5/W6 的 S 选择不依赖任何旧能力（`dependencies=none`），说明没有把“使用 R”当成强制成功条件。

**层次判定（本轮定义）：**

| 层次 | 内容 | 判定 | 证据 |
|---|---|---|---|
| L1 | 无提示自主复用 R 学 S | **通过** | W1/W2/W3 FULL：S 使用 R，27/27，激活成功 |
| L2 | 真主动查询、反例淘汰、正确停止 | **通过** | 初始 2–4 类，1–2 次查询，逐轮淘汰 >0；空查询池正确停止 |
| L3 | S 激活后作为 T 依赖并完成 T | **通过** | 5/5 个可进入 T 的世界：T 使用 S，27/27，激活成功 |
| L4 | 多世界稳健 | **部分** | 5/6；W4 因冻结组合器缺少 `If` 失败 |
| L5 | 策略/成本普遍优势 | **未证实** | 仅 W1 开发集显示 ACTIVE 少 1 次查询；不足以声称普遍优势 |

因此：**本轮达到 L3，L4 部分，L5 证据不足。** 这不是 C8-02 的“L1 未达到”；C8-02 的阻断点在本轮已被实际跨越，但 W4 暴露了新的、明确的组合语法限制。

---

## 1. 授权、冻结与纪律

### 1.1 授权

- 任务开始前执行：
  `python tools/research-console/console_cli.py preflight --task PACK-02R-C8-03`
- 授权记录：`PACK-02R-C8-03`，状态 `ACTIVE`，事件 `ev-1793956936319-7bf513`。
- preflight 返回 `AUTHORIZED_REPAIR: PACK-02R-C8-03`；当时 HEAD 为 `5ca1d74`。
- 授权范围仅限本任务的学生算法、考官 harness、测试和 JSON 证据；未授权生产架构或 PACK-03。

### 1.2 冻结

学生算法与考官 harness 在正式考试前冻结：

- `src/main/kotlin/logic/e3/pack02/C8ActiveStudent.kt`
  SHA-256：`f37d234298263cbc56e40724ed6e840b30135a0d7b340b031f8b7e1bb14ee0e5`
- `src/main/kotlin/logic/e3/pack02/C8Exam.kt`
  SHA-256：`1eaa018024164939ff85ae1537ab6c000abfd1ff82dc8d68fbe18cf33645ce2f`
- 冻结清单：`experiments/representation-evolution-e3/reports/pack02r-c8-03-freeze.json`
- 冻结清单提交：`7f78fc6561ffdbf3abd104011fc42d85a528a44c`

正式测试在运行前重新计算两个文件的 SHA-256，与冻结清单比对；不一致即失败。正式运行后没有修改这两个文件。

### 1.3 关于正式考试的复跑

正式考试在冻结后先运行一次。随后执行全量回归（335 个测试），`C8FormalExamTest` 再次执行了同一份冻结考试。两次的能力结果一致：

- 18 runs / 29 stages / 14 active / 5 T activated / 0 wrong / 17 queries
- 仅 `totalWallMillis` 和验证运行 UUID 不同。

本报告引用的是全量回归后的正式 JSON；学生和考官代码哈希未变。这一点如实记录，不把复跑说成第一次运行。

### 1.4 未改变的状态

- C4 / C8：仍 `PAUSED`。
- PACK-02：仍 `PAUSED`，普通补丁额度未变。
- H01：Console 仍 `REVIEW`，未擅自改成 `ACCEPTED_LIMITED`。
- `independent_closed_book`：`not_run`。
- 未启动 PACK-03。
- 旧 C8-02 / EVAL-01 / EVAL-02 结果保持原样，未覆盖。

---

## 2. 数据冻结

### 2.1 输入空间

- 4 个三值属性：`a,b,c,d ∈ {0,1,2}`
- 全空间：`3^4 = 81` 个输入
- 属性值、输入枚举和规范化键都在 `C8Domain` 中；标签不在学生可见对象中。

### 2.2 划分

| 组 | 大小 | 用途 |
|---|---:|---|
| TRAIN | 18 | 冻结训练集；S 只用前 9 条，T 用全部 18 条 |
| QUERY | 36 | 无标签查询池；S 和 T 各自有独立 Oracle 标签 |
| HELDOUT | 27 | 考官验证；学生不可见 |

- 划分种子：`6941`
- 划分摘要：`4d16e01793ae7d4b24a2218e382b7ed120beb4d151a7424a009ef059795a99af`
- 三组两两不重叠，并集为全部 81 个输入；`C8Data.freeze` 内有硬性断言，测试再次校验。
- 预注册覆盖率检查（学习前）：
  - S 训练：每世界至少 3 正 / 3 负；
  - S HELDOUT：至少 6 正 / 6 负；
  - T 训练：至少 4 正 / 6 负；
  - T HELDOUT：至少 4 正 / 6 负。
- 选择 S 训练 9 条而不是 18 条，是为了保留真实歧义：18 条时 W1 的初始行为类只有 1 个，主动查询没有空间；9 条时 W1 初始 4 类，需要 2 次查询。

### 2.3 标签隔离

- S 和 T 共用同一组 QUERY / HELDOUT **输入**，但标签按阶段独立定义。
- T 的 HELDOUT 标签从不进入学生；S 的 HELDOUT 标签也从不进入学生。
- S 阶段的查询标签不会传给 T 阶段；学生会话没有跨阶段记忆。
- `StudyView` 的字段只有：`worldId, stage, training, queryPool, budget, refs, inputNames, maxDepth, maxCandidates, maxCallsPerCapability, strategy, seed`。
  测试用反射断言其中没有 `target/truth/heldout/family/oracle`。
- `StudyQueryController` 只允许：池内、未重复、预算内、预测覆盖全部当前候选的查询；HELDOUT 输入、池外输入、重复查询、超预算查询都会抛异常。
- 测试断言 `controller.oracleCallCount() == result.rounds.size`，即学生只通过 reveal 读取标签。

---

## 3. 学生算法（已实施）

### 3.1 主循环

`ActiveStudySession.run()`：

1. 从注册表解析 `StudyView.refs` 对应的 active 能力；哈希不匹配返回 `INACTIVE`。
2. 用 `CapabilityComposer` 在冻结预算内生成候选：
   - `maxDepth = 3`
   - `maxNodes = 40`
   - `maxCandidates = 50,000`
   - `maxCallsPerCapability = 24`
3. 若组合器因 `candidate_limit` 停止，返回 `SEARCH_LIMITED`，不声称辨识。
4. 对每个候选做训练筛选；一旦某条公开样本不匹配立即淘汰（early exit），不把 Unknown/Error 当成 false。
5. 对训练一致的候选，在 36 个无标签 QUERY 输入上计算行为签名，按签名分组为行为等价类。
6. 若只剩 1 类：进入候选选择。
7. 否则按策略选一个查询；冻结**全部当前候选**的预测；由 Controller reveal 标签。
8. 保留与观察标签一致的候选；记录消除数、类数变化和查询理由。
9. 重复，直到：唯一类、预算耗尽、没有未用的区分查询、全部候选被反驳。
10. 只有 `IDENTIFIED` 才把候选交给考官；`BUDGET_EXHAUSTED` / `NO_DISTINGUISHING_QUERY` / `NO_CANDIDATE` / `ALL_REFUTED` 都返回 `selected = null`，不激活。

### 3.2 两种通用查询策略

- **divergence**：对每个未用查询点，按候选预测分成 true/false 两组；最大化较小的一侧（minimax），再最小化较大一侧；不区分时跳过。
- **utility**：给候选赋成本权重：
  - `estimatedSteps = 表达式节点数 + 每个 Call 的被调能力正文节点数`（近似内联执行量）；
  - `structuralCost = 节点数 - 不同依赖数`（复用旧知识降低结构成本）；
  - 候选权重 `exp(-structuralCost / 2)`；
  - 查询分数 = 类级 Gini 的期望下降（分支内按行为类聚合，不按同义候选个数聚合）。
- **fixed**：按查询键排序取第一个仍能区分且未用的点。
- **random**：固定种子打乱查询池，跳过不能区分或已用的点。

两种策略都不使用 `preferredId`，也不读取目标函数。

### 3.3 候选选择

`IDENTIFIED` 后：

- utility：按 `(estimatedSteps, nodeCount, canonical)` 取最小；
- 其他策略：按 `(nodeCount, estimatedSteps, canonical)` 取最小。

选择理由写入 `selectionReason`，包括初始/剩余类数、候选数、节点数、估算执行步数和依赖 ID。

### 3.4 验证与激活

考官侧 `C8Evaluator`：

- 用 27 条 HELDOUT 计算 `heldoutCorrect`；
- 构造 B0 `TrustedVerificationSuite`，propose → verify → activate；
- 只有验证 `PASS` 才激活；
- 激活后用 `CapabilityExecutionService.executeActive` 在全 81 输入上审计，记录 `executed/correct/mismatches/blocked`；
- 若 S 激活但全输入审计有 mismatch，T 不运行，避免把错误 S 传播给 T。

---

## 4. 开发实验

开发矩阵（`pack02r-c8-03-development.json`）：

- W1：FULL 的 4 种查询策略；NO-R；BASE；NO-S。
- W2 / W3：FULL utility 的泛化检查。

### 4.1 W1 查询策略对照

| 策略 | S 状态 | S 查询 | S 激活 | S HELDOUT | T 状态 | T 查询 | T 激活 |
|---|---|---:|---|---:|---|---:|---|
| utility | IDENTIFIED | 2 | 是 | 27/27 | IDENTIFIED | 2 | 是 |
| divergence | IDENTIFIED | 2 | 是 | 27/27 | IDENTIFIED | 2 | 是 |
| fixed | IDENTIFIED | 3 | 是 | 27/27 | IDENTIFIED | 2 | 是 |
| random | IDENTIFIED | 3 | 是 | 27/27 | IDENTIFIED | 2 | 是 |

- 四种策略的 S 候选生成数相同（13,206），解释器调用数相同（33,036）；差异只在查询次数。
- ACTIVE 两种策略各少 1 次 S 查询；T 阶段没有差异（5 类，2 次查询）。
- 这是一个**单世界**结果，不能据此声称主动查询普遍更省；见 L5。

### 4.2 W1 基础组对照

| 组 | S 状态 | S 查询 | S 依赖 | S HELDOUT | S 激活 | T |
|---|---|---:|---|---:|---|---|
| FULL | IDENTIFIED | 2 | R | 27/27 | 是 | IDENTIFIED，2 次查询，激活 |
| NO-R | IDENTIFIED | 1 | 无 | 22/27 | 否 | 不运行 |
| BASE | IDENTIFIED | 1 | 无 | 22/27 | 否 | 不运行 |
| FULL + NO-S | — | — | — | — | — | `NO_CANDIDATE` |

- NO-R / BASE 都“辨识”了一个候选，但 HELDOUT 只有 22/27；考官验证拒绝，未激活。
- 这说明 `IDENTIFIED` 只表示“公开证据下唯一行为类”，不等于真实规律；HELDOUT 验证是必要的。
- W1 的 S 在 depth=3 下需要 R 把 `(a==1 && c==1)` 封装成一次调用；仅用 P/Q 需要 depth=4，组合器不会生成。

### 4.3 开发集汇总

| 指标 | 值 |
|---|---:|
| runs | 8 |
| stages | 15 |
| S identified | 8 |
| S activated | 6 |
| T attempted / activated | 6 / 6 |
| NO-S attempted / activated | 1 / 0 |
| wrong activations | 0 |
| total queries | 24 |
| total generated candidates | 225,426 |
| total interpreter calls | 541,711 |
| total stage wall time | 34,520 ms |

---

## 5. 正式多世界考试

正式运行：`C8Experiment.runFormal()`，6 个 FULL 世界 + 6 个 NO-S T 对照 + 6 个 NO-R S-only + 6 个 BASE S-only，共 18 runs / 29 stages。

### 5.1 FULL：S→T 主表

| 世界 | S 目标 | S 类数 | S 查询 | S 使用 R | S HELDOUT | S 激活 | T 类数 | T 查询 | T 使用 S | T HELDOUT | T 激活 |
|---|---|---:|---:|---|---:|---|---:|---:|---|---:|---|
| W1 | R OR d==1 | 4→1 | 2 | 是 | 27/27 | 是 | 5→1 | 2 | 是 | 27/27 | 是 |
| W2 | R OR d==2 | 4→1 | 1 | 是 | 27/27 | 是 | 2→1 | 1 | 是 | 27/27 | 是 |
| W3 | R XNOR d==1 | 2→1 | 1 | 是 | 27/27 | 是 | 2→1 | 1 | 是 | 27/27 | 是 |
| W4 | if R then d1 else d0 | 1→1 | 0 | 是（错误候选） | **24/27** | **否，verification_failed** | — | — | — | — | 未运行 |
| W5 | P XNOR d==2 | 2→1 | 1 | 否 | 27/27 | 是 | 1→1 | 0 | 是 | 27/27 | 是 |
| W6 | Q OR a==2 | 4→1 | 1 | 否 | 27/27 | 是 | 2→1 | 1 | 是 | 27/27 | 是 |

- W1–W3：S 使用 R；W5/W6：S 的 `dependencyIds = []`，直接使用 `Eq/And/Or` 原语。学生没有因为是 FULL 就强行调用旧能力。
- W5/W6 的 S 仍可作为 T 的依赖；T 选中 `And(Eq(b,1), Call(S))`，激活并 27/27。
- W4 的 T 因 S 未激活而阻塞，没有伪造 T 结果。

### 5.2 W1 的主动查询轨迹

S：

| # | 查询输入 | 标签 | 消除 | 类数 | 策略理由 |
|---:|---|---:|---:|---|---|
| 0 | a=0,b=1,c=1,d=2 | false | 103 | 4→2 | `utility gain=0.2693 minSide=56 maxSide=103` |
| 1 | a=1,b=1,c=2,d=0 | false | 28 | 2→1 | `utility gain=0.5000 minSide=28 maxSide=28` |

选中 S：`Or(Eq(d,1), Call(R, ...))`，节点 8，估算执行 15 步，依赖 `pack02r.active.r`。

T：

| # | 查询输入 | 标签 | 消除 | 类数 | 策略理由 |
|---:|---|---:|---:|---|---|
| 0 | a=1,b=1,c=2,d=0 | false | 30 | 5→2 | `utility gain=0.4181 minSide=14 maxSide=30` |
| 1 | a=1,b=0,c=0,d=1 | false | 1 | 2→1 | `utility gain=0.0363 minSide=1 maxSide=13` |

选中 T：`And(Eq(b,1), Call(S, ...))`，节点 9，估算执行 17 步，依赖 `c8v3.S.w1-or-r-d1`。

T 的第 2 次查询只消除 1 个候选，说明当前 query utility 在“小类”上仍可能选到低收益点；这是 L5 不宣称普遍优势的原因之一。

### 5.3 W5 / W6：自主“不复用”

- W5 S：`Eq(Eq(a,1), Eq(d,2))`，节点 7，估算 7 步，`dependencies=[]`。
- W6 S：`Or(Eq(a,2), Eq(c,1))`，节点 7，估算 7 步，`dependencies=[]`。
- 这两个目标的直接原语表达比调用 P/Q 更便宜（P/Q 调用会增加节点和被调正文成本），学生选择不复用；结果 27/27 并激活。
- 这直接回答了“什么知识值得复用”：不是“有旧知识就用”，而是看它是否降低结构/执行成本，以及是否必要。

### 5.4 对照矩阵

| 世界 | FULL S | NO-R S | BASE S | FULL T | NO-S T |
|---|---|---|---|---|---|
| W1 | 27/27，激活 | 22/27，拒绝 | 22/27，拒绝 | 27/27，激活 | `NO_CANDIDATE` |
| W2 | 27/27，激活 | 19/27，拒绝 | 19/27，拒绝 | 27/27，激活 | `NO_CANDIDATE` |
| W3 | 27/27，激活 | `NO_CANDIDATE` | `NO_CANDIDATE` | 27/27，激活 | `NO_CANDIDATE` |
| W4 | 24/27，拒绝 | `NO_CANDIDATE` | `NO_CANDIDATE` | 未运行 | `NO_CANDIDATE` |
| W5 | 27/27，激活 | 27/27，激活 | 27/27，激活 | 27/27，激活 | `NO_CANDIDATE` |
| W6 | 27/27，激活 | 27/27，激活 | 27/27，激活 | 27/27，激活 | `NO_CANDIDATE` |

解释：

- W1/W2 的 NO-R/BASE 失败不是“学生不会主动查询”，而是 depth=3 下目标不可表达；它们仍会给出一个公开数据一致的错误候选，HELDOUT 把它拒绝了。
- W3/W4 的 NO-R/BASE 直接 `NO_CANDIDATE`。
- W5/W6 的目标本身不需要 P/R，NO-R/BASE 也能成功；这避免把“R 必要”误写成所有世界都必要。
- T 的 NO-S 六个世界全部 `NO_CANDIDATE`；没有一次在缺少 S 时激活。T 在 depth=3 下需要 S 作为组合单元。

---

## 6. 成本、激活与错误率

### 6.1 正式考试聚合

| 组 / 阶段 | n | 激活尝试 | 激活成功 | 查询 | 生成候选 | 解释器调用 |
|---|---:|---:|---:|---:|---:|---:|
| FULL S | 6 | 6 | 5 | 6 | 79,236 | 169,355 |
| FULL T | 5 | 5 | 5 | 5 | 104,130 | 235,341 |
| FULL T-NO-S | 6 | 0 | 0 | 0 | 79,236 | 159,704 |
| NO-R S | 6 | 4 | 2 | 3 | 43,884 | 91,829 |
| BASE S | 6 | 4 | 2 | 3 | 4,284 | 8,588 |
| **合计** | **29** | **19** | **14** | **17** | **310,770** | **664,817** |

- 激活成功率：**14 / 19 = 73.7%**。
- 错误激活率：**0 / 14 = 0%**。
- 14 个已激活能力全部做了 81 输入审计：**1,134 / 1,134 正确，0 mismatch，0 blocked**。
- 正式考试总 wall time：39,741 ms（受 JVM、临时 SQLite、机器负载影响，不作为精确性能基准）。

### 6.2 W1 连续链累计成本

| 阶段 | 生成候选 | 解释器调用 | 查询 | 选中节点 | 激活 | 审计 |
|---|---:|---:|---:|---:|---|---|
| S | 13,206 | 33,036 | 2 | 8 | 是 | 81/81 |
| T | 20,826 | 46,993 | 2 | 9 | 是 | 81/81 |
| S+T | **34,032** | **80,029** | **4** | **17** | 2/2 | **162/162** |

- W1 的 S+T 链总共 4 次真实查询、34,032 个候选、80,029 次候选执行、2 次激活、162 次全输入审计。
- T 的候选生成量（20,826）比 S（13,206）高，因为 T 的库多了一个 S 调用；这是连续学习新增依赖的成本。

### 6.3 结构复杂度

| 选中项 | 节点数 | 估算执行步数 | 依赖 |
|---|---:|---:|---|
| W1 S | 8 | 15 | R |
| W1 T | 9 | 17 | S |
| W5 S | 7 | 7 | 无 |
| W6 S | 7 | 7 | 无 |
| W4 S（错误） | 8 | 15 | R |

- R 复用把 W1 S 的扁平表达（需要 depth 4）压缩为 depth 3 的一次调用；结构节点 8，估算 15。
- W5/W6 不复用时节点 7、估算 7，反而比调用旧能力更便宜；这符合成本模型，而不是“复用越多越好”。

---

## 7. 失败分析

### 7.1 W4：已实施但失败，根因是冻结组合器缺少 `If`

- W4 的 S 目标：`if R then d==1 else d==0`。
- 该目标在 B0 IR 中可表达（测试用 `Expr.If` 参考体验证了 81/81 正确）。
- 但冻结的 `CapabilityComposer` 组合循环只枚举：
  `Not / And / Or / Eq / 已激活能力调用`，
  **不生成 `Expr.If` 或 `Expr.Let`**。
- 因此 W4 的真正目标不在学生假设空间中。训练 9 条 + 查询池 36 点只留下 3 个候选、1 个行为类；学生无需查询就“辨识”了。
- 选中的错误候选 `Or(Eq(d,0), Call(R))` 在公开数据上一致，但在 HELDOUT 的 27 点中有 3 点不同（R 为真且 d==2 的区域），只有 24/27。
- 考官验证 `verification_failed`，未激活；T 未运行。没有把 W4 伪造成成功。

这个问题不是“主动查询次数不够”：查询池 36 点全部与错误候选一致，类数已经是 1；即使再查询池内其他点也无法区分。真正缺的是组合语法中的 `If`。

### 7.2 NO-R / BASE 的 W1/W2：depth 预算造成的不可表达

- W1 S = `(a==1 && c==1) || d==1`。
- 使用 R：`Or(Call(R), Eq(d,1))`，depth 3。
- 不使用 R、只用 P/Q：`Or(And(Call(P),Call(Q)), Eq(d,1))`，需要 depth 4。
- 冻结预算是 depth 3；NO-R/BASE 仍会找到一个公开数据一致的错误候选（22/27 或 19/27），但验证拒绝。
- 这不是“NO-R 学生不会学习”，而是“在冻结深度内目标不可由 P/Q 表达”。报告明确区分“不可表达”和“学习失败”。

### 7.3 T 的 NO-S：预期阴性对照

- 六个世界 T 都是 `S_truth AND b==1`。
- 在 depth 3 下，`And(Call(S), Eq(b,1))` 可用；没有 S 时，T 需要更深的扁平表达。
- 六个 NO-S 全部 `NO_CANDIDATE`，没有一次“碰巧”激活；这支持 S 作为 T 组合单元的必要性。
- 注意：这不是“T 永远不能不用 S”；如果预算更深，理论上可以扁平表达。本轮只声明“在冻结 depth=3 与当前原语/能力库下”。

### 7.4 策略优势有限

- W1 开发集：utility/divergence 2 次 S 查询，fixed/random 3 次；正确率都 27/27。
- 差异只有 1 次查询，且只在一个世界；T 阶段四种策略都是 2 次。
- T 的第 2 次查询只消除 1 个候选，说明 utility 的类级 Gini 在候选类很小时可能不如直接最小化最大分支。
- 因此 L5 不能声称“主动查询普遍优于固定/随机”；只能报告“在本轮 W1 上观察到 1 次查询的节省”。

---

## 8. 层次判定与证据

| 层次 | 判定 | 直接证据 | 反例/限制 |
|---|---|---|---|
| L1 自主复用 R | 通过 | W1/W2/W3 FULL：S 依赖 R，无 `preferredId`，27/27，激活 | W5/W6 不需要 R；W4 用了 R 但目标不可表达 |
| L2 主动查询 | 通过 | W1 S 4→2→1，2 次查询；T 5→2→1；每轮消除 >0；空查询池正确停止 | T 第 2 次查询仅消除 1 个 |
| L3 S→T | 通过 | 5/5 个进入 T 的世界：T 依赖 S，27/27，激活，81/81 审计 | W4 因 S 未激活而阻塞 |
| L4 多世界稳健 | 部分 | 5/6 完成 S/T；W5/W6 无 R 也能完成 | W4 失败；只有 6 个世界、1 个数据种子 |
| L5 策略/成本优势 | 未证实 | W1 上 ACTIVE 少 1 次查询；成本统计完整 | 单世界、单种子；T 阶段无差异 |

**总体：L3 达成，L4 部分，L5 未证实。** 这是本轮的实际 LEVEL，不把 L4/L5 写成已通过。

---

## 9. 已实施 / 未实施 / 已验证 / 证据不足

### 9.1 已实施但失败

- W4 的 S：组合器缺少 `If`，目标不在假设空间；3 个候选在公开数据上一致，HELDOUT 24/27，验证拒绝，T 未运行。
- W1/W2 的 NO-R / BASE：depth 3 下不可表达；给出错误候选，HELDOUT 22/27 或 19/27，验证拒绝。
- T 的 NO-S：6/6 `NO_CANDIDATE`；这是预期阴性对照，不是实现缺失。

### 9.2 尚未实施

- B0 `CapabilityComposer` 对 `Expr.If` / `Expr.Let` 的枚举（本轮未修改 B0 组合器）。
- 独立的闭卷考试（`independent_closed_book` 仍为 `not_run`）。
- T 之后的第三层连续学习（U/V/...）。
- 生产级架构、并发/多用户、长期记忆管理；本轮明确不要求。
- PACK-03 的任何启动。

### 9.3 已验证成功

- 四属性 81 输入上的数据冻结与不重叠。
- 训练筛选 + 行为等价类 + 主动查询 + 冻结预测 + 反例淘汰的完整循环。
- 两种通用策略；无 `preferredId` 的自主 R 选择（W1/W2/W3）。
- 自主“不复用”选择（W5/W6）。
- 查询控制器隔离：HELDOUT/池外/重复/超预算均拒绝；学生不能直接读标签。
- S 激活后可经 `executeActive` 执行（测试断言了具体输入输出）。
- S 激活后作为 T 的候选依赖；T 激活并 27/27。
- 验证失败不激活（错误候选、W4、NO-R/BASE 均验证拒绝）。
- 无法辨识时正确停止（空查询池返回 `NO_DISTINGUISHING_QUERY`，`selected=null`）。
- 14 个激活能力全 81 输入审计 1,134/1,134 正确，0 错误激活。

### 9.4 证据不足

- 主动查询相对固定/随机的普遍优势：只有 W1 的 1 次查询差异。
- 成本优势的统计显著性：没有多种子、重复运行、置信区间。
- 多世界稳健性：6 个世界、1 个划分种子；W4 已失败。
- W4 类目标的组合语法覆盖：缺少 `If`，无法判断“有 If 时学生能否学会”。
- 长期连续学习（三层以上）的成本累积：未实施。

---

## 10. 复现入口与产物

### 10.1 代码

- 学生算法：`src/main/kotlin/logic/e3/pack02/C8ActiveStudent.kt`
- 考官/世界/数据/激活：`src/main/kotlin/logic/e3/pack02/C8Exam.kt`
- 能力断言：`src/test/kotlin/logic/e3/pack02/C8ActiveTest.kt`
- 开发矩阵：`src/test/kotlin/logic/e3/pack02/C8ExamTest.kt`
- 正式考试：`src/test/kotlin/logic/e3/pack02/C8FormalExamTest.kt`
- 共享测试基础：`src/test/kotlin/logic/e3/pack02/C8TestSupport.kt`

### 10.2 证据 JSON

- 冻结清单：`experiments/representation-evolution-e3/reports/pack02r-c8-03-freeze.json`
- 开发实验：`experiments/representation-evolution-e3/reports/pack02r-c8-03-development.json`
- 正式考试：`experiments/representation-evolution-e3/reports/pack02r-c8-03-formal.json`

JSON 保留：
- 每个阶段的候选生成数、训练一致数、初始/剩余类数；
- 每轮查询输入、标签、消除数、类数变化、策略理由；
- 选中候选的 canonical AST、节点数、估算执行步数、依赖 pin；
- 前 20 个一致候选的摘要；
- 激活尝试、内容哈希、验证运行 ID、失败码；
- 全 81 输入审计结果；
- 训练/签名/冻结预测/解释器调用/墙钟成本。

### 10.3 运行

```powershell
# 开发矩阵（会写 development.json）
.\gradlew.bat test --offline --tests "logic.e3.pack02.C8ExamTest"

# 正式考试（先核对冻结哈希，会写 formal.json）
.\gradlew.bat test --offline --tests "logic.e3.pack02.C8FormalExamTest"

# 全量
.\gradlew.bat test --offline
```

### 10.4 当前测试

- 全量：**335 passed / 335 total，0 failed**。
- B0 173，E3 162（含 C8-03 新增 10 个测试）。
- 新增测试包含实际能力断言：查询消除、隔离拒绝、验证失败不激活、S 可执行、S 作为 T 依赖、无法辨识时停止、四种策略不伪造激活、数据不重叠。

---

## 11. 限制与下一步

1. **补组合语法**：在冻结预算中显式枚举 `If`（以及必要的 `Let`），再复测 W4 类目标；这是 W4 的直接修复方向，不是“多查几次”。
2. **扩大查询池/多划分种子**：W4 的公开数据覆盖也暴露了 36 点查询池的盲区；需要在学习前冻结更多查询点或多种子，而不是看完成绩再挑。
3. **策略改进**：T 的第 2 次查询只消除 1 个候选；可在 utility 中增加“最小分支收益”或“候选类质量”项，但必须先冻结再考试。
4. **独立闭卷**：当前仍是实现者运行；`independent_closed_book=not_run`，不能声称外部闭卷通过。
5. **长期链**：本轮只到 T；三层以上的成本、依赖传播和遗忘/修正未研究。
6. **统计**：多种子、重复运行、置信区间；本轮只有单种子，不能把 5/6 当作总体成功率。

研究问题的当前回答：

> **在 6 个冻结世界中的 5 个，学生能够从 R 学到 S，再从 S 学到 T；它能自主判断 R 是否值得复用（W1–W3 复用，W5/W6 明确不复用），也能自主选择有区分价值的查询（W1 S 2 次、T 2 次；固定/随机在 S 上需 3 次）。但 W4 暴露了组合语法缺少 `If` 的结构性阻断，且主动查询的成本优势目前只有一个世界的弱证据。**

---

## 12. 研究负责人审查要点

- 本轮没有把 C8-02 的 AMBIGUOUS 结果重复当成主要交付；学生实际执行了主动查询、消除和激活。
- 没有用 `preferredId`；W5/W6 的“不复用”是成本模型的自主结果。
- 没有在正式考试失败后修改学生并覆盖成绩；W4 作为失败保留。
- 没有声称 L4/L5 通过；没有声称独立闭卷。
- C4/C8 仍 `PAUSED`；PACK-03 未启动。
- 所有原始数据、旧实验、旧补丁/授权记录保持原样。

完成后停止，交由外部监督审查。
