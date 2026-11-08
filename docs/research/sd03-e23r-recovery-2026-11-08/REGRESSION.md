# E2.2.2 修复后隔离全量回归：507 tests / 3 failures / 665.268s

## 运行

- 源 HEAD：`891700cb48289fb14b88dfe697df6fa66c68500d`
- 隔离方式：detached Git worktree
- 命令：`gradlew --offline --console=plain --no-daemon test`
- 实际耗时：**665.268s**
- 平台：Windows 11 / 24 CPU / JDK 21
- Gradle：offline，`--no-daemon`，warm cache
- 历史 tracked files：453，前后 digest 相同
- JUnit：113 files，**507 tests / 3 failures / 0 errors / 0 skipped**

## 原始判定

```text
FULL_REGRESSION = FAIL
```

按预注册，任何失败都是 FAIL，不能因“失败都是历史冻结”而改写为 PASS。

## 三个失败

| 测试 | 失败 |
|---|---|
| `C8FormalExamTest` | student source hash 不一致 |
| `MRXFormalTest` | `MRXSpace.kt` hash 不一致 |
| `MRXRFormalTest` | `MRXREvidence.kt` hash 不一致 |

`classify_regression.py`：

```json
{
  "total": 507,
  "passed": 504,
  "failed": 3,
  "errors": 0,
  "skipped": 0,
  "archivalSourceMismatch": 3,
  "otherFailures": 0,
  "fullUnfilteredGradleRegression": "FAIL",
  "activeCodeWithoutHistoricMismatches": "NO_OTHER_FAILURES_RECORDED"
}
```

## 原因审计

`audit_historical_freezes.py --git-history` 对主仓库当前工作树检查了 3 个历史 manifest、11 个 source：

```text
status: ARCHIVE_AND_HEAD_COMPARED_NOT_RESIGNED
mismatchedSources: 0
```

即：**主工作树中 11 个源文件字节哈希全部与历史 manifest 匹配**。

但隔离 worktree 使用 `core.autocrlf=true` 重新 checkout，文件变为 CRLF；测试直接对 `Files.readString` 文本计算 SHA-256，得到 CRLF 哈希：

| 文件 | manifest/LF | worktree CRLF |
|---|---|---|
| `C8ActiveStudent.kt` | `f37d...` | `cdaa...` |
| `MRXSpace.kt` | `b1a2...` | `1e5c...` |
| `MRXREvidence.kt` | `5977...` | `352a...` |

所以这 3 个失败是**隔离 checkout 的换行符环境差异**，不是主仓库源文件内容漂移，也不是学习策略的回退。未修改旧 manifest，未重签 hash，未删除失败测试。

## 修复效果

| 项 | 修复前 | 修复后 |
|---|---|---|
| E1 FROZEN `.json` 碰撞 | FAIL | **PASS** |
| E2.3 token 错位 | FAIL | **PASS** |
| 历史 formal hash | 3 FAIL | 3 FAIL（CRLF 环境） |
| 其他失败 | 2 | **0** |

E1 与 E2.3 的隔离/元数据问题已修复；仅剩 3 个 formal 换行符失败。

## 说明

- 没有把 504/507 说成“全部通过”；
- 没有用 `quickTest` 代替全量回归；
- 没有在隔离 worktree 中修改历史 manifest；
- 未改变 E2.3 学习策略核心。
