# DSH CLI 会话在桌面客户端中的可见性

日期：2026-11-04（Asia/Shanghai）。安装版本：DeepSeek Harness `0.2.1-rc.5`，使用 `C:\dsh`。

**验证范围：安装包源码、随附说明和项目会话落盘元数据。未完成桌面 UI 点开会话的验证。用户按 Esc 停止本次计算机操作后，界面操作已经停止，没有继续退出或重启客户端。** 本报告没有将源码支持的行为写成已经通过的 UI 验收。

## 已确认的会话

| 用途 | Session ID | 证据 |
| --- | --- | --- |
| V0 第一次苹果问答 | `session-9a9aa4c7-4815-4d45-a1d0-cb730cccce81` | 项目 `runs/v0-live-events.jsonl`；另已只读核对 DSH 日志首个 header frame |
| V0 第二次苹果问答 | `session-126da355-174c-4d56-9999-1a16030ef501` | 同上 |
| V1 前的项目背景与学习方案咨询 | `session-f2eb86cb-8fdb-4ba3-985f-fc50277c5476` | 项目 `runs/v1-teacher-advice-events.jsonl` 的 `teacher_result` 事件，时间 `2026-11-04T08:31:52.238973100Z`（北京时间 16:31:52），事件 ID `bdba4d50-ff18-4fbc-9ad6-30a049bfead7` |

项目背景咨询已经成功返回建议；其记录状态仍为 `unverified`，建议是否成立需通过实现和独立测试判断。该会话 ID 从项目事件读取，本报告没有据此声称已在桌面客户端打开它。

两个 V0 会话的 header 都记录 `cwd=C:\自研逻辑模型\runs\teacher-workspace`、格式版本 `4`；创建时间分别为北京时间 16:25:57 和 16:26:01。已确认的文件位置为：

```text
%USERPROFILE%\.dsh\sessions\
  --C-~81EA~7814~903B~8F91~6A21~578B-runs-teacher-workspace--\
    <session-id>\session.v4.jsonl.zstd
```

（原记录中的本机用户目录已替换为 `%USERPROFILE%`；会话 ID 保留，用于本机核对，不含凭据。）

此前两个 V0 问答只用于确认转发与记录链路；项目背景咨询是另一次独立调用。不能把前两次苹果问答本身算作对项目方案的评审。

## 源码确认的机制

以下位置均在安装包 `C:\dsh\resources\app.asar` 内；归档内路径可通过 Electron 自带 Node 的文件系统读取，不要求解包、导入或修改会话。下列行号对应本机已安装版本。

| 归档内文件及行号 | 确认内容 |
| --- | --- |
| `dsh/node_modules/@deepseek-ai/dsh-home-paths/README.zh.md:34–39` | DSH_HOME 未显式设置时默认使用 `~/.dsh`。本次执行环境未设置 DSH_HOME。 |
| `dsh/node_modules/@deepseek-ai/dsh-base/cordis.patch.yml:138–141` | 默认持久化根目录为 `dshHomePath('sessions')`。因此在相同 home 与存储配置下，CLI 和 Desktop 可读取同一份持久历史。 |
| `dsh/node_modules/@deepseek-ai/dsh-headless/README.zh.md:72–74,96–100` | headless 默认创建新 ID，记录工作目录，结束前 flush；`--session-id` 可在受限条件下延续已有会话。 |
| `dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js:1959–2002` | 列表同时读取 live 和 persisted 会话，为带 cwd 的冷会话生成条目，不要求其仍由 CLI 进程持有。 |
| `dsh/node_modules/@deepseek-ai/dsh-api-session-controller/lib/client.js:3245–3259,3961–3963,4289–4296` | 客户端刷新或重新连接时重新读取会话 baseline。另一 headless 进程创建的会话不能假定会实时推送到已经打开的客户端。 |
| `dsh/node_modules/@deepseek-ai/dsh-client-ui-workspace/lib/client.js:1027–1044` | 未归属任何 Workspace 的普通可见会话列入“未分组”。 |
| `dsh/node_modules/@deepseek-ai/dsh-workspace/lib/index.js:111–128,641–653` | 工作区成员关系由显式 sessionIds 记录；挂接时要求 cwd 与工作区规范路径匹配。新建工作区本身不保证收纳既有 CLI 会话。 |
| `dsh/node_modules/@deepseek-ai/dsh-client-ui-workspace/lib/client.js:4731–4739,4754–4777` | 提供复制 Session ID；本机视图文案包含“未分组”“单列表”“最近更新”。 |
| `lib/main.js:12765–12779` | “刷新页面”和重启 Host 菜单仅在 development 条件下注册，不能保证发布版存在该菜单。 |
| `lib/main.js:13055–13058` | `dsh://open` 或 `dsh://open/` 只聚焦主窗口，没有在此提供带 Session ID 的跳转。 |

本机未找到面向用户的“按 Session ID 打开”或“导入会话日志 ZIP”入口。随附 `dsh-session-log-export/README.zh.md:12,28,55–60` 说明的是日志下载；`dsh-session-reference/README.zh.md:32,136` 的 `dsh-session:` mention 是快照引用，并非恢复、订阅或实时链接。无需手动移动或复制 session 文件来完成正常历史读取。

## 保守的人工查看步骤

以下供用户后续自行操作，本次没有继续执行：

1. 等相关 CLI 调用结束，在现有 DSH 客户端选择“视图选项 → 分组方式 → 单列表”，再选择“排序方式 → 最近更新”。使用工作区分组时，先查看“未分组”。
2. 寻找上述时间的会话；标题可能由模型生成，也可能显示“未命名”。点开后，用会话行 `…` 菜单的复制 Session ID 操作与上表核对。查看已有历史不需要再次发送消息。
3. 如果列表仍没有新会话，在所有工作结束且用户愿意时，由用户自行通过应用菜单“退出”后重新打开客户端，以重建列表连接；仅关窗口可能隐藏到托盘。本次没有执行退出或重启。
4. 不把搜索“苹果”无结果当作会话不存在的证据：默认配置可能只搜索标题与工作区名称，标题未必包含问题文本。

添加 `C:\自研逻辑模型` 工作区不是查看这些历史的前提，也不能保证归并它们：已确认的 V0 cwd 是 `runs\teacher-workspace` 子目录，成员关系并非按父目录自动收纳。

本次未验证跨进程实时跟随正在运行的 CLI，也未验证桌面端继续驱动同一会话的完整流程。已确认的是持久化存在及客户端源码具备冷读取路径；UI 可见、可打开仍待用户后续实际确认。

调查过程中未读取 `.credentials.yaml` 或授权 token，也未读取其他用户会话正文、修改 DSH 全局配置或会话存储。
