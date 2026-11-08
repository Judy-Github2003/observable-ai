# GNJV-01 Phase-B 结果：12 任务 × 4 臂封存考官实验（只运行一次）

> 执行者：DeepSeek Harness，2026-11-08。本文件只记录可见的输入、命令、原始数据与结果；
> 不记录模型内部推理。验收裁定属于研究负责人/ChatGPT 独立审计，本文件不写 ACCEPTED。

## 0. 结论速览

| 项 | 结果 |
|---|---|
| 原始审计 | **GNJV01_RAW_AUDIT_PASS**（12 任务 / 48 行 / 冻结哈希一致） |
| 干预与证据更新机制 | **成立**（真实 `do()` 干预被执行，候选按观察淘汰） |
| 识别正确 | GNJV_ACTIVE **12/12**，FIXED **12/12**，RANDOM 7/12，OBS_ONLY 0/12 |
| HELDOUT 预测 | ACTIVE 48/48（覆盖 48/48），FIXED 48/48，RANDOM 43/48，OBS_ONLY 24/48（全部为平凡正确项） |
| 主动策略增益 | **NO_ACTIVE_ADVANTAGE**（ACTIVE vs FIXED：0 胜 / 0 负 / 12 平） |
| 独立闭卷 | `NOT_RUN`；这是冻结后延迟揭示的**开发域**实验 |
| 历史全量回归 | `NOT_RUN`（历史仍为 TIMEOUT，未伪称 PASS） |
| 运行次数 | 正式考官**恰好 1 次**，未重跑 |

## 1. 元数据与证据链

| 项 | 值 |
|---|---|
| START HEAD | `48a82777a2855def7d633e3a2e82a81aff32eb03` |
| 学生冻结提交 | `6d62905c908be443378af98b79af927f2e5e13dc` |
| 封存考官 ZIP SHA-256 | `aa6838728aac4c3c4c3cfd35170b6961ce6b4809abc0bd69f13f39789761c57c`（与 FREEZE.json 一致） |
| 考官源 SHA-256 | `75d7c1b6153c5429fd4fa40b41ea664d070666d13ae8044f90900cb754a02cfd`（与包内 SHA256SUMS 一致） |
| 冻结学生源 SHA-256 | `0380f330…`（校验后未改动） |
| `FREEZE.json` | 未修改、未重签 |
| 输出目录 | `build/gnjv01-phaseb-20261108T155821Z`（新建，未覆盖历史） |
| 学生冻结 SHA-256 | `7b7158b0338f0d3d00a08e9234602b951276bb28f4c4cbf63dc2feb587054069` |
| `EXAMINER-AFTER-FREEZE.jsonl` SHA-256 | `b08362c429952ec5a55a3f923d073330c7c8f8a3d4c19918cecb22360205fb3d` |
| 编译产物 jar SHA-256 | `171d26d0e3c1e4345158bf0a16f31cbd3feb859a687c6e6effc777acb60082d8` |
| 运行环境 | JBR 21.0.11（JDK 21）、Kotlin 编译器 2.2.21、离线 |
| 实际耗时 | **180 ms**（整个 48 臂；不含编译 6.1 s） |

`verify_freeze.py` 先于执行运行：

```text
GNJV01_FREEZE_VERIFIED files=2 examinerSha256=aa6838728aac4c3c4c3cfd35170b6961ce6b4809abc0bd69f13f39789761c57c
```

### 执行入口（与任务书步骤 5 的偏离，如实记录）

宿主**没有 `kotlinc`**（`java` 也不在 PATH）。为满足“独立 Kotlin 编译入口、不调用旧的 507 项 Gradle 回归”，
改为：用 Gradle 缓存中的 `kotlin-compiler-embeddable 2.2.21` 直接调用 `K2JVMCompiler` 编译
**仅两份源文件**，再用 `java` 直接运行考官 `main`：

```powershell
# 编译（只用两份源文件，未经 Gradle 任务）
java -cp "<kotlin-compiler-embeddable-2.2.21.jar>;<kotlin-stdlib-2.2.21.jar>;<kotlin-reflect-1.6.10.jar>;<kotlinx-coroutines-core-jvm-1.8.0.jar>;<annotations-13.0.jar>" `
     org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
     src/main/kotlin/logic/e3/gnjv/GNJV01Kernel.kt `
     src/test/kotlin/logic/e3/gnjv/GNJV01SealedExaminer.kt `
     -no-stdlib -classpath "<kotlin-stdlib-2.2.21.jar>" -d build/gnjv01-phaseb-examiner.jar

# 执行（一次）
java -cp "build/gnjv01-phaseb-examiner.jar;<kotlin-stdlib-2.2.21.jar>" `
     logic.e3.gnjv.GNJV01SealedExaminer build/gnjv01-phaseb-20261108T155821Z
```

未运行 `test` / `check` / `build` / 历史 formal。此偏离不影响科学口径，但必须由审计者知情。

## 2. 冻结屏障

考官先写 48 条学生输出与各自 4 条 HELDOUT 预测，计算 `STUDENTS-FROZEN.jsonl` 的 SHA-256，
写入 `STUDENTS-FROZEN.sha256`，**之后**才生成 `EXAMINER-AFTER-FREEZE.jsonl`。
独立重算：

```text
7b7158b0338f0d3d00a08e9234602b951276bb28f4c4cbf63dc2feb587054069  STUDENTS-FROZEN.jsonl
```

与考官打印的 `frozenSha256` 及 `.sha256` 文件三方一致。

## 3. 四臂结果

| Arm | 识别正确 /12 | HELDOUT 正确 /覆盖 /总数 | 干预次数 | 错误预测 | falseCertainty |
|---|---:|---:|---:|---:|---:|
| OBS_ONLY | 0 | 24 / 24 / 48 | 0 | 0 | 0 |
| RANDOM | 7 | 43 / 43 / 48 | 22 | 0 | 0 |
| FIXED | **12** | **48 / 48 / 48** | 20 | 0 | 0 |
| GNJV_ACTIVE | **12** | **48 / 48 / 48** | 20 | 0 | 0 |

配对识别差（ACTIVE − 对照）：

| 对照 | 胜 | 负 | 平 |
|---|---:|---:|---:|
| OBS_ONLY | 12 | 0 | 0 |
| RANDOM | 5 | 0 | 7 |
| FIXED | **0** | **0** | **12** |

→ 按预注册口径：**NO_ACTIVE_ADVANTAGE**。

### 逐任务（A/F/R 分别为 ACTIVE / FIXED / RANDOM）

| 任务 token | 隐藏结构 | u | 干预数 A/F/R | 识别 A/F/R | HELDOUT 正确 A/F/R |
|---|---|---:|---:|---:|---:|
| `69713b2c840b5a57` | X→Y | 1 | 1/1/2 | 1/1/0 | 4/4/3 |
| `611712c44f6676dc` | X→Y | 1 | 1/1/2 | 1/1/0 | 4/4/3 |
| `bc0c65ad1a90cb80` | X→Y | 1 | 1/1/2 | 1/1/1 | 4/4/4 |
| `bfaa078ab36d64ac` | X→Y | 0 | 1/1/2 | 1/1/1 | 4/4/4 |
| `135a7588e828f63f` | Y→X | 0 | 2/2/1 | 1/1/1 | 4/4/4 |
| `59d17336071cdbfe` | Y→X | 0 | 2/2/2 | 1/1/1 | 4/4/4 |
| `bd51af2c5c129401` | Y→X | 0 | 2/2/2 | 1/1/1 | 4/4/4 |
| `dba864ed6cb20479` | Y→X | 1 | 2/2/1 | 1/1/1 | 4/4/4 |
| `4c579cec3e08e66b` | 共同原因 U | 1 | 2/2/2 | 1/1/0 | 4/4/3 |
| `9460dad706cdcf71` | 共同原因 U | 0 | 2/2/2 | 1/1/1 | 4/4/4 |
| `dfc86e8caff5dee7` | 共同原因 U | 1 | 2/2/2 | 1/1/0 | 4/4/3 |
| `fc70dd52659abc09` | 共同原因 U | 0 | 2/2/2 | 1/1/0 | 4/4/3 |

## 4. 独立核对（超出 `audit_gnjv.py`）

另写独立脚本，从原始 JSONL 反推三个隐藏结构与 `u`，逐条复核：

1. **oracle 一致性**：全部 **62 条**已执行干预的结果，与按隐藏结构方程重算的预测一致（0 处不符）。
2. **识别声明一致性**：48 行 `identifiedCorrect` 与学生记录的候选集完全一致（0 处不符）。
3. **臂等价**：GNJV_ACTIVE 与 FIXED 在 **12/12** 个任务上干预序列与 HELDOUT 预测**逐字节相同**。

## 5. 科学解读（不可互相替代的四层）

1. **干预机制是否执行**：是。62 次真实 `do()` 干预被记录，含基线与结果；无教师调用（`teacherCalls=0`）。
2. **是否按证据更新**：是。每次 update 后候选数单调下降；12 个任务中 ACTIVE/FIXED 均在 ≤2 次干预内收敛到唯一候选。
3. **是否正确识别**：ACTIVE 与 FIXED 12/12；RANDOM 7/12（5 次重复同一操作，浪费第二次预算）。
4. **未见初态预测**：ACTIVE/FIXED 48/48 覆盖且全对；RANDOM 43/48。**但 HELDOUT 只换了外生初态，不是新结构。**

### 必须同时报告的阴性事实

- **`NO_ACTIVE_ADVANTAGE`**。本考卷只有两个合法反转动作 `do(X=¬x)`、`do(Y=¬y)`；
  两个动作在初始候选集上的最坏剩余数都是 2，minimax 打平后按 `target.name` 选 `X`；
  FIXED 也先 `X` 后 `Y`。因此**本设计下 ACTIVE 与 FIXED 必然走同一条轨迹**，
  该考卷对“主动 vs 固定”的区分力为 0。这不是学生成绩问题，是考题结构问题。
- **OBS_ONLY 的 24/48 不是能力**。它们恰好是两个“无效应”输入（`do(X=u)`、`do(Y=u)`），
  三个候选结构在这些输入上给出同一结果，属于**结构性必然正确**；真正的信息量是覆盖 24/48、识别 0/12。
- **`falseCertainty` 恒为 0 是协议性质**。证据由真实结构产生，真候选永不被淘汰，
  因此“唯一剩余候选”必然是真结构；该指标只有在 oracle 撒谎时才可能触发。
- 三个候选结构由设计者给定且本就可辨识；**不能**宣称开放式因果发现、自主表征发明或智能涌现。
- 参考学习器是 ChatGPT 提供的有限假说实现，DeepSeek 只做整合与审计；**不得**记作学生自主发明。

## 6. 协议与执行缺陷记录

1. **宿主无 `kotlinc`**：改用 embeddable 编译器直接调用（见 §1），未使用 Gradle 测试任务。
2. **RANDOM 的“允许重复”确实发生了**：5/12 个任务连续两次选择同一干预，导致 2 个候选残留，
   这是预注册设计的一部分，不是实现错误。
3. **归因登记错误（研究负责人已指出）**：Phase-A 的 Console 记录把 GNJV-01 挂在 `PACK-02`
   （`parent_task_ids=[C4,C8,RES-H01]`），而 GNJV-01 是独立的 G+N+J+V 研究线。
   本次 Phase-B 记录**未**挂 PACK-02；Phase-A 的历史记录不改写，另以追加式更正事件说明。
4. 未发现能使结果 `INVALID` 的信息泄漏、提前读真值或改评分标准的行为。

## 7. 产物

- `STUDENTS-FROZEN.jsonl`、`STUDENTS-FROZEN.sha256`、`EXAMINER-AFTER-FREEZE.jsonl`
- `AUDIT-STDOUT.log`、`RUN-STDOUT.log`、`RUN.json`
- 本文件 `RESULT.md`

## 8. 停止点

正式考试已完成且只运行一次。**不重跑、不调参、不启动 GNJV-02/SD-04**，
等待 ChatGPT 独立审计。
