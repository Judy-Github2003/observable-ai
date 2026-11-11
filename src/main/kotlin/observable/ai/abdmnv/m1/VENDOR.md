# Vendored M1 reference sources (X01-SLICE1)

本目录的 13 个 `.kt` 文件是 **ABDMNV M1 参考实现的只读副本**，用于 X01 桥接在同一
Gradle 模块内编译。它们**不是**仓库原创代码，也**不是**权威冻结源。

- 来源：`Observable-AI-X01-SLICE1-Start-2026-11-11.zip` 的 `reference-m1/`，
  该包又从 2026-11-11 FMPS 补充包复制。
- 处理：逐字节原样复制，未改任何内容；文件头因此保持原作者标注。
- 哈希：见本目录同级交付包 `SHA256SUMS` / `PACKAGE-HASHES.sha256`。
- 注意：其中 `CombinedVersionStore.kt` 是 **270 行版本**，已包含 M2-03.1 的
  `scanActiveConcepts`/`storedConceptCount` 扩展；原始冻结 M1 是 247 行（sha `a2812c59…`）。
  本片不使用这两个扩展接口，桥接只调用 `get`/`executable`。
- 修改纪律：不得在本目录原地修改这些文件；需要变更时应升级来源并在报告中记录新 hash。
