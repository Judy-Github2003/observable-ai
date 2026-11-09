# ABDMNV-01 M1 垂直切片结果（第一轮）

执行者：DeepSeek Harness，2026-11-09。
授权：`ABDMNV-01-M1-2026-11-09`（ACTIVE repair grant，`ev-1794210885557-6f8cad`），preflight `AUTHORIZED_REPAIR`。
依据：`ABDMNV-01-技术设计评审 v2`（SHA-256 `6637efa1…a6ff`）与 M1 开工包（ZIP SHA-256 `693ba834…7864`）。

## 1. 仓库与门禁

| 项 | 值 |
|---|---|
| 起始 HEAD | `a106a09d78c00d91ce0c0e184a5b56a4d8e9c8d1` |
| 分支 | `main` |
| 起始工作区 | 仅 4 个原有未跟踪文档；`research-console` 登记前无改动 |
| 授权记录 | `research-console/repairs.json` → `ABDMNV-01-M1-2026-11-09` |
| preflight | `REFUSED: unknown task`（登记前）→ 登记后 `AUTHORIZED_REPAIR` |
| B0 冻结文件 | 7 个核心文件 `git status` 全部 clean |

## 2. 复用了什么、没复用什么

| 现有模块 | 结论 | 原因 |
|---|---|---|
| `logic.capability.EvalResult` / `CapValue` | **复用** | 已有 Known/Unknown/Error 三态与 Bool/Str/I64 值域，正好承载"三值断言 + ERROR 通道" |
| `logic.e3.ObservedValue` | **复用** | 证据值 Known/Unknown 的既有语义 |
| `logic.e3.SourceKind` 等证据字段 | 部分复用（概念映射） | v2 需要 `source_kind/independence/round/stable_round`，现有枚举语义不同，用新枚举并在适配层注释 |
| `CapabilityInterpreter` | 未复用 | 其 IR 是 `Expr`（Arg/Const/Field/…），与 v2 概念 AST（Term/Param/量化）不同；M1 只复用值模型 |
| `SqliteCapabilityRegistry` | 未复用 | 注册对象是 `CapabilityDefinition`（Expr），概念 AST 无法直接落库；适配留到 M2 |
| `SD02AntiUnify`（LGG） | 未使用 | M1 不构造学习器，候选生成不在本轮 |
| `QueryPlanner` / `ActiveLearning` | 未使用 | 同上 |

结论：**没有另起平行框架**；值模型与证据值直接复用，AST/序列化/评分属于现有系统未覆盖的部分，按 v2 新增最小模块 `logic/e3/abdmnv/`。

## 3. 新增文件

| 文件 | 职责 | 规模 |
|---|---|---|
| `AbdmnvSyntax.kt` | 概念 AST、Scope/Param/VersionRef、ID 语法、规范 S-表达式、SHA-256 | ~300 行 |
| `AbdmnvEval.kt` | `AbdmnvWorld`、确定性求值器、四值语义、关系计费装饰器 | ~250 行 |
| `AbdmnvTypes.kt` | 最小类型检查（sort 推导 + kind/正文/深度约束） | ~180 行 |
| `AbdmnvScoring.kt` | 槽位协议、计数/precision、R1–R6 匹配器（对象与关系对） | ~230 行 |
| `AbdmnvEvidence.kt` | 证据账本（schema/conflict/round）、概念版本存储（不可变/撤销传播/依赖失效） | ~170 行 |
| `AbdmnvHarness.kt` | T2/T3/T5 公开机械检查（ID 依赖、行为敏感、目标歧义） | ~60 行 |
| `AbdmnvContract.kt`（test） | 58 条定向契约测试 | ~420 行 |

## 4. 实现解释（契约歧义的裁决，必须记录）

1. **ID 语法**：v2 正则 `[a-z]+_[0-9]+` 无法接受 `ch_alpha/a1/r_same_color/w_07/o_17`。按 README 要求不改黄金样例，改为
   `[a-z][a-z0-9_]{0,31}`。已加正反例测试（接受 7 个样例 ID，拒绝大写/数字开头/连字符/超长）。
2. **Sort 补 `obj`**：v2 §5.3 的 Sort 列表缺少对象类型，但 `const-obj` 与量词变量需要。M1 增加 `obj`，不影响黄金样例。
3. **attr/rel 的对象实参写法**：黄金 G2 写 `(attr a1 x)`、`(rel r_same_color x (const-obj o_17))`，而 G3 写 `(apply c_0002@2 (var x))`。
   实现按原文区分：attr/rel 位置上的变量渲染为裸名，其余位置渲染 `(var x)`。未改任何黄金样例。
4. **MEMBER 的集合字面量**：v2 有 `Set[Sort]` 但无集合项构造。M1 增加 `(set o_1 o_2)`（最小扩展），用于让 MEMBER 可执行；已在测试中覆盖。
5. **ERROR 传播**：采用严格支配规则——任一子项 ERROR 即返回该 ERROR；`FALSE ∧ ERROR = ERROR`（而不是 FALSE）。
   UNKNOWN 不降级为 FALSE；ERROR 不降级为 UNKNOWN。16 格 AND/OR 真值表与 NOT/EQ/LT/MEMBER 全部可测。
6. **T3 的解释**：不要求 AST 变化，要求**行为随证据变化**（`AbdmnvHarness.behaviorallySensitive`）；只有需要修订时才产生新版本。
   该解释写入测试名与本文，不改 v2 原文。
7. **T2 的 M1 实现**：概念里出现 `const-obj` 即判 `ID 依赖`（最小公开检查）；双射重命名重放待 M2 接入真实世界生成器。
8. **T5**：两个目标在 V 上行为等价 → `ambiguousTargets = true`，评分侧应返回 `UNSCORABLE`，**不**推断"平凡概念"。
9. **版本与撤销**：同一 `(id,version)` 内容变化直接抛 `VERSION_IMMUTABLE_VIOLATION`；`supersede` 要求 version+1；撤销沿依赖传播，`executable()` 递归检查依赖。

## 5. 定向测试结果（真实运行）

| 项 | 值 |
|---|---|
| 编译命令 | `K2JVMCompiler`（embeddable 2.2.21）编译 7 个源文件到 `build/abdmnv-contract.jar` |
| 编译退出码 / 耗时 | `0` / 约 4–9 s |
| 运行命令 | `java -cp build/abdmnv-contract.jar;<stdlib>;build/classes/kotlin/main logic.e3.abdmnv.AbdmnvContractKt` |
| 运行退出码 / 耗时 | `0` / **161 ms** |
| 结果 | **58 passed / 0 failed** |
| 日志 | `build/abdmnv-contract-run.log` |

覆盖分组：A 黄金与 ID（11）、B 四值/类型/关系（18）、C 槽位与 R1–R6（14）、D 证据/版本（6）、T1–T5（5）、稻草人（4）。

## 6. 未运行 / 未实现（不得写成已完成）

- 历史全量回归：`NOT_RUN`（按授权只做定向测试）。
- 六臂正式实验、隐藏考官、世界生成器：`NOT_RUN`（M1 不含）。
- L2 确定性重放：`NOT_RUN` — 需要冻结学习器与完整投递记录，M1 尚无学习器。
- UNSCORABLE 的生成期预验证与冻结：未实现（没有世界生成器），当前只有评分侧的 `UNSCORABLE` 判定。
- T3 的扰动实例、T4 的负世界、T5 的跨世界真值：只有公开 fixture，不是考官材料。
- 与 SQLite Registry / ConceptStore 的持久化适配：未做，M2。
- 属性概念（Attribute）的评分：未纳入 R1–R6，M1 只对 Category/Relation 评分。

## 7. 已知缺陷与下一小步

1. `AbdmnvEvaluator` 对 `SetLit` 只支持 MEMBER 模式，其他场景返回 `unsupported_set`。
2. 类型检查器只推导 sort，不做参数化概念的实例化检查（`Apply` 的 sort 匹配已做）。
3. T2 仍是"含对象常量"的静态判据，尚未做真正的双射重命名重放。
4. 建议下一小步（M1.5）：把 `AbdmnvConceptStore` 接到现有 SQLite Registry 的 hash/依赖读接口；为 T3 增加可配置的扰动世界生成器（公开 fixture 级）。

## 8. 停止点

本切片到此为止：不改 B0/历史冻结文件，不启动正式实验，不开发学习器；等待 ChatGPT 审查后再继续下一轮。
