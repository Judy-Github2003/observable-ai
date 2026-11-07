# SD-02 结果：typed LGG 与可执行宏机制成立，但注册/真实迁移未证明

## 0. 元数据

- 任务：`SD-02-PARAMETRIC-ABSTRACTION-2026-11-07`
- 起始 SHA：`6ed6e62`；执行时工作 HEAD `f4b0d14`（无新代码）
- 结束 SHA：本轮提交（见最终答复）
- Console：ACTIVE → CONSUMED
- 变更：`SD02.kt`、`SD02AntiUnificationTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 目标与执行边界

- 目标：从 ≥2 个已验证程序归纳 typed holes 的参数化结构，真实 `Expr.Call` 可执行，并做注册/迁移/禁用对照。
- 实际来源：2 个手工构造的 fixture 源程序，形状为
  `And(Eq(Arg,Const), Not(Eq(Arg,Const)))`。
  **它们不是学生搜索/账本验证产生的来源**；因此本轮只能证明 anti-unification 机制，不是学生自主归纳的科研证据。
- 无 `truth` 参数进入 `SD02AntiUnify`；不读取 HELDOUT/目标真值。
- 教师贡献：typed LGG 算法、来源 fixture、宏降低方案；未提供 W4/XOR 特判。

## 2. RED/GREEN

| 测试 | RED | GREEN |
|---|---|---|
| 两个不同来源 → typed pattern/holes/substitutions | 骨架返回空 pattern，业务失败 | 通过 |
| 相同源副本拒绝 | 通过（首跑即符合） | 通过 |
| 宏 typecheck + 真实 `Expr.Call` | 空 holes 导致调用构造越界 | 通过 |

- RED：`2 failed`（`DEGENERATE` 断言通过；另 2 项业务失败），日志 `RED.log`。
- GREEN：`3/3，0 failed，0.065s`；`BUILD SUCCESSFUL in 9s`；日志 `GREEN.log`、`junit-summary.json`。

实际 pattern：

```text
And(
  Eq(Arg(h0, Str), Arg(h1, Str)),
  Not(Eq(Arg(h2, Str), Arg(h3, Str)))
)
```

- 4 个 typed holes，全部 `VALUE_ARGUMENT/Str`；
- 两个来源 substitution/reconstruction：`reconstructionOk=true`；
- 非退化（非 root hole、非零参数）。

## 3. 可执行宏（真实 `Expr.Call`，但未注册）

- `CapabilityDefinition("sd02.macro", 1, {h0..h3: Str}, Bool3, pattern)` 通过 `CapabilityTypeChecker`。
- caller 定义使用 `Expr.Call("sd02.macro", 1, macro.contentHash(), args)`；
- `CapabilityInterpreter(InMemoryResolver(listOf(macro)))` 实际执行 caller，返回 `Known(Bool)`。
- 但**没有**走 `propose → verify → activate → resolveActive`，没有 active ref/版本注册记录。
- 因此 G2 只能记 `MACRO_EXECUTABLE_NOT_REGISTERED`，不是 `PASS`。

## 4. G0–G4

| Gate | 判定 | 证据/原因 |
|---|---|---|
| G0 实验可信 | **PARTIAL** | 无 truth 参数、无 HELDOUT；但来源不是学生账本验证，成本未统一 |
| G1 归纳形成 | **MECHANISM_ONLY_PASS** | 4 typed holes、substitutions、reconstruction；来源 fixture，不能算学生自主归纳 |
| G2 可调用固化 | **NOT_DEMONSTRATED** | 真实 `Expr.Call` 解释器执行，但未注册/验证/激活 |
| G3 迁移贡献 | **NOT_DEMONSTRATED** | 未接入真实未见任务搜索，无 ENABLED/DISABLED 真实重跑 |
| G4 生成规则 | **NOT_TESTED** | 未实现 |

## 5. 成本与负面结果

- 本轮没有学生搜索/查询/注册，`oracleNew`、`generatedCandidates`、`interpreterCalls`、`wallMillis` 的完整成本 **UNKNOWN**；不填 0。
- 宏调用返回 `Known(Bool(false))` 对给定绑定（a=`a`, c1=`1`）是正常的 Eq 假，但测试只断言 Known；未做真值正确性统计。
- 无法声称 SD1/SD2、迁移收益或注册成功。

## 6. 下一阶段最值得验证的问题

1. 能否从**学生搜索并经 MRX2Ledger/CHALLENGE 验证**的两个真实来源产生同样的 typed pattern，而不是教师 fixture？
2. 如何把 typed pattern 降为 `CapabilityDefinition` 并通过 propose/verify/activate，在独立嵌套任务中真实 `Expr.Call`？
3. ENABLED/DISABLED 两组真实重跑的成本/成功率是否出现可复现差异？

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD02AntiUnificationTest"
```

未运行全量 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次正式考试或独立闭卷。
