# V2：最小规则自主发现（discovery-v1）

> 实现：DeepSeek Harness（本会话），2026-11-04。任务书见 [SPEC.md](./SPEC.md)，接口见 [CONTRACT.md](./CONTRACT.md)。
> **状态：M1–M3 已完成，公开开发测试通过；独立闭卷 `not_run`。**

## 这一轮要证明什么

目标规则事先**不在**学生规则库里。学生只能看到对象属性、有限假设语法和逐条教师标签，必须自己：

```text
枚举候选 → 用正负样本筛候选 → 主动挑最有区分度的对象提问
→ 吸收标签、淘汰候选 → 在可辨识时晋升 → 关闭教师预测新对象
```

**不是**让教师把 `if (颜色==红色 && 形状==圆形)` 写好交给学生。实现里没有任何具体颜色、形状或训练对象 ID 的分支。

## 支持范围

- 规则语法：单条件 `field == value`，或双条件 `AND`（不同字段）；
- 候选只从输入 `feature_schema` 枚举，规范化去重、稳定排序；
- 三值逻辑：缺属性 `unknown`；AND 中已知 `false` 优先；
- 同对象正反证据 → `conflict`，两条都保留；
- 晋升前必须在完整有限域上只剩一个等价类；
- 规则由解释器执行，缺属性返回 `unknown`。

不支持：OR、否定、嵌套、数值阈值、自然语言理解、视觉/听觉。

## 快速开始

```powershell
# 构建（JDK 21）
./gradlew.bat --no-daemon test installDist

# 生成 E1 全程追踪
python tools/rule_discovery_v1.py --worlds 30 --seed 20261104
```

演示器用**独立 Python oracle** 计算标签，不调用学生的 Kotlin 判分函数；CLI 以 `-` 从 stdin 逐行交互，一个 JVM 跑完一个世界。

## E1 实际追踪（`reports/e1-trace.jsonl`）

| 步骤 | 结果 |
|---|---|
| start（A=true, B=false） | 8 个候选，剩 **3** 个：`颜色==红色`、`形状==圆形`、`红 AND 圆` |
| next_query | 选 **C**（红、方）；分组 true=1 / false=2 |
| observe C=false | 淘汰 `颜色==红色`，剩 **2** 个 |
| next_query | 选 **D**（蓝、圆）；分组 true=1 / false=1 |
| observe D=false | 淘汰 `形状==圆形`，剩 **1** 个 |
| finalize | 晋升 `(颜色==红色) AND (形状==圆形)`，domain_size=4 |
| predict 新对象 | 红圆 true、蓝方 false、红方 false、蓝圆 false |

完整事件在 `reports/e1-events.jsonl`：`hypotheses_generated → query_selected → observation_received → hypothesis_eliminated → rule_promoted → rule_executed`。

## 公开开发结果（30 世界，seed 20261104）

| 指标 | 值 |
|---|---|
| 世界数 | 30（跳过 0） |
| 晋升 | **30/30** |
| 晋升规则与隐藏规则在完整域上等价 | **30/30** |
| 新对象预测 | **200/200** |
| 平均查询数 | 1.47（最多 3） |
| 只记忆 ID 的基线 | 0.0（未见对象只能 unknown） |
| 多数类（全答 false）基线 | 0.745 |
| E2 证据不足 | `unknown / insufficient_evidence` |
| E3 证据冲突 | `observe=conflict`、`finalize=conflict`，不晋升 |

原始数据：[public-30.json](./reports/public-30.json)。

**为什么看起来“太顺”**：假设空间只有单条件/双条件，查询池覆盖完整有限域，主动查询能快速切分候选。这是**受限归纳**，不是开放世界学习；换成不足的查询池（E2）它会明确拒答，而不是硬猜。

## 归因

| 部分 | 归属 |
|---|---|
| 候选枚举、DSL、查询打分、晋升判定 | `designer_programmed`（本实现预置） |
| 本次学到的具体规则 | `student_induced`（从证据中筛出） |
| 教师标签 | `teacher_observation`（只给被问对象的 true/false） |
| 独立闭卷 | `external_audit`，当前 `not_run` |

## 文件

```text
src/main/kotlin/logic/discovery/   内核（模型、枚举、一致性、查询、解释器、存储、审计、CLI）
src/test/kotlin/logic/discovery/   E1–E5 + 边界 + 性质测试（15 项）
experiments/rule-discovery-v1/     SPEC、TASK、CONTRACT、夹具、公开报告
tools/rule_discovery_v1.py         公开演示与 30 世界统计
```

## 未完成

- **独立闭卷未运行**：需要外部审查者用仓库外的新世界、独立 oracle、不同代码路径出题判分；
- 未做 30 个世界的“失败样本”压力测试（当前生成器只产生可解世界）；
- 未实现证据撤回后的恢复（E3 只验证“冲突不晋升”）；
- 未扩展到 OR/否定/数值阈值（L2 方向）；
- `plan.run` 的原子性问题是 V1 遗留，本轮未修。
