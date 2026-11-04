# Observable Learner / 可观测学习者

> 一个把“能力到底属于谁”当作第一问题的教学实验：一个 Kotlin 程序从外部教师那里逐步获得**可审计、可归因、可复验**的能力。

**English**: A Kotlin learner that acquires auditable, attributable abilities from an external teacher. Knowledge lives in SQLite, operations are Kotlin functions with rule provenance, and every answer can be traced from question → rule → code anchor → evidence → verdict.

---

## 现在到哪一步（2026-11-04）

| 阶段 | 内容 | 状态 |
|---|---|---|
| V0 | 只会把问题转发给教师 | 保留为基线 |
| V1 | 原文精确匹配的问答记忆（SQLite） | 通过 |
| 第 1 课 | 对象 ID、名称、同名 vs 同一、未知 | 通过 |
| 第 2 课 | 属性查询、分组、比较、补教 | 通过 |
| 学前 24 课 | 23 条通用操作规则，8 批课程 | **训练 864/864，考试 960/960，24/24 课达标，教师调用 0** |
| 独立抽查 | 冻结版本 + 新世界 + 隔离夹具 | **64/64** |

**这不是“毕业”。** 两套各 120 题的综合卷尚未生成；小学 72 单元未开始。形状、颜色、情绪等只是**文本标签查询**，不代表真实看图、听声或情绪体验。

### V2：规则自主发现（2026-11-04 新增）

V1 是“教师写操作、学生执行”。V2 开始让教师**只给逐条标签**，学生自己枚举候选、挑查询、淘汰、晋升：

| 项 | 状态 |
|---|---|
| 内核 | 受限 DSL、三值逻辑、冲突保留、主动查询、有限域等价类、原子持久化、审计事件 |
| 测试 | 新增 15 项，全套 26/26 通过；V1 未改动 |
| 公开开发 | 30 个世界：30/30 规则等价，新对象预测 200/200，平均查询 1.47 |
| 独立闭卷 | **not_run**（等待外部审查者） |

入口：`logic.discovery.DiscoveryCliKt`；说明见 [V2 实验说明](./experiments/rule-discovery-v1/README.md)，接口见 [CONTRACT](./experiments/rule-discovery-v1/CONTRACT.md)。

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

experiments/
  curriculum/       课程计划、规则目录 rules.json、批量教材 curriculum.json
  lessons/          第 1/2 课材料与说明
  reports/          各阶段验证与审计记录

tools/              批量考试生成器、HTML/JSON 报告渲染
可读性工程/         注释与可读性改造的记录、示例、验证
AGENTS.md           项目约定（注释、作者、日志、审计纪律）
```

---

## 已知边界

- **能力归属**：当前 24 课里 21 条规则是 `teacher_programmed`，2 条是历史回填。学生证明的是“能稳定执行并迁移到新世界”，不是“自己发现了方法”。
- **语义 vs 标签**：通过的是符号层任务；没有视觉、听觉、真实情绪，也没有自由语言理解。
- **考试不是毕业**：单课 40 题达标只说明本课过线；综合卷与跨领域迁移尚未完成。
- **可复现性**：报告里的成绩来自真实进程执行；`runs/` 不随仓库分发，需按上面的命令自行生成。

## 开发约定

- 文件头注明**真实作者、日期、修改原因**；注释写到字段、分支、SQL、并发、异常附近，解释“为什么”，不逐行复述赋值。
- 不预先写“已审计/已合并”；候选方案先隔离验证，再晋升。
- 不伪造通过率；失败保留原 attempt，修正后用新目录重跑。
- 日志不写入凭据、环境变量、授权头、原始 stderr 或模型内部思考。

## License

[MIT](./LICENSE)。Copyright (c) 2026 Observable Learner contributors.

## 致谢

- 教师角色：本机 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) Agent。
- 实现与审计分工：DeepSeek Harness 负责批量课程实现；Codex 负责早期 V0/V1、接口集成、独立抽查与报告校验。真实署名保留在源码与规则目录中。
