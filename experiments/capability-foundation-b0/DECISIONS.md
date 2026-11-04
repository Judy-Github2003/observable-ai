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
