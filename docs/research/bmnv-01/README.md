# Observable AI — BMNV-01 单包研究交付

**组合方向：B × M × N × V**（新观察与知识获取 × 表征结构自主进化 × 科学实验与自主发现 × 科学评测与可证伪性）

**这是一个 ZIP，不分 A/B 包。** 同一个压缩包同时包含：Kotlin 学生参考实现、公开契约、可见考官、一次性冻结脚本、运行器、Python 独立核算工具和研究交付说明。

> 证据边界：这是 **可见考官的开发实验**，**不是独立闭卷**。学生算法由 ChatGPT 提供，是供 DeepSeek 整合/改进的参考起点，不能作为“学生自主发明了学习算法”的证据。

## 给 DeepSeek 的第一份文档

请先阅读 [`docs/02-DEEPSEEK-EXECUTION.md`](docs/02-DEEPSEEK-EXECUTION.md)，不要先运行 examiner。核心研究协议见 [`docs/01-RESEARCH-PROTOCOL.md`](docs/01-RESEARCH-PROTOCOL.md)。

## 包内目录

```text
README.md
PROJECT-MANIFEST.json
SHA256SUMS.txt
docs/
  01-RESEARCH-PROTOCOL.md
  02-DEEPSEEK-EXECUTION.md
  03-EVIDENCE-AND-LIMITATIONS.md
src/main/kotlin/logic/e3/bmnv/BMNV01Kernel.kt
src/test/kotlin/logic/e3/bmnv/BMNV01PublicContract.kt
examiner/BMNV01Examiner.kt
tools/
  freeze_bmnv.py
  public_smoke.py
  run_bmnv.py
  audit_bmnv.py
```

## 不依赖 Gradle 的参考命令

在解压目录执行（要求 JDK + `kotlinc` 在 PATH 中）：

```bash
python tools/public_smoke.py --root .
# 确认修改及公开测试完成后，只冻结一次：
python tools/freeze_bmnv.py --root .
python tools/freeze_bmnv.py --root . --verify
# 正式考试：仅在 Git 提交已冻结、研究门禁通过之后执行一次！
python tools/run_bmnv.py --root . --out ./build/bmnv-01-official-once
python tools/audit_bmnv.py --result ./build/bmnv-01-official-once
```

上面是工具入口展示，**不是要求跳过既有 Research Console 的授权门禁**。如使用 GitHub 仓库，需要将 `examiner/BMNV01Examiner.kt` 复制到 `src/test/kotlin/logic/e3/bmnv/BMNV01Examiner.kt`，再冻结。迁移前后均保存文件 SHA-256。建议将 `tools/*.py` 复制到 `tools/bmnv/`；注意 Python 工具互相导入，须放在同一目录。

如果本机没有 `kotlinc`，可以用仓库现有 Kotlin Gradle plugin / embedded compiler 运行相同的源码，但必须先确认学生及考官源码哈希没有变化。不要为了运行方便直接修改冻结源码。

## 明确不做的事

- 不修改 GNJV-01、SD-03、PACK-02 历史冻结文件。
- 不为了“全绿”重新签学生哈希、反复刷正式考卷或覆盖失败结果。
- 不无条件运行 507 项历史全量回归或 160 次 formal 考试。
- 不擅自认定 OPEN-WORLD 或“自主发明新表征”。有限语法候选搜索只能证明**有界的组合表示形成**。
- 不自动开启 BMNV-02 或其他线性阶段。该组合结束后回到 A–Z 研究地图选择下一组。

## 离线生成验证

编写时用 Kotlin 1.9/JDK21 编译通过，公共合同 11/11 通过；可见考官使用**开发专用盐值**运行过一次以检查代码路径，结果不属于正式实验。正式结果只能由实施者在冻结后提供，且需要保留原始证据。
