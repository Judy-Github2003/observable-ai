# ABDMNV-01 M2-02r 结果：schema、查询隔离、真实预算、查询证据

执行者：DeepSeek Harness，2026-11-09。
授权：`ABDMNV-01-M2-02R-2026-11-09`（ACTIVE repair grant，preflight `AUTHORIZED_REPAIR`）。
基线：`ABDMNV01-M2-02-Fix-2026-11-09.zip`（sha256 `8ae94ea4…fc74`）。

## 结果

| 套件 | 结果 |
|---|---|
| M2-02r 新回归（schema/查询/预算/证据） | **8 / 8 PASS** |
| M2 原公开测试 | **37 / 37 PASS** |
| M1 参考+聚合+审计+门禁 | **106 + 30 + 16 + 5 = 157 / 157 PASS** |
| 合计 | **202 / 202** |

### 448 × 2 实验

| 臂 | produced | MATCH | 矛盾 | 弃答 | 多类 | 查询 | 对象求值 | 总开销 | 组合一致 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 有限域补全（查询池=其余 3 组合） | 418 | **418** | **0** | 30 | 0 | 1,254 | 113,358 | 118,196 | — |
| 未见组合（查询池=1，评分=另 2 个从未观测组合） | 310 | 151 | **0** | 138 | 108 | 335 | 112,439 | 113,670 | **620/896（69.2%）** |

- 补全臂覆盖率 93.3%，零矛盾；这是“有限域补全”。
- 未见组合臂的 MATCH 只有 151（评分对象只有 2 个组合，非平凡要求常常不满足），组合级一致率 69.2%——这是“未见组合泛化”，明显低于补全。
- 负世界（3 原子目标）：全量训练与 5 组合+查询均 `ABSTAIN_NO_CONSISTENT_CANDIDATE`。

## 四项修复

1. **FiniteDomainSchema**：探针域由世界显式声明（8 组合），不再从训练标签推断；`schema/declared-not-inferred` 用只有红色样本的训练集验证不再报 DOMAIN_INCOMPLETE。
2. **查询池隔离**：`M2PublicQueryChannel` 只回答池内组合，池外（含封存测试组合）返回 null；实验把补全与未见组合分成两臂。
3. **真实预算**：候选类型检查、训练求值、探针比较、查询计费、评分求值全部计入 `objectEvaluations/totalEvaluations`；超预算返回截断并报告真实开销（`budget/actual-cost-on-truncation` 断言 > 预算且非零）。
4. **查询证据入账**：每次查询生成 `QueryEvidence(queryId, worldId, combo, label, cost)`，并写入 `CombinedEvidenceLedger`（`sourceKind=PUBLIC_QUERY`、`signedByExaminer=false`）；`requireVerified` 读取返回 `UNVERIFIED_SOURCE`，不会冒充考官验证数据。

## 边界

公开 fixture；固定两原子语法；schema 上限 8 组合（更大域会截断/拒绝）；查询只按类分歧贪心选点；未见组合一致率 69.2% 是公开域内的数字，不是分布外泛化证明。
