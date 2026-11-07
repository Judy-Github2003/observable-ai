# SD-01 预注册：结构发现与迁移

- 任务：`SD-01-STRUCTURE-DISCOVERY-2026-11-07`
- 基线：`f4b0d14`（实际执行 HEAD）
- 性质：小规模开发实验，不是正式考试
- 正式 160 / 独立闭卷 / 全量回归：`NOT_RUN`

## 固定变量

| 项 | 冻结值 |
|---|---|
| 原语/类型 | 现有 Typed IR、`Pack02Knowledge`、`MRX2BSpace` 类型检查/解释器 |
| 发现集 | `mrx-depth-w4`（W4 类）与独立 XOR `(a==1) XOR (d==2)` |
| split | seed 1449：9 TRAIN / 29 QUERY / 16 CHALLENGE / 27 HELDOUT |
| 搜索预算 | 既有 `MRX2BSpace`：maxCandidates=30,000、consistentLimit=2,000；策略 PAIRWISE 与 BASELINE |
| 证据 | TRAIN + 合法 CHALLENGE；HELDOUT 不进入学生 |
| 结构判定 | 在 CHALLENGE 16/16 通过；结构类别 PROGRAM_INSTANCE / PARAMETRIC_MACRO / GENERATION_RULE |
| 迁移 | 另一任务族的 TRAIN cases；记录 checks/correct |
| 消融 | `BASE_SEARCH`（同生成器，不保留结构）；`STRUCTURE_DISABLED_TRANSFER`（禁用结构，真实重跑/重放） |
| 失败判据 | 无候选或 CHALLENGE 不通过 → 不注册；只有 PROGRAM_INSTANCE 不算 SD1；无真实调用/禁用差异不算 SD1 |

## 预注册问题

- RQ1：学生能否从通用原语生成多个候选，用 TRAIN/CHALLENGE 证据筛选？
- RQ2：能否提炼**参数化**可调用结构，并在独立任务真实调用？
- RQ3：相对 BASE_SEARCH，候选/查询/解释器/墙钟是否有收益？

## 负对照

1. 固定有界枚举器直接找到答案：最多 SD0，不是新搜索算法。
2. 封闭程序实例（无参数）不得计 SD1。
3. 只在 TRAIN 一致、未过 CHALLENGE 的候选不得激活。
4. 禁用结构但无真实调用差异 → 无收益。
5. 不读取 HELDOUT/目标真值；不得针对 W4/XOR/变量名写生成分支。

## 停止条件

- W4 可生成候选并 CHALLENGE 通过 → 最多 SD0；若无可调用参数化结构或迁移收益，SD1 `NOT_DEMONSTRATED`。
- XOR 在预算内无正确候选 → 记录 G1/G2 类阴性，不修改语法补答案。
