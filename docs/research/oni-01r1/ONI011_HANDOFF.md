# ONI-01.1 交接

**复核入口**　阅读同目录的 [实验报告](ONI011_EXPERIMENT_REPORT.md)、[冻结协议](ONI011_EXPERIMENT_PROTOCOL.json) 和 [冻结清单](ONI011_FREEZE.json)。下表中的原始工件随 `ONI_01R1_RESULTS.zip` 交付。

| 想查看的证据 | ZIP 中的位置 |
|---|---|
| 每题在每个条件下实际生成了什么 | `ONI011_PREDICTIONS.jsonl` |
| 哪些生成失败、评分依据是什么 | `ONI011_EXAMINER/` 中的 `scored_rows.jsonl`、`failures.jsonl` 和 `score.json` |
| 非零修改是否被紧随其后的层消费 | `ONI011_TRACE/`、`build/oni-01r1/t1/` |
| 怎样检查明确字段的贡献 | `build/oni-01r1/field_probes/`、八道题的字段消融记录 |
| 如何选择层、强度与门槛，哪些配置失败 | `build/oni-01r1/development/` |
| 考官承诺和预测封存顺序 | `ONI011_EXAMINER/data/commitment.json`、`completion.json`、`likelihood_completion.json` |
| 逐 token 的目标对数概率 | `likelihoods.jsonl` |
| 模型、依赖与审查事件 | `build/oni-01r1/model.json`、`environment/`、`build/oni-01r1/authorization_finish.json` |

## 复核现有结果

在结果包根目录用 Python 3.11 或更新版运行。评分器从封存回执取得预测哈希，仅在核对条件矩阵和预测后打开已揭盲的答案。输出目录必须尚未存在。

```powershell
$stamp = Get-Content -Raw completion.json | ConvertFrom-Json
python ONI011_EXAMINER/score.py --exam ONI011_EXAMINER/data --predictions ONI011_PREDICTIONS.jsonl --predictions-sha $stamp.sha256 --ll likelihoods.jsonl --out replay-score
```

`PACKAGE_MANIFEST.json` 提供每个交付文件的大小、SHA-256 和原工作区相对路径。ZIP 旁的 `.sha256` 校验整个压缩档。报告和首次结论使用正式评分原件。复评分的 UTC 写入时间会更新。

## 开发运行环境

原运行环境为原生 Windows CUDA BF16。使用固定提交 `42433e90dad95123ab87729fd7011e7110629556` 对应的 MiniCPM5-2B，真实权重 SHA-256 为 `14fb8e7f0a18d53d1f239773758bf581cee7e456a4523a54622c3a245b64402c`。固定环境及代码在解压包内。模型使用本地只读副本。开发驱动会拒绝覆盖已有记录。

在一份空的运行目录中重做开发时，先按清单中的原路径放好源码、同版模型与依赖，再从仓库根执行。完整运行需要与 `src/oni01/runtime.py` 所列位置一致的本地模型和支持 BF16 的 CUDA 设备。

```powershell
$env:PYTHONPATH = "src"
$env:HF_HUB_OFFLINE = "1"
$env:TRANSFORMERS_OFFLINE = "1"
python -m oni011.experiment develop
```

冻结提交记录实验工作区内的源版本。GitHub 以独立公共提交分发相关文件，逐文件核对请使用冻结清单。已公开的种子和答案仅适用于重放。任何新的盲测须重新冻结、生成新题并封存预测。研究负责人拥有下一轮决策权。

## 本轮裁定依据

隐藏适用题 BASE 和 STRUCT_FORCED 均为 2/24，两个世界各 1/12。P0 与配对恢复检查通过，P1、P3 未达标。P4 未证明跨世界任务收益。I1 在未见多 token 值上没有带来有益翻转。I2 只在两个公开图的深度读取上获得局部支持。I3 未证明。基于本轮证据，建议暂缓 ONI-02。
