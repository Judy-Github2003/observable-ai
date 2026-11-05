# E3-A1 最小主动挑战闭环报告

```text
阶段: A1
范围: 合法输入选择 → 预测冻结 → 可信标签揭示 → 比较 → 记录证据
状态: complete；未执行知识重新学习
代码/测试提交: 2a86d02f1bd16f1cc71edabcb1a4b1803d75d0b8
B0 冻结 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731
测试: 290/290（B0 173 + E3 117），失败 0
独立闭卷: not_run
```

## 1. 工程实现

新增：

- `src/main/kotlin/logic/e3/E3ChallengeRun.kt`
- `src/test/kotlin/logic/e3/E3ChallengeRunTest.kt`
- `experiments/representation-evolution-e3/reports/a1-challenge-runs.json`

复用：

- `E3ChallengeSession` 的 `ChallengePendingState`
- `A9FrozenSearchRecord` / `A9FrozenCandidate`
- B0 `CapabilityInterpreter` 与冻结记录 `maxSteps`
- `Observation` / `EvidenceProvenance`
- 公开查询池 `PublicSample`

未修改 B0、A7 默认策略、A9 评分逻辑。

## 2. 事件顺序

```text
challenge entry
  -> chooseFirst / choose(authorizationId)
  -> freezePrediction
       - 使用 pending.candidate
       - 生成 predictionId/sequence
  -> reveal()
       - 先校验 prediction 存在、请求/输入/候选绑定
       - 才调用 ChallengeLabelSource
       - 生成新 Observation
       - 比较 SUPPORTED / CONTRADICTED / INCOMPARABLE
```

- `prediction.sequence=1`，`labelSequence=2`；标签源在冻结前不被调用。
- `chooseFirst` 是确定性 `designer_primitive`：按授权池顺序取第一个未用、字段合法的输入。
- 重复查询按 `sampleId` 判断；同一 visible 的不同 sampleId 不因属性相同被直接判重。

## 3. 非法操作拒绝

| 场景 | 拒绝码 |
|---|---|
| 未授权输入 | `unauthorized_or_duplicate_input` |
| 预测前索取标签 | `prediction_not_frozen`（标签源未调用） |
| 标签 visible 与请求不一致 | `label_visible_mismatch` |
| 候选被事后替换 | `candidate_replaced_after_entry` |
| 重复 sampleId | `duplicate_query` / 授权池不再返回 |
| 非布尔预测 | 记录为 `INCOMPARABLE`，不伪造支持/矛盾 |

## 4. 真实运行记录

`reports/a1-challenge-runs.json` 两条：

- `supported`：Q1，`IN_DECLARED_QUERY_DOMAIN`，预测 `false`、标签 `false`，`SUPPORTED`；证据 = 原 5 条 + 新 observation。
- `contradicted`：`challenge-wrong-w-aa63b5b7340df5ce`，`OUTSIDE_DECLARED_QUERY_DOMAIN`，预测 `false`、标签 `true`，`CONTRADICTED`；保留原预测与矛盾观察。

实验二使用了一个由评测端授权的**旧 queryDomain 之外的公开可见组合**，不是隐藏测试样本。该点由测试装置选取，用来回归“矛盾被保留”的机制；它不证明学生已具备普遍的反例发现策略。

## 5. 工程实现 / 实验观察 / 未验证假设

**工程实现完成**

- 单次挑战状态机、预测冻结、标签释放顺序、比较与证据追加。

**实验观察**

- 固定 SOLVABLE 案例支持；
- 固定错误泛化点矛盾，且原预测未被改写；
- 非法顺序和非法输入被拒绝。

**未验证的研究假设**

- 挑战策略具有普遍反例发现能力；
- 挑战后重新学习能修正知识；
- 多世界稳定性；
- 独立闭卷；
- E3 相对 B0 的普遍优势。

## 6. 信任边界

- 同一 JVM；`ChallengeLabelSource` 由测试/评测端提供，没有密码学或进程隔离。
- 事件顺序由 `E3ChallengeRun` 状态机维护，不依赖调用者填写 `prediction_frozen` 字符串；但这仍是同进程控制流，不是不可伪造的外部证明。
- 隐藏真值没有进入挑战运行器接口；测试标签源可读取评测端 truth，属于监考侧。
- `independent_closed_book=not_run`。

## 7. 停止点

未调用 Composer/Filter 重新学习，未创建长期知识系统，未扩展 A9 评分，未执行 A2/B1/C1。

A1 完成，等待审查。
