# ABDMNV-01 M1 修复轮结果（审计缺陷 A–H）

执行者：DeepSeek Harness，2026-11-09。
授权：`ABDMNV-01-M1-FIX-2026-11-09`（ACTIVE repair grant），preflight `AUTHORIZED_REPAIR`。
输入：`ABDMNV01-M1-Combined-Audit-Fix-Package-2026-11-09.zip`
（sha256 `d3e350656462205614398e6ab934d5dc26445965a39f78503af79b03d5e3e70c`）。

## 结果

| 套件 | 结果 | 耗时 |
|---|---|---|
| 参考实现公开测试（未改语义） | 106 / 106 PASS | 204 ms |
| 聚合契约测试（按新 API 更新） | 30 / 30 PASS | 177 ms |
| 审计缺陷回归（新增 A–H） | 16 / 16 PASS | 202 ms |
| 端到端演示（新增） | 2 世界 MATCH + 1 世界弃答 | 独立进程 |
| DeepSeek 原 58 条（独立 jar 复跑） | 58 / 58 PASS | 200 ms |

交付 ZIP：`ABDMNV01-M1-Fix-2026-11-09.zip`（源码 + fixtures + 日志 + diff + 校验和）。

## 修复摘要

- **A/B/C**：`propose/get/activate/revoke/supersede` 全部校验 `ref.hash == 内容哈希`；伪造哈希 → `HASH_MISMATCH`。
- **D**：依赖序列化为 `id@version#hash`，pinned hash 进入内容哈希。
- **E**：`checkGraph` 拒绝环；`executable` 用 visiting 集合防环。内容寻址使哈希自洽的环不可构造，故环检测放在结构入口。
- **F**：缺失依赖 `MISSING_DEPENDENCY`。
- **G**：账本 `read/conflicts` 强制 `worldId`；`requireVerified` 区分 `STUDENT_REPORTED` 与 `EXAMINER_VERIFIED`。
- **H**：`PROPOSED` 只有 `candidateExecutable`，`executable` 只认 `ACTIVE_WITH_SCOPE`。
- **5**：学生概念 id = `c_<sha256(world|body)[0..12]>`，跨世界不同；hash 为内容哈希且对对象重命名稳定。
- **4**：新增 `CombinedPipeline`，把 ledger→StudentView→学生→store→board→scorer 串成一条公开链路；E2E 演示含弃答路径。
- **6**：`fixtures/golden` 8 个文件随包，解压即可运行。

## 边界

公开层，不是独立闭卷；无 L2 重放、无隐藏考官、无六臂实验、无历史全量回归。
修复代码在独立工作目录产出并以 ZIP 交付，未写入仓库；仓库只登记授权与本说明。
