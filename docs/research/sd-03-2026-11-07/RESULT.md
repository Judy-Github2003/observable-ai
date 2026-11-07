# SD-03 结果：真实来源可找到，但非退化反统一未成立

## 0. 元数据

- 任务：`SD-03-STUDENT-ABSTRACTION-LOOP-2026-11-07`
- 起始 HEAD：`4603f05`；执行时工作 HEAD `6ed6e62`
- 结束 SHA：本轮提交（见最终答复）
- Console：ACTIVE → CONSUMED
- 变更：`SD03.kt`、`SD03StudentLoopTest.kt`、本目录、README
- `formal_runs=0`；`independent_closed_book=not_run`；`fullRegression=NOT_RUN`

## 1. 实际执行

- 两个匿名开发任务：
  - A：`a=1 ∧ d=1`
  - B：`b=3 ∧ c=3`
- 学生搜索：现有 `MRX2BSpace` PAIRWISE；40 TRAIN、41 CHALLENGE。
- 来源筛选：`MRX2Ledger.validate` 对 challenge 逐一验证；只有 `PASS_ON_CHALLENGE` 才作为来源。
- 两个来源均找到，`validationScope=CHALLENGE(41)`，canonical 不同（见 `TRACE-SD03.jsonl`）。
- 两个来源 body 均含既有能力 `Call(c8v3.p, args...)`，但具体组合结构不同。

## 2. G0–G4

| Gate | 判定 | 证据/原因 |
|---|---|---|
| G0 真实来源/隔离 | **PASS_LIMITED** | 两个来源来自同一次真实 `MRX2BSpace`+`MRX2Ledger.validate`，无 truth lambda 进入 `SD03StudentLoop`；但搜索器是教师工程先验，来源选择仍受 40/41 开发分割影响 |
| G1 非退化反统一 | **NOT_DEMONSTRATED** | `SD02AntiUnify` 返回 `DEGENERATE_PATTERN:IRRELEVANT_SOURCE_DIFFERENCE`；hole=0，无参数化宏 |
| G2 注册/激活 | **NOT_RUN** | 无 pattern，故无 Definition/propose/verify/activate |
| G3 新任务真实调用 | **NOT_RUN** | 无 active macro；未人工构造 caller 冒充 |
| G4 ENABLED/DISABLED | **NOT_RUN** | 无宏可用；未用 `null/0/0` 伪造对照 |

## 3. 关键阴性结果

- 两个来源的**根结构/调用子树不同**；现有 LGG 只处理 `And/Eq/Not/Const/Arg` 等对应结构，遇到不同 `Call` 子树或不同根构造即拒绝。
- 拒绝是正确的安全行为：将不同程序强行用一个 root hole 泛化会违反“非退化/可重建”契约。
- 没有手工补写目标结构，也没有为了通过而扩大枚举模板。

## 4. RED/GREEN

- 本轮新建 `SD03StudentLoopTest`；首次运行因 custom world coverage/类型导入失败，属于环境/测试适配，**不是可记的业务 RED**。
- 改为 40/41 全量开发分割后，两来源可找到，但 LGG 负结果；测试在负结果路径写 `TRACE-SD03.jsonl` 并通过。
- GREEN：1/1（负结果路径），26s；未显示正宏路径。
- 如实记录：**本轮没有“业务 RED → 正宏 GREEN”**；只有一个负结果契约。不能把它包装成 G1/G2/G3 成功。

## 5. 成本与限制

- 来源搜索复用 `MRX2BSpace`，未统一计量 `generated/interpreterCalls` 到单一成本；标 `UNKNOWN`，不填 0。
- 无宏注册/调用/迁移，因此无迁移成本与收益。
- 样本 2 个开发任务、1 个分割；无泛化结论。

## 6. 下一阶段最小问题

1. 是否能在**不手工指定结构**的前提下，让两个真实学生来源的根/调用子树具有可归纳的公共结构（例如通过更受控的源任务族，而非扩大模板）？
2. 如果来源只共享部分 `Call` 参数而 call id 不同，是否应实现 call 级别的来源等价/失败分类，而不是直接拒绝？
3. 在得到合法 pattern 后，如何接入真实 `SqliteCapabilityRegistry` 的 propose/verify/activate，并做 ENABLED/DISABLED 真实重跑？

## 7. 运行命令

```powershell
.\gradlew.bat quickTest --offline --console=plain `
  "-PquickTests=logic.e3.pack02.SD03StudentLoopTest"
```

未运行全量 `test/check/build`、MRXRFormal、MRXFormal、MRXBaseline、160 次正式考试或独立闭卷。
