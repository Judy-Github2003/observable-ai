# B0 M2b 总报告（可信验证与激活）

```text
阶段: M2b
状态: complete
起点 SHA: 39bf387b2135e697943bdc2aeeca2c9b35182836（M2a-FIX2）
提交: M2b-1a 264ccd7 / M2b-1b a314776 / M2b-1c 0e04f67 / M2b-2a 与 M2b-2b 见本次提交
测试: 115 passed / 115 total, 0 failed（V1/V2 27 + M1 21 + M2a 17 + 迁移 5 + 验证 16 + 入库 9 + 激活 9 + 执行 11）
schema_version: 2
independent_closed_book: not_run
verification_source: implementer_run
```

## 交付内容

| 子任务 | 交付 | 提交 |
|---|---|---|
| M2b-1a | `user_version 1→2` 单事务迁移；新增 `verification_runs`、`activations` 两张空表；`0→2` 一次建全；失败回滚 | `264ccd7` |
| M2b-1b | `TrustedVerificationSuite` / `VerificationCase` / `VerificationOutcome`；宿主登记表盖章 source；规范化 suite_hash；内存验证引擎 | `a314776` |
| M2b-1c | `verify(id,version,suite)`：DB `inspect` → 执行 → `verification_runs` + `events(kind='verified')` 同事务追加；逐例证据入事件 JSON | `0e04f67` |
| M2b-2a | `activate(id,version,runId)`：验证绑定、pass、套件登记、source、传递依赖全查；追加 `activations` + 事件；幂等 | 本次提交 |
| M2b-2b | `executeActive(id,version,args,budget)`：每次重新查激活与传递依赖，再调用 M1 解释器；Known/Unknown/Error 区分 | 本次提交 |

## 关键语义（已冻结）

1. **schema 不变旧四表**：`capabilities.status` 仍只允许 `candidate`；激活是由 `activations` 追加记录推导出的逻辑状态，不是改 status 字段。
2. **验证不可冒领**：`suite_hash` 由规范化逐例内容计算；`source` 只来自宿主登记；引擎无 `passed/verdict` 入参；实际 `Error` 一律失败；`Unknown` 不当 `false`。
3. **覆盖度**：Bool3 能力至少 4 个独立确定用例且至少 1 真 1 假，否则 `suite_inadequate`，写库时 `verdict=fail`。
4. **入库原子性**：验证 run + verified 事件、激活记录 + activated 事件分别同事务；事件写入失败用触发器实测回滚。
5. **执行门禁**：正式入口每次都核对 `inspect`、激活哈希、验证 verdict/绑定、全部传递依赖激活链；任一不满足即拒绝并给出具体错误码。
6. **历史只追加**：验证与激活记录不覆盖、不更新；每次 run/activation 新 ID；失败运行保留。
7. **接口层级**：`resolve`/解释器保留为内部开发与验证接口；正式应用必须走 `executeActive`；同进程不是硬隔离。

## 覆盖的场景（原任务书 G 系列中可核对的项）

- 未验证直接激活：拒绝（X02）。
- 验证失败/覆盖不足：不得激活、不得记 pass（X03、W03）。
- run 与能力版本/哈希错配：拒绝（X04、Y08）。
- 验证后修改 body：无法激活、无法执行（Y03、W05）。
- 依赖未激活：调用方不能激活、也不能执行（X05、Y04）。
- 传递依赖损坏：三层链调用方被拒（Y05、Y11）。
- 重启后激活保留、结果一致（Y06、Y11）。
- 正式入口与内部开发入口的区别（Y01）。
- 事务注入失败整笔回滚（W04、X06）。
- 伪造库内 source / 未登记套件：拒绝（X09）。

> 原任务书 G01–G14 的逐条编号清单不在本轮可读文档中；这里按实际行为场景列出覆盖，未虚构编号映射。

## 未实现 / 未运行

- M2c 撤销与传递失效、M3 组合、M4 压缩、M5 基准、M6 冻结均 `not_implemented`（M2c 之前不进入 M3）。
- 独立闭卷 `not_run`；115/115 是实现者在本机执行的测试，不是外部独立验证。
- 执行结果没有单独的执行日志表（schema 固定 6 表）；`Unknown`/`Error` 目前只通过返回值暴露，不能据此声称已持久化执行证据。
- 套件是本地 Kotlin 夹具，"可信"仅指宿主进程内登记，不防恶意数据库管理员，也不证明测试集来源独立。
- 未启动 V3、真实教师 API、模型训练或任意 Kotlin 动态执行。

## 下一最小子任务

`M2c-1a`：追加式撤销记录（`user_version 2→3`，新增 revocations 表），旧程序体/验证/激活历史保留。
