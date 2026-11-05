# B0 M5–M6 工作报告

日期：2026-11-05
执行者：DeepSeek Harness（本会话）
任务来源：研究者授权继续 M5 → M6，B0 完成后冻结，再进入 E3-A
代码冻结 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`（M5-1b）
冻结提交：`aa1172adcc6ba2ae0314aa2f209a57ab37255444`（M6-1）

---

## 1. 本轮做了什么

| 子任务 | 内容 | 提交 |
|---|---|---|
| M5-1a | 冻结 A/B/C 同预算基准协议、生成器 `b0-m5-v1`、CLI 与 3 项验收测试 | `d086c13` |
| M5-1b | 运行 24 任务 × 3 组公开开发基准，输出 JSON 与报告 | `0ca4561` |
| M6-1 | 冻结 SHA、配置、种子、原始结果；干净 worktree 离线复现；写交接文档 | `aa1172a` |

M5 的 D 组（L3）保持 `not_implemented`，没有为补齐实验扩展 L3。
E3-A 尚未开始，按“B0 冻结后另起实验模块”的顺序处理。

---

## 2. 测试与复现

- 全套测试：**173/173**，失败 0。
- 冻结后从干净 worktree（detached `0ca4561`）离线执行：
  - `gradlew.bat --offline --no-daemon test` → 173/173；
  - `gradlew.bat --offline --no-daemon benchmark` → A 6/12、B 9/12、C 9/12，结果与冻结一致（耗时/内存除外）。
- 复现命令与冻结数据见：
  - `experiments/capability-foundation-b0/reports/B0-FREEZE.json`
  - `experiments/capability-foundation-b0/reports/B0-IMPLEMENTATION-REPORT.md`
  - `experiments/capability-foundation-b0/reports/B0-OPEN-ISSUES.md`

---

## 3. M5 真实结果（开发集 12 题/组，种子 20261105）

| 组 | 正确 | wrong | unknown | error | ambiguous | refused | 候选数 | 耗时 ms | 静态 L_total | 复用 |
|---|---|---|---|---|---|---|---|---|---|---|
| A 独立求解 | 6/12 | 0 | 0 | 0 | 3 | 3 | 24528 | 369 | 84 | 0 |
| B 复用基础库 | 9/12 | 0 | 0 | 0 | 3 | 0 | 25872 | 518 | 186 | 6 |
| C B + L1/L2 | 9/12 | 0 | 0 | 0 | 3 | 0 | 25872 | 502 | 186 | 6 |

按任务族：

| 任务族 | A | B | C |
|---|---|---|---|
| REUSE（深度 4） | 0/3，全部 refused | 3/3 | 3/3 |
| SIMILAR（同形不同常量） | 3/3 | 3/3 | 3/3 |
| NO_STRUCTURE（负对照） | 3/3 | 3/3 | 3/3 |
| BOUNDARY（深度 5） | 0/3，全部 ambiguous | 0/3，全部 ambiguous | 0/3，全部 ambiguous |

结论：

1. B 对 A 的增量真实但有限，只来自 REUSE 族的复合原语复用。
2. **C 对 B 没有增量**：训练集已解决程序上没有找到净收益 helper（`helpers=[]`），静态成本、耗时、正确率三项均无改善。
3. BOUNDARY 族三组都表现为 `ambiguous`，没有把预算不足硬判成正确；负对照未被强行压缩。
4. 计时与峰值为单次 JVM 近似值，不构成性能结论；`L_total` 是冻结静态代理，不代表运行更快。

---

## 4. M6 冻结内容

- 代码 SHA、IR 版本 `cap-ir-v1`、schema 3、JDK/Kotlin/Gradle/依赖版本。
- 测试源码 SHA-256、benchmark 结果 SHA-256、协议 SHA-256、种子与预算。
- 干净 worktree 的离线复现记录。
- 能力归属：设计者原语、教师（本轮 `teacher_calls=0`）、`student_composed`、`student_abstracted` 分别列出。
- `independent_closed_book=not_run`，没有声称独立闭卷通过。

---

## 5. 实际遇到的问题与负面结果

1. **C 组无收益**：M4 的压缩正例依赖专门构造的重复结构；在本轮通用任务分布上，L2 没有找到净收益 helper。这是真实的负面结果，没有调权重或改任务来制造收益。
2. **SIMILAR 的复用隐含二值域假设**：`¬red ≡ blue` 只在二值颜色下成立；出现第三种颜色时可能失效。公开开发集未覆盖，已在报告和开放问题中标注。
3. **等价验证范围有限**：`EXHAUSTIVE` 只是调用方声明；M5 的 8 点域是声明域内全枚举，不是全局语义等价证明；无法枚举时必须标 `bounded_verified`。
4. **基准在内存中运行**：不写注册表、不激活能力；验证/激活/撤销门禁由 M2–M4 测试覆盖，不在基准分数中。
5. **统计功效有限**：开发集 12 题、单一种子；不能据此断言 C 永远无收益。

---

## 6. 当前状态与下一步

- B0 已冻结：M0–M6 完成，L3/D 组未实现，独立闭卷未运行。
- E3-A 尚未开始；下一步从 `E3-A-1` 开始，只做表示不足诊断，不做 E3-B/C，不改 B0 冻结语义。
- 本轮结束后等待研究者决定 E3-A 的启动与范围；不自动开大规模课程，不调用真实教师。

---

## 7. 真实分工

- M1 参考骨架：云端 ChatGPT。
- M2a–M6 实现、测试、基准与冻结文档：本会话 DeepSeek Harness。
- 外部评审：研究者已安排 ChatGPT 检查 M3/M4 与阶段报告；本轮 M5/M6 结果尚待外部独立运行。
- 所有自测标记为 `implementer_run`，独立闭卷 `not_run`。
