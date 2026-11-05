# 学习基底 B0：冻结状态

> 实现：云端 ChatGPT 提供 M1 参考骨架；DeepSeek Harness（本会话）集成、补测、修复并完成 M2–M6。
> 冻结代码 SHA：`0ca4561309aeb914df6e3f2c3877a50b080bf731`；参考：`SPEC.md`、`DECISIONS.md`、`reports/B0-IMPLEMENTATION-REPORT.md`。

## 状态

| 里程碑 | 状态 |
|---|---|
| M0 冻结基线 | 完成（`baseline.json`） |
| **M1 Typed IR + 类型检查 + 解释器** | **implemented** |
| **M2a 注册表（candidate）** | **implemented（含 M2a-FIX）** |
| M2b 可信验证与激活 | **implemented**（1a/1b/1c/2a/2b） |
| M2c 撤销与依赖失效 | **implemented**（1a/1b） |
| M3 Composer | **implemented**（1a 候选枚举、1b 证据筛选、1c 注册/留出集） |
| M4 压缩 L1/L2 | **implemented**（1a L1 成本、1b 子树抽取、1c 重写与净收益；L3 `not_implemented`） |
| M5 A/B/C/D 实验 | **implemented**（A/B/C 公开开发；D=L3 `not_implemented`） |
| M6 冻结交接 | **implemented**（`reports/B0-FREEZE.json`、干净 worktree 复现） |
| 独立闭卷 | `not_run` |

## 实现范围

- 11 类节点：`arg/const/field/eq/and/or/not/if/let/ref/call`；二元 `and/or/eq`。
- 类型：`Bool3 / String / Int64 / Record`；`List/Optional` 明确 `not_implemented`。
- 强 Kleene 三值、左到右短路、`if` 条件 unknown 不执行分支。
- canonical JSON：Record 字段与 call 实参 key 排序；`and/or/eq` 操作数顺序保留。
- `content_hash = SHA-256(semantics + signature + canonical AST)`；call 锁定 `(id, version, body_hash)`。
- 静态检查：类型、未绑定 ref、重复绑定、依赖 hash、循环、AST 深度、调用深度。
- 解释器：步数/调用深度预算；错误与 unknown 分离。

## 运行

```powershell
./gradlew.bat --no-daemon test installDist
python tools/capability_b0_benchmark.py   # not_implemented（M5）
```

测试：全套 **173/173**（V1/V2 27 + M1 21 + M2a 17 + M2b 50 + M2c 15 + M3 24 + M4 16）。

## 边界

- 不接 SQLite、不调用教师、不修改 V1/V2。
- 不执行教师给的 Kotlin 字符串；IR 必须先类型检查。
- M1 通过只说明内核正确，不是学习能力、不是压缩收益、不是独立验证。
