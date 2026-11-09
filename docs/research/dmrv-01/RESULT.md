# DMRV-01 正式结果：上下文条件化表征策略的元学习（可见考官，运行一次）

> 执行者：DeepSeek Harness，2026-11-09。本文件只记录可见输入、命令、原始数据与结果；
> 不记录模型内部推理。验收裁定属于研究负责人/ChatGPT 独立审计，本文件不写 ACCEPTED。

## 0. 判定

```text
DMRV-01 / IMPLEMENTER_RUN / VISIBLE_EXAMINER
MECHANISM_D_R（从历史标量反馈学习并冻结上下文策略）: PASS_LIMITED
META_VS_GLOBAL（成本匹配的核心对照）: SUPPORTED_LIMITED（18 胜 / 2 负 / 108 平）
META_VS_SHUFFLED（上下文顺序对照）: 17 胜 / 6 负 / 105 平
M（在给定扩展集合中选择表示）: PASS_LIMITED
V（冻结顺序、独立重算、数据隔离）: PASS_STRUCTURAL
AUTONOMOUS_GRAMMAR_INVENTION: NOT_DEMONSTRATED
INDEPENDENT_CLOSED_BOOK: NOT_RUN
```

一句话：**在 128 个新任务上，上下文条件化元策略比成本匹配的全局反馈对照多 16 个全对任务（80 vs 64），
且优于打乱上下文对照（69）；但两者都远未到上限，上下文只是粗糙的家族代理，奖励优势主要来自
少数“全局单一动作必败”的家族。**

## 1. 环境、门禁与冻结

| 项 | 值 |
|---|---|
| 起始 HEAD | `f8fdd609ca7e7d651a08eb1b20801e6a088bcbf0` |
| 冻结提交 | `2b68ea84bc1224c158aa891a819ccb349216735e` |
| 运行工具提交 | `ed6d60f6b570bada3c80e24251f8305003ec0d78`（无 kotlinc 的等价 runner） |
| Console 授权 | `DMRV-01-SINGLE-2026-11-09`，关联 D1/D3/D4/D7、RES-M01/M05/M06、RES-R01/R02/R05、RES-V01/V02/V03/V04/V05；**未挂 PACK-02** |
| preflight | `AUTHORIZED_REPAIR`（`ev-1794191407339-7e4602`） |
| 冻结源 SHA-256 | kernel `fef43671…`、contract `96229f6a…`、examiner `39b3dc29…`（与包内 SHA256SUMS 一致） |
| `FREEZE.json` SHA-256 | `73ca9f61aaff95d8408ac2eecc75e92b2d981721a4bc9325a73316a48527bac1` |
| 一次性 salt | `168227999440644966`（62 位） |
| 编译 jar SHA-256 | `ad01f1880f28b00c72d5e75b30e80672dd03b7a1e91f8603a6872365c29f4108` |
| 学生输出 SHA-256 | `0b7c45ee874d1307ffae1e4253c5b360699e3a4f57dd74287d7468b8054dd455` |
| 评分文件 SHA-256 | `4839778479a98a584ce981a632d845fe7f77cb591e27f6d9a393348d3ee502d1` |
| 元训练反馈 SHA-256 | `b6df056cd52882b12acb3a86deb121fab04797b49d9705d93f462823c1243823` |
| 策略快照 SHA-256 | `c7052e5a9b1f7e1e928e8e769102bd7f325ee2f232a7b13adb77fe3ee690964d` |
| 环境 | JBR 21.0.11、Kotlin 编译器 2.2.21（embeddable）、离线 |
| 正式运行次数 | **1 次**；`.intent.json` 先于考试写入 |
| 耗时 | 考试本体约 1.3s；runner 整体 11.5s（含编译） |

### 执行偏离（必须知情）

宿主没有 `kotlinc`，`java` 也不在 PATH。按任务书允许的“等价替换编译入口”，使用
`tools/dmrv/run_dmrv_embedded.py`：语义与 `run_dmrv.py` 相同（新输出目录、冻结核对、official 模式先写
一次性 guard、62 位随机 salt、stdout/stderr 日志、`FREEZE-COPY.json`、`RUN.json`、运行后重算源码哈希），
只把 `kotlinc` 换成 Kotlin embeddable 编译器 + `java -cp`。未运行 Gradle 测试任务或历史 507 项回归。

## 2. 规模与冻结屏障

- **元训练**：4 家族 × 8 变体 × 6 种子 = **192 任务**；每个任务实际执行 4 个动作 → **768 条标量反馈**（全信息）。
- **元测试**：4 家族 × 8 变体 × 4 种子 = **128 任务** × 6 臂 = **768 条学生记录**，每条 8 个预测位（共 6,144）。
- 奖励：`correct/8 - 0.08 × candidateEvaluations/44`（人为设定的工程效用，不是学生发明的）。
- 顺序：先写 768 条反馈 → 写 `POLICY-FROZEN.txt` / `POLICY-SHUFFLED-FROZEN.txt` 与其 SHA → 从快照 restore 并校验一致
  → 再跑 128 个测试任务 → 全部学生输出写入 `STUDENTS-FROZEN.jsonl` 并算 SHA → **最后**才写
  `EXAMINER-AFTER-FREEZE.jsonl`。审计脚本重算了策略聚合与每条选择。

## 3. 主结果（128 任务 × 8 预测位 = 1024）

| Arm | 全对任务 /128 | 正确 /1024 | 覆盖 | 覆盖内错误 | 候选枚举 | 奖励和 |
|---|---:|---:|---:|---:|---:|---:|
| **META_CONTEXT** | **80** | **664** | 688 | 24 | 2,784 | **77.94** |
| GLOBAL_FEEDBACK | 64 | 532 | 552 | 20 | 2,048 | 62.78 |
| SHUFFLED_CONTEXT | 69 | 572 | 592 | 20 | 2,384 | 67.17 |
| RANDOM | 50 | 432 | 464 | 32 | 3,416 | 47.79 |
| STATIC_BINARY | 49 | 420 | 448 | 28 | 5,632 | 42.26 |
| COLD_START | 32 | 276 | 296 | 20 | 1,024 | 32.64 |

配对（同任务、同训练证据）：

| 对照 | META 胜 | META 负 | 平 |
|---|---:|---:|---:|
| GLOBAL_FEEDBACK | **18** | **2** | 108 |
| SHUFFLED_CONTEXT | 17 | 6 | 105 |
| RANDOM | 39 | 9 | 80 |
| STATIC_BINARY | 48 | 17 | 63 |
| COLD_START | 48 | 0 | 80 |

离散符号检验（20 个不一致任务，18:2）双侧 p ≈ **4.0×10⁻⁵**；
但任务按 8 变体 × 4 种子成簇，不能当作 128 个独立样本，因此只作方向性参考。

## 4. 按家族（全对任务数 /32）

| 家族 | META | GLOBAL | SHUFFLED | STATIC | RANDOM | COLD |
|---|---:|---:|---:|---:|---:|---:|
| UNARY | 32 | 32 | 30 | 25 | 28 | 32 |
| BINARY | 7 | 0 | 7 | **24** | 9 | 0 |
| PARITY3 | 30 | **32** | **32** | 0 | 7 | 0 |
| MUX | **11** | 0 | 0 | 0 | 6 | 0 |

- GLOBAL 的全局均值为 `ADD_PARITY3`（0.4917），因此它在 UNARY+PARITY3 上满分、在 BINARY/MUX 上全灭。
- META 的 18 个净胜全部来自 BINARY（+7）与 MUX（+11），代价是 PARITY3 少 2 个。

## 5. 学到的策略（真实可查）

`POLICY-FROZEN.txt` 记录 14 个上下文 × 4 动作的计数与累计奖励。实际选择分布：

| 动作 | META | GLOBAL | SHUFFLED |
|---|---:|---:|---:|
| ADD_PARITY3 | 91 | 128 | 116 |
| ADD_MUX | 25 | 0 | 0 |
| ADD_BINARY | 12 | 0 | 12 |

代表性上下文（元训练证据 → 选择）：

| 上下文 | 测试任务数 | 元训练最优动作（全对率） | META 选择 |
|---|---:|---|---|
| `GAP/HIGH/CALM` | 3（全 BINARY） | ADD_BINARY 7/7 | ADD_BINARY |
| `NEAR/MID/CALM` | 8（MUX 7） | ADD_MUX 3/7 vs ADD_BINARY 1/7 | ADD_MUX |
| `GAP/MID/CHANGING` | 47（PARITY3 26、MUX 12、BINARY 9） | ADD_PARITY3 43/76 | ADD_PARITY3 |
| `FIT/MID/CALM` | 31（UNARY 27） | ADD_PARITY3 30/36 ≈ BASE_ONLY | ADD_PARITY3 |

选择确实来自反馈聚合：审计脚本用 `META-TRAIN-FEEDBACK.jsonl` 重算每个 (context, action) 的计数与奖励和，
与冻结策略表逐项一致，并重算了 768 条测试动作。

## 6. 为什么没有更强（必须同时报告）

1. **上下文只是粗糙的家族代理。** 14 个桶里，`GAP/MID/CHANGING` 一个桶就装了 47 个测试任务，
   横跨 PARITY3(26)、MUX(12)、BINARY(9)。任何“一桶一动作”的策略都无法同时解决三者。
   用家族覆盖模型估计，**给定这些上下文的理论上限约 96/128**；META 拿到 80，差 16 属于分配误差。
2. **两处真实误配**：`GAP/HIGH/CHANGING` 桶里 META 选了 ADD_MUX（元训练 3/7 vs PARITY3 2/7），
   而测试样本恰好是 PARITY3，于是丢掉 2 个本来能全对的任务。
3. **“唯一行为类”不等于“真值”。** 8 条训练样本可能让一个错误假设成为唯一一致类：
   全部 6 臂共出现 36 条“覆盖但错”的记录（META 6 条、24 个错误预测）。
   弃答机制只能覆盖“多类并存”，覆盖不了“欠定但唯一”。
4. **SHUFFLED 只降到 69 而不是更差。** 打乱上下文后，平滑先验把大多数选择拉回全局 ADD_PARITY3
   （116/128），所以它接近 GLOBAL；META 对它的 17:6 说明“上下文-奖励对应”确实有作用，但幅度有限。
5. **元训练是全信息 counterfactual**：每个任务四种动作都被执行并评分，不是 bandit 或在线试错；
   19,200 次候选枚举的训练成本必须计入，不能只报部署阶段成本。
6. **同家族内迁移**：测试与训练来自同一组四个生成家族，只换了变体/种子/采样，不是分布外。

## 7. 成本

| 项 | 值 |
|---|---|
| 元训练任务 / 反馈行 | 192 / 768 |
| 元训练候选枚举 | **19,200**（192 × (8+44+16+32)） |
| 测试候选枚举（六臂合计） | 17,288 |
| 测试候选枚举（META / GLOBAL） | 2,784 / 2,048 |
| 考试本体耗时 | 约 1.3 s（runner 整体 11.5 s，含编译） |

META 比 GLOBAL 多枚举 736 个候选（因为更多选择 ADD_BINARY/ADD_MUX），但奖励仍高 15.2：
收益不是“靠更多计算换来的”唯一结果，而是动作分配更匹配任务。

## 8. 科学边界与阴性结论

- 没有证明“学生自主发明学习算法/语法/效用函数”：动作集合、上下文桶、平滑法、奖励函数全部由设计者给定。
- 没有证明开放世界元学习、跨分布迁移或统计普适性：4 种子只是同结构的重复采样。
- 没有证明“自我改进”：这是从**考官提供的**标量反馈中学习一个固定形式的策略表。
- 不能把 META 超过 COLD_START 当作能力证据：COLD_START 只能解 UNARY，是能力下界。
- 可见考官：实施者读过考官与生成家族，故只能算 `IMPLEMENTER_RUN / VISIBLE_EXAMINER`。
- 历史全量回归 `NOT_RUN`；独立闭卷 `NOT_RUN`。

## 9. 产物

- `official/META-TRAIN-FEEDBACK.jsonl`（768 行）、`official/POLICY-FROZEN.txt` / `POLICY-SHUFFLED-FROZEN.txt` / `POLICY-FROZEN.sha256`
- `official/STUDENTS-FROZEN.jsonl`（768 行）+ `.sha256`、`official/EXAMINER-AFTER-FREEZE.jsonl`（768 行）
- `official/INTENT.json`、`RUN.json`、`FREEZE-COPY.json`、`SUMMARY.txt`、`AUDIT.json`
- `PUBLIC-CONTRACT.log`（14/14 PASS）、`OFFICIAL-RUN-STDOUT.log`、`AUDIT-STDOUT.log`

## 10. 停止点

不修改冻结源、不重跑正式考卷、不启动 DMRV-02/BMNV-02。等待 ChatGPT 独立审计后回到 A–Z 研究方向选择。
