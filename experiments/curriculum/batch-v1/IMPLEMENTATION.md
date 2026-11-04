<!-- Codex，2026-11-04：按研究者要求组织DSH批量实现、规则来源、考试及HTML/JSON全链路报告；这是实现契约，不是通过声明。 -->
# 学前24课批量实现契约 v1

范围：原课程计划学前1—24课，保留原V0/V1/课1/课2接口和数据。DSH主实现；两组并行工作、文件各自独占。完成后集中构建修正，再执行DSH第一轮考试，最后Codex用冻结版本及新世界独立抽查。本轮不实现小学72单元，不提前宣称正式学前毕业。

学生仍是Kotlin。训练事实/规则数据来自教材；通用操作由外部教师批量编程引入，标记teacher_programmed，不称自主发现。语言限定公布句式和结构化故事；形状/情绪标签不算真实感知或体验。考试基于受控世界，未知≠假，证据冲突不可被最后一次写入悄悄掩盖。

## 文件分工

- 核心组A（DSH）：独占新增 `src/main/kotlin/logic/{BatchStudent,BatchCli,AuditTrail,RuleCatalog}.kt`，可新增其他Batch前缀Kotlin辅助文件；独占修改 `Main.kt`、`Events.kt`，新增 `src/test/kotlin/logic/BatchStudentTest.kt` 一项连贯核心测试。独占 `experiments/curriculum/batch-v1/rules.json`，不要修改B的文件。
- 教材与报告组B（DSH）：独占 `tools/curriculum_batch.py`、`tools/render_learning_report.py`、`experiments/curriculum/batch-v1/curriculum.json` 和 `materials/`（如需），可在README末尾追加批量运行说明；不要修改任何Kotlin或rules.json。负责训练/第一轮考试生成、机械判分、生成HTML/JSON报告。
- Codex：任务组织、宿主代执行、版本冻结、独立抽查，不提前把抽查数据或答案交给DSH。源码注释和修改真实署名，遵守AGENTS。已知DSH shell在Win32 grantWrite上失败，本轮不要再尝试pwsh/后台绕同一错误；专注交付，宿主将一次性执行你的脚本并把实际结果回送。

## 唯一批量入口与输入输出

`logic.MainKt --batch <requests.jsonl> --batch-out <directory>`：由Main在原模式解析前分流给BatchCli.run。每行独立JSON请求；错误也要输出一行结构化结果并继续其他请求。所有过程走目录内events.jsonl及events.steps.log，stdout仅每行JSON结果。状态按world_id分开持久化在directory/state，world_id作为文件名必须hash或安全编码防路径穿越。

请求：`{"task_id":"...","lesson_id":"preschool-003","world_id":"...","op":"attr.get","args":{"id":"x","key":"形状"}}`。

结果固定外层：`{"task_id":"...","lesson_id":"...","world_id":"...","op":"...","status":"ok|unknown|conflict|error","result":{},"reason":null,"run_id":"...","trace_ids":[],"teacher_invocation_count":0,"model_call_count":0}`。

未知用unknown+reason，不把null或默认false当已知；error表示参数/存储/执行故障。缺少world或对象是unknown。没有任何外部教师调用路径才可记录0。每个请求均有request_received到request_completed/failed的事件链。

`world.load` args为 `{"world":{...},"source":"material path or fixture id"}`。显式导入对象、关系、事实，不根据名称自动填颜色等答案。一个新world先load；重启后可直接查询旧world证明持久化。不要每个请求自动重置世界。世界基本形状：

```json
{"objects":[{"id":"x","name":"图卡","attributes":{"颜色":"红色"},"categories":["图卡类"]}],
 "relations":[{"id":"rel1","subject":"x","predicate":"inside","object":"box"}],
 "claims":[{"id":"c1","subject":"x","predicate":"颜色","value":"红色","truth":true,"active":true,"depends_on":[]}],
 "observations":[{"id":"obs1","conditions":{"开关":"关"},"outcome":{"灯":"灭"}}],
 "story":[{"id":"s1","actor":"甲","action":"take","object":"球"}],
 "policies":[{"id":"p1","when":{"key":"风险","value":"烫"},"decision":"停下求助"}],
 "turns":["甲","乙"]}
```

这些字段按需提供，不必每个world都带全。观测记录获得稳定fact_id、来源、introduced_lesson、acquired_at、run_id；派生结论引用实际依据，不伪造真实世界证据。

## 操作契约（组A按此实现，组B按此造题）

| op | args | 成功result / 约束 |
| --- | --- | --- |
| world.load | world,source | object_count；完整校验后提交，不能半写入 |
| object.name | id | id,name；未观察对象unknown |
| object.findName | name | ids（稳定排序）；无观察unknown |
| object.same | left,right | same_object,same_name；任一对象未知则unknown |
| attr.get | id,key | id,key,value；缺对象或缺属性分别unknown |
| attr.find | key,value | ids；精确匹配，无观察unknown |
| attr.same | left,right,key | same_value；缺任一属性unknown |
| attr.teach | id,key,value | id,key,value；仅已知对象，保存当前值并保留历史来源 |
| quantity.compare | left:{value,unit},right:{value,unit} | comparison为less/equal/greater，difference为左减右；单位不同/缺失unknown，不猜换算 |
| category.members | category | ids；只查显式categories，不以名称/颜色推类别 |
| filter.all | conditions:[{key,value}]，可选ids | ids,unknown_ids；任一已知不满足排除，缺必要条件但未被排除者入unknown_ids，不把未知当满足 |
| relation.query | subject,predicate | targets；只用明确关系，缺失unknown |
| relation.set | subject,predicate,object | subject,predicate,object；更新该主语关系当前值并记录前值 |
| count | ids | count；验证对象，按ID去重；明确空列表=0，未给列表不是0 |
| pair | left:[ids],right:[ids] | pairs:[[left,right]],unpaired_left,unpaired_right；一对一不复用对象 |
| count.compare | left:[ids],right:[ids] | comparison,difference；去重后计数 |
| arithmetic | operator:add/sub,a,b | value；数值类型严格，不依赖题号/实体名写答案 |
| pattern.next | pattern:[values],count | values；重复明确给定模式；没提供规则unknown，不从短序列声称唯一规律 |
| time.compare | left,right（ISO日期） | comparison,difference_days；根据给定时间，不读系统当前日期猜事实 |
| time.relative | reference_date,offset_days | date（ISO） |
| observation.compare | left_id,right_id | changed_conditions,changed_outcomes,controlled,causal_claim:false；controlled仅表示只改变一个已给条件，不宣称因果定律 |
| sentence.parse | text | subject,key,value；公布句式为“对象ID 的 属性名 是 属性值”，保留字符串，不用教师；不支持的句式unknown |
| sentence.render | id,key | text；用当前已观察事实输出相同句式；缺事实unknown |
| story.query | object,field:holder/place | value；按story事件take/give/place重放，give含to、place含place；缺信息unknown，不能猜动机 |
| reference.resolve | candidates:[ids] | id；唯一才resolve，0或多候选unknown且输出need |
| actions.run | steps:[{op,args,可选requires:[{id,key,value}]}] | results；模拟动作支持attr.teach/relation.set；校验前置条件，失败不提交半套动作，记录准备/提交不同阶段 |
| policy.decide | id | decision,policy_ids；只用明确属性和world.policies；缺条件unknown，多匹配决策冲突返回conflict；情绪表达也用显式情绪+教材政策，不读心 |
| turn.next | current | next；给定turns轮换，验证当前角色，状态及前后轮次留痕 |
| compose | shapes:{标签:数量},colors:[轮换值] | items:[{shape,color}]；满足数量和轮换约束，不伪造审美结论 |
| claim.add | claim:{id,subject,predicate,value,truth,可选depends_on} | claim_id；新增证据历史，不覆盖相反证据 |
| claim.evaluate | subject,predicate,value | truth为true/false；无证据status unknown；同时正反证据status conflict、truth:conflict；缺记录不算false |
| claim.retract | id | retracted_ids；撤销对应证据及依赖其的派生证据，不影响无关证据 |
| missing.ask | fields:[名称] | need,question,action:wait；status unknown；只问缺项并等待，不调用教师 |
| plan.run | steps:[{op,args}] | results；复用已有操作并保留每个子调用链，不为P024新写答案分支 |

如果遇到明确歧义，先把不确定点写进交付，统一修正契约；不要各自换字段。可以加明确的元数据字段，不能把expected传给学生程序。

## 全链路可审计

`rules.json`为带版本的规则目录，数组或{rules:[]}均可，B须兼容。每项至少：rule_id,version,title,introduced_lesson,acquired_at,reason,author,acquisition_method,source_file,function,source_anchor。新操作标teacher_programmed；原课1/2能力回填历史时标historical_backfill，时间精度如实，不能捏造当年的逐事件记录。形状等复用课引用原属性规则，不假装新增规则。

每个规则函数旁中文注释含`RULE:<rule_id>`锚点、首次课程、作者、原因；运行事件引用同一个ID/version。规则执行前rule_enter、完成rule_exit，失败rule_error；包含task/lesson/world、span_id/parent_span_id、规则出处、实际参数、事实依据fact_ids、输出/判断和耗时。子操作通过同一分派入口记录，不能只有“最终答案正确”的空壳日志。

目录中code位置与代码快照绑定；宿主运行器在构建后生成manifest.json（源文件sha256、规则目录sha256、实际jar sha256、快照路径、课程材料sha256、开始时间、DSH会话ID）。核心从环境`LOGIC_AUDIT_MANIFEST`读取或显式记unverified，不在缺manifest时冒充代码已核验。source_anchor可由报告器解析成实际行号和代码片段。保留编译产物版本，磁盘源码变化要能检测。

沿用Events原JSONL/中文steps格式、父事件和脱敏。原V0/V1/课1/课2未来运行也在Events里增加可用的代码版本/规则归因元数据：只映射真实执行的函数事件，未映射标unmapped，不把准备/跳过记成执行成功；旧日志不回写。规则/事实来源不同字段保留，来源数据与运行推导不可混淆。不记录凭据、授权头、原始DSH诊断或内部思考。

## 训练、首考、报告

B写 `python tools/curriculum_batch.py --output <dir> --seed <int>`。默认针对本机已有installDist运行：可选`--build`只执行一次离线gradle test installDist；否则必须确认产物存在，不能静默使用旧产物。manifest与代码快照写到output。每次运行用新目录，不覆盖历史成绩或原数据库。

先生成24课公开训练材料并批量送入学生，再生成第一轮DSH考试（按课40题，合计960个可判任务；独立world.load等准备请求不计成绩）。每课题型字段区分basic/transfer/unknown/state，目标按原计划16/12/8/4；确有不适用的状态题可检查来源和重启，不能硬造撤销能力。另可生成两套公开综合卷各120项，标teacher_exam，不冒充Codex独立闭卷。闭卷与抽查由Codex之后另做。

expected/判分规则只在考试执行器保留，送学生的requests.jsonl不含expected。题号/lesson_id不得用于硬编码答案分支。生成器随机更换ID、值、顺序与世界，未提供的事实对应unknown；复用原语覆盖课目标，不能把每课同一道属性查询重复40次凑数。每课至少列出目标与已支持/未支持边界，未实现就标blocked/failed，不伪造通过率。

输入准备、学生运行、机械判分、统计、报告均留痕。机器数据至少含：batch metadata,rule catalog,code/material manifest,training summary,case results(expected/actual/pass/reason/run_id/trace ids/type),per lesson summary,logical issue list；保留raw学生JSONL和events.jsonl。

HTML为离线自包含报告，路径output/report.html，同目录report.json为AI报告。包含课程/结果/规则搜索筛选、按课成绩、每题输入/预期/实际/判断、展开实际规则与事实链、规则首次课程/时间/原因/作者和代码锚点/快照片段、考试与Codex抽查区分、失败最早节点及数据/规则/执行/验收层分类。不要把规则清单当作“每题都执行过”。JSON安全内嵌、动态内容textContent/转义，不能让教材字符串注入HTML。不能依赖CDN/网络或file:// fetch；数据自包含且提供JSON下载/路径。

所有声称通过的数据必须来自真实程序执行。B可为后续Codex抽查JSON预留合并参数；验收失败保留原attempt，集中修正后生成新attempt；不要删除失败证据或只展示最终绿灯。给人看的解释要通俗，给AI的字段要稳定。最终给root简短交付说明、实际文件和如何一次批量运行即可。
