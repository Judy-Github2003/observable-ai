# SD-03 16-pair 结果：16 次真实 LGG 全部未形成可激活宏

## 0. 元数据

- 任务：`SD-03-16PAIR-AUDIT-2026-11-07`
- 起始 HEAD：`85a83a2`；代码基准 `7e96d6f`
- 结束 SHA：本轮提交（见最终答复）
- Console：ACTIVE → CONSUMED
- 变更：`SD03SixteenPairTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 真实来源

- A pool：4 个 `PASS_ON_CHALLENGE`；B pool：4 个。
- 来源来自真实 `MRX2BSpace` + `MRX2Ledger.validate`；`SD03StudentLoop` 不接收 target truth。
- 完整 AST、SHA-256、scope、checks/counterexamples：`SOURCES-16PAIR.jsonl`。

## 2. 16 对实际 LGG 执行

- 每对都调用 `SD02AntiUnify.generalize(listOf(A,B))`，**16/16 `generalizeInvoked=true`**。
- 原始逐对结果：`PAIR-LGG.jsonl`。

| 结果 | 数量 |
|---|---:|
| `ROOT_MISMATCH` 诊断 | 14 |
| `ARG_MISMATCH` 诊断 | 2 |
| `DEGENERATE_PATTERN:IRRELEVANT_SOURCE_DIFFERENCE` | 14 |
| 非退化 pattern（2 holes） | 2 |
| 双向 reconstructionOk | 2 |
| **宏 typecheck 通过** | **0** |
| **可注册/激活的合法 pattern** | **0** |

两个非退化 pair（A0-B1、A0-B3）：

```text
And(
  Call(c8v3.p, version=1, bodyHash=...,
       args={a: Arg(a, Str), b: Arg(b, Str)}),
  Eq(Arg(h0, Str), Arg(h1, Str))
)
```

- holes 只有 `h0,h1`；pattern 仍含自由 `Arg(a)`、`Arg(b)`，不在宏参数表中。
- 双向重建通过，但 `CapabilityTypeChecker` 拒绝（自由变量/未绑定引用），因此 **不是合法可调用宏**。
- 正确结论不是“ARG_MISMATCH 必然失败”，而是“该对可归纳出结构，但降低为宏时未闭合，`TYPECHECK_FAIL`”。

## 3. Gate 判定

| Gate | 判定 | 原因 |
|---|---|---|
| E0 真实来源/预算 | **PASS_LIMITED** | 8 个真实 challenge-pass 来源；成本未统一到单 meter |
| E1 真正 LGG | **E1_NO_VALID_PATTERN** | 16/16 调用；14 退化，2 非退化但 typecheck 失败 |
| E2 持久注册 | **NOT_RUN** | 无合法宏 |
| E3 真实迁移 | **NOT_RUN** | 无 active macro |
| E4 ENABLED/DISABLED | **NOT_RUN** | 无宏；未用 0/0 伪造 |

## 4. RED/GREEN 真实性

- 新测试 `SD03SixteenPairTest` 首跑即通过（它是对 16 对执行结果的契约，不是“预期必须有合法 pattern”）。
- 因此**没有本轮新的业务 RED**；不将旧 SD-01/02/03 RED 冒充本轮 RED。
- GREEN：1/1，24s；JUnit `junit-summary.json`。

## 5. 成本与限制

- 来源池 `generated/interpreterCalls` 未统一计量；`UNKNOWN`，不填 0。
- 无注册/激活/迁移/禁用成本；无收益结论。
- 样本为 2 个开发任务、1 个分割；不能泛化。

## 6. 下一阶段最小问题

1. **自由变量量化**：LGG 应把 pattern 中的自由 `Arg(a/b)` 也纳入 typed holes，或显式拒绝 `FREE_VARIABLE_LEAK`；后者更保守，前者可能让 2 个 pair 通过 typecheck。
2. `Call` 参数级对齐：是否允许把 call 参数作为 holes，同时保留 `id/version/bodyHash` 不变？
3. 如果得到合法宏，如何接入真实 `propose→verify→activate` 与 ENABLED/DISABLED 重跑。

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03SixteenPairTest"
```

未运行全量 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次考试。本轮停止，未启动 SD-04。
