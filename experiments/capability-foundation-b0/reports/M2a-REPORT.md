# B0 M2a 验收报告

```text
M2a commit:           见本次提交
M0 baseline:          a9276f8
M1 commit:            57bc9c7
Old regression:       27/27（V1 11 + V2 16）
M1 tests:             21/21
M2a tests:            9/9（R01-R08、R10；R09 由全套回归覆盖）
Total:                57/57
Store:                SQLite, <state-dir>/capabilities.db, user_version=1
Tables:               capabilities / bodies / dependencies / events
Status:               只允许 candidate
Teacher calls:        0
Independent audit:    not_run
```

## 1. 实现

`src/main/kotlin/logic/capability/CapabilityRegistry.kt`

- `SqliteCapabilityRegistry` 实现 `CapabilityRegistry : CapabilityResolver`。
- `propose`：结构限额（迭代遍历）→ 两种 hash → 重复检查 → 依赖存在/hash 校验 → 类型检查 → 单事务写四表。
- `inspect`：读库 → 重新解析 canonical IR → 复算 `astHash` 与 `contentHash` → 重比对依赖表 → 校验依赖内容 hash。
- `resolve`：`inspect` 成功才返回定义，供 M1 解释器/类型检查器使用。

## 2. R01–R10

| 编号 | 状态 | 证据 |
|---|---|---|
| R01 持久化/重启 | 通过 | `R01 persists and resolves after reopen` |
| R02 不可变版本/幂等 | 通过 | `R02 immutable versions...`（events=1） |
| R03 依赖提取 + call | 通过 | `R03 dependency extraction...` |
| R04 缺依赖/错 hash/无半写 | 通过 | `R04 ... leave no partial rows` |
| R05 篡改 body/content | 通过 | `R05 tampering...`（两种错误码） |
| R06 事务回滚 | 通过 | 触发器强制 events 失败，四表均 0 行 |
| R07 三层链 + 去重 | 通过 | `R07 nested chain...`（依赖 1 行） |
| R08 超限输入不崩溃 | 通过 | 257 字段、200 层 Not → 结构化错误 |
| R09 旧回归 | 通过 | 全套 57/57，V1/V2 27 项未变 |
| R10 坏路径/未知 schema | 通过 | `registry_open_failed` / `unsupported_schema_version` |

## 3. 已知边界

1. **只到 candidate**：没有 `verify/activate`、证据表、依赖失效传播；这些是 M2b/M2c。
2. **无前向引用**：依赖必须已入库；相互递归/前向引用不支持。
3. **读取无缓存**：每次 `inspect` 都重新解析/复算；小规模可接受，大图需后续优化。
4. **只读数据库/磁盘只读**：未单独测（Windows 权限场景），R10 覆盖坏路径与未知 schema。
5. **并发**：单连接、WAL 未启用；单进程测试通过，多进程并发未验证。
6. `Expr.Call.body_hash` 的命名沿用 M1，实际是 callee 的 `contentHash()`；已在 DECISIONS 说明。

## 4. 未做

M2b 激活门禁、M2c 撤回传播、M3 Composer、M4 压缩、M5 基准、M6 交接：全部 `not_implemented`。独立闭卷 `not_run`。
