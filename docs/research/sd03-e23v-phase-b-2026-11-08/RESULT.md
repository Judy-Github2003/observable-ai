# E2.3-V Phase B 结果：32 新任务 / 128 冻结臂；LOCAL_FIRST 仅 1 个配对胜、0 负

## 0. 元数据

- 冻结提交：`325e417`
- Phase A 完成 HEAD：`d4ecda1`
- `FREEZE.json` SHA-256：`f14e6102...`
- 考官 ZIP SHA-256：`603d7fa3...`（复核一致）
- 学生源冻结：5/5 字节匹配
- 执行：**一次** `run_e23v --execute --timeout 300`，未重跑
- `independentClosedBook = NOT_CLAIMED`
- `formal_runs=0`；未运行历史全量回归；未启动 SD-04

## 1. 执行

| 项 | 值 |
|---|---:|
| 32 任务 / 128 臂 | 是 |
| 学生最终冻结 | 128 |
| 冻结屏障 | `ALL_STUDENTS_FROZEN.txt` |
| 考官评分行 | 128 |
| 每任务 QUERY | 4（三主动臂完全相同） |
| 实际耗时 | **16.875s** |
| JUnit | 1 test / 0 fail / 0 error / 8.406s |
| 输出目录 | `build/sd03-e23v-14200231780113485732` |

## 2. 审计

`audit_e23v.py` 修复一个字段名后：

```text
E23V_RAW_AUDIT_PASS
tasks=32
studentArms=128
gradeRows=128
```

原始工具缺陷：

- `run_e23v.py` 从 Gradle stdout 找 `E23V_OUTPUT_DIR`，但该行在 JUnit `system-out`，导致正确跑完后 `outputDirectory=null` 并退出 1；未重跑，改为从 JUnit 读取输出目录。
- `audit_e23v.py` 读取 `lostCorrectChallenge`，数据字段是 `lostPreviouslyCorrectChallenge`，触发 KeyError；仅修正字段名，不改统计口径。

## 3. 四臂结果

| Arm | Challenge PASS /32 | HELDOUT 通过任务 | full refit calls | local repairs | program changes | lost prev | gained |
|---|---:|---:|---:|---:|---:|---:|---:|
| STATIC | 12 | 244/252 | 0 | 0 | 0 | 0 | 0 |
| FULL_REFIT | 12 | 244/252 | 128 | 0 | 30 | 13 | 10 |
| KEEP_IF_AGREE | 12 | 244/252 | 10 | 0 | 10 | 13 | 11 |
| **LOCAL_FIRST** | **13** | **264/273** | **1** | **10** | 11 | **6** | **4** |

- HELDOUT 分母不同，不能直接比较 244/252 与 264/273。
- LOCAL_FIRST 多通过 1 个任务，同时 `lostPreviouslyCorrectChallenge` 从 13 降到 6。
- 唯一配对差异任务：`e23v-asymmetric-tree` / `6b98ec...`
  - FULL_REFIT：Challenge 失败
  - LOCAL_FIRST：Challenge PASS，HELDOUT **20/21**
  - LOCAL_FIRST 在该任务有 2 次局部修复、2 次程序变化

### 配对 vs FULL_REFIT

| 对照 | win | loss | same PASS | same FAIL |
|---|---:|---:|---:|---:|
| KEEP_IF_AGREE | 0 | 0 | 12 | 20 |
| LOCAL_FIRST | **1** | **0** | 12 | 19 |

按预注册：

```text
LOCAL_FIRST: paired wins > paired losses, lostPreviouslyCorrectChallenge 6 < 13
→ PROMISING（仅开发域延迟揭示，不是独立闭卷）
```

KEEP_IF_AGREE：

```text
0 win / 0 loss, 12/12 same → NO_BENEFIT vs FULL_REFIT
```

## 4. 成本

| Arm | atom interpreter calls | full split attempts | local split attempts | wall ms |
|---|---:|---:|---:|---:|
| STATIC | 15,360 | 0 | 0 | 325 |
| FULL_REFIT | 83,520 | 478,685 | 0 | 2,087 |
| KEEP_IF_AGREE | 83,520 | 42,260 | 0 | 1,853 |
| LOCAL_FIRST | 83,520 | 4,473 | 2,748 | 1,848 |

（split 数值为 128 行 `fullSplitAttempts/localSplitAttempts` 汇总；不同量纲不可直接相加当总算力。STATIC 的初始 E1 split 为 113,678，未在上表重复列。）

- 所有主动臂 QUERY 数均为 128；STATIC 为 0。
- LOCAL_FIRST 的 10 次 local repairs 与仅 1 次 full refit 是可核查的局部更新，不是整体重学。

## 5. 判定

| Gate | 判定 |
|---|---|
| 代码冻结 | **PASS**（5/5 源哈希） |
| 128 臂先冻结后评分 | **PASS** |
| 四查询匹配 | **PASS**（审计验证 32 任务） |
| Challenge 对照 | LOCAL_FIRST 13 vs 12；paired +1/-0 |
| HELDOUT | LOCAL 264/273，分母不同不可直比 |
| 预注册判据 | **PROMISING** |
| 独立闭卷 | **NOT_CLAIMED** |

## 6. 阴性/限制

- 只有 1 个配对胜，不能据此声称稳定优越。
- 32 个任务中 19 个 LOCAL 与 FULL 同样失败；NESTED/XOR 仍大量失败。
- STATIC 12/32 与 FULL 12/32 相同；本实验没有证明查询本身带来收益。
- 考官源码对实施者可见；这是延迟揭示开发实验，不是独立闭卷。
- 未根据成绩修改算法或重跑。
- 未运行历史全量回归；此前状态仍 `TIMEOUT`。

## 7. 产物

- `EXAMINER-AFTER-ALL-FREEZE.jsonl`（128 行）
- 32 份 `STUDENT-*.jsonl`
- `ALL_STUDENTS_FROZEN.txt`
- `AUDIT.json`
- `JUNIT-E23V.xml`
- `RUN.json` / `RUN.log` / `EXECUTE-STDOUT.log`
- `AUDIT-TESTS.log`、`AUDIT-STDOUT.log`

本轮停止，等待独立审查；不启动 SD-04。
