#!/usr/bin/env python3
"""Generate the initial Research Console data set.

The generated JSON/Markdown files are the committed data source.  This script is
kept so the seed can be reviewed and regenerated, but normal console edits must
go through the server (which appends history/events).
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "research-console"
NOW = datetime.now(timezone.utc).isoformat()

DIRECTIONS = [
    ("A", "知识诊断、验证与修正", "系统如何检测冲突、验证结论并在反例后修正知识？"),
    ("B", "新观察与知识获取", "系统如何判断缺少信息并合法获得新的观察？"),
    ("C", "概念构造与知识表示", "系统如何从证据构造可执行、可验证、可复用的概念？"),
    ("D", "学习策略与知识积累", "系统如何根据历史学习经验调整学习行动并积累知识？"),
    ("E", "认识论与知识状态", "系统如何区分事实、假设、推断、未知和证据不足？"),
    ("F", "长期开放架构研究", "哪些长期表示、推理和混合架构值得研究？"),
    ("G", "因果推理与反事实", "系统如何从相关观察走向因果理解？"),
    ("H", "形式推理与多步演绎", "系统如何组合已知事实与规则产生可靠新结论？"),
    ("I", "记忆与知识生命周期", "系统如何长期保存、更新、退役和恢复知识？"),
    ("J", "行动模型与工具操作", "系统如何把知识转化为受控行动？"),
    ("K", "教师模型与外部知识协作", "系统如何向教师学习而不依赖教师？"),
    ("L", "世界模型与时间动态", "系统如何理解对象、状态、事件和时间变化？"),
    ("M", "表征结构自主进化", "系统能否学习新的知识结构而不只是填充固定模板？"),
    ("N", "科学实验与自主发现", "系统能否提出假设、设计实验并从结果中学习？"),
    ("O", "错误隔离与知识可靠性", "错误如何被定位、隔离并阻止传播？"),
    ("P", "符号接地与现实对应", "内部概念如何对应对象、属性和观测？"),
    ("Q", "课程学习与自主出题", "系统如何形成逐步增长的学习过程？"),
    ("R", "元认知与资源决策", "系统如何观察自己的知识并决定下一步学习行动？"),
    ("S", "自然语言知识学习", "系统如何从字词和语法逐步形成语言知识？"),
    ("T", "多模态知识融合", "不同模态如何共享、冲突和校验知识？"),
    ("U", "涌现与能力跃迁机制", "多能力组合后是否出现单模块没有的新行为？"),
    ("V", "科学评测与可证伪性", "如何证明能力真实存在而不是记忆或注入？"),
    ("W", "可解释性与知识干预", "如何检查、解释和修改内部知识？"),
    ("X", "多能力组合", "多个能力如何联动形成完整学习循环？"),
    ("Y", "数学、程序与算法发现", "系统如何从基础规则形成数学与程序能力？"),
    ("Z", "多智能体与知识协同", "多个学习者能否共同构建和验证知识？"),
]

NEW_TITLES = {
    "E": ["知识状态模型", "证据强度与来源", "知识适用范围", "开放世界语义", "可撤销推理", "知识边界识别"],
    "G": ["相关与因果区分", "因果方向假设", "干预实验", "混杂因素", "反事实推理", "因果模型修正"],
    "H": ["多步演绎", "证明链构造", "正向与逆向推理", "不确定性传播", "约束求解", "定理与引理复用"],
    "I": ["短期与长期记忆", "知识巩固", "记忆检索", "知识版本演化", "知识依赖失效", "长期一致性"],
    "J": ["行动原语", "行动前置条件", "任务分解", "行动结果学习", "行动失败恢复", "工具能力组合"],
    "K": ["自主向教师提问", "答案结构化", "教师错误识别", "多教师比较", "按需请求帮助", "教师依赖递减"],
    "L": ["对象与状态区分", "事件表示", "状态转移学习", "时间条件推理", "环境变化检测", "动态预测与修正"],
    "M": ["概念结构操作", "自主形成类别", "自主形成关系", "多编码关联", "表征不足检测", "表征升级实验"],
    "N": ["多候选假设生成", "区分性实验设计", "实验顺序选择", "反常结果处理", "研究结果复现", "递归科学发现"],
    "O": ["错误来源定位", "错误传播范围", "隔离未经验证的知识", "修正后的重新验证", "错误知识恢复", "可靠性边界"],
    "P": ["实体识别", "概念实例关联", "属性测量接地", "别名与歧义", "概念适用边界", "跨环境接地"],
    "Q": ["基础概念学习", "题目自动生成", "错题分类", "难度自适应", "跨知识点综合题", "自主课程扩展"],
    "R": ["自我知识检查", "策略选择", "合理停止", "学习优先级", "失败后策略调整", "自身能力边界模型"],
    "S": ["字词学习", "词性与句法", "句子到命题", "多义词消歧", "同义与改写", "多语言概念映射"],
    "T": ["文字与图像对应", "声音与文本对应", "跨模态对象识别", "模态间冲突", "多模态补充证据", "跨模态概念迁移"],
    "U": ["基础能力组合", "双能力协同", "多模块非线性收益", "递归组合", "自发中间结构", "能力跃迁边界"],
    "V": ["未知任务测试", "知识泄漏检测", "学习来源归因", "负面结果保留", "多世界复现", "独立闭卷验证"],
    "W": ["结论来源追踪", "可检查推理解释", "反事实解释", "局部知识修改", "解释忠实性", "知识差异分析"],
    "Y": ["算术概念学习", "数学规律归纳", "多步数学证明", "程序合成", "程序修正", "算法抽象"],
    "Z": ["多学生独立学习", "知识交换", "意见分歧", "协作实验", "知识合并", "分工与专长"],
}

MINIMAL = {
    "A": "构造一个含冲突或反例的受控世界，记录诊断、挑战和修正前后状态；比较有/无该机制的判断。",
    "B": "在一个当前属性不足的世界中增加一个受控观察字段，保留旧观察 Unknown，并比较独立测试收益。",
    "C": "从公开样本生成候选谓词，经 Filter 筛选、heldout 验证和注册，在第二个任务中与不复用的基线比较。",
    "D": "给系统多个知识缺口和有限学习行动，检查其选择依据、停止条件和后续修正是否优于固定策略。",
    "E": "对同一命题提供不同证据组合，要求系统输出不同知识状态并说明何种新证据会改变它。",
    "F": "先做小规模可行性原型或对照，明确哪些部分只是设想；不把架构提案当成已实现能力。",
    "G": "构造两个观察相关但干预结果不同的世界，检查系统是否必须通过干预证据区分因果方向。",
    "H": "给定未见的事实组合，要求两到五步推理得到结论，并用独立检查器核验证明链。",
    "I": "连续执行若干学习任务，再引入推翻旧规则的证据，检查历史保留、依赖影响和恢复。",
    "J": "给一个受控工具任务，要求选择已授权行动、执行、检查结果并记录失败恢复。",
    "K": "让教师提供一条错误或过度概括的规则，学生必须通过独立验证保留、修改或拒绝。",
    "L": "运行多时间步世界，学习状态转移后改变规律，检查旧知识是否被识别为不再适用。",
    "M": "先用无法区分目标的表示，再允许新增关系/结构操作，观察是否能提出并验证更有用的表示。",
    "N": "在隐藏规则环境中允许多次合法操作，检查候选假设、区分性实验和修正过程。",
    "O": "让一个错误中间规则影响多个能力，修正后列出所有受影响依赖并重新验证。",
    "P": "提供名字、属性和对象身份存在歧义的数据，检查系统是否按证据维护对象关系。",
    "Q": "预设一个小课程和小型判分器，观察逐步学习后能否解决未直接训练的组合题。",
    "R": "给多个难度和信息条件不同的任务，检查系统能否依据真实知识状态选择下一步或停止。",
    "S": "用有限词表和受控语法，测试从新句子提取关系并处理语义歧义。",
    "T": "在文字和图像来源中提供部分一致、部分冲突的对象描述，检查独立证据和冲突保留。",
    "U": "设计至少三类能力交互的任务，分别运行单能力、两两组合和完整组合，排除预设工作流。",
    "V": "改变隐藏规则、样本排列和世界种子，检查是否能排除记忆、泄漏和开发者注入。",
    "W": "修改一条参与多条推理链的中间知识，检查系统能否指出受影响结论并验证解释。",
    "X": "把两个已有能力放入同一受控任务，比较单独、顺序组合和整合组合的结果。",
    "Y": "给一个有隐藏测试的简单程序任务，通过候选生成、执行反馈和反例修正得到程序。",
    "Z": "两个学生各自获得不完整证据，通过受控知识交换后共同解决问题，同时拒绝未验证结论。",
}

LEGACY = [
    dict(id="V0", title="V0 教师转发基线", direction="F", status="ACCEPTED_LIMITED",
         research_question="早期系统是否只能转发教师给出的答案？",
         goal="记录教师转发基线的能力边界，作为后续自主学习的对照。",
         known_implementation=["早期 V0 记录与基线材料"],
         evidence_links=["experiments/reports/foundation-audit.md"],
         commit_sha="0ca4561309aeb914df6e3f2c3877a50b080bf731"),
    dict(id="V1", title="V1 精确问答记忆", direction="F", status="ACCEPTED_LIMITED",
         research_question="系统能否记住已见问答，但不能据此泛化？",
         goal="建立精确问答记忆基线并标明其泛化限制。",
         known_implementation=["早期 V1 记忆实验材料"],
         evidence_links=["experiments/reports/foundation-audit.md"],
         commit_sha="0ca4561309aeb914df6e3f2c3877a50b080bf731"),
    dict(id="V2", title="V2 早期规则归纳", direction="F", status="ACCEPTED_LIMITED",
         research_question="早期规则归纳能在有限世界达到什么程度？",
         goal="记录早期规则归纳能力与失败边界。",
         known_implementation=["早期 V2 规则归纳材料"],
         evidence_links=["experiments/reports/foundation-audit.md"],
         commit_sha="0ca4561309aeb914df6e3f2c3877a50b080bf731"),
    dict(id="B0", title="B0 Typed IR 与能力基础设施", direction="A", status="ACCEPTED",
         research_question="如何用类型化程序表示、执行、验证、注册、撤销和组合能力？",
         goal="提供可审计的能力执行与验证基础设施。",
         known_implementation=["src/main/kotlin/logic/capability/CapabilityIr.kt", "CapabilityInterpreter.kt", "CapabilityRegistry.kt", "CapabilityComposer.kt"],
         evidence_links=["experiments/reports/foundation-audit.md"],
         commit_sha="0ca4561309aeb914df6e3f2c3877a50b080bf731",
         patch_used=0,
         known_limitations=["B0 Typed IR 是执行表示，不等于完整概念知识表示。", "冻结生产源码，不允许为本任务修改。"]),
    dict(id="P0", title="P0 A9 确定性错误修复", direction="A", status="ACCEPTED_LIMITED",
         research_question="A9 基线中的状态回退、执行预算和事件顺序问题是否已修正？",
         goal="修复 A9 已知确定性错误并保留反例。",
         known_implementation=["src/main/kotlin/logic/e3/E3A9Baselines.kt", "src/main/kotlin/logic/e3/evaluator/A9IndependentScorer.kt"],
         evidence_links=["experiments/representation-evolution-e3/reports/E3-P0-REPORT.md"],
         commit_sha="f64337d06f33a4256a9ab311df0d36803b27c086",
         patch_used=1,
         aliases=["E3-P0"]),
    dict(id="M1.0", title="M1.0 主动挑战契约设计", direction="A", status="ACCEPTED_LIMITED",
         research_question="已有有界结论后主动挑战需要哪些最小数据契约？",
         goal="定义挑战输入、预测冻结、可信响应和记录边界。",
         known_implementation=["docs/RESEARCH-NORTH-STAR.md", "src/main/kotlin/logic/e3/E3ChallengeSession.kt"],
         evidence_links=["experiments/representation-evolution-e3/reports/E3-M1.1a-REPORT.md"],
         commit_sha="795a2057786236256edd4b58c57b677248f4f100",
         patch_used=0),
    dict(id="M1.1a", title="M1.1a 挑战入口", direction="A", status="ACCEPTED_LIMITED",
         research_question="已有有界结论能否进入独立待挑战状态？",
         goal="建立入口校验，不执行预测或查询。",
         known_implementation=["src/main/kotlin/logic/e3/E3ChallengeSession.kt"],
         evidence_links=["experiments/representation-evolution-e3/reports/E3-M1.1a-REPORT.md"],
         commit_sha="7a5bf6285ac510e80358bd9f3fa777b612a056f2",
         patch_used=1),
    dict(id="A1", title="A1 最小主动挑战闭环", direction="A", status="ACCEPTED_LIMITED",
         research_question="学生能否在已有有界结论后选择合法输入、冻结预测、获取可信标签并比较结果？",
         goal="完成一次不重新学习的主动挑战机制闭环。",
         known_implementation=["src/main/kotlin/logic/e3/E3ChallengeRun.kt", "src/main/kotlin/logic/e3/E3ChallengeSession.kt"],
         evidence_links=["experiments/representation-evolution-e3/reports/E3-A1-REPORT.md", "experiments/representation-evolution-e3/reports/a1-challenge-runs.json"],
         commit_sha="795a2057786236256edd4b58c57b677248f4f100",
         patch_used=1,
         aliases=["M1-active-challenge"],
         known_limitations=["同 JVM 受限机制，不是独立闭卷。", "未证明高价值反例选择策略。"]),
    dict(id="C1", title="C1 最小谓词学习与跨任务复用", direction="C", status="ACCEPTED_LIMITED",
         research_question="学生能否从第一任务证据形成谓词，并在第二任务中实际调用且产生受限收益？",
         goal="完成 Composer 生成、Filter 筛选、验证/激活和第二任务复用。",
         known_implementation=["src/main/kotlin/logic/e3/c1/C1PredicateExperiment.kt"],
         evidence_links=["experiments/representation-evolution-e3/reports/E3-C1-REPORT.md"],
         commit_sha="41d778a974af70eb20ed8b9afeab71faa9fb0b12",
         patch_used=1,
         known_limitations=["单固定世界。", "P_REUSE 生成候选多于 NO_PREDICATE，无成本优势。", "非独立闭卷。"]),
]

for i in range(10):
    LEGACY.append(dict(
        id=f"E3-A{i}", title=f"E3-A{i} 观察诊断子阶段", direction="A", status="ACCEPTED_LIMITED",
        research_question=f"E3-A{i} 对应的观察/诊断子能力是否按契约实现？",
        goal=f"记录 E3-A{i} 的工程能力、证据和边界。",
        known_implementation=["src/main/kotlin/logic/e3/"],
        evidence_links=[f"experiments/representation-evolution-e3/reports/E3-A{i}-REPORT.md"],
        commit_sha="0ca4561309aeb914df6e3f2c3877a50b080bf731",
        patch_used=0,
        known_limitations=["实施者测试，不是独立闭卷。"] if i >= 5 else [],
    ))

A_TASKS = [
    ("A-R1", "反例驱动的知识修正与重新学习", "发现反例后，系统能否撤销旧规则、重新搜索并保留修正历史？", "现有 A1 + B0 能力库/撤销"),
    ("A-R2", "多假设之间的区分性主动查询", "系统能否选择最能区分当前候选的合法查询？", "现有 A1 查询入口 + Composer/Filter"),
    ("A-R3", "未知、证据不足、搜索不足与证伪的区分", "系统能否区分四种失败原因而不混淆？", "现有 A6 预算诊断 + A8 报告"),
    ("A-R4", "同属性异标签、重复测量与来源不确定性", "相同可见属性不同标签时，系统能否按对象、来源和测量语义处理？", "现有 A4/A7 + 观察 provenance"),
    ("A-R5", "面对矛盾保留多个可能解释", "系统能否保留竞争解释，而不是过早宣布噪声或隐藏属性？", "现有 A8 alternative explanations"),
    ("A2-A10", "多世界诊断与基线对照", "现有诊断在多个受控世界上是否稳定、可复现？", "现有 A9 六基线 + 世界生成器"),
    ("A11", "对抗测试、数据泄漏与独立闭卷", "如何检验泄漏、重复标签、预算耗尽和非法 schema？", "现有 A9 冻结记录 + 安全边界"),
    ("A12", "实验冻结、复现与交接", "如何冻结协议、原始结果、失败与未完成事项？", "A10/A11 结果"),
]
B_TASKS = [
    ("B1", "schema 扩展与新属性获取", "一个新观察字段如何合法加入并兼容旧观察？"),
    ("B2", "判断何时需要新信息", "系统如何从诊断中识别当前信息不足？"),
    ("B3", "选择信息来源", "系统如何比较可用信息源并选择合法来源？"),
    ("B4", "不完整属性与 Unknown 处理", "未测量字段如何保持 Unknown 而不是默认 false？"),
    ("B5", "信息来源可靠性与冲突核验", "不同来源冲突时如何保留来源并核验？"),
    ("B6", "从文档、数据库、工具获取信息", "系统如何把外部资料转换为可验证观察？"),
    ("B7", "新属性的增益、冗余和误导性检验", "新属性是否真正改善独立任务，而不是增加噪声？"),
    ("B8", "学习失败后提出需要观察的新属性", "系统能否从失败诊断提出下一项观察需求？"),
]
C_TASKS = [
    ("C2", "显式概念结构原型", "如何表示可检查、可修改、可复用的概念结构？"),
    ("C3", "从实例中归纳属性与关系", "如何从观察中形成属性和关系候选？"),
    ("C4", "由旧概念构造复合概念", "旧概念能否组合成新的可执行概念？"),
    ("C5", "概念层次与类型约束", "概念层次如何保持类型一致和可验证？"),
    ("C6", "概念拆分、合并、修正与退役", "概念发生变化时如何保留历史并撤销依赖？"),
    ("C7", "多编码与概念之间的关联", "不同编码如何指向同一概念并互相校验？"),
    ("C8", "跨不同任务的概念复用", "概念能否在第二任务产生真实复用收益？"),
    ("C9", "例外、条件和上下文约束", "如何表示只在特定上下文成立的规则？"),
    ("C10", "局部等价与全局等价的区分", "同一行为类在局部与全局范围如何区分？"),
]
D_TASKS = [
    ("D1", "知识修正历史和失败经验", "系统如何保存失败并用于后续选择？"),
    ("D2", "并行保留多个猜想", "系统能否同时保留多个候选而非只选一个？"),
    ("D3", "根据证据选择学习行动", "系统如何依据证据选择查询、搜索或停止？"),
    ("D4", "学习策略对照", "不同策略在相同任务上的差异是什么？"),
    ("D5", "错题驱动的新猜想生成", "错误如何转化为下一轮候选？"),
    ("D6", "自主出题与自测", "系统能否生成可判分的自测题？"),
    ("D7", "继续、暂缓、停止的判断机制", "系统何时应停止当前学习路径？"),
    ("D8", "从多个任务归纳学习方法", "跨任务经验能否形成策略改进？"),
    ("D9", "降低对教师规则的依赖", "随知识增长，教师依赖是否下降？"),
    ("D10", "长期学习中的知识一致性与遗忘", "长期积累中如何避免冲突和遗忘？"),
]
X_TASKS = [
    ("X1", "主动挑战、纠错和知识复用联动", "A1 + A-R1 + C1 组合是否产生单模块没有的能力？"),
    ("X2", "诊断与新属性获取联动", "诊断能否触发合法的新观察请求？"),
    ("X3", "新属性获取与新概念构造联动", "新属性能否成为新谓词的证据？"),
    ("X4", "知识复用与失败经验积累联动", "复用失败能否反馈到后续策略？"),
    ("X5", "获取、构造、验证、修正、复用完整循环", "五步闭环是否可端到端运行？"),
    ("X6", "单能力、双能力、多能力组合对照", "组合收益是否超过简单叠加？"),
    ("X7", "未预先指定的中间概念或操作的出现", "是否出现开发者未指定的中间结构？"),
    ("X8", "多级知识组合与递归推导", "组合能力能否递归形成更高层能力？"),
]
F_TASKS = [
    ("F1", "多编码知识表征", "不同编码如何共享同一概念？"),
    ("F2", "分层、递归的知识结构", "知识结构如何分层、递归展开？"),
    ("F3", "显式知识与 Embedding 混合", "显式结构如何与稠密表示互补？"),
    ("F4", "小模型或神经网络辅助候选生成", "学习模型能否帮助生成候选而不取代验证？"),
    ("F5", "教师模型协作", "教师模型如何提供可验证候选？"),
    ("F6", "语言、字词与语法学习", "如何从字词逐步形成语言知识？"),
    ("F7", "数学、代码和逻辑推理", "如何学习可执行的形式推理？"),
    ("F8", "Kotlin 行动模型", "如何把知识变成受控 Kotlin 行动？"),
    ("F9", "任务分解与子任务组合", "复杂目标如何分解为已验证步骤？"),
    ("F10", "面向实际场景的迁移实验", "受控世界的知识如何迁移到现实任务？"),
    ("F11", "学习算法本身的改进", "系统能否改进自己的学习算法？"),
    ("F12", "不同知识表示架构的实验对照", "哪些表示架构在相同任务上更有效？"),
]


def task_record(tid, title, direction, question, goal=None, status="EXPLORATORY",
                priority="medium", dependencies=None, approaches=None, minimal=None,
                known=None, evidence=None, commit="", patch=0, aliases=None,
                limitations=None, success=None, failure=None):
    return {
        "id": tid,
        "aliases": aliases or [],
        "title": title,
        "direction": direction,
        "research_question": question,
        "hypothesis": f"通过受控实验可以判断：{title}所描述的能力是否真实出现，并排除教师注入、数据泄漏和开发者预设工作流。",
        "goal": goal or title,
        "status": status,
        "priority": priority,
        "dependencies": dependencies or [],
        "readiness_conditions": ["需要研究负责人明确授权", "需要可复核的独立测试条件"],
        "known_implementation": known or [],
        "proposed_approaches": approaches or ["TBD：候选技术路线待研究；不得把规划当成已实现。"],
        "minimal_experiment": minimal or MINIMAL[direction],
        "success_criteria": success or ["在独立测试上出现可复核的能力增量", "保留反例、失败和成本记录"],
        "failure_conditions": failure or ["无法优于基线", "只能记忆或依赖教师直接答案", "出现泄漏或不可复核结果"],
        "attribution": "research_owner/ChatGPT 提出；尚未实施，未授权",
        "known_limitations": limitations or ["尚未实现", "尚无本任务的独立实验证据"],
        "patch_used": patch,
        "commit_sha": commit,
        "evidence_links": evidence or [],
        "decision_history": [{"at": NOW, "decision": "imported into Research Console as planning data", "actor": "DeepSeek Harness"}],
        "next_possible_actions": ["等待研究负责人授权后，选择一个最小可验证实验"],
        "authorized_action": "none",
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    tasks = {}
    for legacy in LEGACY:
        rec = task_record(
            legacy["id"], legacy["title"], legacy["direction"], legacy["research_question"],
            goal=legacy.get("goal"), status=legacy.get("status", "EXPLORATORY"),
            known=legacy.get("known_implementation"), evidence=legacy.get("evidence_links"),
            commit=legacy.get("commit_sha", ""), patch=legacy.get("patch_used", 0),
            aliases=legacy.get("aliases"), limitations=legacy.get("known_limitations"),
        )
        tasks[rec["id"]] = rec

    for tid, title, q, dep in A_TASKS:
        tasks[tid] = task_record(tid, title, "A", q, dependencies=[dep], priority="high" if tid == "A-R1" else "medium")
    for tid, title, q in B_TASKS:
        tasks[tid] = task_record(tid, title, "B", q)
    for tid, title, q in C_TASKS:
        tasks[tid] = task_record(tid, title, "C", q, priority="high" if tid == "C8" else "medium")
    for tid, title, q in D_TASKS:
        tasks[tid] = task_record(tid, title, "D", q)
    for tid, title, q in X_TASKS:
        tasks[tid] = task_record(tid, title, "X", q)
    for tid, title, q in F_TASKS:
        tasks[tid] = task_record(tid, title, "F", q)

    for direction, titles in NEW_TITLES.items():
        for index, title in enumerate(titles, start=1):
            tid = f"RES-{direction}{index:02d}"
            q = f"{title}：{next(d[2] for d in DIRECTIONS if d[0] == direction)}"
            tasks[tid] = task_record(
                tid, title, direction, q,
                priority="high" if tid in {"RES-E01", "RES-H01", "RES-I01", "RES-M01", "RES-N01", "RES-U01", "RES-V01"} else "medium",
            )

    directions = []
    for code, name, question in DIRECTIONS:
        ids = sorted(t["id"] for t in tasks.values() if t["direction"] == code)
        directions.append({"code": code, "name": name, "question": question, "task_ids": ids})
    for t in tasks.values():
        for dep in t["dependencies"]:
            pass

    task_doc = {
        "schema_version": 1,
        "updated_at": NOW,
        "source": "Research Console initial import; planning data is not evidence",
        "current_task_id": "C1",
        "authorized_action": "none",
        "independent_closed_book": "not_run",
        "last_test_result": "297 passed / 297 total, 0 failed（B0 173 + E3 124）",
        "last_code_commit": "41d778a974af70eb20ed8b9afeab71faa9fb0b12",
        "last_docs_commit": "c8be93aeec700c17cbd0adb58a42f6f30dcc9079",
        "git_head_at_import": "c8be93aeec700c17cbd0adb58a42f6f30dcc9079",
        "tasks": sorted(tasks.values(), key=lambda x: x["id"]),
    }
    (OUT / "tasks.json").write_text(json.dumps(task_doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "directions.json").write_text(json.dumps({
        "schema_version": 1,
        "directions": directions,
        "note": "A/B/C/D/X/F are existing directions; E,G,H,I,J,K,L,M,N,O,P,Q,R,S,T,U,V,W,Y,Z are the A-Z extension. Directions are not a serial stage plan.",
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "ideas.json").write_text(json.dumps({"schema_version": 1, "ideas": []}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (OUT / "events.jsonl").write_text("", encoding="utf-8")
    (OUT / "decisions.md").write_text("# 研究决策记录\n\n> 由研究负责人维护；DeepSeek 不自行写入验收裁定。\n\n- 2026-11-05: 研究负责人批准 A1、C1 为 `ACCEPTED_LIMITED`；补丁额度均为 1/1。\n- 2026-11-05: 授权建设 Research Console；不授权实施 A–Z 新研究任务。\n", encoding="utf-8")
    (OUT / "research-constitution.md").write_text("""# Observable AI 研究宪章

长期目标：研究人工智能如何表示、获得、验证、修正、组合、迁移知识，以及这些过程如何被观察、干预和验证。

原则：

1. 研究总纲长期稳定，规划可以并行、交叉、按依赖成熟度选择。
2. A/B/C/D/X/F 与 A–Z 扩展方向是研究方向，不是强制串行阶段。
3. 严格区分已实现、受限验收、待审查、进行中、规划、假设和暂停。
4. 开发者预置方法、教师知识和学生归纳结果必须分别归因。
5. 独立闭卷未完成必须写 `not_run`；实施者自测不等于独立验收。
6. 失败、停滞、无收益和不可辨识结果都保留。
7. 不展示虚构完成率；不用规划当成果。
8. 一次任务最多一轮首次实施、一轮验收、一轮精确补丁；补丁额度用尽后暂停。
""", encoding="utf-8")
    (OUT / "README.md").write_text("""# Research Console

真实数据源：`tasks.json`、`directions.json`、`ideas.json`、`events.jsonl`、`decisions.md`。

启动：

```powershell
$env:RESEARCH_CONSOLE_TOKEN = "<local-token>"
python tools/research-console/server.py --port 8765
```

只绑定 `127.0.0.1`。读取无需 token；写入需 `X-Research-Token` 与角色头（或表单字段）。
`experiments/representation-evolution-e3/WORKSTATE.md` 是人的交接视图；`console_cli.py check-workstate` 会校验它与结构化状态是否分叉。
""", encoding="utf-8")


if __name__ == "__main__":
    main()
