# SD-02 预注册：参数化结构归纳

- 任务：`SD-02-PARAMETRIC-ABSTRACTION-2026-11-07`
- 起始 HEAD：`6ed6e62`（SD-01 结项）；实际工作 HEAD `f4b0d14` 后无新代码
- Console：`SD-02-PARAMETRIC-ABSTRACTION-2026-11-07`，ACTIVE → CONSUMED
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 固定变量

| 项 | 冻结值 |
|---|---|
| 来源程序 | 2 个手工构造的已验证形状 `And(Eq(Arg,Const), Not(Eq(Arg,Const)))`，仅作为 anti-unification 机制 fixture；不是由学生搜索/账本验证产生 |
| 原语/类型 | 现有 Typed IR：`Expr.Arg/Const/Eq/And/Not/Call`；`CapType.Str/Bool3` |
| 归纳算法 | 教师提供的 typed LGG（`STUDENT_INDUCED_WITH_TEACHER_ALGORITHM` 仅适用于来源真实由学生产生时） |
| 预算 | 不运行学生搜索；只做结构归纳/宏类型检查/调用 |
| 宏 | `sd02.macro`，输入 4 个 Str hole，输出 Bool3 |
| 迁移/消融 | 本轮未接入真实学生搜索；G3 不预注册为可完成 |
| 失败判据 | 相同源、root hole、零 hole、类型冲突 → 拒绝；宏不能注册/激活 → G2 不成立 |

## 负对照

1. 相同 AST 复制 → `DEGENERATE_IDENTICAL_SOURCES`。
2. 只有 root hole / 零参数 → degenerate。
3. 类型冲突/变量捕获 → reject。
4. 不把 `Expr.Call` 解释器调用冒充注册/激活。
5. 不读取 HELDOUT/目标真值；来源是 fixture，不声称学生自主验证。

## 判定

- G1 机制：type-safe LGG + substitutions + reconstruction → 可测试。
- G2：宏 typecheck 且真实 `Expr.Call` 执行 → 只能 `MACRO_EXECUTABLE_NOT_REGISTERED`，不等于注册/激活。
- G3：无真实学生迁移/禁用重跑 → `NOT_DEMONSTRATED`。
- 不允许把 fixture 结果写成 SD1 科研成功。
