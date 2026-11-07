# MRX Next 2026-11-07 — 对照记录

所有数字来自 `TRACE-*.jsonl` 的真实 `MRX2Student` 运行；不是模拟。
`studentCommit = 7f18114`，开发集 seed 1449，预算/合法动作相同。

## W4 类案例 `mrx-depth-w4`

| 策略 | 状态 | 步骤 | 行动序列 | 新查询 | 账本剩余 | 解释器调用 | 总墙钟 |
|---|---|---|---:|---|---:|---:|---:|---:|
| STATIC | REPAIRED | 2 | VALIDATE_BASE > DNF | 29 | 11 | 74 | 3,574 ms |
| RULE（FIXED） | REPAIRED | 4 | VALIDATE_BASE > KNOWLEDGE > BUDGET > DNF | 29 | 11 | 145 | 6,130 ms |
| EVIDENCE（DYNAMIC） | REPAIRED | 2 | VALIDATE_BASE > DNF | 29 | 11 | 74 | 3,444 ms |

观察：

- 三种策略都修复成功；EVIDENCE 没有成功率优势。
- EVIDENCE 与 STATIC 步骤/查询相同；相对 RULE 少 2 步、少 71 次解释器调用。
- 没有出现两次失败，因此不能用来证明 C3。

## 独立 XOR `mrx-next-xor-a-d`

| 策略 | 状态 | 步骤 | 行动序列 | 新查询 | 账本剩余 | 解释器调用 | 总墙钟 |
|---|---|---|---:|---|---:|---:|---:|---:|
| STATIC | INSUFFICIENT_EVIDENCE | 4 | VALIDATE_BASE > DNF > IF > CNF | 16 | 24 | 141 | 6,227 ms |
| RULE（FIXED） | INSUFFICIENT_EVIDENCE | 4 | VALIDATE_BASE > KNOWLEDGE > BUDGET > DNF | 29 | 11 | 119 | 6,465 ms |
| EVIDENCE（DYNAMIC） | INSUFFICIENT_EVIDENCE | 4 | VALIDATE_BASE > DNF > IF > CNF | 16 | 24 | 141 | 6,082 ms |

观察：

- 三种策略都未成功；EVIDENCE 没有改善结果。
- EVIDENCE 相对 RULE 少 13 次新查询，但多 22 次解释器调用；总成本无明显收益。
- EVIDENCE 第三步 CNF 与 RULE 的 DNF 不同，但第二次反馈均为 COUNTEREXAMPLE/相同可见类型；差异来自策略规则，不是“第二反馈不同”的配对证据。

## 判定

| 项目 | 状态 | 说明 |
|---|---|---|
| 真实执行 | measured | 6 条真实 `MRX2Student` 运行 |
| 模拟/注入 | not_used | 无 mock 反馈、无测试注入动作 |
| 严格配对第二反馈 | **not_run / not_demonstrated** | 两个案例都没有“同初始、同第一失败、第二反馈不同”的配对 |
| STATIC vs RULE vs EVIDENCE | measured | 同预算、同合法池、同 split |
| 160 正式考试 | NOT_RUN | 未授权/未执行 |
| 独立闭卷 | not_run | 未执行 |

**C3 结论：`END_TO_END_NOT_DEMONSTRATED`。**  
**C4 结论：`INCONCLUSIVE`**（W4 无成功差异；XOR 无收益；样本仅两个开发案例）。
