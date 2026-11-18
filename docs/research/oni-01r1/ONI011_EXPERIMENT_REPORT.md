# ONI-01.1 实验报告

固定权重 MiniCPM5-2B 在 24 道适用隐藏题上，强制结构注入答对 **2/24**，相同提示的 BASE 答对 **2/24**，SHAM 答对 **2/24**。预注册主指标 P1 **未通过**。相比 BASE，0 道从错变对，0 道从对变错。

## 原始生成与成对比较

| 条件 | 适用 /24 | 阴性 /12 | 歧义 /8 | 一般 /12 | 适用题平均完整答案 LL | 适用题逐题平均 NLL/token |
|---|---:|---:|---:|---:|---:|---:|
| BASE | 2 | 11 | 0 | 6 | -8.697 | 2.548 |
| SHAM | 2 | 11 | 0 | 6 | -9.293 | 2.735 |
| LEGACY_ANCHOR | 2 | 11 | 0 | 6 | -8.738 | 2.556 |
| VALUE_NO_AUX | 2 | 12 | 0 | 6 | -9.130 | 2.681 |
| STRUCT_FORCED | 2 | 12 | 0 | 6 | -8.657 | 2.658 |
| STRUCT_ZERO | 2 | 11 | 0 | 6 | -8.697 | 2.548 |
| STRUCT_RESTORE | 2 | 12 | 0 | 6 | -8.657 | 2.658 |
| STRUCT_WRONG | 2 | 11 | 0 | 6 | -10.600 | 3.100 |
| STRUCT_GATED | 2 | 12 | 0 | 6 | -8.657 | 2.658 |
| STRUCT_GATE_ZERO | 2 | 11 | 0 | 6 | -8.697 | 2.548 |
| STRUCT_GATE_RESTORE | 2 | 12 | 0 | 6 | -8.657 | 2.658 |
| PLAIN_BASE | 3 | 8 | 0 | 9 | -8.747 | 2.520 |

BASE→FORCED 准确率差的逐题 bootstrap 95% 区间为 [0, 0]；SHAM→FORCED 有益/有害翻转为 0/0。有记忆槽的 BASE 为 2/24，原始提示 PLAIN_BASE 为 3/24。

- 世界 `writoz`：BASE 1/12、FORCED 1/12、SHAM 1/12。
- 世界 `wtivik`：BASE 1/12、FORCED 1/12、SHAM 1/12。

## 哪些字段影响输出

仅注入值为 2/24。八道预定消融题的完整答案正确数如下。

| 条件 | 正确 /8 |
|---|---:|
| BASE | 0 |
| STRUCT_FORCED | 0 |
| DROP_VALUE | 0 |
| DROP_SOURCE | 0 |
| DROP_DEPTH | 0 |
| DROP_PATH | 0 |
| DROP_STATUS | 0 |
| STRUCTURE_FIRST | 0 |
| COUNTERFACTUAL_PATH | 1 |

两个公开图的深度互换使四次原生读取输出均与注入数字一致；来源读取 0/4。五字段的已知码本二选一在下一层实际张量上 80/80，重复测量来自两个图。见[编码与原生字段探针](ONI011_CODEC_SPEC.md)。

## 工程因果与研究判断

- P0 工程通道通过。旧位置第 27 块的回归及原始张量见[基线报告](ONI011_BASELINE_AND_REGRESSION.md)。新位置第 0 块输出按 α=0.65 写入，后续 41 块正常执行。
- P1 未通过。对应门槛为相对 BASE、SHAM 各净增至少四题，至少五次有益翻转、至多一次有害翻转，两个世界各有收益。
- P2 配对工程检查通过，实际非零干预统计为 `{'STRUCT_FORCED': {'triplets': 56, 'active_on': 40}, 'STRUCT_GATED': {'triplets': 56, 'active_on': 39}}`，审计异常数 0。逐题检查全部生成 ID、文本、首步未加工 logits 的哈希以及每步下一层消费。结构内容专一性另由上述 WRONG 和删字段测量评估。
- P3 自动门控未通过。隐藏集指标 `{'tp': 24, 'fp': 21, 'tn': 11, 'fn': 0, 'missing': 0, 'precision': 0.5333333333333333, 'recall': 1.0, 'fpr': 0.65625}`。分布与实际写入见[门控报告](ONI011_GATE_SHIFT_REPORT.md)。
- P4 跨世界稳定性依据两个世界的配对结果及一般题对照判断。一般题上强制注入实际非零 0/12。
- I1 多 token 适用样例上，强制注入 2/24，基线 2。I2 有公开深度字段读取证据，其余字段参与通用继承推理的因果收益待证。I3 的单跳前沿条件只有八题；没有充分证据表明模型独立执行完整继承搜索。

## 校准、隔离与资源

显式求解器解析公开事实并计算最近证据，编码器用冻结输入 embedding 和固定位置排列写入 status、value、source、depth、path 等字段。整串值的子词依序写入提示中的预留槽。生成由原始 LM head 完成。主编码器没有可训练参数，只有自动门控方向与阈值在开发集拟合。LEGACY_ANCHOR 单列历史答案辅助 forward。

24 道开发题、16 道独立验证题。六种层/强度组合保存在 grid.json。验证集强制结构为 10/16，BASE、SHAM、VALUE_NO_AUX 各 7/16。开发时的精确匹配清理全部末尾标点，正式评分按协议最多清理一个。两批隐藏世界在冻结后用系统随机种子生成。同机目录和调用参数区分推理与考官；完整贪心预测封存后才用答案逐 token 做 teacher forcing。

模型提交 `42433e90dad95123ab87729fd7011e7110629556`，前后权重 SHA-256 `14fb8e7f0a18d53d1f239773758bf581cee7e456a4523a54622c3a245b64402c`。RTX 4060 Laptop，BF16，Torch 2.10.0+cu128，Transformers 5.6.2。固定贪心解码，最多 32 个新 token，关闭 KV cache。冻结源码 `9b25d58f915866d45bc23f77431794d248bfc120`，题集 `913a98f30cf712fb2b55d6992419135ae9b8be64a3e002896c5c98cf7cedf673`。授权见[控制台回执](ONI011_AUTHORIZATION_REPORT.md)。

| 适用题耗时 | 请求 p50 / p95 (ms) | 生成 p50 (ms) | 峰值显存 (GiB) |
|---|---:|---:|---:|
| BASE | 354.5 / 781.9 | 348.8 | 4.73 |
| STRUCT_FORCED | 364.2 / 622.2 | 357.9 | 4.73 |
| LEGACY_ANCHOR | 469.7 / 888.9 | 351.5 | 4.73 |

## 失败样例

| 题 ID | 期望 | 实际原始输出 |
|---|---|---|
| qtxcrfccjpvznszn | fitolcokam | UNKNOWN |
| qpqugzcmkbyosfom | wifumed | tufem jijot wifumed |
| qemugsbjzsczbfkh | sufudhuligan | UNKNOWN |
| qvbxwqgpsmoqhmyr | gigub | UNKNOWN |

完整结果收录 728 个条件观测，493 个整串评分失败，预测运行错误 0，似然错误 0。完整失败集与未经整理的张量、输出及版本校验见[交接说明](ONI011_HANDOFF.md)。研究停在 ONI-01R1，等待负责人裁定。

P4 未证明跨世界任务收益。I1 在未见多 token 值上没有带来有益翻转。I2 只在两个公开图的深度读取上获得局部支持。I3 未证明。基于本轮证据，建议暂缓 ONI-02。
