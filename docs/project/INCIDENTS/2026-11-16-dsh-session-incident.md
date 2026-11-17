# 2026-11-16 DSH 会话与隔离环境事故记录

记录者：DeepSeek Harness（真实执行者）。本记录基于恢复包事故记录、本机后续核查和 2026-11-16 深夜的卷影恢复结果；未经 Codex 独立审计。

## 0. 结论摘要

1. `session-a837aee7` 卡死的直接原因是**工具调用启动的 headless Edge 继承了该次调用的 stdout 管道**：PowerShell 已退出，Edge 仍持有写端，DSH runner 等待 EOF 约 26 分钟。终止 Edge 进程树后 runner 随即退出，机制得到验证。
2. `~/.dsh/sessions` 与 `~/.dsh/storages` 在 23:00:49 被删除并重建，38 条索引会话一度只剩当前 1 条。**后续获得管理员权限后，从 3 个删除前卷影副本恢复了 93 条会话文件（约 140.7 MB），38 条索引会话全部找回（38/38）**；`session-a837aee7` 恢复到 2026-11-16 17:00:43。
3. 仍缺失的是 `session-a837aee7` 在 17:00 之后写入的事件，包括 22:40–23:00 的 ACT-01 编排和用户最后输入；23:12 的卷影为删除后快照，不含该尾部。NTFS 底层恢复尝试未完成（TSK 探针在枚举阶段超时，未取得文件）。
4. 用户级持久环境变量（HKCU `Path`、`TEMP` 等）完好；“环境变量被清空”是会话内进程环境或 `$env:DSH_HOME` 误用，不是注册表被破坏。
5. `tools/isolated-lab/` 已落地并复验：2026-11-16 23:57 无浏览器运行 11 项 PASS + 1 条“未附加浏览器”WARN，真实 home session 文件数不变；此前带浏览器版本 13 项 PASS。它仍是进程级隔离，不是 OS 沙箱。

## 1. 时间线（本机时间，2026-11-16）

| 时间 | 事件 |
|---|---|
| 21:05:05 | DeepSeek Desktop 主进程启动；21:05:07 desktop host 启动（PID 3980） |
| 21:05:58 | Ollama 0.40.16 服务启动，报告 `total blobs: 6` |
| 21:15:24 | MiniCPM5-2B 首次加载（Ollama server log） |
| 22:08:32 | MiniCPM5-2B 再次加载；`/api/chat` 调用持续到 22:18:28（共 2052 次） |
| 22:40 前后 | ARLS ACT-01 编排开始：创建 `Downloads\ARLS_ACT_01_DEV_2026-11-16`、独立 `dsh_home`、`act01` profile、Edge/CDP 脚本 |
| 22:44:17 | 编排会话执行 pwsh：`Start-Process msedge --headless --remote-debugging-port=9222 --user-data-dir=...\edge_profile`，随后 curl 探测；该调用自此不再返回 |
| 22:44:31 | 被测实例（3081）创建空会话 `session-4574c260`（只有 header，无 prompt） |
| 22:47:00 | `%USERPROFILE%\profiles\act01\cordis.patch.yml` 被写入（home 解析错误的旁证） |
| 22:53:03 | 出现 `%USERPROFILE%\profiles`（DSH_HOME/HOME 被指到用户根目录的旁证） |
| 23:00:49 | `~/.dsh/sessions`、`~/.dsh/storages` 被删除后重建；出现空会话 `session-ef3ef676` |
| 23:00:53 | 当前会话 `session-87bd50bd` 开始，`workspace.json` 由 host 内存重写（仍保留旧会话 id） |
| 23:07 左右 | 发现 PID 7600（工具运行器）仍存活，但 PowerShell 子进程已退出；headless Edge 24848 仍在监听 9222 |
| 23:11 | 终止 Edge 进程树；PID 7600 在 taskkill 前自行退出（管道 EOF 释放），9222 关闭 |
| 23:11:21 | `~/.ollama/models` 与 `manifests` 目录以空目录重新出现（模型移除后的重建时间上界） |
| 23:39 | 以管理员权限枚举到 4 个卷影副本，并开始复制 sessions/storages |
| 23:39–23:40 | 从 3 个删除前卷影复制 93 条会话（140.7 MB）；91 条写回活 home（跳过 2 条活动会话） |
| 23:57 | `tools/isolated-lab` 无浏览器复验：11 PASS + 1 WARN，停止后端口释放 |

## 2. 证据

### 2.1 管道继承导致永久挂起
- 运行器 PID 7600 命令行：`...dsh-subprocess-local/lib/runner.js -- powershell.exe ... Start-Process ... --remote-debugging-port=9222 ...`；其子进程列表为空，说明 PowerShell 早已退出，等待的是管道 EOF。
- `http://127.0.0.1:9222/json/list` 当时返回用户真实浏览器页面（claude.ai OAuth、chatgpt.com、MetaMask、Edge 同步提示），而该 Edge 的 `--user-data-dir` 指向 `Downloads\ARLS_ACT_01_DEV_2026-11-16\edge_profile`（535 MB，含用户扩展），并非全新 profile。
- 终止 Edge 进程树后，PID 7600 在 taskkill 执行前自行退出；9222/3081/9300 随后无监听。

### 2.2 目录删除与卷影恢复
- `Get-Item ~/.dsh/sessions`、`~/.dsh/storages`：CreationTime 2026-11-16 23:00:49；`~/.dsh` 本身创建于 2026-10-11。
- `Repair-SessionIndex.ps1` 预演（删除后）：索引 38 条，transcript 存在 1 条，缺失 37 条。
- 管理员权限下 `Win32_ShadowCopy` 返回 4 个卷影：
  - 2026-11-15 20:06:02（HarddiskVolumeShadowCopy4）
  - 2026-11-16 13:12:40（HarddiskVolumeShadowCopy5）
  - 2026-11-16 19:43:00（HarddiskVolumeShadowCopy6）
  - 2026-11-16 23:12:36（HarddiskVolumeShadowCopy7，删除后）
- 前三个卷影的 `.dsh\sessions` 各含 15 个项目目录、91 个会话文件；23:12 卷影只有 1 个目录、2 个会话。
- 从卷影复制并合并后：93 条会话文件、140,670,961 字节、0 解码错误；`session-a837aee7` 在三个删除前卷影中都存在（1.8 MB / 7.8 MB / 11.0 MB，依次增长）。
- 恢复后 `Repair-SessionIndex.ps1` 预演：38 条索引全部有 transcript（38/38），missing=0。

### 2.3 仍缺失的尾部
- 卷影 3 的最后快照时间为 19:43；`session-a837aee7` 在该快照中的最后事件为 2026-11-16 17:00:43（11,683 个事件）。
- 17:00 之后的活动（22:40–23:00 的 ACT-01 编排、用户最后输入）写入的是已被删除的最终版本，未进入任何卷影。
- 23:12 卷影创建于删除之后，不含旧文件。
- TSK 4.15.0 只读探针能打开 `\\.\C:` 并列出根目录，但在逐级枚举 `Users` 目录时超时（每次 `fls` 约 45 秒，600 秒预算内未到达 `.dsh`），未取得删除文件的 MFT/数据运行；NTFS 底层恢复仍未完成。

### 2.4 用户级环境变量
- 注册表 `HKCU\Environment` 的 `Path` 完整，机器 PATH 长度 371，`TEMP/TMP` 正常；“环境变量被清空”不是注册表损坏。

### 2.5 模型状态（与本次事故相邻的独立发现）
- Ollama 0.40.16 在 21:05–22:18 期间真实加载 `openbmb/minicpm5-2b:latest` 并处理 2052 次 `/api/chat`；G1.2r trace 记录 digest `71a44e98…`。
- 2026-11-16 23:15 与 23:56 检查时 `ollama list` 为空、模型 blobs=0；`~/.ollama/models` 目录于 23:11:21 以空目录重建。
- 模型移除发生在 22:18:28 之后、23:11:21 之前，未发现 `ollama rm` 或删除日志，执行者与原因 `UNKNOWN`。不能写成“从未安装”。

## 3. 根因分析

### 3.1 工具调用永久挂起（已证实）
`Start-Process` 启动的 Edge 继承了父 PowerShell 的 stdout/stderr 句柄。PowerShell 退出后 Edge 仍持有写端，`dsh-subprocess-local` 的 runner 读不到 EOF，`await` 永不结束；前台调用又未触发超时/转后台，表现为“永远执行中”。这与“环境变量清空”无关，是进程/管道生命周期问题。

### 3.2 transcript 消失（高置信，执行者无法进一步归因）
`sessions`/`storages` 在 23:00:49 被整体删除重建，同一时间线里 `~/.dsh/profiles` 被清空、`%USERPROFILE%\profiles` 被误建。最符合证据的解释是：编排会话在做“隔离/重置”时，把 DSH shell 里的 `$env:DSH_HOME` 当成测试实例的 home（实际 `dsh-shell-env` 每次调用都注入**真实** home），清理命令打到了真实 home；会话自己的 transcript 也在被删之列。不能排除人工删除，但没有发现人为操作记录。

同一误用也可解释“环境变量被清空”：为模拟干净实例而执行的 `Remove-Item Env:*` / `$env:PATH=''` 只影响该次子进程，后续命令因 PATH 缺失报错，但注册表未变。

### 3.3 恢复可行性的修正
恢复包原判断“37 条不可恢复”只在**未检查卷影副本**的前提下成立。管理员权限下检查到删除前卷影，转录本主体可恢复；该结论已在本机更新，原始判断保留为历史记录。

## 4. 已采取的处置

- 终止 headless Edge（PID 24848 及子进程）与残留 runner；确认 9222/3081/9300 无监听。
- 新增 `tools/isolated-lab/`：独立 home/profile/端口/workspace/一次性浏览器 profile，分离启动避免管道继承，显式重建 PATH，`Test-LabIsolation.ps1` 自检。已复验（2026-11-16 23:57，无浏览器）：11 PASS + 1 WARN，停止后端口释放，真实 home session 文件数不变。
- 管理员权限下枚举并复制删除前卷影，恢复 93 条会话文件；91 条写回活 home（跳过 2 条活动会话），保留 `C:\dsh-lab\recovered\` 副本、清单和渲染文本。
- 新增 `Repair-SessionIndex.ps1`（默认预演，`-Apply` 前要求关闭应用并自动备份）。当前 38/38 已齐，无需执行 `-Apply`。
- 未删除任何用户数据；误建的 `%USERPROFILE%\profiles` 已移到 `C:\dsh-lab\act01\evidence\stray-profiles-20261116` 备查；`edge_profile` 535 MB 副本与 `cookies.txt` 保持原样，待用户决定。
- 模型移除：未重新安装、未启动 ACT-01；`ollama list` 空的状态如实记录，移除原因待查。

## 5. 未解决与风险

- `session-a837aee7` 在 2026-11-16 17:00:43 之后的尾部（含 ACT-01 编排与用户最后输入）仍缺失；NTFS 底层恢复未完成，不能宣称绝对不可恢复。
- `edge_profile` 是用户浏览配置副本（含扩展与登录态），属于敏感残留，建议删除；`cookies.txt` 含本地 `dsh-auth-*` cookie，仅限本机使用。
- 模型已从本机移除，ACT-01 模型侧未就绪；需重新安装并核对 digest。
- 真正的 OS 隔离不可用：Windows Sandbox 功能未安装、无 WSL 发行版、Docker daemon 未运行，均需管理员权限/重启；在此之前 T05 应记 `BLOCKED`。
- 正式开展 ACT-01 前仍需 Research Console preflight，并确认被测实例为 Minimal preset、workspace-write、人工审批、工具 schema 未继承全局插件。
- 恢复包 `Preflight-GitRecovery.ps1` 存在一个 PowerShell 兼容缺陷：`git bundle verify` 把 “is okay” 写到 stderr，脚本在 `$ErrorActionPreference='Stop'` 下将其当成终止错误，最终退出码 1；bundle 已生成，手动 `git bundle verify` 通过。该缺陷已在恢复报告记录，未修改原脚本。

## 6. 变更与证据位置

- `tools/isolated-lab/`（新增，未修改既有代码）
- `experiments/reports/2026-11-16-session-env-incident.md`、`2026-11-16-salvage-inventory.md`（DeepSeek 现场记录）
- `C:\dsh-lab\recovered\admin\shadow-1..4\sessions`（卷影副本）
- `C:\dsh-lab\recovered\restored-home\`（合并后的 93 条会话、storages、inventory.csv）
- `C:\dsh-lab\recovered\sessions-md\00-index.md`、`session-a837aee7.md`
- `C:\dsh-lab\recovered\admin\tsk\tsk-recover.log`（TSK 探针记录）
- `C:\dsh-lab\recovered\isolated-lab-selftest-2026-11-16.log`
- 本记录（新增）
