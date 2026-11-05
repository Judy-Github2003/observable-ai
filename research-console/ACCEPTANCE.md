# Research Console 实际验收记录

日期：2026-11-05  
服务地址：`http://127.0.0.1:8765`（仅本机）  
启动方式：

```powershell
$env:RESEARCH_CONSOLE_TOKEN = "<local-token>"
python tools/research-console/server.py --port 8765
```

> 本文件记录实际运行结果；没有伪造截图或浏览器自动化结果。服务是前台开发进程，不是 Windows 服务。

## 数据

- 任务：194 个。
- 研究方向：26 个（A–Z）。
- 初始新增 A–Z 任务：120 个，均为 `EXPLORATORY`/`PROPOSED` 规划状态，未启动。
- A1：`ACCEPTED_LIMITED`，patch `1/1`。
- C1：`ACCEPTED_LIMITED`，patch `1/1`。
- `authorized_action=none`。
- `independent_closed_book=not_run`。
- 测试记录：297/297（B0 173 + E3 124），`implementer_run`。

## 验收清单

| # | 要求 | 实际结果 |
|---|---|---|
| 1 | 浏览器打开首页 | `GET /` 返回 200，页面含 Dashboard |
| 2 | 显示所有方向 | `/map` 含 A–Z 全部 26 个方向 |
| 3 | 任务详情 | `/task/A1`、`/task/C1`、`/task/RES-E01` 返回 200 |
| 4 | A1/C1 状态 | 两页均显示 `ACCEPTED_LIMITED` 与 `1/1` |
| 5 | 新增想法 | 通过 `POST /api/ideas` 新增测试想法；`/ideas` 可见；状态 `PROPOSED` |
| 6 | 修改规划后刷新仍存在 | 修改 `RES-E01.minimal_experiment` 后刷新可见；随后恢复原规划 |
| 7 | 历史前后差异 | `/activity/<event_id>` 显示 unified diff；`history/<event_id>.json` 保存 before/after |
| 8 | 执行端状态一致 | `/api/health` 返回 current C1、授权 none、数据校验通过；CLI 读取同一 `tasks.json` |
| 9 | 无授权拒绝启动 | `console_cli.py preflight --task C1` 输出 `REFUSED: no authorized_action`，退出 2 |
| 10 | WORKSTATE 不分叉 | `console_cli.py check-workstate` 返回 `ok=true`；WORKSTATE 由 `sync-workstate --write` 生成 |
| 11 | 重启后数据仍在 | 终止并重启服务后，测试想法与规划历史仍存在 |
| 12 | 未授权写入/非法迁移被拒 | 无 token 写返回 401；`PROPOSED -> ACCEPTED` 返回 400 |

## API 实测

- `GET /api/health` → 200，`ok=true`，`workstate.ok=true`，`writes_enabled=true`。
- `GET /api/state` → 194 任务、26 方向。
- `POST /api/ideas`（token + planning）→ 200 JSON，idea 为 `PROPOSED`。
- `POST /task/RES-E01/edit`（token + planning）→ 200/303，刷新后存在，事件记录。
- 无 token POST → 401。
- 非法状态迁移 → 400。

## 未完成与限制

- 没有完整账号系统；角色由本地请求头/表单声明，不能抵抗同机恶意进程。
- 没有 HTTPS、VPN 或公网部署；写入接口仅绑定 `127.0.0.1`。
- 没有 Windows 服务托管；机器/会话重启后需手动启动。
- 没有独立闭卷验收；控制台中的 `ACCEPTED_LIMITED` 不改变这一事实。
- 没有外部定时器；`check-workstate` / health 是手动检查入口，不能自动启动新研究任务。

## 恢复

```powershell
$env:RESEARCH_CONSOLE_TOKEN = "<local-token>"
python tools/research-console/server.py --port 8765
```

数据恢复通过 Git：`git diff -- research-console/`、`git checkout <sha> -- research-console/tasks.json`。
