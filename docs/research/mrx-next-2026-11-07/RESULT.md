# MRX Next 2026-11-07 — 研究结果

- 任务：MRX-NEXT-2026-11-07
- 性质：有教师参与的小规模开发实验
- 起始 HEAD：`7f18114`
- 正式考试：`formal_runs = 0`
- 独立闭卷：`not_run`
- 全量回归：`NOT_RUN`

## C1 证据真实性：PASS_LIMITED

- 6 条真实 `MRX2Student` 轨迹（W4/独立 XOR × STATIC/RULE/EVIDENCE）均由真实 executor、`MRX2Ledger` 产生。
- 每条假说/决策的 evidence ID 来自真实 `MRX2Step` 或账本条目；没有用 `stepIndex` 冒充账本版本。
- trace 中无 HELDOUT、目标真值或考官 G1–G5。
- 限制：
  - 候选数量在 `MRX2Step` 中没有精确 before/after，trace 中未填 0 伪装；
  - `diagnosisBefore/After` 仍复制最终诊断，不能作为“假说状态前后独立变化”的强证据；
  - 假说是运行后由 `MRXNextAnalyzer` 生成的 `BUILTIN_BASELINE`，不是学生在执行中自主维护。

## C2 区分性归因：PASS_LIMITED

真实轨迹中假说状态确实分化：

- W4：
  - `H_COUNTEREXAMPLE_PRESSURE = CONFLICTED`
  - `H_STRUCTURAL_COVERAGE = REFUTED`
  - `H_BUDGET_LIMIT = REFUTED`
  - `H_UNKNOWN_EXECUTION = REFUTED`
- 独立 XOR：
  - `H_COUNTEREXAMPLE_PRESSURE = SUPPORTED`
  - 其余三个假说 `UNKNOWN`

支持/反驳证据分别来自真实 step；没有把考官结论灌入学生。  
限制：四个假说是教师/内置模板，不是学生自主生成；排序和记录发生在运行后，不能据此声称“学生独立提出并维护”。

## C3 证据改变决策：NOT_DEMONSTRATED

需要的是严格配对：

> 同初始状态、同第一次失败、同第二动作；第二次真实反馈不同；第三步动作因此不同。

本轮两个开发案例：

- W4：第二次行动 DNF 直接 `PASS_ON_CHALLENGE`，没有两次失败；
- 独立 XOR：STATIC 与 EVIDENCE 第三步都是 CNF；RULE（FIXED）第三步是 DNF，但差异来自策略/队列，不是“第二反馈不同”的配对证据。

因此：

```text
C3 = END_TO_END_NOT_DEMONSTRATED
```

不能把“DYNAMIC 与 FIXED 动作不同”包装成证据驱动学习。

## C4 学习收益：INCONCLUSIVE

| 案例 | STATIC | RULE | EVIDENCE | 结论 |
|---|---|---|---|---|
| W4 | REPAIRED，2 步，74 calls | REPAIRED，4 步，145 calls | REPAIRED，2 步，74 calls | 无成功率增益；与 STATIC 相同，比 RULE 少步数/调用 |
| XOR | INSUFFICIENT，4 步，141 calls | INSUFFICIENT，4 步，119 calls | INSUFFICIENT，4 步，141 calls | 无结果增益；RULE 调用更少 |

- W4 的 EVIDENCE 与 STATIC 相同，不能归因于新证据策略；
- XOR 三种都失败，说明候选空间/表达覆盖是主瓶颈，更多查询/规划不能解决；
- 样本只有两个开发案例，报告 `INCONCLUSIVE`，不计算泛化百分比。

## 教师贡献明细

- `BUILTIN_BASELINE` 四个失败假说模板；
- 设计 W4/XOR 开发案例、split、预算与 STATIC/RULE/EVIDENCE 对照；
- 编写分析器与 trace 格式；
- 未提供目标真值、正确答案、手工动作或 HELDOUT 标签；
- 未把教师提出的假说记为学生自主发现。

## 真实结论

本轮最可靠的发现不是“学生学会了动态重规划”，而是：

1. 真实轨迹可以被记录成“证据 ID → 假说状态”的可审计链；
2. W4 的瓶颈不是预算/Unknown，而是第二次行动已直接成功；
3. 独立 XOR 的瓶颈仍是候选空间覆盖，三种策略都无法成功；
4. 第二轮失败的不同类型没有在真实配对中出现，C3 不能成立。

## 下一轮按信息价值排序的问题

1. 如何构造**同初始、同第一失败、第二反馈真实不同**的配对世界，而不依赖硬编码世界/动作？
2. 学生能否在执行前维护假说并给出“区分性实验”的评价，而不是运行后由分析器补写？
3. 对 XOR 类候选空间缺失，哪种可观测原语/表达扩展能产生可验证候选，且不泄漏目标？

## 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain "-PquickTests=logic.e3.pack02.MRXNextTraceTest"
.\gradlew.bat quickTest --offline --console=plain
```

- `MRXNextTraceTest`：1/1，43s；
- 默认 quickTest：14/14，1s（编译已缓存）；
- 未运行全量 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。

## Git

- 起始：`7f18114`
- 最终：见本轮提交
- `formal_runs = 0`
- `fullRegression = NOT_RUN`
- `independent_closed_book = not_run`
