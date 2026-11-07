# Observable Learner / 可观测学习者

> 一个把“能力到底属于谁”当作第一问题的教学实验：一个 Kotlin 程序从外部教师那里逐步获得**可审计、可归因、可复验**的能力。

**English**: A Kotlin learner that acquires auditable, attributable abilities from an external teacher. Knowledge lives in SQLite, operations are Kotlin functions with rule provenance, and every answer can be traced from question → rule → code anchor → evidence → verdict.

---

## 现在到哪一步（2026-11-07）

| 阶段 | 内容 | 状态 |
|---|---|---|
| V0 | 只会把问题转发给教师 | 保留为基线 |
| V1 | 原文精确匹配的问答记忆（SQLite） | 通过 |
| 第 1 课 | 对象 ID、名称、同名 vs 同一、未知 | 通过 |
| 第 2 课 | 属性查询、分组、比较、补教 | 通过 |
| 学前 24 课 | 23 条通用操作规则，8 批课程 | **训练 864/864，考试 960/960，24/24 课达标，教师调用 0** |
| 独立抽查 | 冻结版本 + 新世界 + 隔离夹具 | **64/64** |
| B0 类型化能力基底 | IR、能力库、验证/激活/撤销、组合、L1/L2 压缩、A/B/C 基准 | **M0–M6 完成；173/173 B0 测试；独立闭卷 not_run** |
| E3 开放表征与学习诊断 | A0–A8 契约/世界族/碰撞/适配/预算/探测/整合 + A9 协议与最小基线 | **A0–A9 阶段完成；后续 PACK-02R/MRX 见下** |
| PACK-02R / C8-03 | 四属性三值世界、主动查询、冻结预测、反例淘汰、R→S→T | **6 世界 5 个完成 S→T；W4 因组合语法缺少 If 失败；C4/C8 仍 PAUSED** |
| MRX-01 | 自主发现结构不足、提出升级并验证学习增益 | **冻结基线 27 世界；W4 开发修复；正式 7×4=28 次，LEVEL 1–3 部分，L4/5 未达成** |
| MRX-01R | 证据账本、可辨识性、FIXED/EVIDENCE/VALUE 策略 | **20 世界×6 条件=120 次；LEVEL 1 达成，L2 部分，L4/5 未达成** |
| MRX-02 | 证据正确性、真实成本、动态重规划 | **Gate 1/2 通过；Gate 3 未通过，按任务书停止，未跑 160 次正式考试** |
| MRX-02B | 候选生命周期、A/B/C 搜索、G0–G4 归因 | **生命周期/G0–G4 完成；PAIRWISE 在 W4 补足 G1/G2，但 G4 仍 false；未跑 160** |
| MRX-02C | 主动辨识、精确覆盖、W4/XOR | **W4 主动查询 27/27；独立 XOR HELDOUT 12/27；精确覆盖与 UNKNOWN 边界已实现** |
| MRX-02D | 证据回放、不可变预测、G1–G5 归因 | **W4 G1/G2/G3/G4 true（仍 provisional）；XOR G1/G2 false，定位为候选未生成/未保留** |
| MRX-02E | 真实两次失败后的第三步重规划 | **真实 DNF→IF→CNF；配对第二反馈不同→第三步不同未证明；LEVEL4_PARTIAL** |
| EH-001/002/003 | 证据来源计量、`quickTest` 分层、零淘汰 observation | **完成；默认 quickTest 14 项；全量回归仍只在授权时运行** |
| MRX Next 2026-11-07 | 可见证据→竞争失败假说→下一步真实实验 | **真实 W4/XOR 轨迹与 trace 完成；C1/C2 部分，C3 未证明，C4 INCONCLUSIVE** |
| MRX Live 2026-11-07 | 行动前假说快照、真实 observation 更新、可区分行动选择 | **真实 A/B 轨迹完成；C1/C2 PASS_LIMITED，C3 NOT_DEMONSTRATED，C4 NO_BENEFIT** |
| MRX Causal 2026-11-07 | LIVE / FROZEN / NO_HYP 消融、历史反例语义、成本审计 | **L1 MECHANISM_ONLY_PASS；L2 REAL_PAIR_NOT_FOUND；L3 INCONCLUSIVE；建议暂停 MRX 假说线路** |
| SD-01 2026-11-07 | 通用组合候选、CHALLENGE 验证、结构固化/迁移 | **SD0_PASS_LIMITED；SD1 NOT_DEMONSTRATED（仅 PROGRAM_INSTANCE）；XOR 无结构；无成本收益** |
| SD-02 2026-11-07 | typed anti-unification、holes、可执行宏、注册/迁移 | **G1 MECHANISM_ONLY_PASS（fixture 来源）；G2 可执行但未注册；G3 NOT_DEMONSTRATED** |
| SD-03 2026-11-07 | 真实学生来源→LGG→可执行宏闭环 | **G0 PASS_LIMITED；G1 NOT_DEMONSTRATED（IRRELEVANT_SOURCE_DIFFERENCE）；G2–G4 NOT_RUN** |
| SD-03 Continuation 2026-11-07 | 8 个真实来源、16 对 AST 取证 | **G0 PASS_LIMITED；G1 NOT_DEMONSTRATED（ROOT/ARG 分歧）；G2–G4 NOT_RUN；无新 RED** |
| SD-03 16-pair 2026-11-07 | 16 对真实来源逐对 LGG | **16/16 调用；14 退化；2 非退化但自由变量 typecheck 失败；E1_NO_VALID_PATTERN；E2–E4 NOT_RUN** |
| SD-03 Data Closure 2026-11-07 | 修复恒假 B、40/20/21 分割、自由变量封闭 | **旧 B 0/81 True；新 B 9/72；A 来源池 0，B 4；NO_VERIFIED_SOURCE_POOL；G0 NOT_DEMONSTRATED** |
| SD-03 Root Cause 2026-11-07 | A0–A3/B0 原子排序消融、GOLD、宏绑定 | **H1 支持（A0 漏 d=1，0 来源）；A1 恢复但撞 30k；GOLD 40/20/21 通过；宏 caller key RED→GREEN** |
| SD-03 Blind Attribution 2026-11-07 | 12 盲任务×3 arms，外部归因 | **36 runs；ATTRIBUTION 12/12 ABSTAIN；C=38/G=32/A=38；G3/G4/G5 NOT_DEMONSTRATED/NO_BENEFIT** |
| SD-03 Blind Attribution R2 2026-11-07 | 预测签名可辨识性、1 次真实探针 | **36 runs；11 ABSTAIN + 1 PAIRWISE_12；探针 0 来源且撞 30k；G2/G3 PASS_LIMITED；G4 NO_BENEFIT** |
| SD-03 Sequential R3 2026-11-07 | 第一次探针失败后的第二决策 | **2 开发任务；edb6 第二次改选 AUDIT_GENERATION_CAP；0 来源/capHit；G3 仅诊断；G4 NO_BENEFIT** |
| SD-03 R3.1 2026-11-07 | 30k 预算下 IF/OR 枚举因果 | **LEGACY IF 0/1728 全被 cap 拒；QUOTA_IF12/24 IF 接纳但 TRAIN consistent=0；nestedIf=0；NO_BENEFIT** |
| SD-03 R3.2 2026-11-07 | 枚举公平、角色覆盖、受限嵌套 | **LEX whenTrue 仅 10/24；ROTATING 24/24；NESTED 接纳 2860；三组 TRAIN consistent=0；NO_BENEFIT** |
| SD-03 E1 2026-11-07 | TRAIN 证据驱动递归条件组合 | **nested 任务 E1_DEPTH_4 1 pass/HELDOUT 20/21，两基线 0；总体 3 pass vs 11/9；MIXED** |
| SD-03 E2 2026-11-07 | 主动查询 + 局部修复 | **16 runs；ACTIVE_FULL 7 QUERY；四臂均 2 pass/42 heldout；local repairs=0；NO_BENEFIT** |
| SD-03 E2.1 2026-11-07 | 反例机会审计 + 单标签局部修复 | **QUERY 池 1 反例 q-2；E2 4 查询 0 命中；LOCAL_ONLY 真替换 TF 但三臂仍 Challenge 失败** |
| SD-03 E2.2 2026-11-07 | 一致猜想查询矩阵与命中 | **8 task-seeds×5 arms；5 反例中 E22 命中 2；5 pass/103 vs STATIC 6/119；有负作用** |

**最近一次全量回归：387/387，0 failed（B0 173 + E3 214，EH-001 时点）。**
EH-003、MRX-02E、MRX Next 之后只做了专项/快速测试，**未重跑全量**；`fullRegression=NOT_RUN`。
**当前默认 `quickTest` 执行 14 项：** EH-001 3 + EH-003 7 + MRX2DEvidence 4；MRX-02E/MRX Next 需要显式 `-PquickTests`。
**这不是“毕业”。** 两套各 120 题的综合卷尚未生成；小学 72 单元未开始。形状、颜色、情绪等只是**文本标签查询**，不代表真实看图、听声或情绪体验。

### V2：规则自主发现（2026-11-04 新增）

V1 是“教师写操作、学生执行”。V2 开始让教师**只给逐条标签**，学生自己枚举候选、挑查询、淘汰、晋升：

| 项 | 状态 |
|---|---|
| 内核 | 受限 DSL、三值逻辑、冲突保留、主动查询、有限域等价类、原子持久化、审计事件 |
| 测试 | 新增 15 项，全套 26/26 通过；V1 未改动 |
| 公开开发 | 30 个世界：30/30 规则等价，新对象预测 200/200，平均查询 1.47 |
| 独立闭卷 | **not_run**（等待外部审查者） |

入口：`logic.discovery.DiscoveryCliKt`；说明见 [V2 实验说明](./experiments/rule-discovery-v1/README.md)，接口见 [CONTRACT](./experiments/rule-discovery-v1/CONTRACT.md)，[工作报告](./experiments/rule-discovery-v1/reports/V2-WORKLOG.md)，[待解决问题](./experiments/rule-discovery-v1/OPEN-ISSUES.md)。

### B0：类型化能力基底（2026-11-05）

B0 把“能力”本身变成可版本化、可验证、可撤销、可组合的对象，作为后续学习实验的基础设施；它**不是**新的知识表征架构，也**不等于**完整的自主学习系统。

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 冻结基线、任务书与目录 | 完成 |
| M1 | 11 节点 Typed IR、三值解释器、类型检查、资源预算 | 完成 |
| M2a–M2c | SQLite 能力库、不可变版本、可信验证、激活门禁、撤销与传递失效 | 完成 |
| M3 | 有界组合、公开样本筛选、`student_composed` 注册、两个新世界与留出集 | 完成 |
| M4 | L1 正文共享验收、冻结成本模型、L2 子树抽取/重写/净收益；L3 `not_implemented` | 完成（L1/L2） |
| M5 | A/B/C 同预算基准与原始结果；D=L3 未实现 | 完成（公开开发） |
| M6 | 冻结、复现与交接 | 完成 |

- 全套测试 **173/173**（实现者自测，不是外部独立验证）；独立闭卷 **`not_run`**。
- 入口与记录：[B0 README](./experiments/capability-foundation-b0/README.md)、[M5 报告](./experiments/capability-foundation-b0/reports/M5-REPORT.md)、[冻结数据](./experiments/capability-foundation-b0/reports/B0-FREEZE.json)、[实施报告](./experiments/capability-foundation-b0/reports/B0-IMPLEMENTATION-REPORT.md)、[工作状态](./experiments/capability-foundation-b0/WORKSTATE.md)。
- 长期方向与边界：[RESEARCH-NORTH-STAR](./docs/RESEARCH-NORTH-STAR.md)；[研究讨论](./docs/RESEARCH-DISCUSSIONS.md)；[研究提案](./docs/RESEARCH-PROPOSALS.md)。
- B0 已完成并冻结（`0ca4561`）；E3-A 已完成 A0–A8（契约 + 世界族 + 碰撞 + 适配器 + 预算诊断 + 探测 + 整合），不自动进入 A9 或 E3-B/C。

### E3：开放表征与学习诊断（2026-11-05）

E3-A 研究：当现有规则不能可靠解释观察时，学生能否区分**可观察特征碰撞、搜索截断、假设歧义、标签不稳定/噪声、证据不足**，并给出可追溯证据与探测建议；允许 `UNDETERMINED`。它不要求解释真实世界为何冲突，也不把“有限域唯一”当成全局证明。

| 子任务 | 内容 | 状态 |
|---|---|---|
| A0 | 基线冻结、隔离目录、只读 B0 边界 | 完成 |
| A1 | `VisibleSchema` / `Observation` / `DiagnosticReport` / `ProbeRequest` 数据契约与校验 | 完成 |
| A2 | SOLVABLE + LATENT_ALIASING 独立世界生成器 | 完成；已修正隔离/查询权限/匹配对照 |
| A3 | NOISY_LABELS + SEARCH_LIMITED + AMBIGUOUS_EVIDENCE | 完成；已补 Composer 低预算对照 |
| A4 | 可观察特征碰撞检测（只做证据检测） | 完成；已补 objectId |
| A5 | B0 Composer/Filter 只读诊断适配器 | 完成；已补输入校验与真实轨迹 |
| A6 | 搜索空间覆盖与预算诊断 | 完成；已补行为预算/层级/事实 |
| A7 | 重复观测与公开探测动作 | 完成；已补执行门禁/响应校验 |
| A8 | 多 finding 诊断整合 | 完成；已修正探测证据合并/候选状态 |
| A9 | 公平对照协议 + 累计预算/评分契约 | 协议/smoke/评分示例完成；A10 未运行 |

- 契约实现：`src/main/kotlin/logic/e3/`；生成器：`src/main/kotlin/logic/e3/evaluator/`。A0–A9 阶段为 **297/297**；引入 PACK-02R/MRX 后最近一次全量为 **387/387**（B0 173 + E3 214，EH-001 时点；之后只跑专项，未重跑全量）。
- 已实现最小生成器与独立 oracle；未实现 A9 对照基线、原因归因或评分器；E3-B/C 未授权。
- `independent_closed_book=not_run`；E3 结果为 `implementer_run`。
- 记录：[E3 SPEC](./experiments/representation-evolution-e3/SPEC.md)、[DECISIONS](./experiments/representation-evolution-e3/DECISIONS.md)、[WORKSTATE](./experiments/representation-evolution-e3/WORKSTATE.md)、[A0/A1 报告](./experiments/representation-evolution-e3/reports/E3-A0-A1-REPORT.md)、[A2 报告](./experiments/representation-evolution-e3/reports/E3-A2-REPORT.md)。

### PACK-02R / C8-03：四属性主动学习（2026-11-06）

在 C8-02 的被动候选筛选失败后，C8-03 把 ACTIVE 机制真正扩展到四属性三值世界：81 个输入，冻结 TRAIN/QUERY/HELDOUT，学生自己生成候选、统计行为等价类、选择查询、冻结预测、按反例淘汰，并把 S 激活后作为 T 的依赖。

- 6 个正式世界，5 个完成 S→T；W4 因冻结的 B0 Composer 不枚举 `If`，S 验证 24/27 被拒。
- 正式 29 阶段、19 次激活尝试、14 次激活成功、0 次错误激活；14 个能力全 81 输入审计 1,134/1,134。
- 全量回归 **325/325**（当轮）；`independent_closed_book=not_run`。
- 记录：[C8-03 报告](./PACK-02R-C8-03-REPORT.md)、[正式 JSON](./experiments/representation-evolution-e3/reports/pack02r-c8-03-formal.json)、[冻结清单](./experiments/representation-evolution-e3/reports/pack02r-c8-03-freeze.json)。

### MRX-01：自主发现结构不足（2026-11-06）

冻结 C8-03 学生在 27 个新世界 ×2 种子上建立未知任务基线；随后加入 H1–H6 诊断、DNF/CNF/IF/KNOWLEDGE/BUDGET 升级实验和 CHALLENGE 反馈池。

- 基线 54 次：12 次激活、29 次错误识别被拒、9 次无候选、4 次搜索受限。
- W4 开发案例：发现 CHALLENGE 反例后实际运行 DNF，修复到 27/27。
- 正式 7 世界×4 条件=28 次：AUTONOMOUS 3/7 激活；结构世界仍受证据覆盖限制；0 错误激活。
- 等级：LEVEL 1–3 部分，LEVEL 4/5 未达成。
- 记录：[MRX-01 报告](./MRX-01-REPORT.md)、[基线 JSON](./experiments/representation-evolution-e3/reports/mrx-01-baseline.json)、[正式 JSON](./experiments/representation-evolution-e3/reports/mrx-01-formal.json)。

### MRX-01R：证据账本与行动策略（2026-11-06）

修复 MRX-01 的证据语义：每个 CHALLENGE 输入只付费一次、保留原始来源/查询序号/使用记录；严格区分未验证、部分、通过、反例、预算耗尽和错误；不允许 Unknown 或占位预测冒充通过。

- 20 世界×6 条件=120 次：C8-03 5 激活，MRX-01 10，FIXED 10，EVIDENCE 10，VALUE 10，ORACLE 10；0 错误激活。
- 证据驱动改变了行动（缺失知识世界 FIXED 选 KNOWLEDGE，EVIDENCE 选 DNF），但没有提高成功率，查询成本反而更高（309 → 353）。
- 等级：LEVEL 1 达成，LEVEL 2 部分，LEVEL 3 部分，LEVEL 4/5 未达成。
- 记录：[MRX-01R 报告](./MRX-01R-REPORT.md)、[正式 JSON](./experiments/representation-evolution-e3/reports/mrx-01r-formal.json)、[冻结清单](./experiments/representation-evolution-e3/reports/mrx-01r-freeze.json)。

### MRX-02：动态实验决策（2026-11-06，停在第 3 关）

把“启动时固定行动队列”改成逐行动循环：验证基础候选 → 记录真实反馈 → 更新假说/可辨识性 → 重新选择下一行动。

- Gate 1 通过：9 个证据/成本测试；真实冻结预测、Unknown/预算/反例分离、PASS_ON_CHALLENGE 带范围。
- Gate 2 通过：两个配对实验中，相同首轮 `VALIDATE_BASE` 在 `COUNTEREXAMPLE` 与 `PASS_ON_CHALLENGE` 反馈后分别选择 DNF 与 STOP。
- Gate 3 未通过：未实现 A/B/C 候选搜索对照和完整 G0–G4 归因；按任务书停止，未运行 160 次正式考试。
- 等级：LEVEL 1、2 达成，LEVEL 3 部分，LEVEL 4/5 未运行。
- 记录：[MRX-02 报告](./MRX-02-REPORT.md)、[关卡证据](./experiments/representation-evolution-e3/reports/mrx-02-gates.json)。

### MRX-02B–02E：候选生命周期、主动辨识与真实双失败（2026-11-06/07）

这一段把“候选是否生成/保留/选错”和“证据是否改变行动”拆开：

- **MRX-02B**：候选生命周期 `GENERATED → TRAIN_CONSISTENT → RETAINED → SELECTED`；A/B/C 搜索对照。PAIRWISE 在 W4 补足 G1/G2，但最终选择仍错（G4 false）。
- **MRX-02C**：把 RETAINED 候选接到真实 QUERY/CHALLENGE 上，按预测分歧主动查询。W4 6 次查询后 HELDOUT 27/27；独立 XOR 18→1 类但 HELDOUT 12/27。新增精确覆盖审计。
- **MRX-02D**：先回放已付费证据再查询；不可变预测快照；Unknown 不转 False；`ERROR` 与 `BUDGET_EXHAUSTED` 分离。W4 G1/G2/G3/G4 true，但状态仍是 `SINGLE_BEHAVIOR_CLASS_PROVISIONAL`；XOR 的 12/27 被归因为 G1/G2 false（正确候选未生成/保留），不是查询不足。
- **MRX-02E**：真实 `MRX2Student` 在公开合成 parity 世界产生两次真实失败，第三步从 DNF→IF→CNF；STATIC 与 DYNAMIC 第三步相同，**配对第二反馈不同→第三步不同未证明**，结论 `LEVEL4_PARTIAL`。
- 正式 160 次考试仍未运行；`independent_closed_book=not_run`。

记录：[MRX-02B](./MRX-02B-REPORT.md)、[MRX-02C](./MRX-02C-REPORT.md)、[MRX-02D](./MRX-02D-REPORT.md)、[MRX-02E](./MRX-02E-REPORT.md)。

### Engineering Harness EH-001–003（2026-11-07）

为小修改建立可重复的工程检查，不把工程通过冒充科研能力：

- **EH-001**：`reusedLabels` 按已付费 `(channel, entry)` 计，不再按淘汰候选数计。
- **EH-002**：独立 `quickTest` task；默认只跑明确列出的快速测试；空 `-PquickTests=` fail-fast；原 `test` 语义不变。
- **EH-003**：零淘汰的回放标签也写入 observation；`ONE_SURVIVOR_WITH_EVIDENCE` 必须由最终幸存者自己拥有 Known 匹配证据，Unknown 不确认。
- 当前默认 `quickTest`：EH-001 3 + EH-003 7 + MRX2DEvidence 4 = **14 项**；MRX-02E/MRX Next 用显式测试类运行。
- 全量回归仍只在研究负责人授权时运行；EH-001 时点为 387/387。

记录：[EH-001](./EH-001-REPORT.md)、[EH-002](./EH-002-REPORT.md)、[EH-003](./EH-003-REPORT.md)、[测试分层](./docs/engineering/TESTING.md)。

### MRX Next 2026-11-07：可见证据与竞争假说

小规模开放探索：在真实 `MRX2Student` 运行后，用学生可见的 step/账本证据生成四个竞争失败假说，并记录支持/反驳/未知 evidence ID；W4 与独立 XOR 各跑 STATIC/RULE/EVIDENCE 三条真实轨迹。

- **C1 证据真实性：PASS_LIMITED**；**C2 区分性归因：PASS_LIMITED**。
- **C3 证据改变决策：NOT_DEMONSTRATED**：没有“同初始、同第一失败、第二反馈不同”的真实配对；XOR 的第三步差异来自策略/队列，不是证据。
- **C4 学习收益：INCONCLUSIVE**：W4 三策略都成功，EVIDENCE 与 STATIC 相同；XOR 三策略都失败，瓶颈仍是候选空间覆盖。
- 共 6 条真实轨迹；无 mock 反馈、无 HELDOUT 进入学生侧；教师贡献仅内置假说模板与分析器，不能称“学生自主发现”。

记录：[RESEARCH-QUESTION](./docs/research/mrx-next-2026-11-07/RESEARCH-QUESTION.md)、[COMPARISON](./docs/research/mrx-next-2026-11-07/COMPARISON.md)、[RESULT](./docs/research/mrx-next-2026-11-07/RESULT.md)。

### MRX Live 2026-11-07：行动前的实时假说

新增可选 `LIVE_HYPOTHESIS`：每一步动作前冻结 `HypothesisSnapshot_before`；真实执行后用 observation 更新为 `after`；决策读取假说状态与动作预测，而不是 `feedbackCode → action` 查表。

- **C1 时间真实：PASS_LIMITED**：before 不含未来 observation，after 版本递增；旧 `diagnosisBefore/After` 仍有限。
- **C2 在线归因：PASS_LIMITED**：4 个教师先验假说可分化，决策记录 `DISCRIMINATE=...`；但动作-假说模型是 teacher-prior。
- **C3 严格配对：NOT_DEMONSTRATED**：A/B 首个真实反馈已不同，A 只有 1 个 action；未找到同前缀、第二反馈不同的配对。
- **C4 收益：INCONCLUSIVE / NO_BENEFIT**：A 成功但仅 1 action；B 失败；无同轮 STATIC/RULE 配对。
- 匿名 session id 为 `session-a/b`，不含 W4/XOR 等家族名；HELDOUT/目标真值不进入学生。
- `LIVE_FROZEN_BELIEF`/`LIVE_NO_HYP` 仅在决策器消融层实现；未接入真实同轮执行。`formal_runs=0`。

记录：[RESULT](./docs/research/mrx-live-2026-11-07/RESULT.md)、[RED](./docs/research/mrx-live-2026-11-07/RED.log)、[GREEN](./docs/research/mrx-live-2026-11-07/GREEN.log)、[TRACE A](./docs/research/mrx-live-2026-11-07/TRACE-session-a.jsonl)、[TRACE B](./docs/research/mrx-live-2026-11-07/TRACE-session-b.jsonl)。

### MRX Causal Ablation 2026-11-07：信念是否真的影响选择

- **L1 机制敏感性：MECHANISM_ONLY_PASS**：同一决策器在“反例”与“预算耗尽”两个语义快照上选择不同；FROZEN/NO_HYP 不产生同样的信念依赖。合成 fixture，不等于真实学习因果。
- **L2 严格配对：REAL_PAIR_NOT_FOUND / NOT_DEMONSTRATED**：只检查 2 个匿名 session；首步真实反馈已不同，A 只有 1 个 action；未做 24×8 有界搜索。
- **L3 收益：INCONCLUSIVE**：无同轮 LIVE/FROZEN/NO_HYP 真实执行；`newQueries=0` 与 ledger 条数不一致，成本标 `UNRELIABLE`。
- 语义修正：后续 PASS 不再把历史反例标为 REFUTED；但假说仍缺 candidate/scope 结构化字段。
- 研究决策：建议暂停 MRX 实时假说线路，转向候选结构/表达空间扩展。
- 记录：[预注册](./docs/research/mrx-causal-ablation-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/mrx-causal-ablation-2026-11-07/CAUSAL-ABLATION-RESULT.md)。

### SD-01 2026-11-07：结构发现与迁移

- 用现有 `MRX2BSpace` 生成候选：W4 28,523 generated / 335 retained，1 个 CHALLENGE 16/16 通过；XOR 112 retained 但无通过候选。
- 仅形成 `PROGRAM_INSTANCE`（无参数），不是可复用宏；迁移到 XOR 5/9，禁用对照无真实调用；**SD1 NOT_DEMONSTRATED**。
- 固定枚举器也找到 W4 答案，因此不宣称新搜索算法或“自主扩展表达语法”。
- 记录：[预注册](./docs/research/sd-01-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd-01-2026-11-07/RESULT.md)、[TRACE](./docs/research/sd-01-2026-11-07/TRACE-SD01.jsonl)。

### SD-02 2026-11-07：参数化结构归纳

- typed LGG 从两个 fixture 来源得到 4 个 `Str` holes 的 `And(Eq(h0,h1),Not(Eq(h2,h3)))`，替换/重建通过。
- 宏通过类型检查并以真实 `Expr.Call` 经解释器执行；但未走 propose/verify/activate，**G2 NOT_DEMONSTRATED**。
- 来源不是学生账本验证程序；迁移/ENABLED-DISABLED 真实对照未做，**G3 NOT_DEMONSTRATED**。
- 记录：[预注册](./docs/research/sd-02-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd-02-2026-11-07/RESULT.md)。

### SD-03 2026-11-07：真实来源结构闭环

- 两个匿名开发任务经 `MRX2BSpace` + `MRX2Ledger.validate` 找到两个不同 challenge-pass 来源；无 truth lambda 进入学生侧。
- 现有 `SD02AntiUnify` 对两个来源返回 `IRRELEVANT_SOURCE_DIFFERENCE`，未形成非退化参数化宏；**G1 NOT_DEMONSTRATED**。
- 无注册/激活、无真实迁移/禁用重跑；`formal_runs=0`、`fullRegression=NOT_RUN`。
- 记录：[预注册](./docs/research/sd-03-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd-03-2026-11-07/RESULT.md)、[TRACE](./docs/research/sd-03-2026-11-07/TRACE-SD03.jsonl)。

### SD-03 Continuation 2026-11-07：真实 AST 取证

- A/B 两个匿名任务各得到 4 个真实 challenge-pass 来源，共 16 对。
- 首次不一致分类：12 个 `ROOT_MISMATCH`、2 个 `ARG_MISMATCH`（示例路径 `$.right` Eq/Not、`$.left` Eq/Call、`$` Or/And）。
- 没有 pair 形成非退化 typed pattern；G1 `NOT_DEMONSTRATED`，未进入注册/迁移/消融。
- 本轮新增取证测试首跑通过，没有新业务 RED；未伪造 RED。
- 记录：[预注册](./docs/research/sd03-continuation-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-continuation-2026-11-07/RESULT.md)、[取证 trace](./docs/research/sd03-continuation-2026-11-07/FORENSIC-SD03.jsonl)。

### SD-03 16-pair 2026-11-07：逐对真实归纳

- A/B 各 4 个真实来源，4×4=16 对，全部实际调用 `SD02AntiUnify.generalize`。
- 14 对 `IRRELEVANT_SOURCE_DIFFERENCE`；2 对非退化/可重建，但 pattern 含自由 `Arg(a/b)`，宏 typecheck 失败。
- 无合法可激活宏：`E1_NO_VALID_PATTERN`；E2–E4 NOT_RUN。
- 记录：[预注册](./docs/research/sd03-16pair-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-16pair-2026-11-07/RESULT.md)、[PAIR-LGG](./docs/research/sd03-16pair-2026-11-07/PAIR-LGG.jsonl)。

### SD-03 Data Closure 2026-11-07：有效世界与自由变量

- 旧 B `b=3&&c=3` 在 81 输入上 0 True/81 False；新 B `b=1&&c=1` 为 9/72。
- seed 1449 的 40/20/21 split 通过正负质量门禁；A 仅 5 个 TRAIN 正例。
- 真实来源池：A=0，B=4；按预注册停止为 `NO_VERIFIED_SOURCE_POOL`，未换 seed/补来源。
- 已实现通用 `freeArgs` 收集与 holes+freeArgs 宏输入，但无真实跨任务 pair 可验证；G0 NOT_DEMONSTRATED。
- 记录：[预注册](./docs/research/sd03-data-closure-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-data-closure-2026-11-07/RESULT.md)、[世界审计](./docs/research/sd03-data-closure-2026-11-07/WORLD-AUDIT.json)。

### SD-03 Root Cause 2026-11-07：A0 为什么是 0

- A0 PAIRWISE/10：28,523 generated，0 train-consistent，0 Challenge-pass；`d=1` 未进 top-10，`rhsAtomRetained=false`。
- A1 PAIRWISE/12：恢复 4 个来源，但 generated=30,000，撞候选上限；A2/A3 也恢复，B0 校准 4。
- GOLD typecheck 通过，TRAIN 40/40、CHALLENGE 20/20、HELDOUT 21/21；排除 IR/Oracle 故障。
- 宏 caller `values` 缺 `call_` 前缀：真实 RED `input_argument_mismatch`，修复后 GREEN。
- 根因是原子 top-10 截断，不是数据/剪枝/类型；A1 同时暴露 30k 上限。
- 记录：[结果](./docs/research/sd03-root-cause-2026-11-07/RESULT.md)、[矩阵](./docs/research/sd03-root-cause-2026-11-07/ROOT-CAUSE-MATRIX.jsonl)。

### SD-03 Blind Attribution 2026-11-07：盲归因全部 ABSTAIN

- 6 个盲世界×2 种子=12 个任务-种子；CONTROL/GENERIC/ATTRIBUTION 共 36 次真实运行。
- 归因器只读 TRAIN observations/ranked atoms/lifecycle；12/12 选择 ABSTAIN。
- Challenge pass 总数：CONTROL 38，GENERIC 32，ATTRIBUTION 38；**相对 CONTROL 收益 0**。
- G0/G1 PASS_LIMITED；G2/G3/G5 NOT_DEMONSTRATED；G4 NO_BENEFIT。
- 记录：[预注册](./docs/research/sd03-blind-attribution-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-blind-attribution-2026-11-07/RESULT.md)、[TRACE](./docs/research/sd03-blind-attribution-2026-11-07/TRACE-BLIND.jsonl)。

### SD-03 Blind Attribution R2 2026-11-07：签名可辨识性修复

- 用相反标签 TRAIN 对在保留原子上的真实预测签名判断不可区分，不再把“属性存在”当信息足够。
- 12 task-seeds：11 ABSTAIN，1 个真实 witness 触发 `PAIRWISE_12` 探针。
- 探针仍 0 来源、generated=30,000 capHit；ATTRIBUTION/Control 均 38 pass、HELDOUT 210；G4 NO_BENEFIT。
- 记录：[预注册](./docs/research/sd03-blind-r2-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-blind-r2-2026-11-07/RESULT.md)、[DECISIONS](./docs/research/sd03-blind-r2-2026-11-07/DECISIONS-R2.jsonl)。

### SD-03 Sequential Attribution R3 2026-11-07：失败探针后的第二决策

- 两个公开开发任务：`edb6ed` 首次 `PROBE_PAIRWISE_12` → 第二次 `AUDIT_GENERATION_CAP`；`068096` 首次 ABSTAIN。
- 决策先封存再评分；第二次快照为真实 MRX2BSpace 重跑，TRAIN/atom universe 不变。
- `edb6ed` 的 signature 阻塞已移除，但 train-consistent 仍为 0、generated 30,000 capHit；第二次动作是诊断请求，不是修复。
- 三臂：CONTROL 3/21、GENERIC 0/0、ATTRIBUTION 3/21；无收益。
- G3 仅 `PASS_LIMITED（diagnostic）`；G4 NO_BENEFIT；G5 NOT_DEMONSTRATED。
- 记录：[预注册](./docs/research/sd03-r3-development-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-r3-development-2026-11-07/RESULT.md)、[CHRONOLOGY](./docs/research/sd03-r3-development-2026-11-07/CHRONOLOGY.jsonl)。

### SD-03 R3.1 2026-11-07：枚举预算竞争拆解

- LEGACY_12：generated 30,000；IF attempted 1,728，admitted 0，全部 globalCapRejected；OR 先接纳 29,676。
- QUOTA_IF12：IF admitted 1,728；但 TRAIN consistent=0；generated 25,052（空配额不借用）。
- QUOTA_IF24：IF admitted 5,720；仍 0 consistent；三组 nestedIfAstCount=0。
- 直接证据：IF 不是不可表达，而是被 OR 先耗尽预算；但恢复 IF 不等于任务可解。
- Challenge/HELDOUT 三组均 0；`NO_BENEFIT`；未声称学生自主修复。
- 记录：[预注册](./docs/research/sd03-r31-development-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-r31-development-2026-11-07/RESULT.md)、[FROZEN](./docs/research/sd03-r31-development-2026-11-07/FROZEN-BEFORE-GRADING.jsonl)。

### SD-03 R3.2 2026-11-07：枚举顺序与受限嵌套

- LEX_DIRECT24 的 `whenTrue` 角色只覆盖 10/24；ROTATING_DIRECT24 覆盖 24/24，证明覆盖差异来自顺序。
- ROTATING_NESTED24 实际接纳 2,860 个嵌套 If；但这是语法+配额再分配的联合干预。
- 三组 generated≈29,044、TRAIN consistent=0、Challenge=0、无 HELDOUT；`NO_BENEFIT`。
- 记录：[预注册](./docs/research/sd03-r32-development-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-r32-development-2026-11-07/RESULT.md)、[FROZEN](./docs/research/sd03-r32-development-2026-11-07/FROZEN-BEFORE-GRADING.jsonl)。

### SD-03 E1 2026-11-07：证据驱动递归条件组合

- 4 个公开开发任务×4 arms：E1_DEPTH_4 在 `mrx-if-nested-3` 首次产生 1 个 Challenge-pass，HELDOUT 20/21；LEGACY/R32 均为 0。
- 冻结程序 19 节点、深度 4、独立解释器 TRAIN 40/40、typecheck=true；6 个条件分裂有证据 ID。
- E1_DEPTH_1 全 0，说明递归深度必要；但 E1 总体 3 pass vs LEGACY 11/R32 9，总 HELDOUT 62/63 vs 63/63，**总体无优势**。
- 开发任务公开，非独立闭卷；未注册/激活能力。
- 记录：[预注册](./docs/research/sd03-e1-development-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-e1-development-2026-11-07/RESULT.md)、[EXAMINER](./docs/research/sd03-e1-development-2026-11-07/EXAMINER-AFTER-FREEZE.jsonl)。

### SD-03 E2 2026-11-07：主动查询与局部修复

- 4 任务×4 arms：STATIC/RANDOM/ACTIVE_FULL/ACTIVE_LOCAL 均 2 pass、HELDOUT 42/42（仅初始成功任务）。
- ACTIVE_FULL 有 7 个真实预测分歧 QUERY，决策先冻结再 reveal；但 refit 未改变 Challenge。
- ACTIVE_LOCAL 的 local repairs=0；没有非根局部替换，不能把整体 refit 当局部修复。
- ACTIVE_FULL preview 30,240 vs 其他 1,920/5,490，成本更高无收益；`NO_BENEFIT`。
- 记录：[预注册](./docs/research/sd03-e2-development-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-e2-development-2026-11-07/RESULT.md)、[EXAMINER](./docs/research/sd03-e2-development-2026-11-07/EXAMINER-AFTER-FREEZE.jsonl)。

### SD-03 E2.1 2026-11-07：反例机会与局部修复

- 4 任务 QUERY 池仅 `if-nested-3` 有 1 个真实反例 q-2；E2_DISAGREEMENT 4 次查询未命中，STRICT 0 查询。
- B 只揭示 q-2 一条标签：LOCAL_ONLY 真实替换非根 `TF`，兄弟不变、旧证据正确；但 NO_UPDATE/FULL_REFIT/LOCAL 三臂 Challenge 均失败。
- 局部修复机制有限成立；反例命中与任务收益未证明；无 HELDOUT。
- 记录：[预注册](./docs/research/sd03-e21-development-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-e21-development-2026-11-07/RESULT.md)、[AUDIT](./docs/research/sd03-e21-development-2026-11-07/AUDIT-OUTPUT.txt)。

### SD-03 E2.2 2026-11-07：一致猜想与查询命中

- 8 task-seed×5 arms，40 个最终冻结；76 个完整查询矩阵快照；56 个 QUERY 标签。
- 3 个 task-seed 有 5 个初始反例；E22 命中 2（q-2、q-6），E2_LEGACY 0，RANDOM 1。
- 但 E22 最终 5 pass/103 HELDOUT，低于 STATIC 6/119；4b1f 从 PASS 变 COUNTEREXAMPLE。
- 命中反例≠学习收益；严格 E21_STRICT 0 查询，等同 STATIC。
- 记录：[预注册](./docs/research/sd03-e22-development-2026-11-07/PREREGISTRATION.md)、[结果](./docs/research/sd03-e22-development-2026-11-07/RESULT.md)、[AUDIT](./docs/research/sd03-e22-development-2026-11-07/AUDIT-OUTPUT.txt)。

### Research Console（本地）

研究门禁、任务状态和证据记录在 `research-console/`。网页控制台只绑定本机：

```powershell
$env:RESEARCH_CONSOLE_TOKEN = "local-console-token-2026"
python tools/research-console/server.py --port 8765
# http://127.0.0.1:8765/
```

启动后访问 `http://127.0.0.1:8765/`；服务只绑定本机。CLI 入口：

```powershell
python tools/research-console/console_cli.py preflight --task <任务ID>
python tools/research-console/console_cli.py finish --task <任务ID> --commit <SHA> --test "<结果>" --report <路径>
```

---

## 这是什么

- **学生不是 LLM 调用壳。** 知识存在 SQLite：对象、属性、关系、证据、观察、故事、政策、轮次，可查、可撤销、带来源。
- **操作是代码，不是提示词。** 每个操作对应一条带 `RULE:<id>` 锚点的 Kotlin 函数，规则目录记录首次课程、引入时间、原因、真实作者。
- **每次执行都留痕。** `rule_enter` / `rule_exit` / `rule_error` 事件带参数、事实依据 `fact_ids`、span/trace、耗时；同时输出人读的 `*.steps.log`。
- **考试纪律。** 冻结代码、关闭教师、换新世界出题；`unknown` 不等于 `false`，证据冲突必须报 `conflict`，不允许静默取最后一次写入。

## 这不是什么

- 不是“又一个 Agent 框架”，也不是在训练模型权重。
- 不记录、也不声称记录模型内部思考；教师运行器只记录可见的进程启动、输入、等待、解析。
- 不把“教师写好、学生执行”包装成“学生自主发现”。规则目录里 `teacher_programmed` 与 `historical_backfill` 分开标注。

---

## 快速开始

需要 **JDK 21**。Gradle 用仓库自带 Wrapper，首次构建需联网下载依赖。

```powershell
# 只构建，不触发历史科研测试
./gradlew.bat --no-daemon installDist

# 日常工程验证：默认 14 项，不跑 MRXRFormal/MRXFormal/MRXBaseline
./gradlew.bat quickTest --offline --console=plain

# 指定测试类（示例）
./gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.MRX2EPlannerContractTest,logic.e3.pack02.MRXNextTraceTest"

# 全量回归：只在研究负责人明确授权时运行
# ./gradlew.bat test --offline
```

**不要**在日常修改后直接执行无筛选 `test`、`check` 或 `build`：它们会重新运行历史科研实验。完整说明见 [工程测试分层](./docs/engineering/TESTING.md)。

Linux/macOS 将 `./gradlew.bat` 换成 `./gradlew`。

构建产物：`build/install/logic-learner/bin/logic-learner`（Windows 为 `.bat`）。

### 离线跑通（不调用任何外部服务）

```powershell
$app = "build/install/logic-learner/bin/logic-learner.bat"

# V0：纯转发，用 mock 教师
& $app --student v0 --teacher mock "什么是苹果？"

# V1：第一次问 mock 教师并记住，第二次关闭教师直接复用
& $app --teacher mock "什么是苹果？"
& $app --no-teacher "什么是苹果？"

# 第 1 课：导入材料 → 查对象 → 反查名称 → 比较同名对象
& $app --lesson 1 导入 experiments/lessons/preschool-001/materials.json
& $app --lesson 1 查 A
& $app --lesson 1 找 苹果
& $app --lesson 1 比较 A D

# 第 2 课：属性查询与分组
& $app --lesson 2 导入 experiments/lessons/preschool-002/materials.json
& $app --lesson 2 找 颜色 红色
```

### 接真实教师（可选）

默认教师是**本机 DeepSeek Harness CLI**，需要先安装并登录，然后设置安装目录：

```powershell
$env:DSH_INSTALL_DIR = "C:\dsh"      # 按实际安装位置
& $app "什么是苹果？"
```

没有安装时，所有课程都能用 `--teacher mock` 或 `--no-teacher` 离线运行。

---

## 批量课程与审计报告

`tools/curriculum_batch.py` 会生成 24 课训练与考试世界、批量调用学生、机械判分，并渲染离线 HTML 报告：

```powershell
python tools/curriculum_batch.py --output runs/attempt-01 --seed 20261104 --build
```

输出：

- `report.html`：自包含离线报告，可按课跳转、按题型/结果筛选，展开每题的规则链、代码锚点、事实依据与判分；
- `report.json`：给机器读的同一份数据；
- `student/events.jsonl`、`student/events.steps.log`：原始执行与中文步骤日志；
- `manifest.json`：源码、规则、课程材料、jar 的哈希快照。

`runs/` 默认不纳入版本控制；每次实验都应使用**新目录**，失败证据不要删除。

---

## 仓库结构

```text
src/main/kotlin/logic/
  Main.kt           命令行入口：V0/V1、课 1/2、--batch 分流
  Student.kt        V1 执行：先查记忆，未命中再求助教师
  AnswerMemory.kt   SQLite 问答记忆、精确匹配、来源、撤销
  CliTeacher.kt     调用本机 DeepSeek Harness CLI（超时、解析、清理）
  Events.kt         JSONL 事件 + 中文 steps 日志 + 脱敏
  ObjectNames.kt    第 1 课：对象/名称/同一性
  LessonOne.kt      第 1 课命令分派
  LessonAttributes.kt / LessonTwo.kt   第 2 课：属性
  BatchStudent.kt   学前 24 课的 23 条操作规则
  BatchModel.kt     世界模型、加载器、持久化
  BatchCli.kt       批量请求入口
  AuditTrail.kt     rule_enter/exit/error、span/trace、事实依据
  RuleCatalog.kt    规则目录加载与校验

  capability/       B0 类型化能力基底
    CapabilityIr.kt / CapabilityJson.kt / CapabilityTypeChecker.kt / CapabilityInterpreter.kt
    CapabilityRegistry.kt        SQLite 能力库（schema v3、不可变版本、验证/激活/撤销）
    TrustedVerification.kt       宿主可信套件与内存验证引擎
    CapabilityVerificationService.kt / CapabilityActivationService.kt / CapabilityExecutionService.kt
    CapabilityComposer.kt        有界强类型候选枚举
    CandidateEvidenceFilter.kt   公开样本筛选、歧义与拒答
    StudentComposition.kt        student_composed 注册与留出集评估
    LibraryCost.kt / SubtreeExtraction.kt / SubtreeRewriting.kt   成本模型与 L1/L2 压缩

  pack02/            PACK-02R / MRX 学习实验
    ActiveLearning.kt / Pack02Knowledge.kt   ACTIVE 隔离、候选/查询/激活
    C8ActiveStudent.kt / C8Exam.kt           C8-03 四属性 S/T 与六世界考试
    C8Study.kt / Eval*.kt                    C8-02 / EVAL 对照与诊断
    MRXWorlds.kt / MRXSpace.kt / MRXStudent.kt / MRXExam.kt   MRX-01 结构诊断/升级
    MRXREvidence.kt / MRXRStudent.kt / MRXRSpace.kt / MRXRExam.kt   MRX-01R 证据账本/策略
    MRX2Evidence.kt / MRX2Student.kt         MRX-02 证据语义、成本与动态重规划
    MRX2BSpace.kt / MRX2BAudit.kt            MRX-02B 候选生命周期与 G0–G4 审计
    MRX2C.kt                                 MRX-02C 主动辨识与精确覆盖
    MRX2D.kt                                 MRX-02D 证据回放与保守确认
    MRX2E.kt / MRX2Student.kt                MRX-02E 真实双失败后的第三步重规划
    MRXNext.kt                               MRX Next 可见证据/竞争假说 trace
    MRXLive.kt                               MRX Live 行动前假说/真实 observation 适配器
    SD01.kt                                  SD-01 结构发现/候选验证/迁移骨架
    SD02.kt                                  SD-02 typed anti-unification/宏执行契约
    SD03.kt                                  SD-03 真实学生来源/反统一闭环

experiments/
  curriculum/       课程计划、规则目录 rules.json、批量教材 curriculum.json
  lessons/          第 1/2 课材料与说明
  reports/          各阶段验证与审计记录
  capability-foundation-b0/  B0 任务书、决策、报告与工作状态
  representation-evolution-e3/  E3/PACK-02R/MRX 报告与 JSON 证据

docs/
  RESEARCH-NORTH-STAR.md  长期研究目标与边界

tools/              批量考试生成器、HTML/JSON 报告渲染
可读性工程/         注释与可读性改造的记录、示例、验证
AGENTS.md           项目约定（注释、作者、日志、审计纪律）
```

---

## 已知边界

- **能力归属**：当前 24 课里 21 条规则是 `teacher_programmed`，2 条是历史回填。学生证明的是“能稳定执行并迁移到新世界”，不是“自己发现了方法”。
- **语义 vs 标签**：通过的是符号层任务；没有视觉、听觉、真实情绪，也没有自由语言理解。
- **考试不是毕业**：单课 40 题达标只说明本课过线；综合卷与跨领域迁移尚未完成。
- **B0 已冻结**：M0–M6 完成；L3 参数化抽象与 D 组 `not_implemented`；独立闭卷 `not_run`。
- **B0 成绩口径**：173/173 为实现者自测；独立闭卷 `not_run`，不能写成外部验证或“已学会自主学习”。
- **E3 当前边界**：A0–A8 完成，A9 评测基础设施冻结（P0 完成），A10 未运行；没有完整的独立原因归因或评分器；不能把“生成器存在”当成诊断能力。A0–A9 阶段 297/297 是 `implementer_run`；引入 PACK-02R/MRX 后当前全量 367/367。
- **PACK-02R / C8-03 边界**：6 个世界中 5 个完成 S→T；W4 因冻结的 B0 Composer 不枚举 `If` 而失败，不是学生能凭空修复的问题。C4/C8 仍 `PAUSED`，不能写成 PACK-02 整体通过。
- **MRX 边界**：MRX-01 的 7 世界×4 条件、MRX-01R 的 20 世界×6 条件都是实现者冻结运行，不是外部独立闭卷；MRX-02 只通过 Gate 1/2，Gate 3 未通过，未运行 160 次正式考试。EVIDENCE/VALUE 在 MRX-01R 中没有超过 FIXED 的成功率，结构世界仍未修复。
- **成本指标**：`L_total` 是冻结的静态长度代理，不代表运行更快；压缩通过不等于获得新知识。MRX-02 的 `MRX2Cost` 已区分 Oracle 新查询/复用、生成候选和解释器调用，但尚未跨全部实验统一核算。
- **可复现性**：报告里的成绩来自真实进程执行；`runs/` 不随仓库分发，需按上面的命令自行生成。

## 开发约定

- 文件头注明**真实作者、日期、修改原因**；注释写到字段、分支、SQL、并发、异常附近，解释“为什么”，不逐行复述赋值。
- 不预先写“已审计/已合并”；候选方案先隔离验证，再晋升。
- 不伪造通过率；失败保留原 attempt，修正后用新目录重跑。
- 日志不写入凭据、环境变量、授权头、原始 stderr 或模型内部思考。

## License

[MIT](./LICENSE)。Copyright (c) 2026 Observable Learner contributors.

## 致谢与真实分工

- 教师角色：本机 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) Agent。
- 早期 V0/V1、课 1/2：Codex（上一阶段，OpenAI）。
- 学前 24 课批量实现：本地 DeepSeek Harness 会话。
- 首考执行、抽查复验、报告校验、开源整理、V2：**本会话 DeepSeek Harness**。
- `codex_spotcheck.py` 由 Codex 编写；本会话执行，并修正 1 处夹具前提（对象缺 `name` 不是非法世界）。
- B0 M0–M4：**本会话 DeepSeek Harness** 实现；M1 参考骨架由云端 ChatGPT 提供；外部独立审查尚未进行。
- PACK-02R/C8-03、MRX-01、MRX-01R、MRX-02：**本会话 DeepSeek Harness** 实现并运行；MRX-01R 曾因跨会话误发被另一会话撤销，研究负责人确认后保留审计并重新激活；以上均为实现者冻结运行，不是外部独立闭卷。

**独立闭卷尚未执行。** 同模型、不同会话不构成第三方验证；闭卷状态在报告中固定为 `not_run`，等待外部审查者。



















