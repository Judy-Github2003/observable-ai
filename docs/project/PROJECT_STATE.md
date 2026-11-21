# Observable AI 当前状态

状态基准：2026-11-16（恢复包基线）。  
本机核对：2026-11-16 23:55 至 2026-11-17 00:1x，执行者 DeepSeek Harness，任务 RECOVERY-01。本文件只写本机可核对的事实；推断与未知单独标注。

## A. 本机核实事实

| 项目 | 本机核实状态 | 证据 |
|---|---|---|
| 正式名称 | Observable AI | 项目负责人 2026-11-16 决策；`docs/project/CURRENT_DECISIONS.md` |
| 本地仓库 | `C:\自研逻辑模型` | `git rev-parse --show-toplevel` |
| remote | `https://github.com/Judy-Github2003/observable-ai.git`（fetch/push 同源） | `git remote -v` |
| 当前分支 | `recovery/2026-11-16-project-records`（恢复分支）；main 仍为 `c5f36c4` | `git branch -vv` |
| HEAD（恢复前） | `c5f36c45652443dbf8c230f2e43477c9555ad7f5`，2026-11-11 16:48 +0800，`chore(console): consume X01-SLICE1 authorization` | `git rev-parse HEAD`、preflight `head.txt` |
| 提交总数 | 240 | `git rev-list --count HEAD` |
| 工作区 | dirty：2 个已跟踪文件修改（`research-console/events.jsonl`、`repairs.json`），13 组未跟踪文件/目录 | preflight `status.txt`、`untracked-files.txt` |
| 链接工作树 | 6 个位于 `%TEMP%` 的 detached worktree，均标记 `prunable`（b0-exam-0ca4561、observable-e222-*） | `git worktree list`；bundle 含其 HEAD refs |
| 有效资料 | 项目 4,430 文件/3.29 GB；Downloads 9,868 文件/7.45 GB（排除 build/.tools/node_modules/edge_profile） | `folder-inventory.json`（2026-11-16 清点） |
| ARLS/Observable AI 归档 | 75 项、11,175 文件、1,325,489,199 字节（1.23 GiB）、333 Markdown、45 ZIP；全部路径存在 | `arls-inventory.csv`、`arls-validation.json`、`arls-zip-sha256.csv` |
| DSH 历史会话 | **已从卷影副本恢复 93 条会话文件（约 140.7 MB）**；当前索引 38 条全部有 transcript（38/38）；91 条已复制回活 home（跳过 2 条活动会话） | `restored-home/inventory.csv`、`shadow-1..4`、`Repair-SessionIndex.ps1` 预演 |
| `session-a837aee7` | 已恢复 11,683 个事件（10.5 MB），覆盖 2026-11-14 01:18 至 2026-11-16 17:00:43；17:00 之后的尾部（含 22:40–23:00 ACT-01 编排与用户最后输入）仍缺失 | `sessions-md/session-a837aee7.md`、shadow-3 快照 |
| 输入草稿 | 3 个会话有本地缓存草稿；仅为片段，不是 transcript | `recovered-drafts.md` |
| Ollama | 0.40.16；当前 `ollama list` 空、`/api/tags` 返回 `{"models":[]}`、模型 blobs=0 | `ollama --version`、`/api/tags`、`~/.ollama/models` |
| MiniCPM 使用证据 | 服务日志显示 `openbmb/minicpm5-2b:latest` 于 21:15、22:08 加载，2052 次 `POST /api/chat`，最后一次 2026-11-16 22:18:28；G1.2r trace 记录 digest `71a44e98…` | `%LOCALAPPDATA%\Ollama\server.log`、`g12r_trace.jsonl` |
| 模型移除时间 | 在 22:18:28（最后一次调用）之后、23:11:21（`~/.ollama/models` 与 `manifests` 目录重新创建）之前；具体移除者/命令未发现，标 `UNKNOWN` | 目录时间、`ollama list` 空、shell history 无 `ollama rm` |
| 当前核心路线 | 系统自身学习逻辑、数学、代码、语义四大基础能力 | 项目负责人 2026-11-16 决策 |
| 当前 Agent | DeepSeek Harness 极简模式计划借用 | ACT-01 未完成 |
| 自研 Agent | 长期目标，当前不作为主任务 | `PROJECT_MASTER.md` |
| 权重微调 | 中长期可选，当前不启动 | `CURRENT_DECISIONS.md` D-006 |

## B. 版本、时间与状态冲突

### Git HEAD
- 2026-11-08 交接文档记载 `b7da2cb8…`；2026-11-14 理论审计记载 `c5f36c45…`、240 commits。
- 2026-11-16 本机实测：HEAD `c5f36c45652443dbf8c230f2e43477c9555ad7f5`，240 commits，与 11-14 记录一致。
- 恢复分支从该 HEAD 创建；main 未被改写。

### Ollama / MiniCPM 时间点冲突（已解释，原因部分 UNKNOWN）
- G1.2r 报告称 2026-11-16 使用 `openbmb/minicpm5-2b:latest`、Q4_K_M、Ollama 0.40.16、digest `71a44e98400fbd490d66332a0ebba31143479a714987c13f45c82e94025cbfa7`，并完成开发集/留出集真实调用。
- 本机 2026-11-16 23:15 与 23:56 检查时 `ollama list` 为空，`~/.ollama/models/blobs` 为空，`manifests` 不存在。
- 服务日志核实：0.40.16 服务于 21:05:58 启动，启动时 `total blobs: 6`；模型在 21:15:24、22:08:32 被加载；21:05–22:18 期间共 2052 次 `POST /api/chat`，最后一次 22:18:28；22:37 的 `/api/tags` 已无模型返回。
- G1.2r trace 的真实调用时间为 22:13:25 起，run 目录与文件 mtime 为 22:16–22:19；报告/目录名中的 `T2236`、`T2242` 与实际墙钟不一致（约 +17–20 分钟偏移），属于**时间标签不一致**，不足以否定调用本身；trace 的 `model_id`/`model_digest` 与报告一致。
- 模型移除发生在 22:18:28 之后、23:11:21 之前；未发现 `ollama rm` 记录，未发现删除日志。结论：**模型确实安装并被真实调用过；现已从本机移除，移除原因与执行者 `UNKNOWN`，不能写成“从未安装”**。
- digest 无法从现存 blobs 复核（已删除），只能由 trace、报告与加载日志交叉支持。

### DSH transcript 恢复
- 恢复包基线写“38 条索引只有 1 条 transcript”。本机在获得管理员权限后检查到 4 个卷影副本（2026-11-15 20:06、11-16 13:12、11-16 19:43、11-16 23:12），其中前三个含删除前 sessions/storages；共恢复 93 条会话文件（140.7 MB），38 条索引会话全部找回。
- 仍缺失：`session-a837aee7` 在 2026-11-16 17:00:43 之后的事件（含 22:40–23:00 的 ACT-01 编排和用户最后输入）。卷影 23:12 为删除后快照，不含该尾部；NTFS 底层恢复未完成（TSK 探针在枚举阶段超时，未取得文件）。
- `Repair-SessionIndex.ps1` 预演现在返回 38/38 present、missing=0，说明索引条目已全部可解析。

## C. 当前实验状态

### G1.2r
- 工程修复完成：共享首轮、反馈上下文保留、feedback/score 数据隔离、计时分离。
- 离线自检：G1.1 44 项、G1.2 codec 954 项、G1.2 baseline 1025 项、G1.2r 54 项通过（开发者报告）。
- 科研结果：真实反例反馈没有救回任务；开发集 11 次反馈重试 0 次救回，留出集 27 次 0 次救回。盲重试 S2 有少量随机救回。
- 结论：接口/实验设计修复成功；反馈方法未证明有效；不宣称学习能力提升。
- 时间标签：报告正文的 22:36/22:42 与实际文件/trace 时间（22:13–22:19）不一致，引用时应以 trace 时间和目录 mtime 为准，或注明来源标签。

### ACT-01
- 任务包存在：`ARLS_ACT_01_MiniCPM5_DSH_Minimal_Task_2026-11-16.zip`（Inventory 已列，SHA-256 见 ARTIFACT_INDEX.csv）。
- `tools/isolated-lab/` 已新增；2026-11-16 23:57 无浏览器复跑：11 项 PASS + 1 条“未附加浏览器”WARN，真实 home session 文件数不变；此前带浏览器版本为 13 项 PASS。
- 这是进程级隔离，不是 OS 沙箱；T05（执行模型生成代码）仍 `BLOCKED`，直到 Windows Sandbox / WSL / Docker 等 OS 隔离可用。
- 正式运行前仍需 Research Console preflight，并确认 Minimal preset、workspace-write、人工审批、工具 schema 不继承全局插件。
- 模型侧：当前 Ollama 无模型，ACT-01 不能直接运行；需重新安装/确认模型来源。

## D. 下一步状态
1. 完成 RECOVERY-01 提交与推送（本文件所在分支）。
2. 重新核实并记录 Ollama 模型移除原因；如需 ACT-01，先重新安装模型并核对 digest。
3. 在具备 OS 沙箱后做 ACT-01 preflight / 冒烟；不得在主机直接执行模型生成代码。
4. 之后设计四大基础能力（逻辑、数学、代码、语义）的共同评测框架；不以 Agent 功能本身作为学习成果。
