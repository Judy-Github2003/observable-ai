# B0 实施报告（冻结版）

```text
阶段: B0
状态: complete
冻结代码 SHA: 0ca4561309aeb914df6e3f2c3877a50b080bf731（M5-1b）
冻结提交: 本报告所在提交
IR 语义版本: cap-ir-v1
DB schema: 3
测试: 173/173，失败 0（implementer_run）
独立闭卷: not_run
```

## 1. 交付内容

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 基线、任务书、目录与决策记录 | 完成 |
| M1 | 11 节点 Typed IR、三值解释器、类型检查、预算、canonical hash | 完成 |
| M2a | SQLite 能力库（4 表）、不可变版本、依赖 pin、读取校验 | 完成 |
| M2b | 可信验证套件、验证入库、激活门禁、正式执行入口 | 完成 |
| M2c | 追加式撤销、传递依赖失效、依赖路径与恢复语义 | 完成 |
| M3 | 有界强类型组合、证据筛选、`student_composed` 注册、两个新世界 | 完成 |
| M4 | L1 正文共享验收、冻结成本模型、L2 子树抽取/重写/净收益；L3 未实现 | 完成（L1/L2） |
| M5 | A/B/C 同预算公开开发基准、原始结果 | 完成（D 未实现） |
| M6 | 冻结、复现、交接与外部验收接口 | 完成 |

## 2. 复现入口

```powershell
$env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'   # 或本机 JDK 21
git checkout 0ca4561309aeb914df6e3f2c3877a50b080bf731
.\gradlew.bat --offline --no-daemon test
.\gradlew.bat --offline --no-daemon benchmark
```

- 干净 worktree 已验证：173/173 通过；benchmark 输出与冻结结果一致（耗时/内存除外）。
- 自定义外部验收：`benchmark --args="--seed <新种子> --tasks-per-family <2..N>"`；生成器不提供目标 AST。
- 无网络、无教师 API；全部判定离线完成。

## 3. 关键设计事实

- 能力状态由 `candidate → verification_runs → activations` 的追加记录推导；`capabilities.status` 始终是 `candidate`。
- 验证套件、`source` 由宿主机登记；搜索/候选不能自报 `passed=true`。
- 撤销只追加；旧程序、旧验证、旧激活历史保留；被撤销版本不可再激活/执行。
- 组合受 `maxDepth/maxNodes/maxCandidates` 限制；证据不足返回 `ambiguous`，超空间返回 `refused`。
- 压缩只在“类型检查 + 有界等价 + 净成本下降”同时成立时接受；否则 `rejected_candidate`。
- 成本是冻结静态代理，不是运行耗时；压缩不等于学习新知识。

## 4. 能力归属

| 来源 | 内容 |
|---|---|
| `designer_primitive` | IR 节点、解释器、验证/激活/撤销/组合/压缩算法 |
| `teacher_proposed` | 无（B0 未调用教师，`teacher_calls=0`） |
| `student_composed` | M3-1c 两个新世界的具体候选（搜索算法仍由开发者提供） |
| `student_abstracted` | M4-1c 的 helper 候选（重新组织已验证程序，不产生新事实） |

## 5. 测试覆盖（173 项）

V1/V2 27、M1 21、M2a 17、M2b 50、M2c 15、M3 24、M4 16、M5 3。

重点覆盖：三值语义、版本锁定、库损坏拒绝、验证/激活事务回滚、撤销传递失效、组合歧义、压缩净收益拒绝、基准确定性与指标完整性。

## 6. 未实现 / 未运行

- L3 参数化抽象：`not_implemented`。
- D 组（C + L3）：`not_implemented`。
- 独立闭卷：`not_run`；没有外部审查者运行隐藏测试。
- 大规模课程、R1、真实教师 API、模型训练：未启动。

## 7. 下一阶段（仅选项，不启动）

T1（教师辅助持续训练）可选方向：
1. `TeacherGateway` mock → 真实教师候选协议：教师只提交不可信候选，学生验证/激活；
2. 教师提供反例/提示而非完整程序，测量单位教师调用的能力增量；
3. 不做教师蒸馏，先扩大 M5 任务规模与种子，验证 C 组在更大分布上的收益。

E3-A（表示不足检测）已获授权但尚未开始；B0 冻结后另起实验模块。
