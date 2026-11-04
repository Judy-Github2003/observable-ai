# B0 M2b-1a 报告

```text
子任务: M2b-1a
状态: complete
基线 SHA: 39bf387b2135e697943bdc2aeeca2c9b35182836
提交 SHA: 见本次提交（对话中给完整 SHA）
推送: 见对话（报告随提交生成，推送在其后执行）
实际执行命令: $env:JAVA_HOME='C:\Users\j1437\.jdks\jbr-21.0.11'; .\gradlew.bat --offline --no-daemon test
测试: 旧 65/65；新增 5/5；失败 0；合计 70/70
新文件/修改文件:
  src/main/kotlin/logic/capability/CapabilityRegistry.kt
  src/test/kotlin/logic/capability/CapabilitySchemaMigrationTest.kt
  experiments/capability-foundation-b0/DECISIONS.md
  experiments/capability-foundation-b0/WORKSTATE.md
  experiments/capability-foundation-b0/reports/M2b-1a-REPORT.md
关键语义及接口: user_version 0→2 一次建全；1→2 单事务补两表并提交；2 不重复迁移；其他版本拒绝
失败用例/未实现/已知边界: 验证器、验证记录、激活、正式执行入口均未实现
独立闭卷: not_run
下一个最小子任务: M2b-1b
```

## 做了什么

- `initializeSchema` 拆成三种路径：
  - `user_version=0`：同一事务创建 v1 旧四表 + v2 新两表，直接写 `user_version=2`；失败 `schema_init_failed` 并回滚。
  - `user_version=1`：单事务执行两条 `CREATE TABLE`，最后 `PRAGMA user_version=2` 才 commit；任一失败 `schema_migration_failed` 并回滚。
  - `user_version=2`：正常打开，不再执行迁移。
  - 其他版本：`unsupported_schema_version`。
- 新增 `verification_runs`、`activations`，字段与约束按任务书固定契约，本步不写任何行。
- 未改旧四表，未改 `capabilities.status CHECK(status='candidate')`，未改读取校验逻辑。

## 事务边界

`PRAGMA user_version` 的写入走 SQLite pager，与 DDL 处于同一事务；M04 用“`activations` 已存在、`verification_runs` 不存在”制造第二个 `CREATE` 失败，验证到：

- 版本号退回 1；
- 第一个 `CREATE` 建出的 `verification_runs` 被回滚掉；
- 迁移前已存在的冲突表保留（不属于本事务）；
- 旧四表逐行快照完全不变；
- 移除冲突表后可正常补迁到 v2。

## 测试

| 用例 | 覆盖 |
|---|---|
| M01 | 真实 v1 夹具（2 能力 + 1 依赖 + 2 事件）迁移后旧行快照逐字节不变，`inspect` 与解释执行正常，新表为空 |
| M02 | 空库直接到 v2，六张表齐全 |
| M03 | 连续重开 3 次仍在 v2，数据不重复 |
| M04 | 第二次 `CREATE` 失败 → `schema_migration_failed`，DDL 与版本号双回滚，可恢复重迁 |
| M05 | `user_version=3` → `unsupported_schema_version`，不建任何表 |

## 未做

- 可信测试套件与验证引擎（M2b-1b）、验证入库（M2b-1c）、激活门禁（M2b-2a）、`executeActive`（M2b-2b）均未实现。
- 独立闭卷 `not_run`；70/70 只是实现者自测，不是外部验证。
