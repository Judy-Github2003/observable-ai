# Observable AI Research Console

## 唯一状态源

- `tasks.json`：任务状态、授权、证据链接、补丁额度。
- `directions.json`：A–Z 方向定义；不保存任务状态。
- `ideas.json`：原始想法；初始均为 `PROPOSED`。
- `events.jsonl` + `history/<event_id>.json`：追加式状态事件与 before/after 快照。
- `decisions.md`：研究负责人决策记录。
- `research-constitution.md`：长期原则。
- `generated/WORKSTATE.md`：由控制台状态生成的交接视图。
- `experiments/representation-evolution-e3/WORKSTATE.md`：人的交接视图，必须与结构化状态同步；`console_cli.py check-workstate` 会报告分叉。

## 启动

```powershell
$env:RESEARCH_CONSOLE_TOKEN = "<your-local-token>"
python tools/research-console/server.py --port 8765
```

服务只绑定 `127.0.0.1`，当前地址：`http://127.0.0.1:8765`。

健康检查：

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8765/api/health
```

服务不是 Windows 服务；会话结束或机器重启后需要重新执行启动命令。数据在 Git 文件中，重启不会丢失。

## 权限

读取公开；写入必须同时提供：

- `X-Research-Token`（或表单 `token`）：本地 token；
- `X-Research-Role`（或表单 `role`）：`planning` / `execution` / `authorization` / `acceptance`。

角色只是本地单用户流程隔离，不是密码学身份系统。执行更新不能替研究负责人验收；`ACCEPTED`/`ACCEPTED_LIMITED` 只能由 acceptance 角色写入。

## API

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/health` | 数据校验、WORKSTATE 同步、Git HEAD、授权状态 |
| GET | `/api/state` | 全量方向与任务 |
| GET | `/api/tasks?direction=&status=&q=` | 任务筛选 |
| GET | `/api/task/<id>` | 单任务 |
| GET | `/api/ideas` | 想法列表 |
| GET | `/api/events` | 追加事件 |
| POST | `/api/ideas` | 新增 `PROPOSED` 想法（planning） |
| POST | `/task/<id>/edit` | 编辑规划字段（planning） |
| POST | `/task/<id>/status` | 状态迁移（按角色） |
| POST | `/api/authorization` | 记录任务授权 action（authorization） |

HTML 页面：`/`、`/map`、`/task/<id>`、`/ideas`、`/activity`、`/activity/<event_id>`。

## 状态与执行门禁

```powershell
python tools/research-console/console_cli.py preflight --task <ID>
python tools/research-console/console_cli.py check-workstate
python tools/research-console/console_cli.py sync-workstate --write
python tools/research-console/console_cli.py finish --task <ID> --commit <SHA> --test "<结果>" --report <path>
```

`preflight` 无授权、补丁耗尽或状态不匹配时返回 `REFUSED` 并退出 2。`finish` 只写 `REVIEW`，不会自行验收。

## 数据恢复

所有数据是普通文本文件：

```powershell
git diff -- research-console/
git checkout <old-sha> -- research-console/tasks.json
```

事件/历史目录只追加，不使用数据库或 LocalStorage。服务重启后从文件恢复。

## 限制

- 同机、单用户 token/role，不是完整账号系统，也没有 HTTPS。
- 不得把写入 API 暴露到公网；远程访问需研究负责人另行授权的 VPN/隧道方案。
- 独立闭卷状态仍为 `not_run`；控制台不会把实施者测试升级为独立验收。
