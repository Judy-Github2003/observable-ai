# BMNV-01 正式结果报告模板（供实施者填写）

> 不要在正式考试前填入任何预测成绩。不要将开发烟测分数冒充正式成绩。

## 1. 环境与冻结
- git HEAD before / after:
- student/examiner frozen source SHA-256:
- Research Console task / approved scope:
- preflight event / completion event:
- Kotlin/JDK/Gradle versions:
- official attempt intent file SHA:
- student output frozen SHA:
- formal run count (must be one):
- independent closed-book: NOT_CLAIMED

## 2. 学生行为与真实机制
- 公开观察碰撞诊断：
- 新观测通道调用真实次数：
- 从公开测量形成的谓词表达式：
- 有限候选数、剩余行为类：
- 概念磁盘保存/再次读取/在四个 B 任务调用的证据：
- 没有模型可辨识时的处理：

## 3. 对照矩阵

| Arm | A 概念可用率 | B 正确/全部 | B 覆盖/全部 | A 观测次数 | B TRAIN+HELDOUT 观测次数 | 总观测次数 | 候选评估量 |
|---|---:|---:|---:|---:|---:|---:|---:|
| FULL | | | | | | | |
| NO_MEMORY | | | | | | | |
| NO_OBSERVATION | | | | | | | |
| NO_COMPOSE | | | | | | | |

## 4. 真正的科研结论
- H-B1（认识信息不足）:
- H-M1（有限语法下新组合谓词）:
- H-M2（真实持久化复用）:
- H-TRANSFER（边际和累计成本）:
- H-N（策略增益是否得到证明）:
- H-V（审计、偏差与泄漏）:
- 异常或失败任务（必须保留原始证据）:
- 哪些结论只在已覆盖的 8 个特征组合下成立:
- 不能声称的能力（独立闭卷、开放语法发明、分布外泛化等）:

## 5. 结果归档
- results ZIP:
- commit SHA:
- STUDENTS-FROZEN.jsonl / SHA256:
- EXAMINER-AFTER-FREEZE.jsonl:
- AUDIT.json:
- FULL regression: NOT_RUN / 另行描述（不得用本次定向测试替代）

## 6. 停止条件
- 不修改已冻结学生或考官以刷成绩。
- 不自动启动 BMNV-02。
- 提交结果后停止，由 ChatGPT 外部审计并回到 26 个方向的组合选择。
