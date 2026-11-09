# ABDMNV-01 M2-02 结果：候选可辨识性与受控公开查询

执行者：DeepSeek Harness，2026-11-09。
授权：`ABDMNV-01-M2-02-2026-11-09`（ACTIVE repair grant，preflight `AUTHORIZED_REPAIR`）。
输入：`M2-01-Partial-Coverage-Review-and-Fix-2026-11-09.zip`
（sha256 `301eb23a213275b6fe1c359e191f47de60d0cb4b3e39289a5eb4dd62d6fd4658`）。

## 结果

| 套件 | 结果 |
|---|---|
| M2 可辨识性新测试 | **6 / 6 PASS** |
| M2 原公开测试 | **37 / 37 PASS** |
| M1 回归 106+30+16+5 | **157 / 157 PASS** |
| 合计 | **200 / 200** |

### 5-of-8 部分覆盖（8 世界 × 56 子集 = 448/臂）

| 臂 | 产出概念 | 留出 MATCH | 矛盾 | 弃答 | 多语义类 | 查询 | 覆盖率 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 无查询 | 170 | 170 | **0** | 278 | 248 | 0 | 38.0% |
| 查询预算 3 | 418 | 418 | **0** | 30 | 0 | 1,254 | 93.3% |

对照审计基线（修复前）：produced=418、match=272、**contradictions=146**、multiple_survivors=312。
修复后同一 448 子集上矛盾降到 **0**；查询臂覆盖率 93.3%，无查询臂 38.0%——不是靠“全部弃答”换零错误。

### 超语法负世界（3 原子目标）

- 全量 8 训练：`ABSTAIN_NO_CONSISTENT_CANDIDATE`，不伪造概念。
- 5 组合部分训练 + 查询预算 3：查询 2 次发现矛盾后 `ABSTAIN_NO_CONSISTENT_CANDIDATE`。

## 修复内容

- `M2CompositionalStudent`：survivor 先按**公开探针域**（属性域笛卡尔积，只含属性、不含标签）的行为指纹分语义等价类；
  多类时按候选分歧选公开查询；单类但覆盖不足时用查询补全，补全后仍不足则标 `STUDENT_COMPOSED_PARTIAL_DOMAIN` 并给出 `PARTIAL_k_OF_n` 范围。
- `equivalentAlternatives` 改为**类内语法重复**数；新增 `semanticClasses`、`queriesUsed`、`domainSize`、`scope`。
- 新增 `PublicQueryChannel` 与 `M2PublicQueryChannel`（预算内只回答公开标签）。
- 新增 `M2PartialCoverageExperiment`（两臂对照 + 负世界）与 `M2IdentifiabilityTests`（6 条）。
- M1 源码未改。

## 边界

公开 fixture、非隐藏考官；查询通道回答的是公开属性组合标签，不是留出对象评分数据；M1/M2 均为固定语法。
不假装解决“目标不在语法内”的部分覆盖情形：没有查询且覆盖不足时只能给出带范围标注的部分推断。
