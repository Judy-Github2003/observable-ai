# B0 M1 决策记录

> 定稿来自云端 ChatGPT《B0 M1 设计定稿》；本文件记录 DSH 集成时的确认与偏差。

## 已确认的语义

1. 11 类节点，`and/or/eq` 二元；`List/Optional` **not_implemented**。
2. Record 内联 schema；`Int64` 常量用规范十进制字符串；Bool3 常量只允许 `true/false`。
3. 三值：`unknown` 是正常结果，`Error` 是系统错误；实际求值发生 Error 时 Error 优先；未执行分支的潜在 Error 不算发生。
4. `and/or` 左到右短路：`false AND X = false`、`true OR X = true`；`unknown AND error = Error`。
5. `if(condition=unknown)` 返回 `Unknown(condition_unknown)`，不执行分支。
6. canonical 不排序 `and/or/eq` 操作数；只排序 Record 字段、call 实参 key、签名参数 key。
7. `content_hash` 含语义版本 + 签名 + canonical AST；call 锁定 `(id, version, body_hash)`，不递归哈希 callee body。
8. 不做 α-规范化；`let` 禁止覆盖参数/局部变量；`ref` 必须已绑定。
9. M1 的 `call` 必须由 resolver 返回完整定义并校验 hash，且可执行。
10. 默认 `maxSteps=10,000`、AST 深度 32、调用深度 16；资源耗尽为 `Error(resource_limit)`。

## 集成时的实际选择

- 参考骨架原样接入，只加文件头署名与补测；未改动语义。
- 参考测试 7 项全部保留；新增 `CapabilityM1ChecklistTest` 10 项补 T03/T05/T06/T07/T08/T09/T10/T11/T13/T14/T15。
- 循环依赖未构造“自洽哈希环”（在严格 hash 锁定下构造不出），改用 17 层调用链验证 `call_depth_limit`；这是对 M1-T13 的有限替代，已在报告列为已知缺口。
- `if` 分支不执行用“预算内条件 + 高成本分支”验证：若执行分支会先 `resource_limit`，实际返回 `Unknown(condition_unknown)`。

## 未做

- M2 注册表、M3 Composer、M4 压缩、M5 基准、M6 交接均 **not_implemented**。
- 独立闭卷 **not_run**。

## 外部审查后的补充（2026-11-04）

- **P0 修复**：`Call` 先对被调定义做可规范化检查，再比对 hash；不可规范化（如 Record 常量）返回 `invalid_dependency_definition`，不再让 `IllegalStateException` 逃逸。错误优先级：`missing_dependency` → `invalid_dependency_definition` → `dependency_hash_mismatch` → callee 类型检查。
- **Record 输入语义**：允许缺字段（读取时 `Unknown(missing_attribute)`）；字段类型错误或出现 schema 外字段 → `input_type_mismatch`。
- **资源上限**：JSON 1 MiB（`json_too_large`）、AST 节点 10,000（`ast_node_limit`）、Record 字段 256（`record_field_limit`）；静态检查也计节点数。
- **例子 roundtrip**：三份 JSON 做“解析→规范化→再解析”稳定性测试；不要求与缩进文本逐字相等。

## M2a 注册表（2026-11-04）

- 四表：`capabilities / bodies / dependencies / events`；`PRAGMA user_version=1`，未知高版本拒绝。
- `capabilities.body_hash = CanonicalJson.astHash(body)`；`capabilities.content_hash = CapabilityDefinition.contentHash()`。**两者不同**，不得互换。
- `Expr.Call.body_hash` 沿用 M1 命名，实际指向被调能力的 `contentHash()`；`dependencies.to_content_hash` 同一口径。
- 只允许 `candidate`；不实现 `verified/active`。
- 同一 `(id,version)` 同内容返回 `already_exists` 且不新增事件；不同内容 `immutable_version_conflict`。
- 依赖必须已存在且 hash 匹配；不支持前向引用/相互递归，因此插入顺序天然无环。
- 读取时从 `canonical_ir_json` 重新解析并复算两种 hash、重比对依赖表，不信任库内 hash。
- 错误码：`registry_open_failed / unsupported_schema_version / schema_init_failed / invalid_signature / record_field_limit / ast_depth_limit / ast_node_limit / missing_dependency / dependency_hash_mismatch / immutable_version_conflict / transaction_failed / body_hash_mismatch / content_hash_mismatch / dependency_table_mismatch / invalid_stored_definition / not_found`。
- 事务：bodies/capabilities/dependencies/events 同一事务；失败整笔回滚（用触发器强制失败验证）。

## M2a-FIX（2026-11-04 复审后）

- `inspect` 的 `readRow` 纳入受控边界：签名 JSON 损坏 → `invalid_stored_definition`；不存在 → `not_found`；`resolve` 返回 null。
- 幂等提交先 `inspect` 校验现有记录；损坏记录不得返回 `already_exists`；不同内容仍 `immutable_version_conflict`。
- `capabilities.semantics_version` 参与读取校验；不一致 → `unsupported_ir_semantics_version`。
- 资源限额扩展到直接构造的定义：ID/参数名 256 字节、常量字符串 64 KiB、canonical AST 1 MiB、provenance 64 KiB 且必须是 JSON 对象；超限 → `definition_too_large` / `invalid_provenance`。
- 复用已有 `body_hash` 前比对正文；不一致 → `body_corruption`，不覆盖。
- 已知简化：SQL/IO 异常与数据损坏目前都归 `invalid_stored_definition`，未再细分。

## M2a-FIX2（2026-11-04）

- `typeError` 与 `CapabilityJson.decodeType` 对齐：拒绝空 Record、空白字段名，递归检查嵌套 Record。
- `propose` 写事务前对每个输入/输出类型做 `CanonicalJson.type → decodeType` 往返；不可逆返回 `invalid_type`。
- 修复前存在 “propose 成功、inspect 失败” 的不变量破坏；X05 回归覆盖。

## M2b-1a schema v2（2026-11-04）

- 版本策略：`0 → 2`（空库一次建全）、`1 → 2`（单事务补表）、`2` 原样打开、其他值 `unsupported_schema_version`。不再存在“新建库停在 v1”的中间态。
- 迁移只增加 `verification_runs`、`activations` 两张空表；不 ALTER 旧四表，不动 `capabilities.status CHECK(status='candidate')`。
- 错误码：初始化失败 `schema_init_failed`；已有 v1 库迁移失败 `schema_migration_failed`。
- `PRAGMA user_version` 的写入与 DDL 同事务：M04 实测第二个 `CREATE` 失败后版本号回到 1、已建表被回滚、迁移前已存在的冲突表保留。
- 迁移不读也不写旧表数据，旧行 `content_hash/body_hash` 不重算；迁移后用 `inspect` 复算校验通过。
- 本步不写任何验证/激活行，`verification_runs` 与 `activations` 为空。

## M2b-1b 可信验证套件（2026-11-04）

- 套件契约：`suite_id`、`suite_version`、`suite_hash`、只读 `cases`；用例含 `case_id`、类型化输入、预期 `EvalResult`。
- `suite_hash` 域分隔 `verification-suite-v1`；Map/Record 字段排序，用例顺序保留；不使用 `toString()`。
- `source` 由宿主 `TrustedSuiteRegistry.register` 盖章，只允许 `host_fixture` / `implementer_run`；能力定义与 provenance 无法传入。
- 同一 `(suite_id, suite_version)` 只允许一个哈希；同内容重复登记幂等，内容不同 `suite_version_conflict`。
- 引擎先 `CapabilityInspector` 检查（类型检查含依赖 pin、复算 contentHash），再逐例执行；只有 Bool3 输出可验证。
- 判定：实际 `Error` 一律失败；预期 `Error` 非法；`Unknown` 不当 `false`；预期/实际同为 `Unknown` 算匹配但不计覆盖。
- 覆盖度：按规范化输入去重的确定用例 ≥ 4 且至少 1 真 1 假，否则 `suite_inadequate`；空套件 `suite_empty`；重复 `case_id` `duplicate_case_id`。
- 代码无 `passed`/`verdict` 入参，verdict 只由引擎计算；本步不写库、不激活。

## M2b-1c 验证入库（2026-11-04）

- `CapabilityVerificationService.verify(id, version, suiteId, suiteVersion)`：先 DB `inspect`，再执行内存引擎，最后追加 run + event。
- `verification_runs` 与 `events(kind='verified')` 同事务；事件写入失败用触发器实测，整笔回滚为 `transaction_failed`。
- 只追加：每次 `run_id=UUID`，不更新旧行；失败运行与 `suite_inadequate` 也保留（`verdict=fail`）。
- 逐例证据进事件 JSON（≤1 MiB）：`case_id/status/counted_as_definite/expected/actual`；超限返回 `verification_trace_too_large`，不截断失败案例、不写库。
- 前置拒绝不落库：`unknown_suite`、`body_hash_mismatch`、`dependency_table_mismatch`、`missing_dependency`、`suite_empty`、`unsupported_output_type`。
- `source` 由宿主登记决定；本轮报告统一写 `verification_source=implementer_run`，库内记录仍如实保留套件登记来源。
- `pass` 仅表示该套件通过；`suite_hash` 只做一致性绑定，不证明测试集独立。

## M2b-2a 激活门禁（2026-11-04）

- `CapabilityActivationService.activate(id,version,runId)`：`inspect` → run 绑定 `(id,version,contentHash)` → `verdict=pass` → 套件已登记且 `suite_hash`/`source` 一致 → 传递依赖全部已激活 → 追加 `activations` + `events(kind='activated')`。
- 不修改 `capabilities.status`；激活是追加记录推导的逻辑状态。报告不得写成数据库 status 字段变化。
- 幂等规则：同 `(id,version)` 已有同 `content_hash` 激活记录 → `already_active`，不追加事件；哈希不同 → `activation_conflict`。
- 依赖检查抽成 `ActivationGate`，与 M2b-2b 正式执行入口共用；深度上限 32，`visited` 去重。
- 错误码：`verification_not_found / verification_mismatch / verification_failed / suite_not_registered / suite_hash_mismatch / untrusted_source / source_mismatch / dependency_not_activated / dependency_hash_mismatch / dependency_depth_limit / activation_conflict / transaction_failed`。
- 库内 `source` 文本被篡改 → `source_mismatch`；未登记套件即使 run 行伪造为 pass 也拒绝。

## M2b-2b 正式执行入口（2026-11-04）

- `CapabilityExecutionService.executeActive(id,version,args,budget)`：每次执行重新 `inspect`、核对激活哈希、验证 run 的 verdict/绑定、递归核对依赖激活，再调用 M1 解释器。
- `ok=true` 只表示门禁通过且确实执行；`resultKind` 区分 `known/unknown/error`，`Unknown` 不是 `false`，`Error` 不是成功预测。
- `CapabilityRegistry.resolve` 与 `CapabilityInterpreter` 保留为内部开发/验证接口；正式应用必须走 `executeActive`。同进程内不是安全边界。
- 执行结果本轮不单独持久化（schema 固定 6 表）；不新增执行日志表，也不拿验证记录冒充执行证据。

## M2c-1a 追加式撤销（2026-11-05）

- schema v3：`revocations(revoke_id, capability_id, version, content_hash, reason_code, evidence_ref, created_at)`，外键指向 `capabilities(capability_id,version)`，只追加。
- 迁移路径：`0→3` 一次建全；`1→3`、`2→3` 单事务补表；`3` 原样打开；其他版本 `unsupported_schema_version`；失败 `schema_migration_failed` 且回滚。
- `revoke` 要求原因码与证据引用非空（各 ≤4 KiB），否则 `invalid_revocation_reason` / `invalid_evidence_ref`。
- 重复撤销：同 `(id,version)` 同哈希 → `already_revoked` 幂等，不追加事件；哈希不一致 → `revocation_conflict`。
- 撤销记录 + `events(kind='revoked')` 同事务；触发器注入失败实测回滚。
- 撤销优先于激活与执行：`CapabilityActivationService` 与 `CapabilityExecutionService` 对已撤销版本返回 `revoked`；`inspect` 仍可读，历史验证/激活/正文记录全部保留。
- 本轮不提供旧版本“取消撤销/恢复”；修正必须发布新版本、重新验证和激活。传递依赖失效与恢复在 M2c-1b。

## M2c-1b 传递失效与恢复（2026-11-05）

- `ActivationGate.check` 递归核对：依赖撤销 → 激活记录 → 哈希 → 引用验证仍为 pass 且绑定一致 → 继续向下 → 最后检查该依赖自身 `inspect`。
- 先递归、后自检，使失败路径落在最深层坏点；`GateFailure(code, path)` 的 `detail` 形如 `chain.b@1 -> chain.c@1`。
- `Registry.inspect` 拆为 `inspectSelf`（本行）与完整版；整链失败但本行完好时，用 `locateDependencyFailure` 判定 `dependency_invalid` 并给路径。
- 错误码：根撤销 `revoked`；依赖撤销 `dependency_revoked`；依赖未激活 `dependency_not_activated`；依赖哈希错配 `dependency_hash_mismatch`；依赖验证/正文损坏 `dependency_invalid`；根自身损坏保留 inspect 原始码。
- 不缓存依赖健康；每次执行重新读库核对，重启一致。
- 恢复只通过新版本 + 新调用方：旧 A/B 钉住旧 C，C 被撤销后不会因 C2 出现而自动恢复。

## M3-1a 有界候选枚举（2026-11-05）

- `CapabilityComposer.compose(ComposeRequest)`：叶子 = 输入参数、Record 顶层字段、公开常量；逐层生成 `not/and/or/eq/call`。
- 类型安全：每个候选先算 canonical hash 去重，再用 `CapabilityTypeChecker` 以目标签名做完整检查；Call 锁定 `(id,version,contentHash)`。
- 不做 `and/or/eq` 交换律归一化，不做 `if/let/ref` 生成，不做语义等价合并；只做“按类型重建”的安全构造。
- 预算：`maxDepth`（组合层数）、`maxNodes`（单表达式节点）、`maxCandidates`（目标类型候选数）、`maxCallsPerCapability`（每能力实参组合上限）。
- 结果：候选按 `(节点数, canonical)` 稳定排序；`reason` 为 `candidate_limit` / `depth_limit`；`nodeLimitSkipped` 记录被节点上限丢弃的数量。
- `Registry.activeCapabilities()`：只返回已激活、验证 pass、未撤销、依赖链健康的快照；组合器不直接查库。
- 来源只记 `composer_bounded_search`；M3-1c 注册时再按实际流程判定 `student_composed`，不得提前声称学生自主发现。

## M3-1b 公开样本筛选（2026-11-05）

- `CandidateEvidenceFilter.filter(inputs, candidates, examples, queryValues, budget, searchExhausted)`：逐候选执行全部公开样本。
- `Known` 精确比较；`Unknown` 不算命中；`Error` 直接淘汰；`Unknown` 不当 `false`。
- 前置拒绝：空样本 `no_public_examples`、同输入不同标签 `conflicting_evidence`、schema 外字段 `unknown_attribute`、参数/类型不符 `invalid_example`、搜索被截断 `search_budget_exhausted`。
- 幸存候选在公开查询域（每参数取值笛卡尔积，≤256 点）上比较输出签名：同一签名归为一个等价类；只有一个等价类才 `IDENTIFIED`，代表取节点最少、canonical 最小者。
- 多个等价类 → `AMBIGUOUS`，返回最多 3 个能区分它们的输入；没有查询域且幸存者多于一个 → `insufficient_query_domain`。
- 没有一致候选 → `REFUSED / no_consistent_candidate`；候选为空 → `hypothesis_space_exhausted`。
- 等价只在公开域上成立，报告写 `bounded_equivalence`，不冒充全局语义等价。
