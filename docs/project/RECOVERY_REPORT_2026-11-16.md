# RECOVERY-01 恢复报告（Observable AI）

- 任务：RECOVERY-01，项目档案与 Git 基线恢复；不开发新功能、不运行学习实验。
- 执行者：DeepSeek Harness；日期：2026-11-16（本机执行至 2026-11-17 凌晨）。
- 仓库：`C:\自研逻辑模型`；remote `https://github.com/Judy-Github2003/observable-ai.git`。
- 恢复分支：`recovery/2026-11-16-project-records`。
- 主恢复提交：`b649c9b64d3cf812f12197cbd2031fc3ff2318c9`（`docs(recovery): restore Observable AI project records`）。
- 本报告所在提交：见本分支 `git log -1`。
- 复核状态：未经 Codex/ChatGPT 复核，不写“已验收”。

## 1. Phase A：本机只读审计

| 项 | 结果 |
|---|---|
| repo root | `C:/自研逻辑模型`（与请求路径一致） |
| HEAD | `c5f36c45652443dbf8c230f2e43477c9555ad7f5`（2026-11-11 16:48 +0800） |
| 分支 | `main`（恢复前）；恢复分支创建后为 `recovery/2026-11-16-project-records` |
| remote | `origin https://github.com/Judy-Github2003/observable-ai.git`（fetch/push 同源） |
| 提交数 | 240 |
| 工作区 | dirty：2 个已跟踪文件修改 + 13 组未跟踪；恢复过程未触碰 |
| 链接工作树 | 6 个 `%TEMP%` 下 detached worktree，均 `prunable`；未 prune |
| 恢复包完整性 | `SHA256SUMS.txt` 35 个文件：missing=0、bad=0 |
| ARLS 清单 | 75 项、11,175 文件、1,325,489,199 字节（1.23 GiB）、333 Markdown、45 ZIP，missing=0 |
| ZIP SHA-256 | 45/45 已计算，见 `ARTIFACT_INDEX.csv` |
| 草稿 | `C:\dsh-lab\recovered\recovered-drafts.md` 存在（3 个会话片段，不是 transcript） |
| isolated-lab | 6 个文件存在；2026-11-16 23:57 无浏览器复跑 11 PASS + 1 WARN，TEST_EXIT=0；此前带浏览器 13 PASS |
| Ollama | 0.40.16；当前无模型；历史加载与 2052 次 `/api/chat` 有日志证据（见 §8） |

### 只读预检与 bundle

- 命令：`Preflight-GitRecovery.ps1 -RepoPath "C:\自研逻辑模型"`。
- 备份目录：`C:\dsh-lab\recovery-backups\observable-ai-pre-recovery-20261116-235547\`。
- bundle：`observable-ai-all-refs.bundle`（2,400,642 字节），含 9 个 refs（main、origin/main、HEAD、6 个 worktree HEAD）。
- `git bundle verify` 手动复核通过：`The bundle records a complete history`。
- 预检脚本存在兼容缺陷：`git bundle verify` 的 “is okay” 写到 stderr，被 `$ErrorActionPreference='Stop'` 当成终止错误，脚本退出码 1；bundle 已生成且有效。未修改原始脚本。

## 2. Phase B：恢复基线

- 从 main HEAD 创建 `recovery/2026-11-16-project-records`。
- 未执行 `git reset --hard`、`git clean`、`git restore .`、`git checkout -- .`；未删除/重建 `.git`；未改写历史；未 force push。
- 工作区原有 2 个修改与 13 组未跟踪保持原样，未提交、未删除、未覆盖。

## 3. Phase C：项目档案

新增 `docs/project/`：

| 文件 | 说明 |
|---|---|
| `PROJECT_MASTER.md` | 项目总档案 + 本机恢复基线 |
| `PROJECT_STATE.md` | 本机核实状态、Git/Ollama/DSH 冲突处理 |
| `THEORY_AND_NORTH_STAR.md` | 理论与研究北极星 |
| `CURRENT_DECISIONS.md` | D-001–D-010 |
| `MILESTONES_AND_EVIDENCE.md` | 里程碑、证据标签、事故恢复更新 |
| `NEXT_TASK.md` | RECOVERY-01 完成状态与后续 |
| `ARTIFACT_INDEX.csv` | 75 条 artifact index |
| `INCIDENTS/2026-11-16-dsh-session-incident.md` | 事故记录（含卷影恢复） |
| `RECOVERY_SOURCES.md` | 恢复来源与证据边界 |
| `GIT_POLICY.md` | Git 强制政策 + 本机执行补充 |

`ARTIFACT_INDEX.csv`：75 行；9 条恢复包 seed 行全部匹配本地文件；45 个 ZIP 有 SHA-256；30 个目录条目 SHA=`UNKNOWN`（不计算目录哈希）；61 条 `evidence_status=UNKNOWN`、4 条 `experiment=UNKNOWN`（未读报告前不猜结论）。

## 4. Phase D：Git 强制化

- `.gitignore` 只做最小合并：新增 cookies/dsh-auth/.dsh/edge_profile/user-data-dir/model 权重/node_modules/.cache 等敏感模式（+17 行）；未忽略 `datasets/`、`docs/research/` 或全部 `*.jsonl`。
- 历史中已跟踪的 `.log` 文件（`git ls-files -ci --exclude-standard`）只记录，不删除、不重写历史。
- 暂存仅 `docs/project/` 与 `.gitignore`；`git diff --cached --check` 只报 Markdown 行尾双空格（原文硬换行）和 EOF 空行，无代码空白错误。
- 暂存差异 secrets 扫描（sk-/ghp_/AKIA/PRIVATE KEY/dsh-auth-*）：0 命中。
- 提交：`b649c9b64d3cf812f12197cbd2031fc3ff2318c9`，`docs(recovery): restore Observable AI project records`。
- 推送：`git push -u origin recovery/2026-11-16-project-records` 成功；远端分支 SHA 与本地一致，未推 main、未 force。

## 5. Phase E：验收清单

1. **repo root / HEAD / 分支 / remote / 提交数**：`C:\自研逻辑模型`；恢复前 HEAD `c5f36c4`；恢复提交 `b649c9b`；分支 `recovery/2026-11-16-project-records`；remote 如上；240 commits。
2. **bundle**：见 §1；手动 verify 通过；包含完整历史与 9 个 refs。
3. **原有 dirty/untracked**：2 个已跟踪修改（`research-console/events.jsonl`、`repairs.json`）与 13 组未跟踪均未触碰；恢复后 `git status` 仍显示它们。
4. **新建/修改文件**：11 个文件、+799 行（10 个 `docs/project/` 文件 + `.gitignore`）；`git show --stat b649c9b` 可复核。
5. **artifact index**：75 行、45 ZIP、9 条 seed 匹配、30 个目录 SHA=UNKNOWN、61 条证据状态 UNKNOWN、4 条 experiment UNKNOWN；没有猜测值。
6. **.gitignore / secrets**：见 §4；`git check-ignore` 验证 `edge_profile`、`cookies.txt`、`.dsh/`、`*.gguf` 生效；没有新增 secrets。
7. **commit / push**：`b649c9b64d3cf812f12197cbd2031fc3ff2318c9`；分支 `recovery/2026-11-16-project-records`；push 成功，远端 `b649c9b`。
8. **Ollama/MiniCPM 冲突**：模型确实安装并真实调用（21:15/22:08 加载，2052 次 `/api/chat`，最后 22:18:28；trace 记录 digest `71a44e98…`）；当前已移除（blobs=0，`ollama list` 空）；移除时间在 22:18:28–23:11:21 之间，原因/执行者 `UNKNOWN`；报告中的 `T2236/T2242` 标签与实际文件/trace 时间（22:13–22:19）存在约 17–20 分钟偏移，引用时以 trace/mtime 为准。不能写成“从未安装”，也不能写成“仍然可用”。
9. **ACT-01**：仍 `BLOCKED`；进程级隔离不是 OS 沙箱；缺 Windows Sandbox / WSL 发行版 / Docker daemon；模型也已不在本机；需重新安装模型、OS 隔离和 Research Console preflight 后才能继续。
10. **NEXT_TASK 一致性**：`NEXT_TASK.md` 已保留恢复包原始下一步，并追加 RECOVERY-01 完成状态；与项目负责人 2026-11-16 决策（先恢复档案与 Git，再 ACT-01，再四大基础能力）一致。

## 6. DSH 会话恢复（本机新增证据）

- 管理员权限下发现 4 个卷影：11-15 20:06、11-16 13:12、11-16 19:43、11-16 23:12；前三个含删除前 `.dsh\sessions`/`storages`。
- 恢复 93 条会话文件（140,670,961 字节，0 解码错误）；38 条索引会话全部找回（38/38）；91 条复制回活 home（跳过 2 条活动会话）。
- `session-a837aee7` 恢复 11,683 个事件（10.5 MB），覆盖 2026-11-14 01:18 至 2026-11-16 17:00:43；17:00 之后的尾部（含 22:40–23:00 ACT-01 编排与用户最后输入）仍缺失。
- 23:12 卷影为删除后快照；TSK 4.15.0 只读探针能打开 `\\.\C:`，但逐级枚举超时，未取得删除文件；NTFS 底层恢复未完成。
- 该结论修正了恢复包基线“37 条不可恢复”：在检查卷影副本后，主体已恢复，仅快照后的尾部仍缺。

## 7. 未解决与风险

- `session-a837aee7` 尾部仍缺；如需，可在管理员权限下继续 TSK/`winfr` 定向恢复，但不能保证成功。
- `edge_profile`（535 MB，含用户浏览器扩展/登录态）和 `cookies.txt` 仍保留；属敏感数据，建议由用户决定删除。
- 模型移除原因未知；ACT-01 模型侧未就绪。
- OS 沙箱不可用，T05 仍 BLOCKED。
- 恢复包预检脚本的 stderr 兼容缺陷未修改；本报告记录并用手动 verify 替代。
- 本报告与恢复文档未经独立复核；不能把“文档恢复完成”写成“研究结论已验收”。

## 8. 变更文件

- `docs/project/`：10 个文件（见 §3）。
- `.gitignore`：+17 行敏感模式。
- 未修改任何研究源码、冻结实验、数据集、`research-console` 或历史提交。
