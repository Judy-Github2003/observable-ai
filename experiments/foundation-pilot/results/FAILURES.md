# OA-NEXT-01 失败、限制与协议偏离

## 1. GitHub push 阻塞

- `git push` 失败：`fatal: could not read Username for 'https://github.com': No such file or directory`（exit 128）。
- `gh auth status` 报 token invalid；Git Credential Manager 无 GitHub 账号；无 SSH key。
- 影响：恢复分支收尾 commit `a8b8e71` 与研究分支 `d2de90e`/结果提交只在本地；远端 recovery 分支仍是 `53c8f02`，PR 未更新。
- 未尝试 force push、未改历史。

## 2. Ollama runner 缺失

- 模型恢复后第一次 `/api/chat` 返回 500；日志为 `llama-server binary not found`。
- 修复：用同版本 0.40.16 安装包静默重装，恢复 `llama-server.exe` 与 ggml DLL；模型目录未删除、未移动。
- 这属于运行环境修复，不是模型版本变化；digest 仍为 `71a44e98…`。

## 3. ACT-01 只到配置层

- 已验证独立 home、loopback 端口、真实 home 95→95 未变、模型路由、`default: minimal` 配置、approval ask / workspace-write 预设。
- 未验证真实请求 header 的工具 schema，也无法取得逐命令**人工**审批通道；按安全规则没有启动 Edge/CDP。
- T01/T03/T04 记 `BLOCKED`，不是“模型失败”。T05 无 OS 沙箱，仍 `BLOCKED`。

## 4. A/B 模型结果

- A 解析失败 4 次（dev 2、heldout 2，均为 code_behavior q01）：模型没有输出 JSON 整数数组。
- B 对同一批题没有超过 A：dev 6/54 vs 11/54，heldout 4/54 vs 13/54；把原始观察塞进 512-token 提示反而干扰小模型。
- 这说明“普通检索”在当前提示与预算下没有形成可用经验，不是“C 一定更好”的证据。

## 5. C 的任务过易

- C 在两个 seed 上都是 54/54；原因是生成器家族可被确定性反演（传递闭包、模 p 仿射参数枚举、过滤+仿射枚举），不是通用学习能力。
- 本轮无法区分“系统真正形成新知识/程序”与“对窄生成器求逆”；需要更难的未见结构、噪声、组合和不可完全观测任务。
- C 使用 0 次模型调用，A/B 每问 1 次；计算预算不匹配，不能声称效率优势。
- C 的 accepted state 有来源/验证/版本，但这只是可追溯性，不等于学习迁移。

## 6. 其他限制

- heldout seed 由开发者选择，标记 `DEVELOPER_HELDOUT`，不是独立闭卷；ChatGPT/独立考官尚未用新 seed 复核。
- 2 worlds × 域是小样本，不能做显著性推断；不把 100% 当作统计结论。
- 未执行 T05/T06/T07；未执行全量 Gradle 回归（按任务时间预算原则只做定向编译/任务）。
- 未删除 `edge_profile` 或 `cookies.txt`（需用户授权）。
