# RECOVERY-01 恢复来源与证据边界

日期：2026-11-16（本机执行至 2026-11-17 凌晨）。执行者：DeepSeek Harness。  
本文件只记录来源、路径和可核验方式；不替代原始证据，不把开发者报告写成独立验证。

## 1. 恢复包

- 包文件：`%USERPROFILE%\Downloads\ObservableAI_Recovery_Kit_2026-11-16.zip`（163,236 字节）。
- 解压目录：`C:\dsh-lab\recovery-kit\ObservableAI_Recovery_Kit_2026-11-16\`。
- 完整性：对包内 `SHA256SUMS.txt` 的 35 个文件逐一核对，`missing=0`、`bad=0`。
- 已完整阅读：`00_START_HERE/README_FIRST.md`、`01_PROJECT_RECORD/*`、`02_GIT_POLICY/*`、`03_RECOVERY/*`、`04_TEMPLATES/*`、`05_SOURCE_EVIDENCE/*`。

## 2. 本机只读审计证据

| 内容 | 位置 |
|---|---|
| Git 只读预检输出、tracked diff、untracked 清单、bundle | `C:\dsh-lab\recovery-backups\observable-ai-pre-recovery-20261116-235547\` |
| bundle 路径 | 同上 `observable-ai-all-refs.bundle`（2,400,642 字节，含 9 个 refs） |
| bundle 校验 | 脚本内 `git bundle verify` 因 stderr 处理缺陷退出 1；手动复核 `The bundle records a complete history`（见 `GIT_POLICY.md` §8） |
| 仓库身份 | `repo-root.txt`、`head.txt`、`branches.txt`、`remotes.txt`、`commit-count.txt`、`log-20.txt` |
| 工作区 | `status.txt`、`status-porcelain-v2.txt`、`tracked-working-tree.patch`、`staged.patch`、`untracked-files.txt`、`ignored-but-tracked.txt` |

## 3. 资料与清单

| 内容 | 位置 / 结果 |
|---|---|
| ARLS 75 项清单 | `C:\dsh-lab\recovered\arls-inventory.csv` |
| 清单本机校验 | `C:\dsh-lab\recovered\arls-validation.json`：75 项、11,175 文件、1,325,489,199 字节、333 Markdown、45 ZIP、missing=0 |
| ZIP SHA-256 | `C:\dsh-lab\recovered\arls-zip-sha256.csv`（45 个 ZIP） |
| 两目录清点 | `C:\dsh-lab\recovered\folder-inventory.json`：项目 4,430 文件/3.29 GB，Downloads 9,868 文件/7.45 GB |
| 回收站 | `C:\dsh-lab\recovered\recycle-inventory.jsonl`、`recycle-restored\`（25 项/129.7 MB，7 个不同 SHA-256 均与现存文件重复） |
| 输入草稿 | `C:\dsh-lab\recovered\recovered-drafts.md`（3 个会话片段，不是 transcript） |
| ACT-01 包完整性 | `pkg\SHA256SUMS.txt` 校验 `missing=0`、`bad=0` |

## 4. DSH 会话恢复证据

| 内容 | 位置 |
|---|---|
| 卷影枚举 | `C:\dsh-lab\recovered\admin\vssadmin-shadows.txt`、`shadows.json` |
| 卷影复制 | `C:\dsh-lab\recovered\admin\shadow-1..4\sessions`、`storages` |
| 合并恢复基线 | `C:\dsh-lab\recovered\restored-home\sessions`、`storages`、`inventory.csv` |
| 恢复清单 | 93 条会话、140,670,961 字节、0 解码错误；38/38 索引会话存在 |
| 可读文本 | `C:\dsh-lab\recovered\sessions-md\00-index.md`、`session-a837aee7.md` |
| 索引预演 | `Repair-SessionIndex.ps1` 输出：38/38 present、missing=0 |
| 仍未恢复 | `session-a837aee7` 2026-11-16 17:00:43 之后的事件（含 22:40–23:00 ACT-01 编排及用户最后输入） |
| NTFS 底层尝试 | `C:\dsh-lab\recovered\admin\tsk\tsk-recover.log`：TSK 可打开 `\\.\C:`，逐级枚举超时，未取得删除文件 |

## 5. 隔离环境证据

| 内容 | 位置 |
|---|---|
| 脚本 | `tools/isolated-lab/`（`lab-common.ps1`、`Start-LabInstance.ps1`、`Stop-LabInstance.ps1`、`Test-LabIsolation.ps1`、`Repair-SessionIndex.ps1`、`README.md`） |
| 最近复验 | `C:\dsh-lab\recovered\isolated-lab-selftest-2026-11-16.log`（2026-11-16 23:57，无浏览器：11 PASS + 1 WARN，TEST_EXIT=0） |
| 历史复验 | 2026-11-16 较早时带一次性浏览器：13 项 PASS；停止后端口释放 |

## 6. Ollama / MiniCPM

| 内容 | 位置 / 结果 |
|---|---|
| 服务版本 | `ollama 0.40.16`；API `http://127.0.0.1:11434` 返回 `{"version":"0.40.16"}` |
| 服务日志 | `%LOCALAPPDATA%\Ollama\server.log`：21:05:58 启动、`total blobs: 6`；模型 21:15/22:08 加载；2052 次 `POST /api/chat`，最后一次 22:18:28 |
| 当前模型 | `ollama list` 空、`/api/tags` 为 `{"models":[]}`、`~/.ollama/models/blobs` 为空 |
| 模型移除 | 时间在 22:18:28 之后、23:11:21（`models`/`manifests` 目录重建）之前；未发现 `ollama rm` 或删除日志；原因 `UNKNOWN` |
| G1.2r 证据 | `%USERPROFILE%\Downloads\ARLS_G1_2r_DEV_2026-11-16\g12r\results\traces\g12r_trace.jsonl`：首条 22:13:25，记录 `model_id=openbmb/minicpm5-2b:latest`、digest `71a44e98…`；run 目录 mtime 22:16–22:19 |

## 7. 证据边界

- 本恢复只做档案、索引、Git 基线和会话恢复；没有运行任何学习实验、没有启动 ACT-01、没有下载模型、没有清理 DSH 索引、没有删除 `edge_profile`。
- 恢复包提供的项目记录作为基线；本机事实与之冲突处已在 `PROJECT_STATE.md`、`MILESTONES_AND_EVIDENCE.md`、`INCIDENTS/2026-11-16-dsh-session-incident.md` 中显式标注。
- 卷影是崩溃一致快照：可恢复已提交前缀，不保证快照之后的写入；`session-a837aee7` 的尾部因此仍缺。
- 所有“自测通过”只是开发者执行记录，不构成独立闭卷；G1.2r 的阴性结论保持。
- 本记录与恢复报告未经 Codex 或 ChatGPT 复核，不写“已验收”。

## 8. 路径占位符（OA-NEXT-01 规范化，2026-11-17）

`ARTIFACT_INDEX.csv` 与本文件中的 `%USERPROFILE%`、`%LOCALAPPDATA%` 是占位符，不会被 CSV 或 Git 自动展开，也不应当作可直接传入文件 API 的字面路径。`tools/git/Test-ArtifactIndex.ps1` 只显式展开这两个变量后做本机存在性、大小和 SHA-256 核验；展开逻辑不写回索引。
