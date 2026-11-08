# BMNV-01 正式结果：B+M+N+V 单包开发实验（可见考官，运行一次）

> 执行者：DeepSeek Harness，2026-11-09。本文件只记录可见输入、命令、原始数据与结果；
> 不记录模型内部推理。验收裁定属于研究负责人/ChatGPT 独立审计，本文件不写 ACCEPTED。

## 0. 结题判定

```text
BMNV-01 / IMPLEMENTER_RUN / VISIBLE_EXAMINER
MECHANISM_B:  PASS（48/48 场景检测到可见碰撞并真实读取新通道；官方考卷无 NO_GAP 消融臂）
MECHANISM_M:  PASS_LIMITED（48/48 行为正确、12 个不同表达式；语法由设计者给定）
MECHANISM_N:  NOT_DEMONSTRATED（固定通道顺序 + 唯一性提前停止，无信息增益规划）
EVIDENCE_V:   PASS_STRUCTURAL（192 行、账目、真值、磁盘记忆全部由 Python 独立重算一致）
TRANSFER:     FULL 与 NO_MEMORY 质量并列 3072/3072；生命周期读数 14336 vs 15360（FULL 少 6.7%）
AUTONOMOUS_GRAMMAR_INVENTION: NOT_DEMONSTRATED
INDEPENDENT_CLOSED_BOOK: NOT_RUN
NEXT_ACTION: RETURN_TO_A_Z_RESEARCH_SELECTION
```

一句话：**有限原语搜索 + 文件持久化 + 四任务复用确实跑通了，且在同等满分质量下比无记忆基线少 6.7% 的读端口调用；
但“主动实验策略”“自主发明语法”“分布外泛化”都没有被证明。**

## 1. 环境、门禁与冻结

| 项 | 值 |
|---|---|
| 起始 HEAD | `3f6496295f2bff830616814450d488fc899293cf` |
| 冻结提交 | `bcb91b7e71f4ed68ed0acc0ad217ff18c5c6ae6d` |
| 运行工具提交 | `6a313b4b1a5e4847885c3cd144f393fe1c9ef4b2`（无 kotlinc 的等价 runner） |
| Console 授权 | `BMNV-01-SINGLE-2026-11-09`，关联 B2/B7/B8、RES-M01/M05/M06、RES-N01/N02/N03、RES-V01/V02/V03/V05；**未挂 PACK-02** |
| preflight | `AUTHORIZED_REPAIR`（`ev-1794156502370-a081a9`） |
| 冻结源 SHA-256 | kernel `031cfb64…`、contract `239179fa…`、examiner `651afc8d…`（与包内 SHA256SUMS 一致） |
| `FREEZE.json` SHA-256 | `d6506f0637c52b0170e8b56758285a427ac47b1ea0530dd6349730d646606aea` |
| 环境 | JBR 21.0.11、Kotlin 编译器 2.2.21（embeddable）、离线 |
| 正式运行次数 | **1 次**（`build/bmnv-01-official-once`，`.intent.json` 先于考试写入） |
| 学生输出 SHA-256 | `03c86b6f995967687c3f9229678aa772a87ad5f7f8ff3026f9813ddee0494aa8` |
| 评分文件 SHA-256 | `db9196495a209c9af9e92a75aa4096111e61ee5ba29bb56bb0630f8e25f1761c` |
| 编译 jar SHA-256 | `0b7a9de75782f0e231dcb627533387830047878ac0aa3997eceb81d6571654ee` |
| 一次性 salt | `2013042127567488547`（记录在 `INTENT.json` / `RUN.json`） |
| 耗时 | 整体 21.75s（编译约 18s + 考试约 1.5s） |

### 执行偏离（必须知情）

宿主没有 `kotlinc`，`java` 也不在 PATH。任务书允许“编译器不可用时采用已有 Kotlin Gradle plugin / embedded compiler，
保持相同的冻结源并保存同等运行证据”。因此使用 `tools/bmnv/run_bmnv_embedded.py`：语义与 `run_bmnv.py` 相同
（先校验冻结哈希、要求新输出目录、生成 63 位随机 salt、**先写 `.intent.json` 再启动考官**、结束后写 `RUN.json`），
仅把 `kotlinc` 调用换成 embeddable 编译器 + `java -cp`。未运行 Gradle `test/check/build` 或历史 507 项回归。

## 2. A 阶段：碰撞 → 测量 → 组合谓词 → 持久化

- 48/48 场景 `aStatus = LEARNED_BOUNDED`，**48/48 概念在 8 个三传感器输入上与真实目标行为一致**（独立重算 384/384）。
- 学到 **12 个不同表达式**（3 个传感器配对 × 4 个算子），每个重复 4 个种子：

| 表达式 | 次数 | 表达式 | 次数 |
|---|---:|---|---:|
| `XOR\|ALPHA\|BETA` | 4 | `XNOR\|ALPHA\|BETA` | 4 |
| `AND\|ALPHA\|BETA` | 4 | `OR\|ALPHA\|BETA` | 4 |
| `XOR\|ALPHA\|GAMMA` | 4 | `XNOR\|ALPHA\|GAMMA` | 4 |
| `AND\|ALPHA\|GAMMA` | 4 | `OR\|ALPHA\|GAMMA` | 4 |
| `XOR\|BETA\|GAMMA` | 4 | `XNOR\|BETA\|GAMMA` | 4 |
| `AND\|BETA\|GAMMA` | 4 | `OR\|BETA\|GAMMA` | 4 |

- 早停真实发生：ALPHA/BETA 配对 16 个场景只读 2 个通道（32 次读数）；含 GAMMA 的 32 个场景读满 3 个通道（48 次）。
  合计 A 阶段 **2048 次真实读端口调用**。
- **持久化真实发生**：48 个 `PERSISTED-CONCEPTS/<taskId>.concept` 文件写入磁盘并在同一流程中重新加载；
  审计逐字节比对 48/48 一致，且 B 阶段使用的正是重新加载后的对象（`#` 分隔的 5 段编码）。

## 3. B 阶段四臂对照（48 场景 × 4 个 B 任务 × 16 heldout = 3072 个预测位/臂）

| Arm | B 正确/全部 | 覆盖 | 弃答 | A 读数 | B TRAIN 读数 | B HELDOUT 读数 | 总读数 | 候选评估 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **FULL** | **3072/3072** | 3072 | 0 | 2048 | 6144 | 6144 | **14336** | 1,152 |
| NO_MEMORY | 3072/3072 | 3072 | 0 | 0 | 9216 | 6144 | 15360 | 27,648 |
| NO_OBSERVATION | 0/3072 | 0 | 3072 | 0 | 0 | 0 | 0 | 0 |
| NO_COMPOSE | 0/3072 | 0 | 3072 | 2304 | 0 | 0 | 2304 | 0 |

- 配对 FULL vs NO_MEMORY：**0 优 / 0 劣 / 48 平**（全部并列满分）。
- FULL 的 4 个 B 任务包装全部识别正确：`DIRECT`/`XOR_CONTEXT`/`AND_CONTEXT`/`OR_CONTEXT` 各 48 次。
- 边际成本：每个 B 任务 FULL 读 **64**（32 训练 + 32 heldout），NO_MEMORY 读 **80**（48 + 32）。
  生命周期：FULL `2048 + 12288 = 14336`，NO_MEMORY `0 + 15360 = 15360` → **FULL 少 1024 次读数（6.7%）**。
- 候选搜索量：FULL 每 B 任务 6 个包装 × 4 = 24；NO_MEMORY 每 B 任务 24 表达式 × 6 包装 = 144。

## 4. 独立复核（超出 `audit_bmnv.py`）

1. **A 概念**：48 × 8 个输入逐一重算，0 处与考官真值不符。
2. **记忆文件**：48/48 与 JSONL 中 `memory` 字段逐字节一致；12 个不同概念体。
3. **预测来源**：`bModelCodes` 声明的模型可逐条复现全部非空预测（audit 已检查）。
4. **读数账目**：`aReads + ΣbTrainReads + ΣbTestReads == totalReads == grade.totalReads`，192/192 成立。
5. **等价类现象**：NO_MEMORY 从零搜索时，在 **24/48** 个场景选到了与 A **不同但行为等价**的表达式
   （如 `XNOR|ALPHA|BETA` 对 `XOR|ALPHA|BETA`），预测仍全对——说明“表达式身份”不唯一，行为类才唯一。

## 5. 必须同时报告的阴性/限制

1. **质量天花板**：FULL 与 NO_MEMORY 都 3072/3072，本考卷**无法区分质量差异**，只能比较成本。
   这不是“FULL 比无记忆更聪明”，只是“在 B 上少读一个通道”。
2. **不是 48 个独立世界**：48 场景 = 12 个结构（3 配对 × 4 算子）× 4 个种子；种子只改对象 ID 与打乱顺序。
   不能据此做显著性推断，也不能称跨结构泛化。
3. **HELDOUT 不是分布外**：训练与 heldout 覆盖同样的 8 个三传感器组合，只是对象 ID 不同。
4. **NO_OBSERVATION / NO_COMPOSE 是“按构造弃答”**：这两个臂在 B 阶段根本不运行学习器，覆盖必然为 0；
   它们证明的是“没有观测/没有二元表达就无法作答”，不是“FULL 优于强基线”。
5. **官方考卷没有 NO_GAP 负对照**：碰撞检测的“不该新增结构”只在公开合同里测过（`NO_VISIBLE_COLLISION`），
   正式 48 场景全部是有碰撞的。
6. **B 的正确性近乎结构性**：B 训练覆盖全部 8 组合 × 2 上下文，任何拟合的包装都唯一确定其行为类；
   一旦 A 概念正确，B 满分几乎必然。考卷不能展示“部分学习”。
7. **N 方向 `NOT_DEMONSTRATED`**：通道顺序固定 ALPHA→BETA→GAMMA，只做“唯一行为类即停”，
   没有期望信息增益排序，也没有与随机/固定顺序的对照。
8. **记忆机制很薄**：只有“写文件—读回—使用”，没有版本、失效、冲突撤销。
9. **可见考官**：考官源码与学生在同一个包内，实施者读过考官；只能算 `IMPLEMENTER_RUN / VISIBLE_EXAMINER`。
10. **历史全量回归 `NOT_RUN`**：不能用本次定向测试替代 507 项历史结论。

## 6. 产物

- `official/STUDENTS-FROZEN.jsonl`（192 行）、`STUDENTS-FROZEN.sha256`、`EXAMINER-AFTER-FREEZE.jsonl`（192 行）
- `official/PERSISTED-CONCEPTS/`（48 个 `.concept` 文件）
- `official/INTENT.json`、`RUN.json`、`SUMMARY.txt`、`AUDIT.json`
- `PUBLIC-CONTRACT.log`（11/11 PASS）、`OFFICIAL-RUN-STDOUT.log`、`AUDIT-STDOUT.log`

## 7. 停止点

不修改冻结源、不重跑正式考卷、不启动 BMNV-02。等待 ChatGPT 独立复核后回到 A–Z 研究方向选择。
