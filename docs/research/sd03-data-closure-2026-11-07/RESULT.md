# SD-03 Data Closure 结果：旧 B 恒假已证实，新 A 无来源

## 0. 元数据

- 任务：`SD-03-DATA-CLOSURE-2026-11-07`
- 起始 HEAD：`caf9d5d`
- 结束 SHA：本轮提交（见最终答复）
- Console：ACTIVE → CONSUMED
- 变更：`SD02.kt`（freeArgs 收集）、`SD03.kt`（自由变量封闭宏构造）、`SD03DataClosureTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 世界与分割审计

`WORLD-AUDIT.json`：

```json
{"type":"world_audit","oldB_true":0,"oldB_false":81,"newB_true":9,"newB_false":72}
{"type":"split","seed":1449,"train":40,"challenge":20,"heldout":21,
 "trainTrueA":5,"trainFalseA":35,"trainTrueB":6,"trainFalseB":34}
```

- 旧世界 B `b=="3" && c=="3"` 在 81 个输入上 **0 True / 81 False**，确认无效。
- 新 B `b=="1" && c=="1"` 有 9 True / 72 False。
- 固定 seed 1449 的 40/20/21 split 有正负；质量门禁通过。

## 2. 真实来源池

- 新 A：真实 `MRX2BSpace + MRX2Ledger` 搜索，**0 个** `PASS_ON_CHALLENGE` 来源。
- 新 B：4 个真实来源。
- 因为 A 池为空，不能构造预注册的 A×B pair；写入 `NO_VERIFIED_SOURCE_POOL.json`：
  `A=0, B=4, NO_VERIFIED_PAIR_IN_BOUND`。
- 未手工补 A 来源、未换 seed、未扩大模板。

## 3. 自由变量封闭修复（代码已完成，未被真实 pair 触发）

`SD02AntiUnify` 新增通用 `freeArgs` 收集：

- 遍历 `Arg/Const/Call/Field/Eq/And/Or/Not/If/Let/Ref`；
- 排除 hole 名和 `Let` 局部绑定；
- 把共享自由 `Arg` 提升为宏显式参数；
- `SD03StudentLoop.buildMacroAndCall` 现在使用 **holes + freeArgs** 作为 `CapabilityDefinition.inputs`，并用完整 library（含 `c8v3.p`）做 typecheck。

这是通用作用域修复，不是 `a/b` 特判。  
但本轮真实 A 池为空，**没有真实 pair 经过该修复**；因此不能把代码完成当 G1 成功。

## 4. Gate 判定

| Gate | 判定 | 原因 |
|---|---|---|
| G0 真实来源 | **NOT_DEMONSTRATED** | A 池 0；只有 B 池 4，无跨任务 pair |
| G1 非退化/闭合 LGG | **NOT_RUN** | 无 pair 可执行 |
| G2 注册/激活 | **NOT_RUN** | 无合法宏 |
| G3 真实学生调用 | **NOT_RUN** | 无 active macro |
| G4 ENABLED/DISABLED | **NOT_RUN** | 无宏；未伪造 0/0 |

## 5. RED/GREEN 真实性

- `SD03DataClosureTest` 首跑因 Kotlin 类型引用/审计顺序修正后通过；这些是编译/测试适配，不是业务 RED。
- 本轮**没有新的业务 RED**；没有复用旧 RED。
- GREEN：1/1，13s；JUnit `junit-summary.json`。

## 6. 失败机制

A 世界 `a=1 ∧ d=1` 在 40 TRAIN 中只有 5 个正例；B 世界 6 个。  
当前 `MRX2BSpace` PAIRWISE 在 A 上未产生 challenge-pass 候选；这不是“A 在全部空间不可表达”，只是当前有限搜索/split 的数据覆盖不足。  
按预注册协议，不能为了得到 pair 换 seed 或改写 A 目标；因此停止下游。

## 7. 下一阶段最小问题

1. 在**不改变 seed 和目标**的前提下，A 的 5 个 TRAIN 正例是否足以让当前搜索器产生稳定候选？若不足，需要单独预注册“split 敏感性/分层采样”实验，而不是偷换主实验。
2. 自由变量封闭修复需要用另一个有 A/B 双池的有效任务族重新验证；本轮代码尚未取得真实 pair 证据。
3. 若未来有合法闭合宏，再走真实 `propose→verify→activate` 与 ENABLED/DISABLED。

## 8. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03DataClosureTest"
```

未运行全量 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。本轮停止，未启动 SD-04。
