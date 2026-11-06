# MRX-01 报告：自主发现认知结构不足、提出升级并验证学习增益

- 任务：`MRX-01`
- 授权：Research Console 专项授权，研究负责人 2026-11-06
- 基线：PACK-02R-C8-03，学生 SHA `a6ab1a51f1beefcb0e1a1cd9d0c3180c2d01f76e`
- MRX 学生/协议冻结提交：`8c4399d6cad28d40cf431653104e32ec19b87b1a`
- 冻结清单提交：`7363ae34003b5e30067c0e1cb121624a822592c5`
- 正式结果提交：`8b8c084f49f93c7d3416e891140e96bd13cf7e67`
- 最终测试：Kotlin **343/343**（0 failed；B0 173 + E3 170）
- `independent_closed_book = not_run`
- C4 / C8 保持 `PAUSED`；未启动 PACK-03

---

## 0. 结论摘要

本轮实现了真正的“失败诊断 → 升级实验 → 选择 → 验证”链路，而不是只输出一个失败码：

1. 用冻结 C8-03 学生在 **27 个新世界 × 2 个种子 = 54 次运行**上建立未知任务基线；
2. 失败时保留 H1–H6 竞争解释（表达空间、深度、知识、证据、查询覆盖、噪声/冲突），每条都带支持/反对证据、缺失信息和可执行诊断动作；
3. 学生实际运行升级实验：DNF/CNF 深度扩展、`If` 条件结构、恢复已激活但未使用的知识、扩大候选预算、继续观察/不行动；
4. 使用独立 CHALLENGE 反馈池；CHALLENGE 标签必须付费查询，且永远不作为最终 HELDOUT 成绩；
5. 开发案例 W4：冻结基线 HELDOUT 24/27，学生发现 CHALLENGE 反例，诊断 H1/H2/H4/H5，实际尝试 DNF，修复后 **27/27 并激活**；
6. 负例：歧义世界正确“不升级”（26/27，无激活）；噪声世界正确判为 H6 `NO_ACTION`；缺失知识世界选择恢复知识并 27/27；
7. 正式四条件在 7 个新世界 × 4 条件 = **28 次运行**上完成，但结果明显弱于开发案例。

**核心结论：**

- 当存在可获得的 CHALLENGE 反例时，学生能够识别结构/表达不足，提出并实际测试升级，修复后学习到原本不可得的知识（W4 开发案例）。
- 当反例只存在于最终 HELDOUT、CHALLENGE 池没有覆盖区分输入时，学生**无法**仅靠增加查询推断结构不足。正式结构世界 f-depth / f-if 正是这种情况：基础候选与全部 CHALLENGE 标签一致，学生没有触发升级；即使 ORACLE-OPERATOR 强制给出正确操作，候选选择仍只得到 24/27。这不是伪造成功，而是“未知不等于否定”的科学边界。
- 正式 AUTONOMOUS 激活 **3/7**（可表达、缺失知识、搜索预算）；结构世界 0/2 修复；歧义/噪声正确不升级；错误激活 **0**。
- 因此：**机制已实现并部分有效，但尚未达到可迁移的自主结构发现。LEVEL 1/2/3 部分，LEVEL 4/5 未达成。**

---

## 1. 授权、冻结与纪律

- 任务开始前执行 `preflight --task MRX-01`，返回 `AUTHORIZED_REPAIR: MRX-01`。
- 授权事件：`ev-1793961698101-281787`，状态 `ACTIVE`。
- 冻结清单：`experiments/representation-evolution-e3/reports/mrx-01-freeze.json`
- 冻结文件 SHA-256：
  - `MRXSpace.kt` `b1a24f4a2d9e35c99b87fdfff56d9ef655f9662985af95c65d4ac34152c86e8a`
  - `MRXStudent.kt` `4a3fee4d5a2b8f94211f3a9752bb9c9c56591c6924535ec74669646481e11811`
  - `MRXWorlds.kt` `e669f802d6843887432471bafff581246d9267d7067c7eb4855a91ac73fdb42b`
  - `MRXExam.kt` `521375557130668979879ebd4407b7d4e158ad2b5e101c5488d4f03025d4d10d`
- 正式测试先重算这四个文件的 SHA-256，与冻结清单比对，再运行。
- 正式世界和种子规则在运行前固定：
  - 7 个正式世界，ID 与开发世界不同；
  - 种子规则 `findSeed(from=7100+index*137)`，只按覆盖率与歧义例外位置选择，不看学生成绩；
  - 数据划分 9 TRAIN / 29 QUERY / 16 CHALLENGE / 27 HELDOUT；
  - CHALLENGE 不进入最终成绩。
- 正式结果未用于回改学生；失败世界保留。
- 基线 54 次运行只调用冻结 C8-03 学生，未改原学生。
- 旧 C8-02/C8-03/EVAL JSON 保持原样；正式全量回归后已恢复被测试复跑改写的旧 JSON。

---

## 2. 第一阶段：冻结 C8-03 基线

### 2.1 数据

- 4 个三值属性，81 个输入。
- 每个世界 2 个种子；每种子划分：
  - TRAIN 9
  - QUERY 29
  - CHALLENGE 16
  - HELDOUT 27
- 四组两两不重叠；学习前冻结；覆盖率检查（S 训练 ≥4 正/4 负，HELDOUT ≥6 正/6 负）。
- 基线学生只看到 TRAIN + QUERY；不看 CHALLENGE，也不看 HELDOUT。

### 2.2 世界

27 个世界，7 类：

| 类别 | 世界数 | 说明 |
|---|---:|---|
| EXPRESSIBLE | 6 | 冻结 depth=3 可解决 |
| DEPTH | 6 | 需要 depth=4 的 DNF/CNF 类表达 |
| IF_STRUCTURE | 4 | 条件/嵌套条件，扁平化更深 |
| AMBIGUOUS | 4 | 只有一个 HELDOUT 输入是反例 |
| MISSING_KNOWLEDGE | 3 | NO-R 库，需要恢复 R 或更深表达 |
| SEARCH_BUDGET | 2 | 候选上限 500，理论可表达但搜索不足 |
| NOISY | 2 | 训练中同一输入相反标签 |

### 2.3 基线结果

| 类别 | 运行 | 激活 | 识别 | 错误识别（HELDOUT 不足） | NO_CANDIDATE | SEARCH_LIMITED |
|---|---:|---:|---:|---:|---:|---:|
| EXPRESSIBLE | 12 | **12** | 12 | 0 | 0 | 0 |
| DEPTH | 12 | 0 | 9 | 9 | 3 | 0 |
| IF_STRUCTURE | 8 | 0 | 6 | 6 | 2 | 0 |
| AMBIGUOUS | 8 | 0 | 8 | 8 | 0 | 0 |
| MISSING_KNOWLEDGE | 6 | 0 | 6 | 6 | 0 | 0 |
| SEARCH_BUDGET | 4 | 0 | 0 | 0 | 0 | **4** |
| NOISY | 4 | 0 | 0 | 0 | **4** | 0 |
| **合计** | **54** | **12** | **41** | **29** | **9** | **4** |

- 基线只在可表达世界成功；所有结构/歧义/缺失/预算/噪声世界都未激活。
- 29 次“识别”是错误的：公开证据下唯一，但 HELDOUT 不通过；验证拒绝，没有错误激活。
- 4 次搜索预算不足返回 `SEARCH_LIMITED`；9 次无候选。
- 这为第二阶段提供了真实的失败分布，而不是人工构造的失败码。

JSON：`experiments/representation-evolution-e3/reports/mrx-01-baseline.json`

---

## 3. 第二阶段：失败诊断（已实施）

### 3.1 证据

学生只使用：

- 基础学习状态：`IDENTIFIED / NO_CANDIDATE / SEARCH_LIMITED / BUDGET_EXHAUSTED / NO_DISTINGUISHING_QUERY / ALL_REFUTED`
- `composeReason`：`depth_limit / candidate_limit`
- 生成数、训练一致数、初始/剩余行为类数、查询次数
- 训练集中是否存在同一输入相反标签
- 注册表中存在但 `StudyView.refs` 未包含的 active 能力
- CHALLENGE 反例（付费查询；最多 24 次预算，基础验证最多用 16 次）

不使用 HELDOUT、不使用目标函数、不使用 `preferredId`。

### 3.2 竞争解释

| ID | 猜想 | 支持证据 | 反对/缺失 | 诊断动作 |
|---|---|---|---|---|
| H1 | 目标不在当前表达空间/需要新结构 | `NO_CANDIDATE`、一致数 0、CHALLENGE 反例 | 若 DNF/IF 也无一致候选 | DNF/IF/CNF |
| H2 | 深度不足 | `depth_limit`、depth=3 结束仍无候选 | 若 depth+1 仍无候选 | DNF |
| H2b | 候选预算不足 | `candidate_limit` / `SEARCH_LIMITED` | 若扩大预算仍失败 | BUDGET |
| H3 | 已有知识不足/依赖不可用 | 注册表有未使用的 active 能力 | 若恢复知识无收益 | KNOWLEDGE |
| H4 | 训练证据不足，错误概括 | IDENTIFIED + CHALLENGE 反例 | 反例不说明需要新操作 | CHALLENGE |
| H5 | 查询域未覆盖区分输入 | QUERY 已唯一但 CHALLENGE 有反例 | CHALLENGE 也无法区分 | IF/DNF/更多查询 |
| H6 | 噪声/冲突/额外属性 | 同一输入相反标签 | 结构升级不能修复标签冲突 | NO_ACTION |

这些是学生根据证据打分和排序的竞争诊断；代码中没有“看到 W4 就加 If”的分支。W4 开发案例中实际同时出现 H1/H2/H4/H5，学生选择实测 DNF，而不是由教师指定。

### 3.3 升级动作

所有动作都在隔离分支中实际运行：

- `DNF`：2–3 个合取项的析取，深度扩展；
- `CNF`：析取子句的合取；
- `IF`：条件表达式，可含一层嵌套；
- `KNOWLEDGE`：把注册表中已 active 但未在 refs 中的能力加入库；
- `BUDGET`：把候选上限从 500/50k 扩大到 200k；
- `CHALLENGE`：继续查询 CHALLENGE；
- `NO_ACTION`：证据不足或冲突。

候选空间只从训练标签选择有区分度的字面量（最多 10 个），再生成 DNF/CNF/IF 候选，避免 B0 组合器在 depth=4 的完全组合爆炸。每个动作使用独立主查询预算；CHALLENGE 预算共享并计数。

---

## 4. 第三阶段：W4 与负例开发实验

JSON：`experiments/representation-evolution-e3/reports/mrx-01-development.json`

| 世界 | 种子 | 基础状态 | CHALLENGE 反例 | 诊断 | 选择 | 结果 | HELDOUT | 激活 |
|---|---|---:|---|---|---|---|---|---|
| W4 `if R then d1 else d0` | 1449 | IDENTIFIED | 1 | H1/H2/H4/H5 | **DNF** | REPAIRED | **27/27** | 是 |
| 歧义世界 `R OR d1` 单点例外 | 1857 | IDENTIFIED | 0 | 无 | **NONE** | IDENTIFIED | 26/27 | 否 |
| 缺失知识 `R OR d1`, NO-R | 2020 | IDENTIFIED | 1 | H1/H2/H4/H5/H3 | **KNOWLEDGE** | REPAIRED | **27/27** | 是 |
| 噪声 `R OR d1` 冲突重复 | 2227 | NO_CANDIDATE | 0 | **H6** | **NO_ACTION** | NO_ACTION | 0/27 | 否 |

要点：

- W4：冻结基线 24/27；学生通过 CHALLENGE 反例发现错误，实际运行 DNF 后 27/27。它**没有**选择 `If`，而是选择了可表达同一函数的 DNF；这符合“不要求一次猜中 If”。
- 歧义世界：CHALLENGE 16 个标签全部与候选一致；学生正确地不升级。HELDOUT 仍只有 26/27；这不是学生能在不可获得信息上推断的东西。
- 缺失知识：学生把已 active 的 R 重新纳入库，27/27。
- 噪声：检测到同输入相反标签，判为 H6，不尝试结构升级。
- 这三个负例覆盖了“不需要 If 的失败”，防止形成“失败就加 If”的伪策略。

---

## 5. 第四阶段：正式四条件考试

### 5.1 设置

7 个正式世界，28 次运行：

- `BASELINE`：冻结 C8-03，depth=3；
- `DEPTH`：冻结 C8-03，depth=4，候选上限 50k（只扩大原搜索预算）；
- `ORACLE-OPERATOR`：考官强制提供 world 的预期操作（DNF/IF/KNOWLEDGE/BUDGET），不计自主发现；
- `AUTONOMOUS`：MRX 学生自主诊断/选择/实验。

正式学生/协议已先冻结；运行前校验 SHA。

### 5.2 正式结果

| 世界 | 类别 | BASELINE | DEPTH | ORACLE | AUTONOMOUS |
|---|---|---|---|---|---|
| f-expr | 可表达 | **27/27 激活** | SEARCH_LIMITED | 27/27 | 27/27，NONE |
| f-depth | 深度 | 25/27 拒绝 | SEARCH_LIMITED | 24/27 拒绝，DNF | 25/27 拒绝，未升级 |
| f-if | 条件 | 24/27 拒绝 | SEARCH_LIMITED | 24/27 拒绝，IF | 24/27 拒绝，未升级 |
| f-ambig | 证据不足 | 26/27 拒绝 | SEARCH_LIMITED | 26/27 拒绝 | 26/27 拒绝，正确不升级 |
| f-miss | 缺失知识 | 23/27 拒绝 | SEARCH_LIMITED | **27/27 激活**，KNOWLEDGE | **27/27 激活**，KNOWLEDGE |
| f-search | 搜索预算 | SEARCH_LIMITED | SEARCH_LIMITED | **27/27 激活**，BUDGET | **27/27 激活**，BUDGET |
| f-noise | 噪声 | NO_CANDIDATE | SEARCH_LIMITED | NO_CANDIDATE | NO_ACTION，H6 |

正式汇总：

| 条件 | 运行 | 激活 | 正确操作 | 无必要升级 | 查询 | 生成候选 | 解释器调用 |
|---|---:|---:|---:|---:|---:|---:|---:|
| BASELINE | 7 | 1 | 3 | 0 | 13 | 73,844 | 145,491 |
| DEPTH | 7 | 0 | 0 | 0 | 0 | 350,000 | 0 |
| ORACLE-OPERATOR | 7 | 3 | 7 | 0 | 64 | 141,747 | 213,394 |
| AUTONOMOUS | 7 | 3 | 4 | 0 | 83 | 100,256 | 171,903 |

- 错误激活：**0 / 28**。
- 正式结构世界 f-depth / f-if：基础候选与全部 16 个 CHALLENGE 标签一致，`baseMismatch=0`，学生没有证据触发升级；AUTONOMOUS 维持原候选，HELDOUT 25/27、24/27，验证拒绝。
- ORACLE 强制 DNF/IF 也只得到 24/27：在 CHALLENGE 预算/覆盖不足时，升级候选选择仍会选到错误概括。这不是“操作没给对”，而是“候选判别证据不足”。
- DEPTH 7/7 `SEARCH_LIMITED`：把 B0 组合器 depth 从 3 加到 4，候选空间先爆炸，candidate_limit 在找到目标前触发。**单纯扩大原搜索预算不够**；需要聚焦的候选空间管理。
- f-miss / f-search 的 AUTONOMOUS 与 ORACLE 完全一致：恢复知识和扩大预算是正确且可执行的升级。
- f-ambig / f-noise 的 AUTONOMOUS 没有进行无必要升级；`unnecessaryUpgrade=0`。

### 5.3 指标

| 指标 | 值 | 说明 |
|---|---:|---|
| 表达结构不足识别率 | 0/2 | f-depth/f-if 未被识别（无 CHALLENGE 反例） |
| 搜索预算不足识别率 | 1/1 | f-search 正确识别并修复 |
| 缺失知识识别率 | 1/1 | f-miss 正确识别并修复 |
| 错误归因率 | 2/7 | 结构世界归为“无证据”，未误加 If；但未修复 |
| 正确操作选择率 | 4/7 | 含 NONE/NO_ACTION 正确选择 |
| 无必要升级率 | 0/7 | 歧义/噪声/可表达均未升级 |
| 升级后新任务成功率 | 3/3 已尝试 | 只统计实际选择升级的世界；结构世界未触发 |
| 错误激活率 | 0/28 | 无 heldout 不足的激活 |
| 查询成本 | 83 | AUTONOMOUS 总计；基础验证 16 次/世界是主要成本 |
| 生成/执行成本 | 100,256 / 171,903 | 候选/解释器调用 |
| 结构升级前后成本 | 表格见下 | 学习成本未归一化 |

结构世界（f-depth/f-if）在开发 W4 上成功、在正式种子上失败，差异不在学生代码，而在 **CHALLENGE 池是否覆盖区分输入**：

- W4 开发种子 1449：CHALLENGE 含 `R=true,d=2` 的反例，基础候选被挑战推翻，触发 DNF 修复，27/27。
- 正式种子 7238/7378：CHALLENGE 16 点没有覆盖该区分区域，基础候选与全部挑战标签一致；学生没有可用反例，不能合法推断结构不足。

这正是任务书强调的边界：**如果所有合法 QUERY/CHALLENGE 输入上候选行为一致，而反例只在 HELDOUT，学生不能假装从不可获得的信息中推断答案。**

---

## 6. 成本与复现

### 6.1 基线

- 54 次运行：12 激活、29 验证拒绝、9 无候选、4 搜索受限。
- 所有失败均保留，未删除。

### 6.2 开发

- W4：生成 42,601 候选，15 次查询（含 CHALLENGE），27/27。
- 歧义：17 次查询（含 16 次挑战验证），26/27，无升级。
- 缺失知识：3 次查询，20,520 候选，27/27。
- 噪声：0 查询，H6。

### 6.3 正式

- 28 次运行，总生成 665,000+ 候选（含 DEPTH 的 350,000），错误激活 0。
- DEPTH 条件本身消耗 350,000 候选但没有激活，说明“只加深度”不是有效策略。
- AUTONOMOUS 的查询成本高于 BASELINE，主要来自每个世界的基础 CHALLENGE 验证（最多 16 次）。

### 6.4 复现

```powershell
# 冻结基线
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRXBaselineTest"

# 开发 W4/负例
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRXDevelopmentTest"

# 正式（先校验冻结 SHA）
.\gradlew.bat test --offline --tests "logic.e3.pack02.MRXFormalTest"

# 全量
.\gradlew.bat test --offline
```

全量：**343/343，0 failed**（B0 173 + E3 170）。

---

## 7. 成果等级判定

| 等级 | 判定 | 证据 |
|---|---|---|
| LEVEL 1 失败原因识别 | **部分** | 正确识别 H3 缺失知识、H2b 搜索预算、H6 噪声；结构 H1/H2 只在 CHALLENGE 有反例时识别，正式结构世界 0/2 |
| LEVEL 2 结构升级提议 | **部分** | 实际提出并运行 DNF/IF/CNF、KNOWLEDGE、BUDGET；W4 选择 DNF；正式结构世界未触发 |
| LEVEL 3 自主实验选择 | **部分** | W4 排除 If 选择 DNF；f-miss/f-search 选择正确；结构世界无证据，未选择 |
| LEVEL 4 自主修复学习 | **未达成** | 开发 W4 27/27；正式结构 0/2；正式总 3/7 |
| LEVEL 5 可迁移策略 | **未达成** | 只在少量世界/种子；结构识别依赖挑战覆盖，无法证明可重复优势 |

**总体：机制已实现，LEVEL 1–3 部分，LEVEL 4/5 未达成。** 不把 W4 开发成功当成通用结构发现能力。

---

## 8. 已实施 / 失败 / 未实施 / 证据不足

### 8.1 已实施且有效

- 27 世界 × 2 种子的冻结基线；6 类失败分布。
- H1–H6 竞争诊断，带支持/反对/缺失/动作。
- DNF/CNF/IF 聚焦候选空间；KNOWLEDGE/BUDGET/NO_ACTION 动作。
- 独立 CHALLENGE 反馈池；付费查询；不作为最终 HELDOUT。
- W4 开发修复 27/27；歧义/噪声正确不升级；缺失知识恢复 27/27。
- 四条件正式对照；错误激活 0。

### 8.2 已实施但失败

- 正式 f-depth/f-if：基础候选与 CHALLENGE 一致，未触发升级；HELDOUT 25/27、24/27。
- 正式 ORACLE 在 f-depth/f-if 也失败（24/27）：候选选择仍受挑战覆盖限制。
- DEPTH 条件 7/7 `SEARCH_LIMITED`：扩大原 depth 预算导致候选爆炸。
- 结构世界正式识别率 0/2；升级后结构任务成功率 0/2。

### 8.3 未实施

- `Let` 操作（IR 支持，但本轮未列入实际升级动作；目录中只实现 DNF/CNF/IF）。
- T 层连续学习在 MRX 正式考试中的复测；R/S/T 长期可用性只验证了 S 激活，未重跑 T。
- 真正的独立闭卷（`independent_closed_book=not_run`）。
- 多种子/重复运行的统计置信区间；正式每个世界只用一个种子。
- 更深嵌套 If 与 Let 专属世界。
- 生产级权限/基础设施。

### 8.4 证据不足

- 自主结构发现的普遍性：正式结构 0/2，开发 W4 1/1；不能声称可迁移。
- 主动查询/挑战查询的成本优势：AUTONOMOUS 比 BASELINE 查询更多，主要用于验证；没有证明净收益。
- 结构升级相对 ORACLE 的增益：AUTONOMOUS 与 ORACLE 在正式都是 3/7；差异只在操作选择来源。
- 错误归因率的稳定性：2/7 受世界构成影响。

---

## 9. 研究负责人审查要点

- 本轮没有把“提供 If”当成 LEVEL 4；W4 开发修复是学生实际运行 DNF 得到的。
- 没有根据正式 HELDOUT 结果回改学生；f-depth/f-if 失败保留。
- 没有伪造 CHALLENGE 覆盖：正式结构世界确实没有可用反例，学生正确地没有凭空推断。
- 没有声称 LEVEL 4/5；没有声称独立闭卷。
- 原 PACK-02R 数据、旧 JSON、旧授权记录保持原样。
- C4/C8 仍 `PAUSED`；PACK-03 未启动。

**最终回答：**

> 一个已经能主动学习与复用的学生，在**有可获得反例**时能够意识到表达不足、提出并实际测试结构升级，从而学到原本无法学习的知识（W4 27/27）。但在正式设置中，当结构缺口只能由不可见的 HELDOUT 反例暴露时，学生无法合法推断，结构世界 0/2 修复；这说明当前系统仍未达到开放演化学习所需的“自主设计可区分实验”的能力。机制前进了一步，但 LEVEL 4/5 未达成。

任务完成后停止，等待外部研究监督审查。
