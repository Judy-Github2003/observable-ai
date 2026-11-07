# SD-03 Blind Attribution 预注册

- 任务：`SD-03-BLIND-ATTRIBUTION-2026-11-07`
- 起始 HEAD：`f12f7c0`（根因诊断）
- Console：`SD-03-BLIND-ATTRIBUTION-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 冻结变量

| 项 | 值 |
|---|---|
| 开发教材 | 旧 A/B、seed 1449、ROOT-CAUSE-MATRIX（不计入盲测） |
| 盲任务 | `MRXWorlds.baseline` 中 EXPRESSIBLE/DEPTH/IF_STRUCTURE 各 id 排序前 2，共 6 |
| 种子 | 20261107、20261108（12 个任务-种子） |
| 分割 | `C8Domain.all().shuffled(Random(seed))`：40 TRAIN /20 CHALLENGE/21 HELDOUT |
| 学生 | MRX2BSpace PAIRWISE/10，maxCandidates=30000，consistentLimit=2000 |
| Arms | CONTROL=KEEP；GENERIC=DIVERSITY/10；ATTRIBUTION=盲快照决策（KEEP/DIVERSITY/BASELINE/PAIRWISE12/ABSTAIN） |
| 评估 | Challenge pass 数；HELDOUT 仅考官侧，不进入学生/归因器 |
| 归因器 | `SD03BlindAttributor`，只接收 TRAIN observations、ranked atoms、lifecycle、allowed actions |

## 关卡

- G0：快照无 truth/world id/category/heldout；时间不可回填。
- G1：≥2 竞争假说 + UNKNOWN；ABSTAIN 合法。
- G2：区分性干预；单次静态观察只算 SUPPORTED。
- G3：证据导致行动变化；CONTROL/GENERIC 无同样变化。
- G4：HELDOUT 收益；成本可信。
- G5：跨盲任务复用；无收益则不达成。

## 停止

若归因器全部 ABSTAIN 或没有证据支持行动，不强行制造 action；报告 NOT_DEMONSTRATED。
