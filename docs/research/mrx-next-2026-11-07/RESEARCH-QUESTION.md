# MRX Next 2026-11-07 — 研究问题与预注册

## 主问题

在真实 MRX 学生循环中，学生能否依据自己可见的证据维护多个竞争性失败解释，并在证据变化后改变下一步实验选择？

## 假设

- H1：可见的真实反馈足以改变竞争性失败假说的相对证据状态。
- H2：假说状态更新能驱动下一步真实实验选择，而不是只触发固定错误码映射。
- H3：相较 STATIC/RULE，EVIDENCE 方案在至少一类任务上改善成功率或降低成本；本轮不承诺 H3 成立。

## 可证伪条件

- 若同一初始状态、同一第一次失败、第二次失败类型/证据不同，但第三步动作仍相同，则 H2 不成立。
- 若所有假说只能从事后考官真值填写，或证据 ID 不是真实步骤/账本条目，则 H1 不成立。
- 若 EVIDENCE 相比 STATIC/RULE 没有可复现收益，H3 不成立，报告 `INCONCLUSIVE`。

## 信息边界

| 类别 | 本轮实际可见/使用 |
|---|---|
| 学生 Student | 9 TRAIN、29 QUERY、16 CHALLENGE 的合法标签；`MRX2Student` 的步骤、真实反馈、账本条目、剩余预算、候选摘要；不读 HELDOUT/目标真值 |
| 教师 Teacher | 仅提供四个 `BUILTIN_BASELINE` 失败假说模板与记录字段；没有提供新算子、正确答案或手工动作排序 |
| 考官 Examiner | HELDOUT/目标真值只用于测试中的 `StudyQueryController` 构造；不进入学生结果、trace 或假说 |

## 预注册开发案例

1. `mrx-depth-w4`：既有 W4 深度案例，候选保留/辨识相关。
2. `mrx-next-xor-a-d`：独立属性 XOR `(a==1) XOR (d==2)`，检验候选空间覆盖不足。
3. 固定 split seed 1449；9/29/16/27；相同合法动作、查询预算 40、查询源 20。
4. 对照：STATIC（冻结队列）、RULE（FIXED 固定顺序）、EVIDENCE（当前 MRX2E DYNAMIC planner + 证据/假说记录）。

## 预注册判定

- C1 证据真实性：trace 中每个 step/hypothesis/decision 的 evidence ID 必须能在真实 step 或 ledger 条目中找到。
- C2 区分性归因：至少两个竞争假说得到不同支持/反驳/未知状态。
- C3 证据改变决策：需要真实配对世界/轨迹中第二次反馈不同且第三步动作不同；本开发集若无，则 `NOT_DEMONSTRATED`。
- C4 学习收益：STATIC/RULE/EVIDENCE 同条件比较；样本小则 `INCONCLUSIVE`。
