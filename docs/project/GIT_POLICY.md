# Git 强制研发政策

生效：2026-11-16  
适用：Observable AI 全部代码、实验、文档、数据管线和任务包维护。

## 1. Git 是项目事实记录，不是可选备份

- 所有源码变更必须进入 Git。
- 所有正式研究任务必须有任务记录、结果报告和对应 commit SHA。
- 所有影响实验结论的协议、参数、数据生成器、评分器和冻结清单必须版本化。
- 每轮 DeepSeek 执行结束必须给出 `git status`、分支、HEAD、commit SHA 和 push 状态。
- 未提交的工作不能宣称正式交付完成。

## 2. 每轮固定流程

### 开始前
```powershell
git status --short --branch
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git remote -v
git log -5 --oneline --decorate
```
记录任务开始时的 HEAD、工作区状态、当前分支和 remote。

### 开发前
- 每项任务使用独立分支，例如 `research/LE-CORE-01`、`fix/G1-2r-followup`。
- 恢复任务使用 `recovery/2026-11-16-project-records`。
- 不在 main 上直接开发。
- 不覆盖已有未提交改动；不把他人/用户的改动混入当前提交。

### 提交前
```powershell
git diff --check
git status --short
git diff --stat
git diff --cached --stat
```
- 只用明确路径 `git add <path>`，禁止习惯性 `git add -A`。
- 检查 secrets、cookies、浏览器 profile、模型权重、临时数据和大文件。
- 测试没跑就明确记录 `NOT_RUN`；失败就记录真实失败。

### 提交与推送
- 每个逻辑完整的任务至少一个 commit；修复与实验报告可分开提交。
- commit message 使用清晰前缀：`docs(...)`、`fix(...)`、`feat(...)`、`test(...)`、`experiment(...)`、`chore(...)`。
- remote 可用时推送任务分支：`git push -u origin <branch>`。
- 禁止 force push；禁止重写已经共享的历史。
- 研究负责人/独立审查通过后，再合并 main。
- 里程碑冻结时创建 annotated tag，tag 名包含实验编号与版本。

## 3. 绝对禁止

- `git reset --hard`
- `git clean -fdx` / `git clean -fd`
- `git checkout -- .` 或 `git restore .` 用于“清理工作区”
- 删除 `.git` 或重新 `git init` 覆盖现有仓库
- `git push --force` / `--force-with-lease`
- 不检查工作区就切换分支或覆盖文件
- 用 `git add -A` 把不明文件全部提交
- 改写旧实验数据、冻结文件或负结果以追求全绿

## 4. 大文件与敏感资料

默认不提交：
- `.env`、`.env.*`、`*.key`、`*.pem`、API keys、密码、token；
- `cookies.txt`、`dsh-auth-*`、真实浏览器 profile、`edge_profile/`；
- `.dsh/` 会话/存储目录；
- 模型权重（GGUF、safetensors、bin 等）；
- `build/`、`.tools/`、`node_modules/`、Gradle cache、临时运行目录；
- 大型生成数据或未确认许可的数据集。

历史 ZIP 不默认整体进入 Git。对外部归档使用 `ARTIFACT_INDEX.csv` 记录文件名、原路径、大小、SHA-256、用途、许可、对应 commit。若确需版本化大型文件，先评估 Git LFS、许可和仓库体积，取得研究负责人批准。

## 5. Git 与实验可复现性

每个正式实验记录：
- Git commit SHA；
- 模型名称、版本、digest、量化、运行参数；
- 数据集/生成器/评测器 SHA-256；
- 训练/开发/反馈/留出集合边界；
- 原始 events JSONL、失败案例和结果报告；
- teacher / model / system / human 的贡献归因；
- 测试运行状态及耗时。

实验结果必须绑定 commit。代码变化后不得冒充原冻结结果。

## 6. 备份策略

- 每次里程碑冻结创建 Git bundle：`git bundle create <name>.bundle --all`。
- bundle 放仓库外的备份目录。
- bundle 只备份已提交 refs；未提交 diff 和未跟踪文件需单独备份/审查。
- 重要远端分支推送后，仍保留本地 bundle。
- 不把 Git 当成唯一的二进制归档系统。

## 7. 例外
如果 Git 状态异常、remote 与预期不符、分支历史分叉、存在大量未知 untracked/ignored 文件，先停下并报告，不得自行重置、清理或重建。

## 8. 本机执行补充（2026-11-16，RECOVERY-01）

- 本机仓库：`C:\自研逻辑模型`；remote `https://github.com/Judy-Github2003/observable-ai.git`；main HEAD `c5f36c45652443dbf8c230f2e43477c9555ad7f5`；240 commits。
- 恢复分支 `recovery/2026-11-16-project-records` 从 main 创建；main 未被改写，未 force push，未执行 reset/clean/restore。
- 工作区原有 2 个已跟踪文件修改（`research-console/events.jsonl`、`research-console/repairs.json`）和 13 组未跟踪文件/目录在恢复前已存在；RECOVERY-01 未删除、覆盖、重命名或提交它们。
- `.gitignore` 已逐项审查，仅做最小合并：补充 cookies/dsh-auth/.dsh/edge_profile/model 权重等敏感模式；未忽略 `datasets/`、`docs/research/` 或全部 `*.jsonl`。
- 历史上已有被忽略但仍被跟踪的 `.log` 文件（`git ls-files -ci --exclude-standard` 可查）；本轮只在报告中记录，不删除、不重写历史。
- 仓库存在 6 个位于 `%TEMP%` 的 `prunable` detached worktree；本轮未 prune、未删除。
- 恢复包 `Preflight-GitRecovery.ps1` 因 `git bundle verify` 的 stderr 输出在 `$ErrorActionPreference='Stop'` 下被当成终止错误而退出码 1；bundle 已生成，手动 `git bundle verify` 通过（`The bundle records a complete history`）。未修改原脚本。
- 本恢复提交只包含 `docs/project/` 与经审查的最小 `.gitignore` 变更；未提交 `datasets/`、`docs/research/`、`tools/isolated-lab/`、`experiments/reports/` 等未跟踪内容。

## 9. 预检脚本修复（OA-NEXT-01，2026-11-17）

- 原恢复包 `Preflight-GitRecovery.ps1` 的 `git bundle verify` 成功信息写到 stderr，在 `$ErrorActionPreference='Stop'` 下被误判为失败；已报告，未修改原包。
- 仓库跟踪的修复版：`tools/git/Preflight-GitRecovery.ps1`。它把 native git 的 stdout/stderr 作为数据重定向到文件，只用退出码判定成功；不写入仓库、不删除/覆盖既有 bundle，备份目录在仓库外且带时间戳。
- 自检：`tools/git/Test-PreflightGitRecovery.ps1`。健康 bundle → exit 0；截断/损坏 bundle → 非 0；缺失 bundle → 非 0。自检只在 `%TEMP%` 下创建临时仓库，不触碰真实 bundle。
- 本索引核验：`tools/git/Test-ArtifactIndex.ps1`，显式展开 `%USERPROFILE%`/`%LOCALAPPDATA%` 后检查存在性、大小和 SHA-256。
- 原恢复包中的备份：`C:\dsh-lab\recovery-backups\observable-ai-pre-recovery-20261116-235547\observable-ai-all-refs.bundle` 保持原样；本轮只新增修复脚本与提交，不覆盖任何旧 bundle。
