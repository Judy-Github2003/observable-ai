# V2 工作报告（2026-11-04）

> 执行者：DeepSeek Harness（本会话，DeepSeek 模型）
> 仓库：<https://github.com/Judy-Github2003/observable-ai>
> 提交：`75f8e44`（初始）→ `8a13a86`（V2）
> 状态：V2 内核与公开开发测试完成；**独立闭卷 `not_run`**

---

## 1. 一句话摘要

把 V1 的“教师写操作、学生执行”，推进到 V2 的“教师只给逐条标签，学生自己枚举候选、主动提问、淘汰、晋升”：新增 8 个内核文件、15 项测试，30 个公开世界全部学出等价规则、200/200 新对象预测正确；同时更正了仓库里混淆的“Codex / DeepSeek”归属，并把未解决问题集中移交外部审查。

---

## 2. 本轮做了什么

| 时间 | 工作 | 结果 |
|---|---|---|
| 上午 | 24 课批量首考与复验（承接上一阶段） | 首考 958/960，定位第 13 课单元素外推；修正后 960/960，24/24 |
| 上午 | 独立抽查 | 62/64 → 修正夹具与 `count` 载荷后 64/64 |
| 下午 | 开源整理 | MIT、README 重写、清理嵌套 `.git`、脱敏、推送 `observable-ai` |
| 晚上 | 读 V2 任务书，M0 冻结基线 | HEAD `75f8e44`，11/11 测试，记录源码哈希 |
| 晚上 | 身份更正 | 确认本会话是 DSH/DeepSeek；此前“Codex”是另一阶段 agent |
| 晚上 | 按方案 A 自行实现 V2 | 8 个内核文件、15 项测试、CLI、演示器 |
| 晚上 | 公开开发验证 | 30 世界：30/30 规则等价，200/200 预测，平均查询 1.47 |
| 晚上 | 报告与提交 | 报告、契约、夹具、E1 追踪；推送 `8a13a86` |

---

## 3. 身份与归因更正（重要）

此前仓库与交接文档里把实现者写成 “Codex”，需要更正：

| 工作 | 真实执行者 |
|---|---|
| V0/V1、课 1/2 原始实现 | Codex（上一阶段，OpenAI） |
| 24 课批量内核与课程生成器 | 本地 DeepSeek Harness 会话 |
| 首考执行、抽查复验、报告校验、开源 | **本会话 DeepSeek Harness** |
| `codex_spotcheck.py` 脚本 | Codex 编写；本会话执行并修正 1 处夹具前提 |
| V2 全部实现 | **本会话 DeepSeek Harness** |

结论：仓库里“Codex 独立抽查”只能表述为“另一模型写的脚本 + 本会话执行”，**不构成独立第三方验证**。真正的闭卷交给外部审查者（ChatGPT）。

---

## 4. V2 实现

### 4.1 文件

```text
src/main/kotlin/logic/discovery/
  DiscoveryModel.kt        状态、规则、证据、JSON 读写
  HypothesisGenerator.kt   单条件/双条件枚举、规范化、集合 hash
  RuleInterpreter.kt       三值求值（缺属性 unknown，false 优先）
  ConsistencyEngine.kt     证据筛选、冲突、有限域等价类
  QueryPlanner.kt          查询前打分（分组数，同分按 ID）
  DiscoveryStore.kt        原子写 state.json、追加 events.jsonl
  DiscoveryAudit.kt        学习事件
  DiscoveryCli.kt          JSONL 入口 + 状态机（stdin 可交互）
```

### 4.2 语义

- 候选只来自输入 schema；实现里没有具体颜色、形状、训练对象 ID 的分支。
- 同一对象正反标签 → `conflict`，两条证据都保留，禁止晋升。
- 晋升条件：无冲突、有正负样本、剩余候选恰一个等价类（在完整有限域上比较）。
- 规则由解释器执行；缺属性返回 `unknown`，不猜 `false`。
- 状态按 `world_id` 分目录原子替换；重启后候选、淘汰历史、已晋升规则都能恢复。

### 4.3 踩到的真 bug

`JsonObjectBuilder.put` 返回“旧值”，不是 builder。原写法：

```kotlin
promoted?.let { put("promoted", it.toJson()) } ?: put("promoted", JsonNull)
```

`let` 返回 null，Elvis 执行后半句，把刚写的规则覆盖成 `null`。表现：finalize 说晋升成功，下一个请求 predict 说 `rule_not_promoted`。改为显式 `if/else`，并补了“晋升后重启仍能预测”的测试。

---

## 5. 验证结果

### 5.1 测试

| 套件 | 数量 | 结果 |
|---|---:|---|
| V1 原有 | 11 | 全过 |
| V2 场景（E1–E5、边界、重启、审计） | 10 | 全过 |
| V2 性质（域穷举、三值、等价） | 5 | 全过 |
| **合计** | **26** | **0 失败** |

### 5.2 公开 30 世界（seed 20261104）

| 指标 | 值 |
|---|---|
| 晋升 | 30/30 |
| 规则与隐藏规则在完整域上等价 | 30/30 |
| 新对象预测 | 200/200 |
| 平均查询 | 1.47（最多 3） |
| ID 记忆基线 | 0.0 |
| 多数类（全答 false）基线 | 0.745 |
| E2 证据不足 | `unknown / insufficient_evidence` |
| E3 冲突 | `observe=conflict`、`finalize=conflict` |

### 5.3 E1 追踪

```text
start A=true,B=false  → 8 候选，剩 3（红 / 圆 / 红且圆）
next_query            → C（红,方）
observe C=false       → 淘汰“红”，剩 2
next_query            → D（蓝,圆）
observe D=false       → 淘汰“圆”，剩 1
finalize              → (颜色==红色) AND (形状==圆形)
predict 红圆/蓝方/红方/蓝圆 → true/false/false/false
```

---

## 6. 仓库变更

| 提交 | 内容 |
|---|---|
| `75f8e44` | V1/V0、课 1/2、24 课批量、MIT、README |
| `8a13a86` | V2 内核、CLI、15 项测试、报告、夹具、演示器 |

新增公开材料：

- `experiments/rule-discovery-v1/README.md`：问题、范围、E1 追踪、30 世界结果
- `reports/V2-REPORT.md`：开发报告与风险
- `CONTRACT.md`：CLI、状态、事件、错误码
- `reports/public-30.json`、`e1-trace.jsonl`、`e1-events.jsonl`
- `OPEN-ISSUES.md`：移交外部审查的问题清单

---

## 7. 未解决 / 已移交

详见 [OPEN-ISSUES.md](../../experiments/rule-discovery-v1/OPEN-ISSUES.md)。核心五条：

1. **独立闭卷未做**：需要外部世界与独立 oracle；此前状态一律 `not_run`。
2. **同模型审查**：实现与公开演示都是本会话，不能自称独立。
3. **世界偏容易**：查询池覆盖完整有限域，30/30 可能高估。
4. **超纲规则不可自辨**：学生只能报 `insufficient_evidence`，无法区分“数据不足”与“规则不在假设空间”。
5. **冲突撤回、噪声、预算**：语义未定，需外部拍板。

---

## 8. 下一步

1. 外部审查者按 `OPEN-ISSUES.md` 第 5 节交付闭卷协议并执行；
2. 根据闭卷失败项补：部分查询池、噪声标签、超纲世界、冲突撤回；
3. 通过后再谈 L2（OR/否定/数值阈值）与小学课程；
4. 在闭卷结果出来前，所有材料只写“公开开发测试通过”，不写“已证明自主学习”。
