# 学习基底 B0：当前状态

> 实现：DeepSeek Harness（本会话），2026-11-04。
> 任务书：[SPEC.md](./SPEC.md)（收到后复制入库）。
> **当前只完成 M0；M1–M6 尚未开始。** 这不是“部分完成”，是“冻结基线后暂停，等待外部设计答复”。

## 状态表

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 冻结基线 | **完成** | `baseline.json`：HEAD `37e2dd7`、27/27 测试、JDK 21 / Kotlin 2.2.21 |
| M1 Typed IR + 解释器 + 类型检查 | **not_started** | 已删除半成品文件，保证仓库可构建 |
| M2 注册表 / 证据 / 依赖 | **not_started** | — |
| M3 Composer | **not_started** | — |
| M4 库学习 L1/L2 | **not_started** | — |
| M5 A/B/C/D 前哨实验 | **not_started** | — |
| M6 冻结与交接 | **not_started** | — |
| 独立闭卷 | **not_run** | 无外部执行 |

## 为什么暂停

B0 的 M1 就要求同时定死：IR JSON 形状、类型系统、三值/错误语义、canonical 归一化边界、资源预算、call 的版本与循环语义。这些一旦写错，M2–M4 都要返工。我不想在没有外部确认的情况下先写 1000 行再解释“为什么这样设计”。

求助文档：`runs/handoff/B0-求助文档_2026-11-04.md`（未入库，供上传）。

## 已做 / 未做

- 已做：读取 SPEC、核对 HEAD 与测试、建立本目录与 `baseline.json`。
- 未做：任何 `src/main/kotlin/logic/capability/` 源码、测试、CLI、注册表、组合器、压缩器、实验数据。
- 仓库仍可构建；V1/V2 未改动。

## 收到答复后

按答复先做 M1（IR + 类型检查 + 解释器 + 测试），跑通并提交；再依次 M2→M3→M4。每个里程碑单独提交，缺项写 `not_implemented`。
