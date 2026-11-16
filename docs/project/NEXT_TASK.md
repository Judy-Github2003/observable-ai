# 当前下一任务

## 任务名称
RECOVERY-01：Observable AI 项目档案与 Git 基线恢复

## 唯一目标
恢复项目档案和强制 Git 工作流，不开发新学习功能，不运行新的学习实验。

## 完成标准
1. DeepSeek 在本机核对仓库根目录、HEAD、分支、remote、工作区、提交数。
2. 生成仓库外 `git bundle` 备份；保存 tracked diff 与 untracked inventory。
3. 读取 `C:\dsh-lab\recovered\arls-inventory.csv`，建立 artifact index。
4. 在仓库 `docs/project/`（如已存在则先合并，不覆盖）建立项目主档案。
5. 建立 Git 强制政策文档。
6. 只提交恢复文档，不修改研究源码、冻结数据或历史结果。
7. 在 `recovery/2026-11-16-project-records` 分支提交并推送（remote 可用时）；不直接提交 main，不 force push。
8. 报告 commit SHA、push 结果、变更文件、未解决问题。

## RECOVERY-01 完成后
1. 复核 ACT-01 隔离与模型安装状态。
2. 设计并执行 ACT-01 安全 preflight / 冒烟测试。
3. 设计四大基础能力学习的共同评测框架：逻辑、数学、代码、语义。
4. 以固定 MiniCPM 权重对照零历史、普通检索、Observable AI 学习机制三组。
5. 只有在真实迁移收益可复现后，才扩大课程和学习机制。

## 当前明确不做
- 不做模型权重微调或 Q8 升级。
- 不重构完整 Agent。
- 不启动新的 AST/DSL 提示词优化。
- 不删除或重跑历史冻结实验。
- 不清理 Git 历史。

## RECOVERY-01 完成状态（2026-11-16 本机执行）

- 已完成只读 Git 审计、bundle 备份、ARLS 清单核对、`docs/project/` 档案恢复和恢复分支提交。
- 恢复分支：`recovery/2026-11-16-project-records`；main 未改写、未 force push。
- 本机核对补充：DSH 会话已从卷影副本恢复 93 条，38/38 索引可解析；`session-a837aee7` 尾部（17:00 之后）仍缺失。
- Ollama：模型曾被真实加载并调用（21:15–22:18，2052 次 `/api/chat`），现已移除；ACT-01 模型侧未就绪。
- 下一步仍按本文件“RECOVERY-01 完成后”执行；本轮不开发新功能。

