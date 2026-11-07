# SD-03 Continuation 预注册

- 任务：`SD-03-CONTINUATION-2026-11-07`
- 起始 HEAD：`e2ca963`（SD-03 负结果）；实际代码 HEAD `4603f05` 后无新提交
- Console：`SD-03-CONTINUATION-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 固定变量

| 项 | 冻结值 |
|---|---|
| 开发任务 | 两个匿名合取任务 A/B；40 TRAIN、41 CHALLENGE |
| 来源池 | `MRX2BSpace` PAIRWISE + `MRX2Ledger.validate`；每任务最多 4 个 challenge-pass |
| 来源选择 | 先封存池，再做全 pair 取证；不使用新任务答案/未来分数 |
| 配对上限 | 4×4=16 对；超过不扩 |
| LGG | 现有 `SD02AntiUnify`，恰好两来源 |
| 诊断 | 首次 AST 不一致路径 + `ROOT_MISMATCH/CALL_ID_OR_HASH_MISMATCH/ARITY_MISMATCH/TYPE_MISMATCH/ARG_MISMATCH/UNSUPPORTED_NODE/OTHER` |
| 停止 | 任一对无非退化共同结构 → `G1_NOT_DEMONSTRATED`，不进入注册/迁移 |

## 负对照

- 不手工构造目标 AST 或 evidence ID；
- 不用新任务答案/隐藏分数挑 pair；
- 不把 `Call` 名称/版本/hash 不同视为同一能力；
- 不把 `InMemoryResolver` 调用冒充注册/激活；
- 不用 `null/0/0` 冒充 DISABLED 对照。

## 判定

- G0：真实搜索+账本来源 → 可 `PASS_LIMITED`。
- G1：所有 pair 取证后无合法 pattern → `NOT_DEMONSTRATED`。
- G2–G4：无 pattern 则不运行。
