# FL-PILOT-01：四领域学习预实验（OA-NEXT-01）

创建：DeepSeek Harness，2026-11-17。任务编号 OA-NEXT-01，阶段 D。

## 目的与边界

固定本地 `openbmb/minicpm5-2b:latest`（Q4_K_M，digest `71a44e98400fbd490d66332a0ebba31143479a714987c13f45c82e94025cbfa7`）权重，比较三臂：

- **A / ZERO_HISTORY**：只给任务说明和问题，不给该随机世界的训练样例。
- **B / RAW_RETRIEVAL**：给同一份 public 训练观察原文，再给问题；不做显式规则/程序构造。
- **C / OBSERVABLE_LEARNING**：不把训练观察塞给模型；由 Kotlin `FoundationPilot` 从观察中归纳候选规则/程序，验证后写入可版本化状态，再用已接受状态回答未见问题。

本预实验只验证科研链路、证据格式和可归因差异，不宣称“学会四门学科”，也不把 C 的高分说成通用智能。生成器家族很窄（符号闭包、模 p 仿射、过滤+仿射），C 能精确反演是该家族的性质，不能外推到开放世界。

## 代码与数据

- `prompts.py`：A/B 的 public prompt 渲染与答案解析。
- `run_model_arm.py`：模型臂 runner；只读 public，不读 sealed；记录 prompt SHA、原始输出、延迟、token 摘要。
- `src/main/kotlin/observable/ai/foundation/FoundationPilot.kt`：Kotlin 学习/验证环路；`SYSTEM_DERIVATION`，不调用模型，不执行模型生成代码。
- Gradle 入口：`gradlew foundationPilot -PpilotArgs="--study;<path>;--questions;<path>;--out;<dir>"`。
- 外部考官工具来自 `ObservableAI_NEXT_01_2026-11-17.zip` 的 `tools/`：
  - `foundation_microbench.py`（生成 public/sealed 分离数据）
  - `score_microbench.py`（只读 exact-match 评分）
  - `selftest.py`（考官自检）

开发数据与 sealed 答案放在仓库外 `C:\dsh-lab\fl-pilot-dev`；仓库只保存脱敏结果、协议和源码。隐藏答案不进入模型/Agent 工作区。

## 模型调用预算

- 同一模型、同一 `num_ctx=4096`、`temperature=0`、`num_predict=512`；
- MiniCPM5 的 hidden thinking 在 512 token 内可能吞掉答案，因此 A/B 统一设置 `think=false`，让答案进入 `message.content`；
- A/B 每问一次调用；C 不调用模型（0 次）。C 与 B 的计算预算**不匹配**，这是本轮明确限制；比较的是“系统级归纳+验证”与“原始模型/原始检索”，不是效率优势；
- 同一 world 的 A/B/C 使用完全相同的 public 训练观察与问题；C 不读取 sealed。

## 防泄漏与归因

- `sealed/answers.jsonl` 只由评分器读取；runner 参数不指向 sealed。
- C 的每个答案记录 `proposal_id`；接受状态包含 `origin=SYSTEM_DERIVATION`、`verifier_id`、`validation.passed`、`tested`。
- 若 C 的答案只是把训练样例塞回提示词，则不能算系统学习；本实现不把观察传给模型，C 的预测来自已接受规则。
- 开发集用于调试；冻结后使用新 seed 的 heldout 只运行一次，并标注 `DEVELOPER_HELDOUT`（seed 由开发者提供，不是独立闭卷）。

## 运行步骤（本机）

```powershell
# 1) 考官自检
python <kit>\tools\selftest.py

# 2) 生成开发数据
python <kit>\tools\foundation_microbench.py --seed 24117 --worlds 2 --out C:\dsh-lab\fl-pilot-dev

# 3) C 臂：Kotlin 学习/验证
$env:JAVA_HOME='C:\自研逻辑模型\.tools\jdk-21'
.\gradlew.bat --no-daemon foundationPilot -PpilotArgs="--study;C:\dsh-lab\fl-pilot-dev\public\study.jsonl;--questions;C:\dsh-lab\fl-pilot-dev\public\questions.jsonl;--out;C:\dsh-lab\fl-pilot-dev\kotlin-c"

# 4) A/B 臂
python run_model_arm.py --study ... --questions ... --out ...\runs\A-dev --arm A
python run_model_arm.py --study ... --questions ... --out ...\runs\B-dev --arm B

# 5) 评分（只读 sealed）
python <kit>\tools\score_microbench.py --predictions ... --answers ... --report ...
```

## 状态

- 开发集已完成；heldout 在冻结后运行，结果见 `RESULTS.md` / 交付 ZIP。
- 模型运行前的 Ollama runtime 修复：`llama-server.exe` 缺失，使用同版本 0.40.16 安装包恢复；模型 digest 与历史记录一致。
- 本目录不保存 sealed 答案、浏览器 profile、cookies 或密钥。
