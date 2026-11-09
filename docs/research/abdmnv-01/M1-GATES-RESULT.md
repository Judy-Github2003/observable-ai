# ABDMNV-01 M1 门禁修复轮结果（GATE-01..05）

执行者：DeepSeek Harness，2026-11-09。
授权：`ABDMNV-01-M1-GATES-2026-11-09`（ACTIVE repair grant，preflight `AUTHORIZED_REPAIR`）。
输入：`ABDMNV01-M1-Fix-Audit-Gates-2026-11-09.zip`
（sha256 `61e078629a4799744269ccefa9f424e0917125abf8c281176472c1866635f682`）。

## 结果（本 ZIP 内自包含）

| 套件 | 结果 | 耗时 |
|---|---|---|
| 参考实现公开测试 | 106 / 106 PASS | 186 ms |
| 聚合契约测试 | 30 / 30 PASS | 154 ms |
| 上轮审计回归 A–H | 16 / 16 PASS | 182 ms |
| 本轮门禁 GATE-01..05 | 5 / 5 PASS | 119 ms |
| 合计 | **157 / 157** | |

公开 held-out 演示：训练 a–d、评分 e–h；w_01 泛化 MATCH，w_02 留出 CONTRADICTION，w_03 弃答。

## 修复摘要

- **GATE-01**：`executable` 要求依赖全部 `ACTIVE_WITH_SCOPE`；`activate` 前置检查，否则 `DEPENDENCY_NOT_ACTIVE`。
- **GATE-02**：`revokeDependents` 先用 `get(ref)` 做 hash 绑定校验；伪造 ref 返回空列表，不级联。
- **GATE-03**：`checkGraph` 增加 scope 规则（局部→同 world/global；global→global），违规 `CROSS_WORLD_DEPENDENCY`。
- **GATE-04**：`CombinedSlotBoard` 增加可信 `ConceptCatalog`；`submitConcept` 校验 kind/sort；无来源时 `submit(ref)` 拒绝。
- **GATE-05**：`requireVerified` 要求 `EXAMINER_VERIFIED` **且** `signedByExaminer`；pipeline 只在受信读取后构造已签署视图。
- **公开 held-out**：`CombinedEndToEndDemo` 训练/留出分离，打印 in-sample 与 held-out，含失败与弃答。

## 边界

`signedByExaminer` 是结构标记，不是密码学签名；无 L2 重放、隐藏考官、六臂实验、历史全量回归。
修复代码在独立目录产出并以 ZIP 交付；仓库只登记授权与本说明。
