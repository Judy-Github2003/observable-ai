# OA-NEXT-01 下一轮建议

## 1. 先恢复可复核交付（工程前置）

1. 重新授权 GitHub（`gh auth login` 或 GCM），推送：
   - `recovery/2026-11-16-project-records`（含 `a8b8e71`）；
   - `research/OA-NEXT-01`（含 `d2de90e` 与结果提交）。
2. 打开/更新 PR，让 ChatGPT 按 commit SHA 复核，而不是只看本地报告。

## 2. 科学优先，不扩展 Agent

本轮 C=100% 是窄生成器求逆，不能作为学习能力证据。下一轮应直接攻击可证伪的迁移问题，而不是增加 Agent 功能：

1. **加更难的世界**：加入噪声、冲突证据、部分可观测、组合规则、未见谓词/结构，使“枚举参数”不能一次解决。
2. **独立考官**：由 ChatGPT/独立进程用新 seed 生成 hidden 题，DeepSeek 不接触 sealed；当前 `DEVELOPER_HELDOUT` 只能作内部开发证据。
3. **预算匹配**：C 若用模型提议，必须与 B 有可比调用/token；若 C 用纯符号归纳，必须报告“C 不需要模型”的归因，并做 C-without-rules 消融。
4. **消融与归因**：每个 C 答案记录被复用的 proposal id；删除 accepted state 后重跑，若分数不回退，说明 C 的优势不是来自学习状态。
5. **B 的检索基线要更强**：至少比较“原文全给”“检索 top-k”“结构化摘要”三种；当前 B 只是把全部样例塞进提示，不能代表检索上限。
6. **保持阴性结果**：若 C 在 harder worlds 上没有超过 B，如实记录，不调任务凑差异。

## 3. ACT-01 的前置条件

- 必须先有真实人工审批通道和 request-header 工具 schema 捕获；否则继续 `BLOCKED`。
- T05 必须等 Windows Sandbox / WSL / Docker 等 OS 隔离可用；进程级隔离不算。
- 不要为了跑通 Agent 而降低审批或开放云回退。

## 4. 一句话

先把“可复核的迁移收益”做出来：更难的世界、独立考官、匹配预算、可消融的 C。Agent 扩展不应抢占这条主线。
