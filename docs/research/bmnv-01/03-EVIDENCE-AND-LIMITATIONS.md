# BMNV-01 证据审查清单与限制

## 方法和实现的区分

- `BMNV01ReferenceStudent`、`BmnvExpr`、`BmnvGrammar` 由 ChatGPT 在本包中提供；这是已写出的 Kotlin 程序，不是 AI 通过实验自行发明的学习方法。
- 自主性质仅限程序在运行时根据测量证据选择 **具体的表达式、传感器组合、B 任务包装规则**，而不是开发者预先为每个场景指定的 `targetId`。
- 可以给出“在预定义假设空间内发现并复用关系”的限制性结论；不能据此声称摆脱神经网络、通用理解或无限开放自我扩展。

## 证据等级

- **代码完整性**：冻结 hash 与运行前后的文件真实匹配。
- **机制执行**：探针读端口实际调用、文件持久化实际发生、B 读取文件并调用表达式，输出有可追溯依赖。
- **有限测评**：学生输出先全量冻结，再写评分；Python 与 Kotlin 独立实现的真值函数一致。
- **策略因果证据**：预算/权限/表达空间公平的配对对照才可支持优势；本包若未实现同预算强对照，不得声称本层级。
- **独立闭卷**：本包不能达到，必须另由独立考官保管隐藏代码和未见目标。

## 关于 N 方向的特殊说明

参考程序的通道读取次序是 ALPHA、BETA、GAMMA，搜索到唯一行为类就提前停止。它确实会根据测量结果决定是否停止，但并**没有**根据期望信息增益主动排序通道。N 的科学实验策略优势应判为 `NOT_DEMONSTRATED`，除非有新预注册实验及独立对照予以证明。

## 数据局限

A 有 16 条观察，B 四类任务各有 16 条训练、16 条新对象 heldout。这些对象共享相同 8 个测量组合；并非组合外的测试。若 FULL、NO_MEMORY 都 100%，只能说明这个有限语法问题已被求解，不能证明广泛泛化。

## 关于成本

FULL 会额外承担 A 学习成本，但能在四个 B 任务复用时减少重复测量；NO_MEMORY 不承担 A 学习成本，但 B 每轮重学。必须同时给出逐任务收益、累计收益和算法计算代价。当前参考实现的存储格式可被其他程序读取，但还没有知识失效、冲突撤销和概念版本管理机制。

## 关于评测完整性

Kotlin 考官计算真实分数，Python 审计独立重算每条 ground-truth 真值、预测覆盖、读端口账目及 A 概念真值表。Python 不自动验证发行者不可作弊、仅运行一次、GitHub 历史真实不可篡改，也不能排除执行者看过同包考官后人工调参。因此实验只属于可见评测。

## 建议结题陈述模板

- `BMNV-01 / IMPLEMENTER_RUN / VISIBLE_EXAMINER`
- `MECHANISM_B`: PASS / LIMITED / FAILED
- `MECHANISM_M`: PASS / LIMITED / FAILED
- `MECHANISM_N`: PASS / NOT_DEMONSTRATED / FAILED
- `EVIDENCE_V`: PASS_STRUCTURAL / INVALID / INCOMPLETE
- `TRANSFER`: ...（要求给出总成本和对照）
- `AUTONOMOUS_GRAMMAR_INVENTION`: NOT_DEMONSTRATED
- `INDEPENDENT_CLOSED_BOOK`: NOT_RUN
- `NEXT_ACTION`: RETURN_TO_A_Z_RESEARCH_SELECTION

若旧历史全量回归因超过 30 分钟或 CRLF/manifest 原因未通过，不得以本轮快速测试替代历史结论，也不应把旧错误悄悄“修绿”。
