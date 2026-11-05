# E3-A4 复核修正 + A5 工作报告

```text
阶段: E3-A
范围: A4 objectId/worldId 边界修正 + A5 只读 B0 诊断适配器
状态: complete
代码/测试提交: b64db2ae5bdffea34f392e84a79acd0605ab26f5
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 217/217（B0 173 + E3 44），失败 0
独立闭卷: not_run
```

## 1. A4 两项修正

### CollisionParticipant.objectId

- 增加 `objectId`；保留 `observationId/sourceId/round/label`。
- 现在可以区分：
  - 同一对象、不同来源/轮次的重复测量；
  - 不同对象但同一完整可见向量的碰撞。
- JSON 编码同步加入 `objectId`；测试覆盖同对象与不同对象两种情况。

### worldId 安全边界

- 当前 `w-<SHA256(seed/index/family) 前 8 字节>` 只是**不需要额外存储的伪匿名 ID**，不是安全隔离。
- 若 seed 低熵，持有算法者可枚举候选 seed/index/family 并比对 ID；因此正式考试不能依赖该哈希隐藏生成参数。
- 正式实验要求：
  - 评测生成器、seed、HiddenTruth 在独立进程/独立 classpath；
  - 学生只接收 `StudentView`（公开观察 + 受控 queryPool 查询）；
  - seed 不写入学生可见文件/日志；
  - 如需不可关联 ID，使用评测端秘密盐或运行期随机映射，但这属于考试基础设施，不在 A5 扩展。
- 本轮不实现复杂身份系统，只记录风险与部署要求。

## 2. A5 适配器

文件：`src/main/kotlin/logic/e3/B0DiagnosticAdapter.kt`

```text
E3B0DiagnosticAdapter.diagnose(
  schema, observations, constants, queryDomain, budget
) -> DiagnosticSignal
```

- 只调用冻结 `CapabilityComposer`、`CandidateEvidenceFilter`；不改 B0。
- 完整 Known + 有标签的观察转为 B0 `PublicExample`；`ObservedValue.Unknown` 和未标注观察显式排除并记录 ID，**绝不把 Unknown 变成普通字符串**。
- 复用 A4 `ObservableCollisionDetector`；发现同向量标签冲突时状态为 `OBSERVATION_COLLISION`，不启动 Composer/Filter，避免假装搜索能解决逻辑矛盾。
- 记录：
  - `SearchTrace(maxDepth, generated, exhausted, reason, candidateCount)`
  - `FilterTrace(status, reason, survivorCount, queryCount, domainSize)`
  - 有界范围 `EvidenceScope(hypothesisSpace, observationDomain, queryDomain)`
- 状态严格区分：
  - `INVALID_INPUT`
  - `NO_COMPLETE_OBSERVATIONS`
  - `OBSERVATION_COLLISION`
  - `IDENTIFIED_WITHIN_BOUNDED_SPACE`
  - `AMBIGUOUS_WITHIN_BOUNDED_SPACE`
  - `REFUSED` / `NO_CANDIDATE`
- `depth_limit` 只记录为搜索截断，并附注 `does NOT prove the target rule is outside the hypothesis space`；不把它写成表示不足。
- `IDENTIFIED` 只表示当前 `maxDepth/maxNodes/library=[]` 假设空间与公开 queryDomain 内辨识，不是全局真理。
- 不 import evaluator；不读取 `WorldFamily`、`HiddenTruth`、target rule 或 `ruleDepth`。

## 3. 真实输出（样例）

`reports/a5-diagnostic-signals.json`（seed 101/103/107/109，仅公开输入）：

| case | status | search | filter |
|---|---|---|---|
| solvable | IDENTIFIED_WITHIN_BOUNDED_SPACE | depth3, generated 1255, depth_limit, 1246 candidates | IDENTIFIED, survivor 1 |
| latent | OBSERVATION_COLLISION | 未运行 | 未运行 |
| search_limited | AMBIGUOUS_WITHIN_BOUNDED_SPACE | depth3, generated 181, depth_limit, 175 candidates | AMBIGUOUS, survivor 6 |
| ambiguous | AMBIGUOUS_WITHIN_BOUNDED_SPACE | depth3, generated 181, depth_limit, 175 candidates | AMBIGUOUS, survivor 20 |

- latent 冲突出现在 `objectId=obj-0a/obj-0b`、不同来源、同轮；适配器保留该证据并跳过搜索。
- `solvable` 的 `IDENTIFIED` 仍受限于 queryDomain（本样例只有 2 个公开查询点），不是全局证明。
- search_limited 的 `depth_limit` 不能单独证明真实规则不在假设空间。

## 4. 测试

- 适配器：SOLVABLE、LATENT（冲突）、SEARCH_LIMITED（depth_limit）、AMBIGUOUS、部分 Unknown、全 Unknown、非法输入。
- A4：objectId 同/不同对象、来源/轮次、Unknown、无冲突、编码。
- 全套 **217 passed / 217 total，0 failed**；B0 173/173；E3 44/44。

## 5. 未做 / 下一步

- 未实现 A6 搜索空间覆盖诊断、A7 之后策略。
- 未修改 B0；E3-B/C 未授权。
- 下一步：`A6` 搜索空间覆盖与预算诊断。
