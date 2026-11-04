# B0 M1 验收报告

```text
commit:              见本次提交（M1）+ a9276f8（M0）
old_regression:      27/27（V1 11 + V2 16）
new_tests:           17（参考 7 + 清单补 10）
IR/types:            11 节点；Bool3/String/Int64/Record；List/Optional not_implemented
composer:            not_implemented
registry:            not_implemented
compression_L1/L2/L3: not_implemented / not_implemented / not_implemented
benchmark:           not_implemented
teacher_calls:       0
invalid_promotions:  n/a（M1 无晋升）
independent_closed_book: not_run
```

## 1. 实现

| 文件 | 内容 |
|---|---|
| `src/main/kotlin/logic/capability/CapabilityIr.kt` | AST/类型/值、canonical JSON、content hash、InMemoryResolver |
| `CapabilityJson.kt` | 严格 JSON 解析（固定 key 集、Bool3/Int64 规范、深度限制） |
| `CapabilityTypeChecker.kt` | 签名、字段、eq/分支、let/ref、call 锁定、循环/深度 |
| `CapabilityInterpreter.kt` | 三值求值、短路、预算、错误分类 |

参考骨架由 ChatGPT 提供；DSH 集成、加文件头、补测并修复。

## 2. 验收清单

| 编号 | 状态 | 证据 |
|---|---|---|
| T01 单条件 | 通过 | `CapabilityM1Test.threeDistinctProgramsUseSamePrimitiveSet` |
| T02 双条件 AND | 通过 | 同上 + 参考测试 |
| T03 缺字段 | 通过 | `M1-T03 missing field stays unknown` |
| T04 Kleene 吸收 | 通过 | 参考 `unknownIsNotFalseAndKleeneShortCircuits` |
| T05 not/eq unknown | 通过 | 新增 `M1-T05 T06 ...` |
| T06 if unknown 不执行分支 | 通过 | 参考 + 预算实验 |
| T07 类型错误 | 通过 | `M1-T07` + 参考 |
| T08 常量拒绝 | 通过 | `M1-T08` |
| T09 canonical 稳定 | 通过 | `M1-T09` + 参考 |
| T10 操作数顺序 | 通过 | `M1-T10` |
| T11 let/ref | 通过 | `M1-T11` + 参考 |
| T12 pinned call | 通过 | 参考 `lockedCallWorksAndTamperingFails` |
| T13 依赖缺失/hash/环 | **部分** | 缺失与 hash ✓；17 层链验证 `call_depth_limit`；未构造自洽哈希环 |
| T14 预算 | 通过 | 参考 + `M1-T14` |
| T15 类型检查先于执行 | 通过 | `M1-T15` |
| T16 三种组合 | 通过 | 参考 AND/OR/NOT+LET |
| T17 旧回归 | 通过 | 全套 44/44 |

## 3. 测试结果

- `./gradlew.bat --offline --no-daemon test`：**44/44**，0 失败。
- 旧 V1/V2：27/27 未变。
- M1 测试：17 项（`CapabilityM1Test` 7 + `CapabilityM1ChecklistTest` 10）。

## 4. 已知缺口

1. **循环依赖**：严格 hash 锁定下无法构造自洽环；改用调用深度上限验证。独立审查者可用故意弱化的测试 resolver 构造。
2. **examples JSON 未做文件级 roundtrip 断言**：三份 canonical 样例已入库，但测试用字符串而非读文件。
3. **无 CLI**：M1 是库级内核，按定稿不提供独立命令。
4. **M2–M6**：全部 `not_implemented`；未接 SQLite、未做组合/压缩/基准。
5. **独立闭卷**：`not_run`。

## 5. 请外部审查者

- 用固定 commit 检查 11 节点语义与 T01–T17；
- 构造循环依赖场景（可替换 resolver）；
- 确认 M1 成绩只代表内核正确，不代表学习或压缩能力。
