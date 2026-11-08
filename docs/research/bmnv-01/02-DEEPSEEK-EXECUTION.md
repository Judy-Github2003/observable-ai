# 直接发给 DeepSeek 的执行指令：BMNV-01（单包版）

你收到一份名为 `Observable-AI-BMNV-01-SINGLE-2026-11-09.zip` 的研究包。**请按此 ZIP 的研究协议开展单轮实验，不要将其拆分成 A/B 两个交付阶段。**

## 研究目标

本轮组合研究是 B+M+N+V：学生从公开观察碰撞中判断现有信息不足，调用受控测量获得新属性，以现有逻辑原语构造可执行的组合概念，持久化后在多个不同任务中复用，并通过同场景对照和证据审计判断机制价值。

它是新的独立组合研究，不是 GNJV-02、SD-04 或 E2.4。

## 第一部分：先检查、再整合

1. 解压 ZIP，核对 `SHA256SUMS.txt` 和 `PROJECT-MANIFEST.json`。阅读 `README.md`、`docs/01-RESEARCH-PROTOCOL.md`、`docs/03-EVIDENCE-AND-LIMITATIONS.md`。
2. 在 `C:\自研逻辑模型` 先执行 `git rev-parse HEAD` 与 `git status --porcelain`；保留四个历史未跟踪文档，禁止 `git clean`。GNJV-01 已完成，前序 HEAD 供参考：`3f6496295f2bff830616814450d488fc899293cf`，实际如有变化按现状报告。
3. 建立独立 BMNV-01 研究记录，关联 B2/B7/B8、RES-M01/M05/M06、RES-N01/N02/N03、RES-V01/V02/V03/V05。**不要复用 GNJV-01 的包归属，更不能再挂到 PACK-02 的 C4/C8/H01 下。**
4. 研究负责人明确授权本轮 BMNV-01，但仍需遵守 Research Console 的 preflight、append-only history、finish 流程。若新任务尚未登记，先按项目既有记录接口合法登记并授权，且不要将旧任务状态自动改为 ACCEPTED。
5. 将 `BMNV01Kernel.kt`、`BMNV01PublicContract.kt` 放到 ZIP 所给的相对源码路径；将 `examiner/BMNV01Examiner.kt` 复制到仓库的 `src/test/kotlin/logic/e3/bmnv/BMNV01Examiner.kt`。`tools/*.py` 可以放在 `tools/bmnv/` 下并保持相互导入。将科研文档保存到 `docs/research/bmnv-01/`。
6. 不允许修改 B0 基座、GNJV-01、SD-03 历史冻结源码/manifest/评分证据。所有 BMNV 改动必须在新文件或经明确授权的 BMNV 文件范围内。

## 第二部分：公开验证（可以反复运行，不是正式考试）

- 使用 `python tools/bmnv/public_smoke.py --root .` 或等价的项目 Kotlin Gradle 定向测试运行公开合同。公开合同应有 **11 项**检查，覆盖碰撞识别、二元表达、序列化复用、B 任务、无观测预算、不应新增结构、无关传感器与不合法 AST。
- ZIP 的学生代码是 ChatGPT 的参考实现，**不是 DeepSeek 学生独立发现的算法**。你可以在冻结前做必要的移植/兼容性修复，但若修改学习机制或实验协议，必须清楚记录动机、差异、测试证据；不得基于正式考试成绩事后改动。
- 若发现实验协议无法检验预注册假设，先报告；不要为了制造正面结论增加隐藏对照、删除失败世界或调整预注册阈值。
- 考官源码位于同一个 ZIP，可用于编译与接口检查，但不能把考官隐藏目标当成学生的先验知识。不要修改考官以适配学生。

## 第三部分：只冻结一次

1. 在公开测试完成、代码可编译、许可范围明确后调用 `python tools/bmnv/freeze_bmnv.py --root .`。
2. 运行 `python tools/bmnv/freeze_bmnv.py --root . --verify`，检查学生 + 考官源码哈希。
3. 将源码、测试、考官、工具和 `docs/research/bmnv-01/FREEZE.json` 一并提交 Git 并推送。记录 `HEAD`、文件清单、SHA-256。
4. 冻结之后不允许修改 `BMNV01Kernel.kt`、`BMNV01PublicContract.kt`、`BMNV01Examiner.kt`；发生冻结错误应停止并提交问题报告，不得覆盖冻结源。

## 第四部分：仅一次正式运行

冻结提交已产生，且 Research Console 授权通过后，执行：

```
python tools/bmnv/run_bmnv.py --root . --out build/bmnv-01-official-once
python tools/bmnv/audit_bmnv.py --result build/bmnv-01-official-once
```

执行脚本会在调用考试前先写一个独立 `.intent.json`；考官拒绝重用已存在的输出目录。编译器不可用时，可采用已有 Kotlin Gradle plugin / embedded compiler，保持相同的冻结源，并保存同等运行证据；若工具异常，不得不留痕地多次刷同一试卷。

规模：**48 个场景 × 4 个实验臂 = 192 条学生记录，4 个不同 B 任务/场景**。

四臂：FULL、NO_MEMORY、NO_OBSERVATION、NO_COMPOSE。前三个方向中的 N（自主实验设计）只做有限的证据充分停止；这一版参考算法是固定顺序选择通道，不能声称高信息价值行动优于固定策略。

## 第五部分：总结科研成果，不把机制性 PASS 夸大成开放学习

必须报告（用原始 JSONL 和审计脚本支持）：

1. 48 场景中，有多少 A 学出了行为等价的组合表示；其表达式、观测证据、候选复杂度、测量成本分别是什么。
2. 概念是否以文件形式真实持久化，然后供 4 个 B 任务调用；能否从审计轨迹区分存储、加载、使用。
3. B 的正确数、覆盖数、弃答数；不能用“正确/已回答”替代总体准确率。
4. FULL 与 NO_MEMORY 的 **B 边际观察量**与 **A+四个B的完整观察量**；同时报告候选搜索量、运行时间。哪一方更优，就照实报告。
5. NO_OBSERVATION 和 NO_COMPOSE 为什么失败；明确它们是表达能力/可访问观测的消融，而不是匹配能力的强基线。
6. 学生是否自己发明了语法或发现完全新类型？本包参考学生没有，必须记 `NOT_DEMONSTRATED`。
7. 跨新对象身份成立，不等于跨结构或跨分布泛化；不得用 4 seeds 直接声称统计稳健。
8. 本轮不具备独立闭卷，报告里写 `IMPLEMENTER_RUN / VISIBLE_EXAMINER`。

请严格区分：**机制确实运行**、**发现了新结构实例**、**策略有比较优势**、**自主创造新表示语言**、**独立泛化**这五个不同层级。

## 第六部分：交付

完成后提交一份 ZIP：

`Observable-AI-BMNV-01-2026-11-09-results.zip`

至少包含：`FREEZE.json`、原始 `STUDENTS-FROZEN.jsonl`、学生 SHA256、`EXAMINER-AFTER-FREEZE.jsonl`、`RUN.json`、运行意图 `.intent.json`、`AUDIT.json`、测试日志、具体变更文件和 Git commit、科研评估 `RESULT.md`，以及结果包内文件的 SHA-256 清单。

如果结果出现 `NOT_FOUND`、`AMBIGUOUS`、无收益或错误，请原样保留。**不要替用户自行发起 BMNV-02，也不要自动继续修复直到全绿。** 完成后停止，交给 ChatGPT 做独立复核。
