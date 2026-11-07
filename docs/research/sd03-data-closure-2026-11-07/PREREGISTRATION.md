# SD-03 Data Closure 预注册

- 任务：`SD-03-DATA-CLOSURE-2026-11-07`
- 起始 HEAD：`caf9d5d`（16-pair 审计）
- Console：`SD-03-DATA-CLOSURE-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 固定变量

| 项 | 冻结值 |
|---|---|
| 输入域 | `C8Domain.all()`，81 个，`a/b/c/d ∈ {0,1,2}` |
| 旧世界 B | `b=="3" && c=="3"`（仅负对照，预期恒 False） |
| 新世界 A | `a=="1" && d=="1"` |
| 新世界 B | `b=="1" && c=="1"` |
| 分割 | `C8Domain.all().shuffled(Random(1449))` → 40 TRAIN /20 CHALLENGE/21 HELDOUT |
| 质量门禁 | 全域与各 split 均有正负；否则 `INVALID_SPLIT` 停止 |
| 来源 | `MRX2BSpace` PAIRWISE；每世界最多 4 个真实 `PASS_ON_CHALLENGE`；`MRX2Ledger` 证据 |
| 宏 | 现有 typed LGG + 通用自由变量封闭；恰好两来源 |
| 下游 | 仅有合法宏时注册/迁移；否则 NOT_RUN |
| 停止 | 任一来源池为空 → `NO_VERIFIED_SOURCE_POOL`；不换 seed/扩大模板 |

## 负对照

- 旧 B 81/81 False 必须作为无效数据记录；
- 不手工 AST、不直接 `truth(input)` 选择来源；
- 不把 2 个非退化但自由变量未闭合的旧 pattern 当成功；
- 不把 `InMemoryResolver` 调用冒充注册；
- 不用 0/0 作为 DISABLED。
