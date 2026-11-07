# SD-03 Continuation 结果：真实来源已取证，未找到可归纳 pair

## 0. 元数据

- 任务：`SD-03-CONTINUATION-2026-11-07`
- 起始 HEAD：`e2ca963`；执行时 HEAD `4603f05`
- 结束 SHA：本轮提交（见最终答复）
- Console：ACTIVE → CONSUMED
- 变更：`SD03.kt`（来源池 + AST 诊断）、`SD03ForensicTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. GATE 1：真实来源与 AST 取证

两个匿名合取任务 A/B，40 TRAIN、41 CHALLENGE：

- A pool：4 个真实 `PASS_ON_CHALLENGE` 来源
- B pool：4 个真实 `PASS_ON_CHALLENGE` 来源
- 共 16 个 pair；每个都输出首次不一致路径与类别（`FORENSIC-SD03.jsonl`）。

真实分类结果：

| 类别 | 出现次数（首 16 对） | 示例路径/类型 |
|---|---:|---|
| `ROOT_MISMATCH` | 12 | `$.right` Eq/Not；`$.left` Eq/Call；`$` Or/And |
| `ARG_MISMATCH` | 2 | `$.right.left` d/a |

没有 pair 达到非退化、可重建的 typed pattern。  
`SD02AntiUnify` 对这些实际来源仍会拒绝；这不是“搜索不够大”的证明，而是当前来源族的真实结构分歧。

## 2. GATE 2：归纳结论

- 来源池是真实学生搜索（`MRX2BSpace` + `MRX2Ledger.validate`），不是手工 AST。
- 但 16 对中没有共同根/共同 `Call` 结构；`ROOT_MISMATCH` 为主。
- 没有合法 pattern、没有 typed holes、没有可重建宏。
- **G1 = NOT_DEMONSTRATED**；不进入注册/激活/迁移。

## 3. G0–G4

| Gate | 判定 | 原因 |
|---|---|---|
| G0 真实来源/隔离 | **PASS_LIMITED** | 8 个真实 challenge-pass 来源；无 truth 进入 `SD03StudentLoop`；但搜索器/任务族是教师工程先验 |
| G1 非退化归纳 | **NOT_DEMONSTRATED** | 16 pairs 全部 root/arg 分歧，无 pattern |
| G2 注册/激活 | **NOT_RUN** | 无 pattern |
| G3 新任务调用 | **NOT_RUN** | 无 active macro |
| G4 ENABLED/DISABLED | **NOT_RUN** | 无宏；未伪造 0/0 |

## 4. 成本与阴性结果

- 来源池的 `generated/interpreterCalls` 未统一计入单一 meter；标 `UNKNOWN`，不填 0。
- 未发生注册、验证、激活、迁移或查询成本；不能声称收益。
- 取证 trace 只含学生可见 AST/路径/类型，不含 HELDOUT/目标真值。

## 5. RED/GREEN 真实性

- 本轮新增 `SD03ForensicTest` 首次运行即通过（仅对现状取证），因此**没有新的业务 RED**；`GREEN.log` 为 2/2。
- 不把旧的 SD-01/02/03 RED 复用为本轮 RED；如实标 `NO_NEW_BUSINESS_RED`。
- `SD03StudentLoopTest` 旧的负结果仍保留。

## 6. 下一阶段最小问题

1. 为什么两个源任务的学生搜索都产生 `Or/And` 根差异？是任务族设计、候选选择还是 `MRX2BSpace` 的覆盖问题？
2. 能否在**预注册**的相似任务族上让两个真实来源共享根/`Call` 骨架，而不手工挑 pair？
3. 如果共同结构只出现在 `Call` 参数层，是否需要实现 call 参数对齐与重建，而不是直接拒绝？

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03StudentLoopTest,logic.e3.pack02.SD03ForensicTest"
```

结果：2/2，0 failed，28.943s；未运行全量 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次正式考试。
