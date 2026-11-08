# E2.3-V Phase A：学习器源码冻结

## 状态

```text
E23V_FREEZE_CREATED
FREEZE.json sha256 = f14e610237e4d05176a596fc3d2edd428a63e9c28e9f47294a9c4d4a1f778b62
```

- 冻结前 HEAD：`9ae266e`
- 考官压缩包 SHA-256（仅登记，不读取）：`603d7fa3...`
- `independentClosedBook = NOT_CLAIMED`
- 8 类目标、32 个任务、每实例 4 次查询
- Arms：STATIC / FULL_REFIT / KEEP_IF_AGREE / LOCAL_FIRST

## 五个冻结源文件

| 文件 | SHA-256 |
|---|---|
| `SD03EvidenceCompositionE1.kt` | `1b793697...` |
| `SD03ActiveQueryLocalRepairE2.kt` | `c77d0e66...` |
| `SD03E23EvidenceUpdates.kt` | `eceb6d0b...` |
| `SD03E2RepoAdapter.kt` | `72724ddf...` |
| `build.gradle.kts` | `6c42ee0b...` |

完整值见 [FREEZE.json](FREEZE.json)。

## 定向快速测试

命令：

```powershell
.\gradlew.bat --offline --no-daemon --console=plain quickTest `
  "-PquickTests=logic.e3.pack02.SD03E23UpdateContractTest,logic.e3.pack02.SD03E22ContractTest,logic.e3.pack02.SD03EvidenceCompositionE1ContractTest"
```

| 测试类 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `SD03E23UpdateContractTest` | 7 | 0 | 0 | 0 |
| `SD03E22ContractTest` | 10 | 0 | 0 | 0 |
| `SD03EvidenceCompositionE1ContractTest` | 8 | 0 | 0 | 0 |
| **合计** | **25** | **0** | **0** | **0** |

- 真实墙钟：**7.8s**
- Gradle：`BUILD SUCCESSFUL in 7s`
- 测试用例时间合计：0.105s

## 边界

- 未修改学习算法；
- 未运行历史完整回归；
- 未进行新任务评分；
- 未读取或解压 Phase B 考官包；
- 未启动 SD-04；
- 工作树在冻结时干净（4 个与本研究无关的 untracked 文档已临时移出，冻结后恢复）。
