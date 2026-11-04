<!-- 本文件新增：DeepSeek Harness，2026-11-04；原因：按研究者要求实现学前第二课“颜色与简单分组”。 -->
# 学前第二课：颜色与简单分组

课程 ID：`preschool-002`。本课是项目原创的受控训练世界，不代表幼儿教材原文，也不声称进行了图像识别或语义理解。

## 材料与边界

正式材料沿用第一课对象：A=苹果、B=杯子、C=球、D=苹果、E=书。颜色只由研究者显式给定：

| 对象 ID | 名称 | 初始颜色 |
| --- | --- | --- |
| A | 苹果 | 红色 |
| B | 杯子 | 蓝色 |
| C | 球 | 红色 |
| D | 苹果 | 绿色 |
| E | 书 | 未提供 |

材料位于同目录 `materials.json`。E 的对象和名称已知，但没有颜色记录，所以第二课里查询 E 的颜色必须返回 `unknown`，不能预置黄色，也不能推断成“不是黄色”。

属性记录按“对象 ID＋属性名＋属性值”保存，属性名是数据参数，不是 Kotlin 中写死的颜色分支；同一机制可继续用于大小、形状等后续标签。对象身份、名称和属性互相独立：

- A 和 C 都是红色，只表示同色，不表示同一对象；
- A 和 D 同名“苹果”，但颜色不同；
- A 是红色且叫苹果，不能推出所有苹果都红；
- 未记录属性返回 `unknown`，不落入“假”或“否定”，也不调用教师。

## CLI

每次运行执行一条命令，stdout 输出紧凑 JSON：

```text
logic-learner --lesson 2 导入 experiments/lessons/preschool-002/materials.json
logic-learner --lesson 2 查 颜色 A
logic-learner --lesson 2 找 颜色 红色
logic-learner --lesson 2 比较 颜色 A C
logic-learner --lesson 2 比较 颜色 A E
logic-learner --lesson 2 教 颜色 E 黄色
logic-learner --lesson 2 查 颜色 E
```

命令形式为 `查 <属性名> <对象ID>`、`找 <属性名> <属性值>`、`比较 <属性名> <对象ID1> <对象ID2>`、`教 <属性名> <对象ID> <属性值>`。属性名直接作为参数传入，因此后续课程可复用 `大小`、`形状` 等名称。

## 状态与顺序

默认专属文件：

- 课程库：`runs/lessons/preschool-002/attributes.db`（含对象名称与属性两张表）。
- 结构化记录：`runs/lessons/preschool-002/events.jsonl`。
- 中文步骤记录：`runs/lessons/preschool-002/events.steps.log`。

本课不读写 `runs/memory.db`，不改写第一课 `runs/lessons/preschool-001/`。第一阶段的导入材料只给 A-D 颜色和 E 的名称；确认 E 颜色为 `unknown` 后，第二阶段才显式执行 `教 颜色 E 黄色`，随后重启再查以确认持久化。重做初始未知阶段应使用新的 `--lesson-db` 和 `--events`。

课程运行全部本地完成，不创建教师、不调用模型，事件与步骤日志均记录 `teacher_invocation_count=0`。
