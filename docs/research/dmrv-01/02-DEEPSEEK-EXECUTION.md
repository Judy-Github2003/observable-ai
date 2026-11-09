# DeepSeek 执行指令：DMRV-01 单包研究

你是 Observable AI 的实施者，使用 Kotlin/JDK 21 + Python 执行一次预注册研究，最后返回完整 results.zip。附件的学生参考算法与可见考官来自 ChatGPT；你负责集成、核查、冻结、运行和报告，不能声称学生自主发明学习算法。

**重要：本次研究不属于 SD-03/E2.4，也不是线性研发下一阶段。它是 A–Z 中 D+M+R+V 组合的新实验。**

## 1. 安装与环境

1. 阅读 `README.md`、`docs/01-RESEARCH-PROTOCOL.md`、`docs/03-SCIENTIFIC-BOUNDARIES.md`。
2. 用 `python tools/verify_package.py` 校验 ZIP 文件树。
3. 确认 git HEAD 和工作区；上一轮 BMNV 提交作为基线。不得删除四个原有未跟踪文档或覆盖用户其他代码。
4. 运行 `python tools/install_into_repo.py "C:\\自研逻辑模型"`（从解压目录执行），或按 README 文件映射手动复制到仓库；安装工具默认拒绝覆盖同名文件。
5. 研究控制台需要登记独立 DMRV-01 研究包（可关联 D1/D3/D4/D7、RES-M01/M05/M06、RES-R01/R02/R05 和 RES-V01/V02/V03/V04/V05；以实际 Research Console 条目为准）。禁止误挂 PACK-02 或篡改已验收研究。通过可用的 preflight。若门禁缺失，仅登记新研究授权，勿绕过检查。

## 2. 公开验证及冻结

先用定向方式执行公开测试。标准 Kotlin 安装环境例子：

```powershell
kotlinc src/main/kotlin/logic/e3/dmrv/DMRV01Kernel.kt src/test/kotlin/logic/e3/dmrv/DMRV01PublicContract.kt -include-runtime -d build/dmrv01-public.jar
java -cp build/dmrv01-public.jar logic.e3.dmrv.DMRV01PublicContract
```

预期 14/14 测试通过。若标准 `kotlinc` 不存在，可以用现有 Kotlin embeddable 编译器，必须记录实际编译器、命令、源码 SHA 与执行日志；不允许悄悄改逻辑或放宽测试。

先检查有没有 `FREEZE.json`；确保未存在，再运行：

```powershell
python tools/dmrv/freeze_dmrv.py --repo .
```

将学生 `DMRV01Kernel.kt`、公开契约和考官的实际 SHA-256 写入 `docs/research/dmrv-01/FREEZE.json`，连同执行协议一并提交 Git。这是冻结提交。不可运行考试后再修改或重新生成冻结清单。

## 3. 正式考试，仅一次

**正式测试与开发烟测必须隔离。** 不得在正式任务种子上进行预先模拟、调参或选择 favorable salt。公开测试与开发烟测不是正式结果。**不要求运行开发烟测**。

按照 `run_dmrv.py` 入口运行一次：

```powershell
python tools/dmrv/run_dmrv.py --repo . --official --output build/dmrv-01-official-once
```

`run_dmrv.py` 会：核对冻结源码 → 编译 Kotlin → 创建一次性考试意图文件 → 随机生成一次 salt → 在新目录运行考官 → 保存原始记录。

如果主机没有 kotlinc，换用已经存在的 embedded compiler，仅允许**等价替换编译入口**，学生/考官源码不能变，必须保留一次性意图、冻结核对和真实运行命令。

考试包括 192 元训练任务、128 元测试任务、6 个对照臂、768 条冻结学生结果。先写 `POLICY-FROZEN.txt` 和 `STUDENTS-FROZEN.jsonl`，并计算 SHA；**之后**才能保存考官评分。不得修改预先给定的奖励函数、评测任务或策略对照。

## 4. 独立审计

```powershell
python tools/dmrv/audit_dmrv.py build/dmrv-01-official-once
```

审计必须重算元训练 reward 与策略表、每条学生预测、隐藏真值、弃答与准确率、动作选择、对照成本、配对结果。如果审计不通过，原始记录保留，标记 INVALID/INCOMPLETE，而不是反复重考。

## 5. 不得执行

- 不准修改 GNJV、BMNV、MRX、SD-03、PACK-01/02 和 B0 历史冻结源码、结果或事件。
- 不准运行旧历史无筛选 `test/check/build` 或 160 次 formal；本轮只做独立专项测试。
- 不准修改冻结后的 DMRV 学生、考官或评分代码，也不准反复运行一次性正式试卷。
- 不准根据正式成绩调参、挑选最优随机 salt 后覆盖。
- 不准把 `META_CONTEXT` 超越 `COLD_START` 自动写成学习策略进化；必须考察与 `GLOBAL_FEEDBACK` 的配对。
- 不准把 ChatGPT 提供的学习算法标为 `student_invented`。
- 不准自动启动 DMRV-02、BMNV-02 或其他研究包。

## 6. 交付

返回 `Observable-AI-DMRV-01-2026-11-09-results.zip`，至少包括：

- `RESULT.md`（含 PASS/NOT_DEMONSTRATED/NO_BENEFIT 判定与限制）；
- `FREEZE.json`、学生/考官/公开契约 SHA 与冻结提交；
- 原始 `POLICY-FROZEN.txt`、`POLICY-SHUFFLED-FROZEN.txt`、`POLICY-FROZEN.sha256`；
- 原始 `META-TRAIN-FEEDBACK.jsonl`、`STUDENTS-FROZEN.jsonl`、`STUDENTS-FROZEN.sha256`、`EXAMINER-AFTER-FREEZE.jsonl`；
- `AUDIT.json`、`RUN.json`、编译日志、公开契约日志；
- 总正确/覆盖/弃答/计算成本、配对胜负、元训练成本、按四种世界家族分类的成绩；
- Git 变更列表、最终 HEAD、是否成功 push、未触碰四个原有未跟踪文件的说明。

每次实验后停下，交给 ChatGPT 独立审计，**不自动实施下一阶段**。
