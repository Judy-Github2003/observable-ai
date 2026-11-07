# SD-03 16-pair 预注册

- 任务：`SD-03-16PAIR-AUDIT-2026-11-07`
- 起始 HEAD：`85a83a2`（SD-03 continuation）；代码 `7e96d6f`
- Console：ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 固定条件

| 项 | 值 |
|---|---|
| 源世界 A / B | `a=1 ∧ d=1`；`b=3 ∧ c=3`（仅 examiner 持有） |
| 学生 | `MRX2BSpace` PAIRWISE，FULL library，RETAINED |
| 数据 | `C8Domain.all().take(40)` TRAIN；`.drop(40)` 41 CHALLENGE |
| 来源池 | 每任务 4 个 `PASS_ON_CHALLENGE`，稳定原顺序 |
| 配对 | 4×4=16；每对必须实际调用 `SD02AntiUnify.generalize` |
| 归纳 | 现有 typed LGG，不新增 AST 重写 |
| 成功 | 非退化 + 对两源精确重建 + typecheck 通过 + 非 root hole |
| 停止 | 无合格 pattern → `E1_NO_VALID_PATTERN`；不进入注册/迁移 |

## 负对照

- 不手工 AST、不选 pair 看结果；
- 不把 `ARG_MISMATCH` 预先判失败，实际调用结果为准；
- 不把仅 16 行日志当 16 次 generalize；
- 不把非闭合 pattern 的 typecheck 失败当成功；
- 不使用旧 RED/编译失败冒充本轮 RED。

## 判定

- E1：16 对实际 generalize 结果。
- E2–E4：无合法 pattern 则 `NOT_RUN`。
