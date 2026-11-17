# OA-NEXT-01 执行报告（Observable AI）

- 任务：OA-NEXT-01；执行者：DeepSeek Harness；日期：2026-11-17。
- 代码基线：`research/OA-NEXT-01`，从恢复分支 `recovery/2026-11-16-project-records` 的 `a8b8e71` 创建。
- 阶段代码提交：`d2de90e`（`feat(foundation): add OA-NEXT-01 four-domain pilot loop`）。
- 结果提交：本目录随 `docs(foundation): record OA-NEXT-01 pilot results` 提交（SHA 见 `GIT.md`/最终答复）。

## A. RECOVERY-01 收尾 — PASSED（本地）/ push BLOCKED

| 检查 | 结果 |
|---|---|
| A-016 `.md` 类型修正 | PASSED：`artifact_type=report_markdown`，SHA-256 `fc6847dcab265ce3…` |
| 用户 home 路径规范化 | PASSED：索引与公开文档改为 `%USERPROFILE%`/`%LOCALAPPDATA%` 占位符；`Test-ArtifactIndex.ps1` 显式展开后 75/75 通过 |
| Git bundle verify stderr 修复 | PASSED：`tools/git/Preflight-GitRecovery.ps1`；健康 bundle exit 0、非法内容/缺失 bundle 非 0；自检 PASS |
| 原 bundle 保留 | PASSED：未覆盖 `observable-ai-pre-recovery-20261116-235547` |
| 恢复分支 commit | `a8b8e71`（`fix(recovery): finalize Git audit and artifact index`） |
| push | **BLOCKED**：GitHub 凭据失效（`gh` token invalid，GCM 无账号）；`git push` 返回 128，未 force push、未改历史、未合并 main |

## B. MiniCPM5-2B Q4 恢复 — MODEL_READY / API_TOOL_READY

- 模型已恢复：`openbmb/minicpm5-2b:latest`，Q4_K_M，digest `71a44e98400fbd490d66332a0ebba31143479a714987c13f45c82e94025cbfa7`，与历史记录一致。
- 运行环境修复：Ollama 0.40.16 的 `llama-server.exe` 缺失导致 500；使用同版本安装包恢复 runner，未动模型目录、未下载 Q8、未微调。
- 文本与工具 schema 冒烟通过；工具调用只记录、不执行。
- 详见 `ENV.md`、`C:\dsh-lab\oa-next01\model-smoke.json`。

## C. ACT-01 安全冒烟 — BLOCKED

- 已验证（配置/隔离层）：独立 `C:\dsh-lab\act01\home`、只绑 127.0.0.1、真实 home 95→95 未变、模型路由 ollama、`agent-preset-registry default: minimal`、approval ask / workspace-write 预设存在。
- 未验证：真实请求 header 的工具 schema；逐命令**人工**审批通道；Minimal preset 运行时实际工具列表。
- 按 `06_SAFETY.md`，在无法验证这些条件时不启动 Edge/CDP、不改真实 DSH home；因此 T01/T03/T04 记 `BLOCKED`，T05/T06/T07 未运行。
- 原 ACT-01 参考包 SHA-256：`6936e4d89da3fd9d9c2943c7784f1ce7bc0488f50bc634984cf11e606fddd1e2`（匹配）。
- 明细见 `ACT01_SMOKE.csv`。

## D. FL-PILOT-01 — COMPLETED（开发 + DEVELOPER_HELDOUT）

### D.1 协议

- 生成器：`foundation_microbench.py`（外部考官）；评分器：`score_microbench.py`（只读 sealed）。
- 4 域 × 2 worlds/域，dev seed 24117；heldout seed 99173，冻结后运行一次。
- A：零历史；B：同一训练事实的原文检索；C：Kotlin `FoundationPilot` 归纳→验证→接受状态→回答，不使用模型。
- A/B：同一模型、`num_ctx=4096`、`temperature=0`、`num_predict=512`、`think=false`；C 模型调用 0 次（计算预算不匹配，见限制）。
- 冻结记录：`FREEZE.json`（代码/生成器/评分器/prompt/模型 digest）。

### D.2 结果（exact match；每域 16/16/12/10 题，两个 world 合计）

| split | arm | semantics | logic | mathematics | code_behavior | total |
|---|---|---:|---:|---:|---:|---:|
| dev (24117) | A | 5/16 | 6/16 | 0/12 | 0/10 | 11/54 (20.4%) |
| dev | B | 4/16 | 2/16 | 0/12 | 0/10 | 6/54 (11.1%) |
| dev | C | 16/16 | 16/16 | 12/12 | 10/10 | 54/54 (100%) |
| heldout (99173) | A | 6/16 | 6/16 | 1/12 | 0/10 | 13/54 (24.1%) |
| heldout | B | 2/16 | 2/16 | 0/12 | 0/10 | 4/54 (7.4%) |
| heldout | C | 16/16 | 16/16 | 12/12 | 10/10 | 54/54 (100%) |

- A 解析失败：dev 2、heldout 2；B 解析失败 0。
- C 接受 8/8 个 world 的规则/程序（dev 与 heldout 各 8），拒绝 0；`LEARNING_STATE.jsonl` 可追溯。
- **解释边界**：C 的 100% 是因为生成器家族可被确定性反演（闭包/仿射参数/过滤+仿射），不是通用学习能力；本轮不足以区分“真正学习机制”与“窄家族求逆”。B 未超过 A，说明把原始样例塞进 512-token 提示没有形成可用检索收益，反而干扰了小模型。
- 任务对 C 过于简单；下一轮必须换成不能靠一次参数枚举解决的未见结构/噪声/组合任务，并让 C 与 B 使用匹配的模型预算。

### D.3 状态

- 考官自检：PASS。
- 开发集：COMPLETED。
- heldout：COMPLETED，标记 `DEVELOPER_HELDOUT`（seed 由开发者选择，不是独立闭卷）。
- 阴性/限制：见 `FAILURES.md`。

## 交付

`FOUNDATION_RESULTS.csv`、`LEARNING_STATE.jsonl`、`MODEL_TRACES_dev.jsonl`、`MODEL_TRACES_heldout.jsonl`、`RESULTS_MANIFEST.json`、`ENV.md`、`GIT.md`、`ACT01_SMOKE.csv`、`FAILURES.md`、`NEXT_RECOMMENDATION.md`、`SHA256SUMS.txt`，以及最终 ZIP `ObservableAI_NEXT_01_Results_2026-11-17.zip`。
