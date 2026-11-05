# PACK-01 联合研究报告

```text
研究包: PACK-01
子任务: D2 / RES-N02 / A-R1
状态: ACCEPTED_LIMITED（研究负责人裁定；含 PACK-01-EXCEPTION-01）
代码/测试提交: 7b5fa828bbf8531d4e21f7a59de41bcb39ca684c
测试: 307/307（B0 173 + E3 134），失败 0
独立闭卷: not_run
```

## 1. 授权与边界

- Research Console 中 `active_pack=PACK-01`，只包含 `D2`、`RES-N02`、`A-R1`。
- `preflight` 同时检查全局授权、子任务授权、任务状态和 WORKSTATE 同步；其他任务不能因全局授权非空而启动。
- 未修改 B0 核心、A9 评分框架或 A1/C1 已验收代码。
- 未启动 B1、C2、H01 或其他研究方向。

## 2. 实验世界

- 可见属性：`a,b ∈ {"0","1"}`。
- 初始公开证据：`(0,0) -> false`。
- 公开查询池：`q10=(1,0)`, `q01=(0,1)`, `q11=(1,1)`。
- 真实标签只由测试用 `PackLabelSource` 提供；学生/会话侧不读取隐藏目标规则。

## 3. D2：多候选保留

实际记录（`reports/pack01-run.json`）：

- Composer 生成 73 个表达式；
- Filter 初始保留 **36 个一致候选**；
- 状态 `AMBIGUOUS`，没有强行选择一个；
- 每个候选记录 canonical、证据 ID 与来源 `composer_bounded_search`；
- 新证据后重新构建候选集，保留前后快照。

## 4. RES-N02：区分性查询

- 会话在公开查询池中选择 `q10=(1,0)`；
- 选择依据是候选预测差异：`q10` 上候选有 **2 种不同预测**；
- 未使用真实标签选择查询；
- 查询前冻结预测：`frozenSequence=1`，随后才调用标签源；
- 标签返回 `q10=true`，记录为新观察。

## 5. A-R1：反例驱动更新

新证据后重跑现有 Composer/Filter：

- 候选从 **36** 降到 **13**；
- 淘汰 **23** 个与新标签不一致的候选；
- 更新后状态仍为 `AMBIGUOUS`，未伪装成唯一正确规则；
- 旧候选、淘汰集合、前后快照和原因均保留；
- 对更新的 13 个候选在未参与学习的 `(1,1)` 上记录预测，结果仍不唯一（真/假均有）。

因此：

- **D2 成立**：确实保留并更新多个候选；
- **N02 成立**：查询确实由候选预测差异驱动；
- **A-R1 部分成立**：知识状态确实从 36 个候选变为 13 个，23 个被证伪；但没有产生唯一可激活的新规则，也没有更换 B0 已激活能力。

## 6. 边界场景

- 无合法查询：`chooseDistinguishing()` 返回 null，不伪造实验。
- 预测前取标签：`reveal()` 抛出 `IllegalStateException`。
- 新证据导致所有候选失效：通过直接观察更新测试，状态变为 `UNDETERMINED`，返回 `no_candidate_after_new_evidence`。
- 不把失败写成成功；未通过验证的候选没有进入 B0 激活。

## 7. 研究结论

PACK-01 证明了一个受限的联合机制：

```text
多候选 (36)
  -> 候选预测差异 (q10, 2 类)
  -> 冻结预测
  -> 获取标签 (true)
  -> 淘汰 23 个候选
  -> 13 个候选保留，状态仍 AMBIGUOUS
```

**已验证**：三个模块可以在同一会话中连续运行，且新证据确实改变了知识状态。

**未验证**：

- 学生能自主选择高价值实验策略；
- 修正后形成唯一正确规则；
- 更换已激活 B0 能力；
- 多世界稳定性；
- 独立闭卷；
- 相比固定查询策略的普遍优势。

## 8. 未完成与风险

- 该世界只有一个初始样本，候选空间存在大量逻辑上冗余但行为不同的表达式；36→13 是真实变化，但不等同于接近目标规则。
- 修正后仍 `AMBIGUOUS`，不能宣称知识修正完成到唯一规则。
- 未调用 B0 注册/激活/撤销，因为没有唯一稳定候选；不能声称版本化知识替换已验证。
- 同 JVM、测试用标签源；`independent_closed_book=not_run`。

## 9. 下一步

三个子任务及研究包已按研究负责人裁定写回 `ACCEPTED_LIMITED`；例外额度 `1/1` 已使用。不得启动 PACK-02 或其他任务。
## 10. PACK-01 唯一补丁（7b5fa82）

补丁额度 1/1。

### FIX-01：当前知识状态

- `current()` 现在返回最新快照，而非初始快照。
- `history()` 保留 initial + 每次更新快照；原始证据不覆盖。
- `reveal()` 成功后把新快照设为当前状态；后续 `chooseDistinguishing()` 从更新后的候选集合出发。
- 支持连续两次合法观察；第二次查询必须来自更新后的候选集。
- 重复揭示同一查询被拒绝；仍为 AMBIGUOUS 时不标记 IDENTIFIED。

### FIX-02：会话自身冻结预测

- `freeze(query)` 校验 sampleId 与完整输入 (a,b)，不信任调用方 predictions。
- 冻结时用当前候选重新计算预测，伪造/篡改预测拒绝。
- 冻结记录绑定 sampleId、输入、候选 canonical 集合、预测和序号。
- `reveal()` 必须存在有效冻结；重复揭示拒绝。
- 新观察保留 `sourceId`；知识快照不丢来源。
- 不使用隐藏标签决定查询。

### FIX-03：Console 门禁

- 研究包模式保留白名单；非包模式要求任务授权与全局授权一致。
- `finish()` 只允许已授权且 IN_PROGRESS/PATCH_ONCE 的任务提交 REVIEW；REVIEW/ACCEPTED/PAUSED/未授权均拒绝。
- WORKSTATE 检查增加实际 authorization、包状态、子任务状态匹配，而不只是字符串存在。
- `tools/research-console/test_console_gate.py`：4/4 通过。

### 补丁后实验结果

- 原始 36→13 结果保留，测试世界未修改。
- 连续两轮合法观察测试通过。
- 伪造预测、改变输入、未冻结揭示、重复揭示均拒绝。
- 仍不要求唯一规则、不要求 B0 激活替换、不要求效率提升。

### 仍未验证

- 连续更新后仍未形成唯一可激活规则；
- 未更换 B0 已激活知识；
- 未证明查询策略优于固定策略；
- 独立闭卷仍为 `not_run`。
## 11. 验收裁定与一次性例外（已写回）

研究负责人裁定：

- D2：`ACCEPTED_LIMITED`
- RES-N02：`ACCEPTED_LIMITED`
- A-R1：`ACCEPTED_LIMITED`
- PACK-01：`ACCEPTED_LIMITED`

例外记录：

```text
exception_id: PACK-01-EXCEPTION-01
exception_limit: 1
exception_used: 1
scope: console_state_and_gate_tests_only
authorized_by: research_owner
```

修补轮次：普通补丁 `1/1` + 本次例外 `1/1`；普通上限仍为 1，不继承给其他包。
子任务 `patch_used` 已同步为 `1/1`；没有重新开启研究执行权限。

保留限制：

- 主要是受控小世界实验；
- 可以维护多个候选并根据证据更新；
- 可以使用候选分歧选择合法查询；
- 能根据反例修正候选集合；
- 尚未证明普遍的自主科学发现；
- 尚未完成已激活知识的完整版本替换闭环；
- `independent_closed_book=not_run`。
