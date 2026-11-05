# Observable Learner / 可观测学习者

> 一个把“能力到底属于谁”当作第一问题的教学实验：一个 Kotlin 程序从外部教师那里逐步获得**可审计、可归因、可复验**的能力。

**English**: A Kotlin learner that acquires auditable, attributable abilities from an external teacher. Knowledge lives in SQLite, operations are Kotlin functions with rule provenance, and every answer can be traced from question → rule → code anchor → evidence → verdict.

---

## 现在到哪一步（2026-11-05）

| 阶段 | 内容 | 状态 |
|---|---|---|
| V0 | 只会把问题转发给教师 | 保留为基线 |
| V1 | 原文精确匹配的问答记忆（SQLite） | 通过 |
| 第 1 课 | 对象 ID、名称、同名 vs 同一、未知 | 通过 |
| 第 2 课 | 属性查询、分组、比较、补教 | 通过 |
| 学前 24 课 | 23 条通用操作规则，8 批课程 | **训练 864/864，考试 960/960，24/24 课达标，教师调用 0** |
| 独立抽查 | 冻结版本 + 新世界 + 隔离夹具 | **64/64** |
| B0 类型化能力基底 | IR、能力库、验证/激活/撤销、组合、L1/L2 压缩、A/B/C 基准 | **M0–M6 完成；173/173 测试；独立闭卷 not_run** |
| E3 开放表征与学习诊断 | A0–A8 契约/世界族/碰撞/适配/预算/探测/整合 + A9 协议与最小基线 | **进行中；全套 290/290 测试** |

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

- 契约实现：`src/main/kotlin/logic/e3/`；生成器：`src/main/kotlin/logic/e3/evaluator/`；全套 **290/290**（B0 173 + E3 117）。
- 已实现最小生成器与独立 oracle；未实现 A9 对照基线、原因归因或评分器；E3-B/C 未授权。
- `independent_closed_book=not_run`；E3 结果为 `implementer_run`。
- 记录：[E3 SPEC](./experiments/representation-evolution-e3/SPEC.md)、[DECISIONS](./experiments/representation-evolution-e3/DECISIONS.md)、[WORKSTATE](./experiments/representation-evolution-e3/WORKSTATE.md)、[A0/A1 报告](./experiments/representation-evolution-e3/reports/E3-A0-A1-REPORT.md)、[A2 报告](./experiments/representation-evolution-e3/reports/E3-A2-REPORT.md)。

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
# Windows
./gradlew.bat --no-daemon test installDist

# Linux / macOS
./gradlew --no-daemon test installDist
```

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

experiments/
  curriculum/       课程计划、规则目录 rules.json、批量教材 curriculum.json
  lessons/          第 1/2 课材料与说明
  reports/          各阶段验证与审计记录
  capability-foundation-b0/  B0 任务书、决策、报告与工作状态

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
- **E3 当前边界**：完成 A0–A8（契约 + 世界族 + 碰撞 + 适配器 + 预算诊断 + 探测 + 整合）；A9 评测基础设施已冻结（P0 完成），A10 未运行；没有原因归因或评分器；不能把“生成器存在”当成诊断能力。全套 290/290 是 B0+E3 的 `implementer_run`。
- **成本指标**：`L_total` 是冻结的静态长度代理，不代表运行更快；压缩通过不等于获得新知识。
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

**独立闭卷尚未执行。** 同模型、不同会话不构成第三方验证；闭卷状态在报告中固定为 `not_run`，等待外部审查者。
















