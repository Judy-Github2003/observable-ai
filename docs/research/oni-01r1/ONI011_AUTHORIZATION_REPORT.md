# ONI-01.1 授权记录

Codex 于 2026-11-18 按研究负责人的当前指令，登记机器任务 `ONI-01R1`。负责人在对话中要求正式执行、通过 Research Console 授权、在 GitHub 同步项目并维护中英双语首页。开发范围来自这条指令和所指的任务包。

| 字段 | 记录 |
|---|---|
| 任务包 SHA-256 | `caa6662023fa3720b9671438a4f975f54e4e3c36732d3c08bd84a0f83f2ca4cb` |
| 登记前预检 | `REFUSED: unknown task` |
| 登记方式 | 使用 Research Console 已有专项登记流程，在写锁内校验账本、计算范围摘要、追加负责人授权事件、保存记录 |
| 授权事件 | `ev-1794957773049-6155af` |
| 登记后预检 | `AUTHORIZED_REPAIR: ONI-01R1`，exit 0 |
| 初始 Git HEAD | `25ee635e00c8fc837042a1be53a7e2088dd19344` |
| 全局动作、普通补丁额度 | `none`、`null`。本轮使用单次专项授权 |
| WORKSTATE | `ok: true, problems: []`。原 PACK-02 状态为 PAUSED |

批准的新文件范围为 `src/oni011/`、`tests/oni011/`、`docs/research/oni-01r1/`、`build/oni-01r1/`、`README.md`、`README.en.md` 和 `README.history.md`。上轮源文件、固定 BF16 模型作为只读输入。GitHub 同步限于本任务相关工件和项目双语首页。

运行命令为 `python -X utf8 -B tools/research-console/console_cli.py preflight --task ONI-01R1`。登记后完整预检回执保存在 `build/oni-01r1/authorization_preflight.txt`。
