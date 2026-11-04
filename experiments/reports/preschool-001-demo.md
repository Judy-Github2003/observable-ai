# 学前第一课：对象、名称和同一性验收

日期：2026-11-04。实现版本：`V2-L1`，第一课 ID：`preschool-001`。

第一课已实现并完成首轮验收。研究者给定 A=苹果、B=杯子、C=球、D=苹果；E 不在初始材料中。学生能够将给定名称持久化，按 ID 查询、按名称反查全部已记录对象，分别判断同一身份与同名，遇到未记录对象返回 `unknown`，显式补教后再保存。

这是有限的结构化记录操作能力。教材是本项目原创受控训练世界，名称由研究者给定；本轮未验证图片识别、自然语言理解、现实物体识别、自主概念形成或类别推理。研发 AI 写程序与学生运行时操作的能力分开记录。

## 实现贡献与局部审计

实际使用本机 DeepSeek Harness `0.2.1-rc.5`、`deepseek-account / deepseek-flash`，在独立候选目录新增 `ObjectNames.kt`、`LessonOne.kt`、`LessonOneTest.kt`。研发会话：`session-4d9aee58-d05d-4606-a2e6-1cc5636ddf50`；正常退出，27 次可见工具调用，331.08 秒。DSH 只在候选目录读写代码，没有运行构建，也没有提前声称测试通过。调用证据在 `runs/deepseek-preschool-001-20261104/result.json`，三份原稿保存在同目录 `dsh-delivery/`，未保留模型内部思考。

Codex 接入 `Main.kt` 的 `--lesson 1` 分支，准备课程材料，审查 DSH 交付，完成以下局部修正后才合入正式源码：

- 专用库在任何建表或写入前校验归属；只接受空用户表库，或表集合完整且 schema、lesson 元数据匹配的本课库。旧 `answers` 库、大小写变体 `Answers`、陌生业务库、残缺或错配元数据均拒绝，避免向已有文件偷偷追加课程数据。
- 中文步骤补上本次实际命令、命中名称、同名对象列表及比较结果，读日志能知道具体执行内容。
- 材料导入事件保留本批已提交对象及其来源，后续重教覆盖当前名称时仍能回看历史观察。当前对象库每个 ID 只保存一条最新记录。
- 缺少命令参数时，失败归到 `LessonOne.execute`，不把尚未调用的 `ObjectNames.findById` 记成失败；数据库错误提示同时说明问答的 `--memory` 与课程的 `--lesson-db`。
- 扩充原有第三个课程用例验证上述库边界，不新增框架。每个新增代码文件都在字段、分支、参数绑定、事务和异常处理附近写中文注释，注明真实贡献者、日期及原因。

源码变更限于 `Main.kt` 和上述三份新 Kotlin 文件。原 V0/V1 实现、教师适配器、日志实现、构建配置和六项旧测试保持原文件内容。审计差异及合入 SHA256 清单位于 `runs/deepseek-preschool-001-20261104/`。首课运行在创建 `AnswerMemory` 或任何 `Teacher` 之前分流，使用独立对象库；本轮研发所调用的 DSH 不计作学生课程运行时的教师请求。

## 真实 CLI 验收

使用正式 `installDist` 产物，23 条命令每条启动一个全新 JVM。环境中的 `DSH_INSTALL_DIR` 特意指向不存在的目录，验证课程不依赖外部教师后端。全部 23 项通过，其中默认课程 16 次运行，新材料与错误处理在另一份专用库中进行 7 次运行。

| 操作 | 实际结果 |
| --- | --- |
| 导入正式材料 | 保存 A、B、C、D 四条对象，不含 E |
| 重启后分别查询 A/B/C/D | 苹果、杯子、球、苹果，来源指回导入运行 |
| 找苹果 | 返回 A、D，保留同名的两个对象 |
| 比较 A、D | `same_object=false`，`same_name=true` |
| 比较 A、A | `same_object=true`，`same_name=true` |
| 补教前查 E | `unknown`，`object_not_observed`，不输出猜测名称 |
| 补教前比较 A/E、E/E | 均为 `unknown`，不伪造已知身份判断 |
| 显式执行 `教 E 书` | 提交后保存，来源为 `researcher_explicit_teaching` |
| 再启动进程查询 E | 返回书，来源指回显式教学运行 |
| 补教后复查 A、找书 | A 仍为苹果，书对应 E |
| 查询未记录名称 | `unknown`，`name_not_observed` |
| 新材料 X83/T26=积木、R51=小船 | 无需修改学生代码，导入、反查、同名比较正确 |
| 含重复 ID 的坏材料 | 退出码 2，无部分对象写入，已有 R51 仍为小船 |
| 查 `r51`，仅记录了 `R51` | `unknown`，保留精确字符串匹配 |

默认课程留下 164 条 JSONL 事件，无 teacher actor 或 `teacher_request`。16 条课程入口事件均为教师调用 0、模型调用 0。当前默认对象库的最终状态为 A=苹果、B=杯子、C=球、D=苹果、E=书；E 的初始未知和后续显式教学分别保留在运行记录中。

原问答库 `runs/memory.db` 在课程前后的 SHA256 完全一致：`8c1dbdb4887615d0fe049b2f6950b18aa3ee952b676a76762e7b9eb4a88b3c2d`。

## 构建与必要检查

候选副本离线构建和正式项目 `gradlew.bat --offline --no-daemon test installDist` 均通过。正式测试合计 9 项：V0 四项、V1 一项、日志一项、本课三项；失败 0、错误 0、跳过 0。

通过 IDEA MCP 对正式项目执行 `build_project`，返回 `isSuccess=true`、`problems=[]`；对本次四个 Kotlin 文件执行 error 级检查，未报告错误。原始返回保存在 `runs/deepseek-preschool-001-20261104/idea-validation.json`。

最后两处错误路径修正后重新执行正式构建与全部九项测试，并重复 IDEA 构建/检查，均通过；另启动两个独立 JVM 检查缺少 ID、非 SQLite 文件。前者退出 2，日志只把参数失败归命令入口；后者退出 1，提示包含正确的 `--lesson-db`。这两次附加检查使用独立库和日志，结果在 `runs/deepseek-preschool-001-20261104/error-validation.json`，不混入上面的 23 次课程验收。

## 查看与复做

- 教材：`experiments/lessons/preschool-001/materials.json`；课程说明：同目录 `lesson.md`。
- 当前对象记忆：`runs/lessons/preschool-001/objects.db`。
- 中文函数/步骤：`runs/lessons/preschool-001/events.steps.log`。
- 完整结构化事件：`runs/lessons/preschool-001/events.jsonl`。
- 23 次进程验收的命令、响应和断言结果：`runs/lessons/preschool-001/acceptance-results.json`。

现在可直接运行 `--lesson 1 查 A`、`--lesson 1 找 苹果`、`--lesson 1 比较 A D` 或 `--lesson 1 查 E`。IDEA 的入口为 `logic.MainKt`，Program arguments 填对应命令，工作目录为项目根目录。若要重新观察“补教前 E 未知”，用 `--lesson-db` 指定一个新的数据库文件，并用 `--events` 分开保存日志；重新导入材料不清空已教过的 E。
