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
