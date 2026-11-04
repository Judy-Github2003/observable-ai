# Observable AI / 可观测学习者：学习基底 B0 实施任务书

> 任务性质：**执行型研发任务**，可直接交给 DeepSeek Harness（DSH）在仓库内开发。
>
> 任务名称：**B0 — Typed Capability Kernel & Library Learning Foundation（类型化能力内核与程序库学习基底）**。
>
> 制定日期：2026-11-04。
>
> 目标仓库：https://github.com/Judy-Github2003/observable-ai
>
> 制定任务时核对的 `main`：`37e2dd7adba0b0d621711fe792b749f96d440cff`。**开始执行时请重新记录实际 HEAD；若已有新提交，以实际代码为准，并在报告列出差异，不要强行回退。**
>
> 研究者决策：**暂停 V3 自修复 Agent、暂停小学课程批量生成，先建设能够支撑后续长期训练的统一学习基底。**
>
> 执行者：DeepSeek Harness（实现、单测和公开开发验证）；外部审查者：ChatGPT/Codex 或其他独立运行者（冻结后的黑盒检查）；项目研究者负责路线与接受决策。

---

## 0. 给 DSH 的第一条指令

现在**不要**启动 V3 的教师循环、不要直接扩展 `OR/NOT/阈值` 的学习课程、不要增加小学 72 单元、不要一次性生产数万道训练题。先完成一个可测试的、可以长期承载知识与技能增长的 **B0 独立基础模块**。

当前仓库已经展示：

1. V1/Kotlin 能够执行规则、保存知识和审计；但其中大量操作是教师预先编写。
2. V2 的 `logic.discovery` 能在限定的 `单条件 / 双条件 AND` 假设空间中枚举、主动询问、淘汰并晋升一条规则；已有独立 CLI、证据、状态、审计。
3. V2 的开发实验也揭示：目标超出假设空间时，可能**错误晋升**与证据相容但实际错误的规则；“一个候选”不等于“真实规律”。
4. 现有规则/知识/方法没有共同的**类型化能力表示、依赖图、版本治理、可组合执行与程序压缩**设施。

**本轮唯一核心问题：**

> 一组通过显式 IR 表示、可验证、可审计的基础能力，能否在不请求教师逐题写代码的条件下，被类型安全地组合、抽象和压缩为可重复使用的高阶能力；并在新任务上减少解题描述长度、搜索成本或教师需求，而不降低正确性？

B0 的完成**不等于**自主策略学习、训练模型权重、通用 Agent、通用理解或独立发明算法。必须区分「开发者预置的解释器/组合算法」「教师交付的程序候选」「学生通过组合或证据筛选形成的具体能力」。

**工作方式：**逐个里程碑写代码、运行真实测试、记录失败；禁止只交设计文档。若实现中遇到必须取舍的架构问题，在 `DECISIONS.md` 记明确的选择、替代方案、测试与影响，然后继续采用最小可行方案。不要为了达成漂亮分数放宽判分。

---

## 1. 目标与非目标

### 1.1 本轮必须实现的能力

- **同一个能力对象**可描述事实查询规则、布尔判断、纯函数程序片段、由其他能力组合而来的程序；基础模型预留未来的外部工具/学习策略种类，但**本轮不执行具有副作用的 Agent 操作**。
- 有稳定的、**强类型、纯函数、确定性**程序中间表示（IR）；本轮运行时不得直接执行从教师处来的任意 Kotlin 字符串。
- 能力有输入/输出契约、有效性范围、不可变版本、依赖、可追溯来源、验证状态、证据、测试和执行记录。
- 能力间可以**类型检查后组合**；调用依赖时锁定特定能力版本，防止下游升级造成静默变化。
- 可自动从多个已经验证的程序中发现**重复子结构**，提出共享能力候选；通过独立验证后才激活；衡量压缩对未来任务的净收益。
- 能实现离线、可复现的小规模实验，且能与不做复用/不做压缩的基线比较。
- 有统一的能力获取成本和来源口径；为后续教师调用和学习策略试验预留 API，而非立即调真实教师。

### 1.2 明确禁止

- 不把 DeepSeek 生成的代码自动热加载成具备文件/网络/进程权限的 Kotlin 插件。
- 不允许学习器修改测试器、真值 oracle、验证策略、训练/测试切分或验收阈值。
- 不让教师直接把隐藏测试答案、`hidden_rule`、`expected_label` 塞给学习器。
- 不把 `teacher_programmed` 重新标记为 `student_induced`；不把压缩重写算成创造了新语义。
- 不要求从头实现完整 DreamCoder、e-graph 引擎、SMT 求解器、神经网络或复杂策略搜索。
- 不为追求压缩率丢弃原始程序、旧证据和失败轨迹。
- 不因增加工具数量而宣称学习能力提高：**必须有冻结后的新任务效果证据**。

### 1.3 兼容性边界

- 尽可能只在 `src/main/kotlin/logic/capability/` 新增独立模块，以及 `src/test/kotlin/logic/capability/` 新增测试。
- V1 的 `AnswerMemory/BatchStudent`、V2 的 `logic.discovery` 首轮都保留现有行为。**可以新增只读/显式调用的适配器，禁止改变原有判分规则来凑成绩。**
- 兼容 JDK 21、仓库的 Kotlin JVM 及现有 JSON/SQLite 依赖；非必要不引入第三方大依赖。
- B0 使用独立状态目录与独立 CLI；旧命令/测试应继续通过。

---

## 2. 核心架构：统一能力系统

建议分成五层，层间职责明确。

```text
                         题目 / 世界 / 已验证观察
                                  |
                                  v
                     [学习与任务规划接口 - B0 只留接口]
                                  |
                 +----------------+----------------+
                 |                                 |
                 v                                 v
           [Capability Registry]              [Composer]
         定义/版本/证据/依赖图                 类型检查/候选组合
                 |                                 |
                 +--------------+------------------+
                                |
                                v
                      [Typed IR Interpreter]
                   纯函数、三值语义、资源限制
                                |
                                v
                     [Verifier & Comparator]
                 外部真值/等价检查/新任务测试
                                |
                                v
                         [Library Learner]
                  重复发现→抽象→重写→A/B验收
                                |
                                v
                    更新版本化的能力库及审计

教师模型 → （将来通过 TeacherGateway 输出候选能力/解释/样本）
          本轮只实现接口 + mock；不能绕过验证器。
```

应明确三个边界：

1. **解释执行层**拥有实现语义的底层指令，但底层能力由设计者预置，不计作学生学到。
2. **能力层**拥有可复用、可版本化的程序和契约；学生可产生候选、验证、使用并组合。
3. **学习与审计层**记录是谁提出候选、来自哪些输入与反馈、经过哪些独立测试。未来可替换为自主 Agent，但绝不能成为黑盒答案灌输通道。

---

## 3. 类型化 IR：语法、类型和语义

### 3.1 类型系统最低集合

采用显式、可序列化的类型，而非在执行阶段随意隐式转换：

```text
Type = Bool3 | String | Int64 | Record<field_schema> | List<T>（仅预留）
     | Optional<T>（若实现）

Eval = Value<T> | Unknown(reason,evidence) | Error(code,details)
```

`Bool3 = true / false / unknown`，**未知不是 false，也不是抛异常**；`Error` 为格式/类型/资源/实现错误，**不能伪装成 unknown**。缺字段、错误类型、未注册能力、循环依赖需要区分。

本轮真正支持 `Bool3、String、Int64、Record` 即可；`List` 在契约中预留但不得半实现后声称通过。`Record` 按声明的字段 schema 访问，值域可以是 String 或 Int64。`Unknown` 传播契约需有统一测试；必要时根据表达式语义采用 Kleene 三值逻辑（例如 `false AND unknown = false`、`true OR unknown = true`），而不是简单将 unknown 全部传染。

### 3.2 最小 AST 指令集

建议最少支持：

| Node | 语义 | 要求 |
|---|---|---|
| `arg` | 读取形参 | 明确类型与参数名 |
| `const` | 声明类型的字面值 | String/Int64/Bool3 |
| `field` | 从 Record 读取字段 | 缺字段返回 `Unknown(missing_attribute)` |
| `eq` | 类型相同值的比较 | 输出 Bool3，未知传播 |
| `and` / `or` / `not` | 三值逻辑 | 见能力暴露规则 |
| `if` | 条件分支 | 条件 unknown 不得任意选择分支 |
| `let` / `ref` | 共享局部值 | 禁止动态脚本、变量捕获混乱 |
| `call` | 调用版本锁定的能力 | 校验参数、返回类型、依赖和递归 |

**特别注意“内核存在的指令”与“学生已学会的能力”不是一回事。** 即使运行时解释器实现了 `or`，也不代表学生已自主学会 OR。能力库必须有可见能力清单和 `available_to_student`/`exposure_policy` 标志；搜索器只能使用训练前已授权的能力。后续 OR 学习实验应对“教师提供 OR 能力”和“学生通过现有能力组合推导 OR”严格区分归因。

可以在实现内部用 sealed class，也可以以 JSON AST 为主并在反序列化后做类型化，但**不允许将未经类型检查的 JSON 直接执行**。

### 3.3 类型检查、规范化与确定性

- 类型检查分两步：先验证节点与字段，再验证 `call` 的版本化能力签名和依赖图；失败不得写入 active。
- AST 规范化须稳定、确定性：字段排序、显式类型、版本锁定、无关空白不影响 hash；但**不能把顺序敏感的操作错误排序**。
- `and` / `or` 的交换律只在明确的纯语义范围使用；如果 `Unknown`、错误或短路行为使两个程序可观察行为不等价，不能凭直觉归一化。
- `content_hash = SHA-256(canonical_AST + signature + referenced_version_hashes + semantics_version)`；同内容去重，修改任何依赖版本则产生新 hash。
- 运行时限制执行步数、AST 深度、调用深度、内存/输出大小；遇到循环或预算耗尽返回明确 `Error(resource_limit/cyclic_dependency)`，不可无限挂起。
- 禁止 AST 原语直接访问：文件、网络、反射、类加载、进程、系统环境、系统时钟、随机数和教师接口。
- 确保同一输入、能力版本和运行时语义版本下，输出稳定；所有测试记录对应的能力版本与解释器版本。

### 3.4 一个展示“组合而非记忆答案”的最小例子

假设已有 `field_record`、`equal_string` 和 `and_bool3` 三项**设计者提供的原语能力**，组合器可形成新程序：

```json
{
  "op": "and",
  "args": [
    {"op":"eq", "args":[
      {"op":"field", "record":{"op":"arg","name":"item"}, "name":"color"},
      {"op":"const", "type":"String", "value":"red"}
    ]},
    {"op":"eq", "args":[
      {"op":"field", "record":{"op":"arg","name":"item"}, "name":"shape"},
      {"op":"const", "type":"String", "value":"circle"}
    ]}
  ]
}
```

**上述为建议的协议示意，不要求逐字照抄；DSH 必须给出真正可运行的最终 JSON。** 这个组合可以归因为 `student_composed`（只有组合算法真正独立选取 AST 时）；如果由 DSH 开发者硬编码该 AST，则仅可记作 `designer_seeded`。后续测试必须对新颜色、字段、对象、规则顺序换世界。

---

## 4. 统一能力对象：CapabilityRecord v1

**能力不是“只有一段代码”。** 它同时具有接口、实现、适用范围、验证证据和可信度边界。

建议存储结构（实际字段名可微调；必须文档化并保证迁移）：

```json
{
  "capability_id": "classify.red_and_round",
  "version": 1,
  "kind": "pure_program",
  "signature": {
    "inputs": [{"name":"item", "type":"Record:ProductV1"}],
    "output": "Bool3"
  },
  "implementation": {"format":"typed_ir_v1", "body_ref":"sha256:<AST_HASH>"},
  "semantics_version": "ir-v1",
  "scope": {"schema":"ProductV1", "assumptions":["color/shape 字段来自声明值域"]},
  "dependencies": [
    {"id":"core.equal_string", "version":1, "hash":"<PINNED_HASH>"},
    {"id":"core.and_bool3", "version":1, "hash":"<PINNED_HASH>"}
  ],
  "provenance": {
    "acquisition_method":"student_composed",
    "proposer":"local_composer",
    "teacher_calls":0,
    "source_task_ids":["task-001","task-002"],
    "source_evidence_ids":["e-001","e-002"]
  },
  "validation": {
    "status":"verified_bounded",
    "domain_or_test_manifest":"sha256:<MANIFEST_HASH>",
    "verifier_id":"bounded_equivalence_v1",
    "limitations":["仅在声明范围内验证"]
  },
  "lifecycle":"verified",
  "content_hash":"sha256:<CANONICAL_HASH>"
}
```

**能力种类建议**：`fact_query`、`predicate`、`pure_program`、`procedure`、`learning_strategy`、`external_tool`。本轮只需真正运行 `predicate/pure_program`；其余可以有元数据种类但应显式标为 `unsupported_execution`，不能假装已经支持行动模型。

**能力来源枚举至少包含**：`designer_primitive`、`designer_seeded`、`teacher_proposed`、`teacher_programmed`、`student_induced`、`student_composed`、`student_abstracted`、`manual_import`。来源是不可随意更改的事实；后续纠错用追加事件记录，不覆盖旧记录。

**能力生命周期**：

```text
proposed → type_checked → provisional → verified_bounded → active
   |             |                |              |
   +------ rejected/invalid ------+              +→ deprecated / retracted
```

- `verified_bounded` 不等于数学上全域证明；应带 `domain_or_test_manifest` 与确切的 `validation_strength`。
- `active` 只意味着在特定范围可被默认检索/执行，不等于无条件真理。
- 提供证据撤销与能力降级接口：若能力依赖的证据或上游能力被撤销，应**传播 stale/needs_revalidation**，不能保持无条件 active。
- 如果产生新的能力版本，旧版本不可原地修改；已有下游依赖仍指向旧版本，直到重新验证升级。

---

## 5. 能力注册表与持久化

### 5.1 选择

优先采用 **SQLite 事务性注册表 + 可导出的 JSONL 审计事件**；仓库已有 SQLite JDBC，适于多张表和依赖关联。独立数据库路径 `<state-dir>/capabilities.db`，不要直接改写 V1 的问答 SQLite。

建议最小关系结构：

```text
capabilities(capability_id, version, kind, status, input_schema_json,
             output_schema_json, body_hash, semantics_version, provenance_json,
             validation_json, created_at, PRIMARY KEY(capability_id,version))

bodies(body_hash PRIMARY KEY, canonical_ir_json, byte_size)
dependencies(from_id,from_version,to_id,to_version,to_hash)
evidence(evidence_id PRIMARY KEY, source_type, payload_hash, metadata_json, state)
capability_evidence(capability_id,version,evidence_id,role)
validations(validation_id PRIMARY KEY,capability_id,version,manifest_hash,
            verdict,scope_json,verifier_version,created_at)
events(event_id PRIMARY KEY,sequence,kind,capability_id,version,trace_id,
       payload_json,timestamp,prev_event_hash,event_hash)
library_snapshots(snapshot_hash PRIMARY KEY, manifest_json,created_at)
```

实际数据库建模可调整，但必须覆盖上述**不可变版本、依赖、证据、审计和库快照**语义；必须提供 schema 版本及向前迁移/拒绝未知版本的明确行为。

对 SQLite：打开 `foreign_keys`，合理使用事务、WAL，单进程并发安全优先；重要状态迁移与事件应位于同一数据库事务。JSONL 是导出物，**不是与 DB 各自独立写入、可能发生一半成功一半失败的两个真相源**。

### 5.2 必须支持的接口

```text
registry.propose      写候选（不可 active）
registry.inspect      查看签名、AST、来源、状态、依赖和验证
registry.type_check   类型与依赖检查
registry.verify       执行由外部固定的验证清单
registry.activate     只有满足门槛才激活
registry.execute      离线执行指定能力@版本
registry.deprecate    保留历史并使默认使用失效
registry.snapshot     固定能力图与 body hash
registry.export_audit 导出完整、可复查的事件轨迹
```

禁止 `activate` 让调用方只传 `{"passed":true}` 就通过；验证器必须在本地运行经过哈希锁定的独立测试集合或有独立认证的验证证据，并记录确切输入/输出、校验范围与版本。

**只读/权限边界**：候选提议者和教师不能调用 `activate` 的无条件绕过路径。CLI 可以简化身份模型，但至少要有分离的 `propose` 与 `verify/activate` 流程和机器可检查的门禁。

---

## 6. 能力组合（Composer）

### 6.1 本轮应当怎么做

第一阶段做**有界、确定性的程序组合器**，不要用一次模型调用代替组合算法。

输入：

- 目标类型签名，例如 `Record<ProductV1> -> Bool3`；
- 已启用的能力清单（按版本锁定）；
- 可用常量/字段（只由公开 schema 提供）；
- 搜索深度、候选上限、运行步数、任务给出的公开正反例；
- **不可见**的 hidden oracle 和测试答案。

输出：

- 一组满足类型约束的候选 Typed IR；
- 每个候选的结构 hash、使用的能力版本、推导来源、复杂度与公开证据成绩；
- 若候选存在歧义，返回 `ambiguous` 和候选，不按最简单自动冒充正确；
- 未能搜索到候选时区分 `search_budget_exhausted`、`hypothesis_space_exhausted`、`not_typeable` 等原因。

最低实现：按 AST 节点数量或深度的**广度优先/成本优先枚举**，缓存 canonical hash、过滤类型错误、压缩等价重复（只做已证明安全的语义归一化）；配置固定上限，输入相同则输出次序稳定。**不要求解决一般程序合成难题。**

### 6.2 组合器真正要证明什么

至少证明：相同的一组原语，不新增手写规则代码，也能在多个世界上组合出具体目标能力；记录哪些是算法自动产生、哪些是开发者示例植入。

测试任务可由外部测试器提供一组公开输入输出，然后学生搜索候选。若只有单个候选能解释训练样本，它仍只是**在当前假设空间可辨识**，必须经过留出测试或限定域验证才可晋升。

### 6.3 依赖和调用语义

- `call` 必须指向 `capability_id@version@hash`，禁止默认解析“最新版本”参与历史复算。
- 构建时检测循环依赖；无尾递归/任意递归支持，本轮直接拒绝。
- 组合程序显式声明使用的旧能力；若其中一个被撤销，需要将下游标为 `needs_revalidation`。
- 执行记录包含 `input_hash, output_hash, capability_version, dependency_hashes, trace_id, steps, elapsed_ms`；不得无端将外部 oracle 判分记为学生自验。

---

## 7. 程序压缩与抽象（Library Learner）

### 7.1 不要把压缩理解成“代码变短”

本轮的目标是：在同样的验证强度下，用共享的可执行子程序**减少整个任务集合的表示成本**，并评估是否改善未来问题的处理成本。

推荐一个明确可计算的代理目标：

```text
L_total = L(library_definitions)
        + Σ L(task_programs | library)
        + λ * L(interface_overhead)
```

其中 `L` 使用**同一版本、固定权重**的规范化 AST 编码成本（例如节点权重、调用成本、参数成本、常量成本、库定义成本）。不要用没有口径的“压缩了 90%”取代。若使用 JSON 字节数作为简单基线，也应说明它依赖编码格式、只能作为工程指标。

注意：共享能力自身也要计入库定义成本；不能只数调用处的长度，而把新增函数定义成本忽略。频繁被复用的能力才更可能带来净收益。

### 7.2 三层压缩路线（分步，不得虚报全做了）

**L1 — 完全相同结构去重（必须）**：对 AST canonical hash 去重；已有语义同一的能力定义只保存一次 body，多处引用不重复落盘。验证“节省存储”与“减少学习样本”是两个不同指标。

**L2 — 公共子树抽取（必须）**：跨至少两个独立任务发现可复用的共享子树，生成 `extract_helper` 候选，并对调用方做重写。候选应满足类型安全、无变量捕获、没有副作用；先用简单的 AST subtree hash 和明确的提取阈值，不必实现 e-graph。

**L3 — 参数化抽象/反统一（可选，必须独立列成绩）**：对形状相同、部分常量/字段不同的 AST 提出参数化 helper，如 `hasFieldValue(item, field, value)`；不能把一个常量世界过拟合成永远为真。要生成可验证的参数签名、调用点实参映射、全调用点等价检查，并计入参数传递成本。

`L1/L2` 必须实际实现，`L3` 在 `L2` 正确通过后才尝试。若时间/依赖限制导致 L3 无法完成，报告为 `not_implemented`，不能为了数量假装抽象。

### 7.3 接受/拒绝一个压缩候选的规则

压缩候选必须按顺序经过：

1. **静态合法**：能类型检查；不存在循环依赖、捕获变量、未锁版本或越权操作。
2. **行为等价**：对声明的有限域（若足够小可穷举）执行原程序与重写程序；其他情况做受控性质测试和定向边界用例，并明确 `bounded_verified`，不可假装全域证明。
3. **全量回归**：以前已激活能力在其原验证范围内输出不发生变化（包括 `unknown` 和 `error`）；失败即拒绝并保留差异样本。
4. **成本改善**：以固定 `L_total` 核算，新库加上重写任务的总成本必须有净减少；没有改善时保留为 rejected candidate，不激活。
5. **留出任务非劣**：冻结的测试集合不因压缩而出现更多错误晋升/执行错误；如果“搜索速度更快”是宣称的收益，必须做同预算对照。
6. **事务化发布**：新 helper 与重写后能力作为**新版本**一次性写入并激活；原版本保留。可回滚到旧 snapshot。

**验证不能只比训练样本输出；否则可能把两个只在训练集碰巧相同的程序错误合并。** 允许相同 body/hash 去重，不允许仅凭观测结果相同就断言语义等价。

### 7.4 首个强制压缩演示

给出三项已验证任务（示意，字段与对象 ID 需另外换世界）：

```text
P1(item) = isRed(item) AND isRound(item)
P2(item) = isRed(item) AND isSmall(item)
P3(item) = isRed(item) AND isMetal(item)
```

让压缩器从原始 Typed IR 中识别重复的 `isRed(item)` 子程序，提出共享 helper，然后重写 P1/P2/P3，验证**每个程序在声明域内完全等价**，比较压缩前后全库成本。注意：三处复用未必足以覆盖引入 helper 的成本，是否激活以真实成本计算为准，而不是预先设定“必须成功压缩”。

再追加新任务 P4，检验已存在 helper 是否能够被复用；任务不能使用与开发示例完全相同的对象 ID。再加入“随机无重复结构”的**负对照集**，观察压缩器是否会为了产生成绩滥造 helper。

### 7.5 压缩不能修改知识结论

即使某个规则来自 `student_induced`，抽象出来的 helper 也只是 `student_abstracted_from_verified_programs`；如有教师介入，保留额外来源。**不能将“重写同义程序”计为学习了新的事实或发现了新的世界规则。**

---

## 8. 未来教师蒸馏接口：本轮只定义契约，不执行真实 Agent

预留：

```text
TeacherGateway.propose_capability(task_summary, missing_signature, constraints)
TeacherGateway.propose_counterexample(candidate, declared_scope)
TeacherGateway.explain_failure(failure_bundle)
TeacherGateway.propose_abstraction(program_bundle)
```

返回统一的**不可信候选**：`proposal_id`、`proposal_kind`（rule/AST/algorithm/test_suggestion）、`payload`、`proposer`、`token_cost`、`context_hash`、`assumptions`。它们**不能直接进入 active**，必须经过和本地候选相同的类型检查、独立验证与成本审计。

本轮只需 `MockTeacherGateway`，用于证明协议、成本与来源计量能工作。不应把真实 DSH 环境变量、 API key、CLI stderr 写入事件或测试输出。

未来教师调用分层可参考：

- 先尝试已有能力（不需要教师）；
- 再尝试本地组合/抽象/反例搜索；
- 仍失败时按信息价值请求提示、反例或方法；
- 实在必要才请求教师生成新的安全 AST/Kotlin 候选；
- 任何教师建议均可被外部验证器否决。

**切记**：本轮为 B0“打底”，不是让 DSH 现在实现完整 TeacherEscalationAgent 或自修改主程序。

---

## 9. 测试与实验：让训练效率成为可测量量

### 9.1 四个基线组（不可缺少）

使用同一批任务、同样输入信息与预算：

| 组别 | 能力机制 | 目的 |
|---|---|---|
| A / Independent | 每题单独 Typed IR，无共享库 | 看纯粹逐题解法的成本 |
| B / Reuse-only | 允许使用已注册能力，不自动抽象 | 测量显式复用收益 |
| C / Dedup+Extract | B + 完全结构去重 + 公共子树抽取 | 测量 L1/L2 程序压缩增量 |
| D / Abstract | C + 参数化抽象（仅当 L3 实现） | 测量高级抽象实际贡献 |

所有组必须使用**相同的底层解释器**、相同已知样本、相同课程顺序、相同 CPU/内存预算和相同外部测试器；区别只在开放给学生的库学习能力。不可让 C/D 额外读取隐藏测试答案。

### 9.2 样本切分

至少构造下列小型、完全可复现的实验集：

1. **结构重复集**：多个不同任务有真正可复用的共同子程序，预期 L2 有机会获得正收益。
2. **结构相似但语义不同集**：字段或值存在细微差别，验证压缩不会误合并。
3. **无公共结构负对照集**：随机合法 Typed IR；预期不应强行激活没有净收益的 helper。
4. **跨世界迁移集**：新字段名/值/ID、不同顺序/正负比例，至少三类符号化任务场景（例如物品属性、访问资格、设备兼容性）。三类都仍属于属性谓词任务；**不宣称跨语言/视觉或广泛跨域泛化**。
5. **未知/错误/资源边界集**：缺字段、无效类型、依赖循环、版本替换、预算超限、反例推翻已验证前提。

每组明确：`train`、`development`、`hidden_test`（独立审查者未来生成），按**程序模板/结构族**而非仅按随机对象 ID 切分。简单换 ID 不算完全独立的泛化考试。学生训练不能查看 hidden_test 文件。

### 9.3 必须报告的指标

**能力有效性**：

- `verified_capabilities`、`active_capabilities`、`invalid_promotions`、`rollback_count`；
- 按任务的 `correct / wrong / unknown / error`，不能合并 error 与正确拒答；
- 能力组合成功率、类型检查失败率、依赖版本不匹配率；
- 压缩前后**完整声明域的等价检查**通过率（有限域）；超出有限域必须列验证范围；
- 新世界覆盖率与任务正确率。

**学习效率**：

- `L_library`、`ΣL_task`、`L_total` 的前后数值、相对变化和压缩前后能力数量；
- 平均每任务可执行 AST 节点数/解释步数/搜索枚举候选数/耗时/峰值内存；
- `teacher_calls`、`teacher_input_tokens`、`teacher_output_tokens`、外部调用时长；B0 的离线实验这些值应全为 **0**，不能凭空写成节约了多少 token；
- 新任务利用已学能力的比例、复用次数、没有复用能力时的反事实对照；
- 每个阶段/任务难度分桶的正确率与成本，不只报一个总体平均。

**不得滥用指标**：

- `compression_ratio` 与 `learning_efficiency` 不是同一件事；压缩更小并不自动意味着学生学得更快。
- 共享 helper 可能增加动态调用开销；所以同时测静态 L_total 和真实运行耗时。
- 因为 B0 只做 mock 教师，即使 `teacher_calls=0`，也不能断言“已摆脱 DeepSeek”。

### 9.4 最低通过标准（作为工程门槛，不预设研究结论）

- 旧项目测试（按实际 HEAD 基线）全部通过；B0 自有确定性与异常边界测试全部通过。
- 类型错误、循环依赖、越权 AST、测试未通过、证据状态过期，均**0 次非法激活**。
- 所有已激活 B0 能力在冻结的有限验证域内，和原程序**100% 等价**（包含 unknown/Error 行为）；例外须明确列为失败，不能平均掩盖。
- 在刻意设计的真实重复结构训练集上，**至少存在一个**通过严格净收益核算和全域验证的可复用抽象（否则应停下分析原因，而非调低标准偷过）。
- 在负对照集，**不得激活任何 L_total 净增或行为不等价的抽象**。
- 断网/关闭 DSH 后，已安装的纯能力依然可执行；`teacher_calls=0` 有机器可验证的计数。
- 数据库从旧版本启动、重启、导出/导入 snapshot 后，能力 AST、版本 pin、执行输出、来源和事件顺序一致。
- 压缩的未来任务收益不设“必然 >0”门槛，必须如实比较 A/B/C/D。若效果不显著，研究结论就是目前的抽象无明显迁移收益。

---

## 10. 独立审查、闭卷与防信息泄漏

**公开开发验证**由 DSH 自行执行并报告；**独立闭卷**由外部审查者在固定 commit 上换新样本/新模板/新种子执行。开发者不能提前将隐藏样本写入库或修正评测器。

请在代码冻结后交付：

1. Git SHA + 所有源码、gradle lock（若存在）、语义版本、库 snapshot、训练数据与生成器 hash；
2. 可从全新目录一条命令启动的离线测试；
3. 未在提交中泄漏的独立测试输入约定（审查者可用自编脚本生成）；
4. 每个 run 的 `manifest.json`、 `report.json`、原始 `events.jsonl`、失败案例；
5. 能力的 `provenance`、teacher/学生/开发者归属清单；
6. 若能力来自压缩，提供原程序、重写程序、依赖快照、验证输入集和 L_total 明细。

对于噪声、超纲目标与训练前不存在的新规则：本轮只需保留适用范围与不确定性，不要求完成开放世界反证；**禁止把边界外问题拒答计为“学会了新能力”**。

对旧 V2 的 36 世界开发记录，不覆盖、不改写；应标记为实现者开发测试，独立闭卷此前未执行。可以作为向 B0 适配的背景，不可直接搬成 B0 成绩。

---

## 11. 建议代码目录与交付文件

DSH 可以根据 Kotlin 项目实际结构调整名字，但不能丢失职责。

```text
src/main/kotlin/logic/capability/
  CapabilityModel.kt        类型、签名、生命周期、来源、依赖
  CapabilityIr.kt           Typed IR 节点与 JSON 编码、canonical hash
  CapabilityTypeChecker.kt  静态类型与版本约束
  CapabilityInterpreter.kt  纯解释执行、三值逻辑、预算
  CapabilityRegistry.kt     SQLite 事务、版本、依赖、快照
  CapabilityComposer.kt     有界类型导向组合搜索
  LibraryLearner.kt         L1 去重、L2 子树抽取、L3 可选
  CapabilityVerifier.kt     固定验证清单、等价测试、晋升门禁
  CapabilityAudit.kt        可复查事件与成本
  CapabilityCli.kt          独立 JSONL CLI
  TeacherGateway.kt         mock、成本计数、未来教师候选契约
  DiscoveryAdapter.kt       可选：把 V2 规则导入 B0（不能改原 V2 语义）

src/test/kotlin/logic/capability/
  IrAndTypeTest.kt
  RegistryLifecycleTest.kt
  ComposerTest.kt
  LibraryLearnerTest.kt
  UnknownAndBoundaryTest.kt
  RegressionAndSnapshotTest.kt

experiments/capability-foundation-b0/
  README.md                 任务背景与实际支持范围
  CONTRACT.md               CLI、状态、异常、IR 和能力格式
  DECISIONS.md              所有影响语义/判分的设计取舍
  fixtures/                 公开正反例、能力、结构重复/负对照
  benchmarks/               A/B/C/D 同题分组与生成器
  reports/                  真实构建、运行、指标、失败报告

# 配套脚本（可用 Kotlin/Python）
tools/capability_b0_benchmark.py
```

**适配 V2 的特别要求**：

- V2 `Rule` 只支持 1–2 个 `Clause` 的 AND。可通过适配器将其**带来源与有限域范围**地映射为 B0 `predicate` Typed IR。
- 旧 V2 规则不应自动升格为全域可靠能力；只能标 `imported_bounded`，并保留 V2 的规则 hash 和证据 IDs。
- 不能在导入时假装 OR/NOT 已经由学生掌握；适配器最多是转换表示与执行。独立测试 B0 与旧 V2 同一规则的有限域行为一致。

---

## 12. 分阶段工作顺序与每阶段停止条件

### M0 — 冻结基线与方案确认（必须先做）

- 记录最新 HEAD、当前测试数量/结果、JDK/Kotlin/Gradle 配置、V2 现有问题与状态。
- 建立新实验目录、`DECISIONS.md` 和完整目录规划；在 `README` 写明 B0 不覆盖 V3。
- 给出真实 API 草案和至少五个恶意/边界输入样例。
- **门槛**：现有测试能在本机原样重跑；如果不能，报告真实阻碍，不伪称回归通过。

### M1 — Typed IR + 解释器 + 类型检查（第一优先）

- 实现第 3 节语义；canonical hash；执行步数/调用深度限制。
- 用基础能力完成至少三种不同规则组合，测试 Unknown/Error、类型错误与确定性。
- **门槛**：对同一 Typed IR 相同输入输出稳定；非法 AST 无法执行；无外部服务调用。

### M2 — Capability Registry + 证据/依赖/版本（第一优先）

- 实现第 4–5 节能力生命周期与事务存储；激活门禁；库快照与恢复。
- 依赖失效时下游 `needs_revalidation`；旧版本仍可追溯。
- **门槛**：断电模拟/异常、重启、重复导入、版本替换、循环依赖都能在测试中正确处理。

### M3 — Composer（第二优先）

- 实现第 6 节有界组合、公开样本筛选、确定性搜索、歧义/预算诊断。
- **门槛**：用相同底层原语在新属性世界合成至少两条**未直接硬编码进生产代码**的具体能力；未区分候选不得虚假晋升。

### M4 — Library Learner L1/L2（第二优先）

- 实现语义安全去重和公共子树抽取；版本化重写；计算 L_total；负对照。
- 可以试探 L3，但**不得挤占前面正确性工作**。
- **门槛**：至少一个净收益示例与一个拒绝劣质压缩的反例；可证明或受限验证的行为等价；旧版本可回滚。

### M5 — 小规模训练前哨实验（严格限量）

- 公开开发阶段可用小型、固定生成器，例如**每组 20–50 个任务**，三类符号化场景，多 seed，含负对照与反例。
- 执行 A/B/C/D 的同题比较；额外记录教师成本为零。
- **门槛**：完整 raw 数据、环境、审计和失败样本；明确效果是正向、持平还是负向，不可只报总体成功率。

### M6 — 冻结、交接与训练准入判定

- 冻结代码与验证契约；提交 `B0-IMPLEMENTATION-REPORT.md`、`B0-BENCHMARK-RESULTS.json`、`B0-OPEN-ISSUES.md`。
- 报告明确 `implemented/not_implemented`、`development_passed/failed`、`independent_closed_book=not_run`（除非独立审查者真的运行）。
- **门槛**：至少能让独立审查者在无教师环境从干净 checkout 复现核心结果。

**实施授权边界**：默认直接执行 M0–M4，质量允许则完成 M5–M6 的公开开发部分。不得因为任务书篇幅长就只做前两项、报告却写完整完成；任何缺项要明确写 `not_implemented`。整个任务不需要研究者逐步确认，可按本任务书的安全默认决策继续。

---

## 13. 开始长期训练之前的 Go/No-Go 标准

当满足以下条件时，才建议讨论大规模学前→小学→中学知识训练以及未来 V3 教师 Agent：

- 核心 Typed IR、注册表和组合器经过正确性验证，不能靠教师逐题填答案。
- 能力库可版本化，撤销证据/能力后，不会静默复用旧结论。
- 压缩候选可以自动提出、自动拒绝、自动验证和无损回滚；不存在仅凭训练样本一致就合并的逻辑漏洞。
- 学习实验可以独立记录“学会了新规则”和“把旧程序抽象压缩”这两种完全不同的结果。
- 至少一个新任务分布验证了能力库的复用价值；如果只在训练集变短，也应如实标记为仅存储改善。
- 独立审查者能够冻结版本并运行新世界，不复用 DSH 的同一生成器当作独立验收。

若这些条件缺失，**不要急于灌入海量数据**。数据量越大，可能越快放大错误抽象、混淆能力归属和造成难以迁移的技能碎片。

---

## 14. DSH 最终必须交付的东西

1. **实际 Kotlin 实现**：核心源码、测试、Gradle 集成和可运行的 CLI（不能只写一个 MD）。
2. **能力与 IR 正式契约**：明确签名、三值语义、生命周期、版本、依赖、错误码、压缩成本公式。
3. **至少三段可复查轨迹**：从公开样本组合得到能力、从重复程序抽取 helper 并验证、因行为改变而拒绝错误抽象。
4. **公开开发 A/B/C/D 数据**：给出成功/失败总数、正确率、Unknown/Error、L_total、查询/搜索成本、耗时、能力复用数和原始逐任务输出。
5. **真实作者与能力归因表**：`designer_primitive/teacher_proposed/student_composed/student_abstracted` 等逐项可查，禁止把工具编写归为学生自主发明。
6. **完整 git 提交**：不要改写旧报告、不要删除失败历史；注明 HEAD 和所做修改；仓库可干净构建。
7. **移交独立审查的问题清单**：至少包括当前假设空间限制、行为等价边界、压缩收益的样本外证据、类型扩张策略、是否应开放下一阶段训练。

报告开头必须有一页简明事实表：

```text
commit:
old_regression:
new_tests:
IR/types:
composer:
registry:
compression_L1:
compression_L2:
compression_L3:
benchmark_worlds:
benchmark_tasks:
teacher_calls:
correct/wrong/unknown/error:
L_total_before/after:
invalid_promotions:
independent_closed_book: not_run
known_failures:
```

**所有没有执行的检查写 `not_run`，所有没有实现的功能写 `not_implemented`。拒绝把推测、开发单测和第三方闭卷混成一种成绩。**

---

## 15. 参考研究：学习思想，不照抄实现

1. **DreamCoder**：Kevin Ellis 等，*DreamCoder: Bootstrapping Inductive Program Synthesis with Wake-Sleep Library Learning*，PLDI 2021，https://doi.org/10.1145/3453483.3454080 。重点看程序库学习、重复子程序抽象以及评估迁移，而不是本轮复现它的神经搜索策略。
2. **CEGIS**：Armando Solar-Lezama 的课程说明，https://people.csail.mit.edu/asolar/SynthesisCourse2020/Lecture10.htm 。重点看候选→验证→反例→修订，确保“一个相容候选”不是正确性证明。
3. **Minimum Description Length（MDL）思想**：把库定义的成本与任务程序成本一起核算；本任务书采用可复查的工程代理目标，并不宣称严格实现通用 MDL 训练。

以上研究提供设计启发，不能被写成“Observable AI 已复现或超越 DreamCoder”。本轮要回答的是：**这个受控、可复验的 Kotlin 学习基底是否真的产生了可组合、可压缩、可迁移的能力增量。**

---

## 16. 最后要求

请 DSH 在动手前通读仓库真实代码与本文件，然后立即进入 M0→M1→M2→M3→M4。能稳定构建以后再进入 M5。

**优先级永远是：正确性与能力归因 > 可复验性 > 真实迁移收益 > 压缩收益 > 工程速度 > 漂亮的成绩。**

本轮不要将研究者推进到 V3 或小学大规模训练；先提交一个值得长期积累知识和能力的基础设施，再决定下一轮训练从哪里开始。
