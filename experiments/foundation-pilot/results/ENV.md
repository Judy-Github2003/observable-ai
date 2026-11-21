# OA-NEXT-01 环境与模型记录

日期：2026-11-17。执行者：DeepSeek Harness。

## 硬件与系统

| 项 | 值 |
|---|---|
| OS | Windows 11 专业版，build 26200 |
| CPU/内存 | Intel Core i7-14650HX（16 核/24 线程）；31.7 GB RAM |
| 磁盘 C: | 约 430 GB 空闲（2026-11-17 14:04） |
| Ollama | 0.40.16，`127.0.0.1:11434` |
| 模型 | `openbmb/minicpm5-2b:latest`，Q4_K_M，1.6 GB |
| 模型 digest | `71a44e98400fbd490d66332a0ebba31143479a714987c13f45c82e94025cbfa7` |
| 历史 digest 对比 | 与 G1.2r 记录的 `71a44e98…` **一致**，未发生模型版本变化 |
| 模型路径 | `%USERPROFILE%\.ollama\models`（OLLAMA_MODELS 未覆盖） |

## 2026-11-17 运行环境修复

- 恢复模型后第一次 `/api/chat` 返回 500；Ollama server log 明确：
  `error starting llama-server: llama-server binary not found`。
- `%LOCALAPPDATA%\Programs\Ollama` 只剩 `ollama app.exe`/`ollama.exe`，`lib\ollama\llama-server.exe` 缺失。
- 使用 `%USERPROFILE%\Downloads\OllamaSetup.exe`（同版本 0.40.16，1.86 GB）静默重装，恢复 `llama-server.exe` 与 ggml DLL；**未删除、移动或重建模型目录**。
- 重装后 `/api/chat` 成功；模型清单与 digest 如上。
- 未下载 Q8、未微调、未替换模型。

## 直接 API 冒烟

- 文本冒烟：HTTP 200，`done_reason=stop`；关闭 thinking 后 content 非空。
- 工具 schema 冒烟：`record_observation(text: string)` 返回合法 `tool_calls`，
  只记录 JSON，不执行任何命令/文件操作。
- 详见 `C:\dsh-lab\oa-next01\model-smoke.json`。
- 结论：`MODEL_READY`、`API_TOOL_READY`（仅 schema 层）。

## 隔离环境（ACT-01 门槛）

- 测试 home：`C:\dsh-lab\act01\home`；profile：`act01`；端口 3081 仅 loopback。
- 2026-11-17 14:02 复验：
  - `DSH_HOME` 与真实 home 分离：PASS；
  - 端口仅 127.0.0.1：PASS；
  - 真实 home session 文件数 95 → 95：PASS；
  - `agent-preset-registry default: minimal`：配置层 PASS；
  - `approval: ask` / `sandbox: workspace-write` 预设存在于配置；
  - 11 PASS + 1 WARN（未附加浏览器），exit 0。
- 未验证：真实请求 header 的工具 schema、逐命令人工审批通道、Minimal preset 的运行时实际工具列表。
- 未启动 Edge/CDP；未使用真实浏览器 profile；未修改真实 `~/.dsh`。
