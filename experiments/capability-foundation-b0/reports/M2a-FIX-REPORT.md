# B0 M2a-FIX 报告

```text
M2a-FIX commit:       见本次提交
M2a commit:           67c1f1f
Old regression:       27/27
M1 tests:             21/21
M2a tests:            9/9
M2a-FIX tests:        6（X01, X02, X02b, X03, X04, X04c）
Total:                63/63
Independent audit:    not_run
```

## 修复

| 编号 | 修复 |
|---|---|
| P0-1 | `readRow` 纳入 `inspect` 的受控边界；签名 JSON/类型不可解析 → `invalid_stored_definition`；`resolve` 返回 null |
| P0-2 | 同 `(id,version)` 先 `inspect` 验完整性；损坏不得 `already_exists`；不同内容仍 `immutable_version_conflict` |
| P1-3 | 读取 `semantics_version`；不等于 `IR_SEMANTICS_VERSION` → `unsupported_ir_semantics_version` |
| P1-4 | 直接定义也做限额：ID 256B、常量 64KiB、canonical AST 1MiB、provenance 64KiB 且须 JSON 对象；复用 body 前比对正文，损坏 → `body_corruption` |

## 测试

- `X01`：inputs_json 非法 JSON / output_json 非法类型 → `invalid_stored_definition`，`resolve=null`。
- `X02`：篡改 body 后重复提交 → 失败，事件仍为 1；`X02b` 正常幂等 → 1 行 1 事件。
- `X03`：`semantics_version=cap-ir-v999` → `unsupported_ir_semantics_version`。
- `X04`：70KB 常量 → `definition_too_large`；非法/超大 provenance → `invalid_provenance`。
- `X04c`：已有 body 被篡改，新 id 复用 → `body_corruption`，无新行。

## 已知边界

- SQL/IO 异常与数据损坏统一报 `invalid_stored_definition`，未细分。
- 未做 M2b 激活门禁、M2c 撤回、M3/M4；独立闭卷 `not_run`。
