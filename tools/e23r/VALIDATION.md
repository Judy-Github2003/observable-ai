# 本地验证记录（不属于正式科研）

1. 从用户上传的 E22 原始结果 ZIP 中提取 8 份 E22 `STUDENT-EVENTS-*.jsonl`，按既有 world/seed 重新派生 token，使用 Python `--check` 比较配置：`E23_REPLAY_PLAN_VERIFIED`，8 tasks，11 labels；manifest SHA256 `a47dceb31165bffda5c2f6a6de671a22fcb288ff70cb3b298f458dd125405c80`。
2. 用上轮结果包中的 E23 源码和回归脚本建立临时仓库副本，`apply_recovery.py` 运行 `CHECK`、`--apply`、再次 `CHECK`，三份文件状态分别从 `PATCH_NEEDED` 到 `ALREADY_PATCHED`；E22 原始 JSONL 不变。
3. 7 项 Python 工程测试全部通过，包括 token 派生、历史查询记录对齐、沙箱精确删除策略、历史 JUnit 仍 FAIL、未知源码拒绝覆盖。
4. `classify_regression.py` 对用户上传的实际回归 JUnit XML 复核：507 tests / 502 PASS / 5 FAIL / 0 skipped，其中 3 份历史 formal freeze mismatch + 2 份本轮修复目标。
5. **没有**完整真实仓库 Gradle 编译，没有无筛选 `test` 重跑，没有 E23 研究对照评分。本包只提供补丁和核验工具，不给出新科研成绩。
