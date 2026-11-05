# B0 M4 总报告（程序库学习与压缩）

```text
阶段: M4
状态: complete（L1/L2；L3 not_implemented）
起点 SHA: a8fe5f6b94c24b8536de59b3d4af5907f2e6863e（M3-1c）
提交: M4-1a fdad6b5 / M4-1b d06c72a / M4-1c 见本次提交
测试: 170 passed / 170 total, 0 failed
schema_version: 3
independent_closed_book: not_run
verification_source: implementer_run
```

## 交付内容

| 子任务 | 交付 | 提交 |
|---|---|---|
| M4-1a | 冻结 `L_total` 成本模型；L1 正文共享验收、损坏库拒绝、共享统计 | `fdad6b5` |
| M4-1b | 跨任务完全相同子树识别；`extract_helper` 候选、来源路径、依赖 pin、替换 Call | `d06c72a` |
| M4-1c | 原位重写、类型检查、有限域/受控等价、净收益接受或拒绝、发布与撤销门禁 | 本次提交 |

## 结果

- **L1**：同一 `body_hash` 只落一行正文（M2a 已有机制），`L1LibraryAudit` 只统计与计价；两个能力复用同一正文时总成本 20，而非重复计正文。
- **L2**：三个任务共享 `And(color==red, shape==circle)` 时，提出一个 helper 候选并在原位置重写；成本从 59 降到 53（冻结权重）。
- **拒绝**：只共享小 `isRed` 时，节省 2 但 helper 成本 13，净增 → `rejected_candidate`，不激活。
- **等价**：扩展域包含缺字段 `Unknown` 与错误类型 `Error`，重写前后逐点一致；受控集标记 `bounded_verified`。
- **门禁**：helper 与重写版本按“helper → 下游”顺序验证/激活；重启后仍可执行；撤销 helper 后下游返回 `dependency_revoked`；旧版本保留可查。

## 语义边界

1. 压缩不等于学习新知识；helper 只是对已验证程序的重新组织，来源仍是 `student_abstracted_from_verified_programs`（M4 报告口径），不是新事实。
2. 行为等价只在声明域/受控集上成立；`bounded_verified` 不是全域证明。
3. 成本是冻结静态代理，不代表运行时间；没有净收益就拒绝，不为漂亮数字放宽。
4. L3 参数化抽象未实现，报告为 `not_implemented`，不影响 L1/L2。
5. 没有独立闭卷；所有结果为实现者自测。

## 未实现 / 未运行

- L3 反统一与参数化 helper：`not_implemented`。
- A/B/C/D 对照实验与真实迁移收益：M5 `not_implemented`。
- 独立闭卷：`not_run`。

## 下一最小子任务

`M5-1a`：冻结可复现实验协议（任务族、种子、预算、train/development 切分、独立闭卷约定）。
