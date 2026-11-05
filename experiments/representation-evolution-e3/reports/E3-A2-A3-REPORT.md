# E3-A2 审查修正 + A3 工作报告

```text
阶段: E3-A
范围: A2 审查修正（4 项）+ A3 世界族
状态: complete
A2/A3 代码提交: 797ba371150f11a6a29d02dbae99e9650c0302ee
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 197/197（B0 173 + E3 24），失败 0
独立闭卷: not_run
```

## 1. A2 审查修正

1. **held-out 隔离**
   - `HiddenTruth` 新增显式 `heldOut: List<HiddenPoint>`，不再从 `fullDomain` 的 D 系列 ID 推断。
   - 测试直接检查 public observations、queryPool、`truth.heldOut` 三组可见向量两两不重叠，并断言 heldOut 恰为 2 个。

2. **种子变化测试**
   - 签名移除了 `worldId`、sampleId、seed 及派生 ID，只比较 schema、可见值/标签、来源/轮次、查询池可见向量、有限域真值。
   - 不同 seed 必须在实际内容上变化，不能再靠 ID 必然通过。

3. **Oracle 查询权限**
   - 新增 `PublicQueryOracle`：只保存 queryPool ID→label，`query(id)` 对其他 ID 返回 `Rejected("query_not_allowed")`。
   - 新增 `StudentView(public, query)`；`EvaluatedWorld.studentView()` 不携带 `HiddenTruth`/`fullDomain`。
   - 测试验证 H/D 系列 ID 和任意猜测 ID 均被拒绝，`allowedIds` 只等于 queryPool。

4. **世界类型泄漏**
   - 所有公开 `worldId` 改为中性 `world-<seed>-<index>`，不含 family 或 family 派生 hex。
   - 新增 `E3AdvancedWorldGenerator.matchedPair()`：SOLVABLE 与 LATENT_ALIASING 共用相同 schema、字段值域、公开/查询/隐藏数量、来源格式（source-a/source-b、round 1、可靠性声明一致）。
   - 对照世界只能从标签/证据模式区分，不能从字段名、来源格式或 ID 后缀区分。

## 2. A3 三个新世界族

所有 A3 世界使用相同三字段 schema `a,b,c ∈ {0,1}`、来源格式与公开/查询/隐藏分层。

| 家族 | 证据模式 | 测试 |
|---|---|---|
| `NOISY_LABELS` | 同一可靠来源、同一对象、不同轮给出相反标签；声明 `reliableDeterministicLabels=true` | 同可见向量组内同源、可靠、标签冲突 |
| `SEARCH_LIMITED` | 真值为三变量 XOR（`ruleDepth=4`）；无碰撞；所有单字段/AND/OR 浅候选均不拟合公开数据 | `ruleDepth>3`、无碰撞、浅候选枚举不拟合 |
| `AMBIGUOUS_EVIDENCE` | 无碰撞；`a==1` 与 `b==1` 两个浅假设同时拟合公开数据 | 两个不同字段假设均 consistent |

说明：

- `SEARCH_LIMITED` 的 `ruleDepth` 是评测端元数据，不进入公开数据；其公开证据是“浅候选全部不拟合 + 无碰撞”，不是内部 family 标签。
- `NOISY_LABELS` 与 `LATENT_ALIASING` 都可能出现同可见向量不同标签，但前者是**同一可靠来源的时间冲突**，后者是**不同来源且未声明确定性**；这是可审计的证据层区别。
- 这些世界族不证明诊断算法已能正确分类；A4 才做碰撞检测。

## 3. 测试

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'
.\gradlew.bat --offline --no-daemon test
```

- 总计 **197 passed / 197 total，0 failed**
- B0 子集 **173/173**（原冻结测试未改动）
- E3 子集 **24/24**

## 4. 未做与边界

- 未实现诊断算法、A4 碰撞检测、A5 之后任务。
- 未修改 B0 冻结源码；未调用教师；`teacher_calls=0`。
- `LATENT_ALIASING` 仍只证明当前可见属性不能唯一决定标签，不证明学生能发现隐藏属性。
- `independent_closed_book=not_run`；所有结果为 `implementer_run`。

## 5. 下一步

`A4`：可观察碰撞检测器（只做证据检测），完成后停止等待“继续”。

## 复核后更正（2026-11-05）

- 本报告初版称 AMBIGUOUS_EVIDENCE 有 5 条公开观察，实际当时为 4 条。现已补第 5 条同标签重复观测，A3 三族统一为 5 条；详见 E3-A4-REPORT.md。
- 公开 worldId 已改为不暴露 seed/index 的 w-<hash>。

