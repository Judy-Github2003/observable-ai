// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：按 experiments/curriculum/batch-v1/IMPLEMENTATION.md 实现学前24课全部批量操作。
// 2026-11-04 Codex 局部修正：首考与独立抽查发现两处边界过宽（单元素模式被当作唯一规律、
// unknown 计数仍返回已知子集数量），只收紧判定，不新增能力；记录见 attempt-01 与 spotcheck-01。
// 所有能力都只处理显式材料、显式教学或结构化输入；标签查询不等于真实看图/听说/情绪体验。
// 原第1/2课语义回填到对象/属性规则；形状等复用课引用原属性规则，不虚构新规则。
package logic

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 批量学生执行器。
 *
 * 每个 op 先由 AuditTrail 写 rule_enter，再执行；执行结果（包括 unknown/conflict）写 rule_exit，
 * 异常写 rule_error。世界状态按 world_id 安全编码持久化；请求之间不自动重置世界。
 * 规则注释中的引入时间：本轮新规则为2026-11-04；历史回填项按原实现日期记录。
 */
internal class BatchStudent(
    private val store: BatchStateStore,
    private val audit: AuditTrail,
    // 注入时钟只为测试和可追溯时间；时间题仍只使用题面给定日期，不读系统当前日期猜事实。
    private val clock: () -> Instant = { Instant.now() },
) {
    // plan.run 允许复用操作，但必须限制递归深度，避免材料构造无限自调用。
    private var executionDepth = 0

    /** 按 op 分派；未知 op 也留下可见失败事件，不返回没有来源的答案。 */
    fun execute(op: String, args: JsonObject): OpOutcome {
        if (executionDepth >= 8) {
            audit.note("execution_guard", "操作递归超过上限：$op", "failure")
            return OpOutcome(
                status = OpStatus.ERROR,
                result = buildJsonObject { put("op", op) },
                reason = "plan_depth_exceeded",
            )
        }
        val ruleId = ruleIdFor(op)
        if (ruleId == null) {
            audit.note("unsupported_op", "不支持的操作：$op", "failure")
            return OpOutcome(
                status = OpStatus.ERROR,
                result = buildJsonObject { put("op", op) },
                reason = "unsupported_op",
            )
        }
        executionDepth++
        try {
            return audit.invokeRule(ruleId, op, args) { span -> dispatch(op, args, span) }
        } finally {
            executionDepth--
        }
    }

    private fun dispatch(op: String, args: JsonObject, span: RuleSpan): OpOutcome = when (op) {
        "world.load" -> worldLoad(args)
        "object.name", "object.findName", "object.same" -> objectRule(op, args)
        "attr.get", "attr.find", "attr.same", "attr.teach" -> attributeRule(op, args, span)
        "quantity.compare" -> quantityCompare(args)
        "category.members" -> categoryMembers(args)
        "filter.all" -> filterAll(args)
        "relation.query", "relation.set" -> relationRule(op, args)
        "count", "pair" -> countRule(op, args)
        "count.compare" -> countCompare(args)
        "arithmetic" -> arithmetic(args)
        "pattern.next" -> patternNext(args)
        "time.compare", "time.relative" -> timeRule(op, args)
        "observation.compare" -> observationCompare(args)
        "sentence.parse", "sentence.render" -> sentenceRule(op, args)
        "story.query" -> storyQuery(args)
        "reference.resolve" -> referenceResolve(args)
        "actions.run" -> actionsRun(args, span)
        "policy.decide" -> policyDecide(args)
        "turn.next" -> turnNext(args, span)
        "compose" -> compose(args)
        "claim.add", "claim.evaluate" -> claimRule(op, args)
        "claim.retract" -> claimRetract(args)
        "missing.ask" -> missingAsk(args)
        "plan.run" -> planRun(args)
        else -> OpOutcome(OpStatus.ERROR, buildJsonObject { put("op", op) }, "unsupported_op")
    }

    // RULE:batch.world_load
    // 引入课程：preschool-001（批量基础设施，全课程复用）；作者：DeepSeek Harness；原因：受控世界必须整体校验后原子导入。
    private fun worldLoad(args: JsonObject): OpOutcome {
        val source = args.requiredString("source", "args")
        val worldElement = args["world"]
            ?: throw BatchArgumentException("args.world 缺失")
        val now = clock()
        val world = BatchWorldLoader.load(
            worldId = audit.worldId,
            source = source,
            lessonId = audit.lessonId,
            runId = audit.events.runId,
            element = worldElement,
            now = now,
        )
        // 重新载入同一 world 时保留首次创建时间并递增版本；旧状态损坏则直接报错，不静默覆盖。
        val previous = store.load(audit.worldId)
        if (previous != null) {
            world.createdAt = previous.createdAt
            world.loadCount = previous.loadCount + 1
            world.revision = previous.revision + 1
        }
        store.save(world)
        val facts = buildList {
            world.objects.keys.forEach { add(objectFact(it)) }
            world.claims.keys.forEach { add(claimFact(it)) }
            world.observations.values.forEach { add(it.factId) }
        }
        return ok(buildJsonObject {
            put("object_count", world.objects.size)
            put("relation_count", world.relations.count { it.active })
            put("claim_count", world.claims.size)
            put("observation_count", world.observations.size)
            put("story_count", world.story.size)
            put("policy_count", world.policies.size)
            put("turn_count", world.turns.size)
            put("world_id", world.worldId)
            put("source", world.source)
            put("load_count", world.loadCount)
            put("revision", world.revision)
        }, facts)
    }

    // RULE:batch.object_identity
    // 引入课程：preschool-001（历史回填，原实现 Codex，2026-11-04）；原因：区分对象ID、名称、同名与同一。
    // 本轮只把原第1课语义接到批量入口，没有捏造当年的逐事件时间。
    private fun objectRule(op: String, args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        return when (op) {
            "object.name" -> {
                val id = args.requiredString("id", "args")
                val obj = world.objects[id] ?: return unknown(
                    "object_not_observed",
                    buildJsonObject { put("id", id) },
                )
                val name = obj.name ?: return unknown(
                    "name_not_observed",
                    buildJsonObject { put("id", id) },
                    listOf(objectFact(id)),
                )
                ok(buildJsonObject { put("id", id); put("name", name) }, listOf(objectFact(id)))
            }
            "object.findName" -> {
                val name = args.requiredString("name", "args")
                val ids = world.objects.values
                    .filter { it.name == name }
                    .map { it.id }
                    .sorted()
                if (ids.isEmpty()) {
                    return unknown("name_not_observed", buildJsonObject {
                        putJsonArray("ids") { }
                    })
                }
                ok(buildJsonObject {
                    putJsonArray("ids") { ids.forEach { add(it) } }
                }, ids.map(::objectFact))
            }
            "object.same" -> {
                val left = args.requiredString("left", "args")
                val right = args.requiredString("right", "args")
                val missing = buildList {
                    if (!world.objects.containsKey(left)) add(left)
                    if (!world.objects.containsKey(right)) add(right)
                }.distinct()
                if (missing.isNotEmpty()) {
                    return unknown("object_not_observed", buildJsonObject {
                        putJsonArray("missing_ids") { missing.forEach { add(it) } }
                    })
                }
                val sameObject = left == right
                val leftName = world.objects.getValue(left).name
                val rightName = world.objects.getValue(right).name
                if (leftName == null || rightName == null) {
                    return unknown("name_not_observed", buildJsonObject {
                        put("same_object", sameObject)
                        put("same_name", JsonNull)
                    }, listOf(objectFact(left), objectFact(right)))
                }
                ok(buildJsonObject {
                    put("same_object", sameObject)
                    put("same_name", leftName == rightName)
                }, listOf(objectFact(left), objectFact(right)))
            }
            else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_op")
        }
    }

    // RULE:batch.attribute
    // 引入课程：preschool-002（历史回填，原实现 Codex，2026-11-04）；原因：属性名是数据参数，缺失属性不是否定。
    // 第3课形状和第24课综合复用本规则，不新增“形状专用”规则；显式补教保留前值和来源历史。
    private fun attributeRule(op: String, args: JsonObject, span: RuleSpan): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        return when (op) {
            "attr.get" -> {
                val id = args.requiredString("id", "args")
                val key = args.requiredString("key", "args")
                val obj = world.objects[id] ?: return unknown(
                    "object_not_observed",
                    buildJsonObject { put("id", id); put("key", key) },
                )
                val value = obj.attributes[key] ?: return unknown(
                    "attribute_not_observed",
                    buildJsonObject { put("id", id); put("key", key) },
                    listOf(objectFact(id)),
                )
                ok(buildJsonObject {
                    put("id", id); put("key", key); put("value", value)
                }, listOf(attributeFact(id, key)))
            }
            "attr.find" -> {
                val key = args.requiredString("key", "args")
                val value = args.requiredString("value", "args")
                val ids = world.objects.values
                    .filter { it.attributes[key] == value }
                    .map { it.id }
                    .sorted()
                if (ids.isEmpty()) {
                    return unknown("attribute_value_not_observed", buildJsonObject {
                        putJsonArray("ids") { }
                    })
                }
                ok(buildJsonObject {
                    putJsonArray("ids") { ids.forEach { add(it) } }
                }, ids.map { attributeFact(it, key) })
            }
            "attr.same" -> {
                val left = args.requiredString("left", "args")
                val right = args.requiredString("right", "args")
                val key = args.requiredString("key", "args")
                val missingObjects = buildList {
                    if (!world.objects.containsKey(left)) add(left)
                    if (!world.objects.containsKey(right)) add(right)
                }.distinct()
                if (missingObjects.isNotEmpty()) {
                    return unknown("object_not_observed", buildJsonObject {
                        putJsonArray("missing_object_ids") { missingObjects.forEach { add(it) } }
                    })
                }
                val leftValue = world.objects.getValue(left).attributes[key]
                val rightValue = world.objects.getValue(right).attributes[key]
                val missingAttributes = buildList {
                    if (leftValue == null) add(left)
                    if (rightValue == null && right != left) add(right)
                }
                if (missingAttributes.isNotEmpty()) {
                    return unknown("attribute_not_observed", buildJsonObject {
                        putJsonArray("missing_attribute_object_ids") {
                            missingAttributes.forEach { add(it) }
                        }
                    }, listOf(objectFact(left), objectFact(right)))
                }
                ok(buildJsonObject {
                    put("same_value", leftValue == rightValue)
                }, listOf(attributeFact(left, key), attributeFact(right, key)))
            }
            "attr.teach" -> {
                val outcome = applyAttributeTeach(world, args, clock())
                if (outcome.status == OpStatus.OK) {
                    touch(world)
                    store.save(world)
                }
                // 补教事件由规则包装器记录；这里额外标注这是显式教学而非教师推断。
                audit.note(
                    type = "attribute_taught",
                    description = if (outcome.status == OpStatus.OK) {
                        "显式教学属性已保存"
                    } else {
                        "显式教学未保存：${outcome.reason ?: outcome.status}"
                    },
                    phase = if (outcome.status == OpStatus.OK) "success" else "info",
                    span = span,
                ) {
                    put("result", outcome.result)
                    put("reason", outcome.reason?.let(::JsonPrimitive) ?: JsonNull)
                }
                outcome
            }
            else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_op")
        }
    }

    /** 在一个世界副本上执行属性补教；调用方决定是否落盘（actions.run 需要先模拟全部步骤）。 */
    private fun applyAttributeTeach(world: BatchWorld, args: JsonObject, now: Instant): OpOutcome {
        val id = args.requiredString("id", "args")
        val key = args.requiredString("key", "args")
        val value = args.requiredString("value", "args")
        val obj = world.objects[id] ?: return unknown(
            "object_not_observed",
            buildJsonObject { put("id", id); put("key", key) },
        )
        val previous = obj.attributes[key]
        obj.attributes[key] = value
        obj.history += WorldAttributeHistory(
            key = key,
            oldValue = previous,
            newValue = value,
            changeType = "explicit_teaching",
            source = "explicit_teaching",
            introducedLesson = audit.lessonId,
            acquiredAt = now.toString(),
            runId = audit.events.runId,
        )
        return ok(buildJsonObject {
            put("id", id)
            put("key", key)
            put("value", value)
            put("previous_value", previous?.let(::JsonPrimitive) ?: JsonNull)
        }, listOf(attributeFact(id, key)))
    }

    // RULE:batch.quantity_compare
    // 引入课程：preschool-004；作者：DeepSeek Harness；原因：有单位数量比较，单位不同或缺失时不猜换算。
    private fun quantityCompare(args: JsonObject): OpOutcome {
        val leftObject = args["left"] as? JsonObject
            ?: throw BatchArgumentException("args.left 必须是对象")
        val rightObject = args["right"] as? JsonObject
            ?: throw BatchArgumentException("args.right 必须是对象")
        val left = quantity(leftObject, "args.left") ?: return unknown(
            "quantity_or_unit_missing",
            buildJsonObject { put("missing", "left") },
        )
        val right = quantity(rightObject, "args.right") ?: return unknown(
            "quantity_or_unit_missing",
            buildJsonObject { put("missing", "right") },
        )
        if (left.unit != right.unit) {
            return unknown("unit_mismatch", buildJsonObject {
                put("left_unit", left.unit)
                put("right_unit", right.unit)
            })
        }
        val comparison = left.value.compareTo(right.value)
        return ok(buildJsonObject {
            put("comparison", when {
                comparison < 0 -> "less"
                comparison > 0 -> "greater"
                else -> "equal"
            })
            put("difference", numberJson(left.value.subtract(right.value)))
            put("unit", left.unit)
        })
    }

    // RULE:batch.category_members
    // 引入课程：preschool-005；作者：DeepSeek Harness；原因：只查材料显式 categories，不按名称/颜色推类别。
    private fun categoryMembers(args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val category = args.requiredString("category", "args")
        val ids = world.objects.values
            .filter { category in it.categories }
            .map { it.id }
            .sorted()
        if (ids.isEmpty()) {
            return unknown("category_not_observed", buildJsonObject {
                putJsonArray("ids") { }
            })
        }
        return ok(buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(it) } }
        }, ids.map { categoryFact(it, category) })
    }

    // RULE:batch.filter_all
    // 引入课程：preschool-006；作者：DeepSeek Harness；原因：多条件筛选必须区分“已知不满足”和“缺必要条件”。
    private fun filterAll(args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val conditionsElement = args["conditions"] as? JsonArray
            ?: throw BatchArgumentException("args.conditions 必须是数组")
        require(conditionsElement.isNotEmpty()) { "args.conditions 不能为空" }
        val conditions = conditionsElement.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw BatchArgumentException("args.conditions[$index] 必须是对象")
            Condition(
                key = item.requiredString("key", "args.conditions[$index]"),
                value = item.requiredPrimitive("value", "args.conditions[$index]"),
            )
        }
        val unknownIds = LinkedHashSet<String>()
        val candidates = when (val idsElement = args["ids"]) {
            null, is JsonNull -> world.objects.keys.toList()
            else -> {
                val requested = stringArray(idsElement, "args.ids").distinct()
                requested.forEach { if (!world.objects.containsKey(it)) unknownIds += it }
                requested
            }
        }
        val matched = mutableListOf<String>()
        candidates.forEach { id ->
            val obj = world.objects[id] ?: return@forEach
            var mismatch = false
            var missing = false
            for (condition in conditions) {
                val actual = obj.attributes[condition.key]
                if (actual == null) {
                    missing = true
                } else if (actual != condition.value) {
                    mismatch = true
                    break
                }
            }
            when {
                mismatch -> Unit
                missing -> unknownIds += id
                else -> matched += id
            }
        }
        val facts = matched.flatMap { id ->
            conditions.mapNotNull { condition ->
                if (world.objects.getValue(id).attributes.containsKey(condition.key)) {
                    attributeFact(id, condition.key)
                } else {
                    null
                }
            }
        }
        return ok(buildJsonObject {
            putJsonArray("ids") { matched.sorted().forEach { add(it) } }
            putJsonArray("unknown_ids") { unknownIds.sorted().forEach { add(it) } }
        }, facts)
    }

    // RULE:batch.relation
    // 引入课程：preschool-007；作者：DeepSeek Harness；原因：空间/部分整体只用明确关系，缺失不猜。
    // relation.set 保留旧关系为 inactive 并记录前值，不让最后一次写入掩盖历史。
    private fun relationRule(op: String, args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        return when (op) {
            "relation.query" -> {
                val subject = args.requiredString("subject", "args")
                val predicate = args.requiredString("predicate", "args")
                val targets = world.relations
                    .filter { it.active && it.subject == subject && it.predicate == predicate }
                    .map { it.target }
                    .distinct()
                    .sorted()
                if (targets.isEmpty()) {
                    return unknown("relation_not_observed", buildJsonObject {
                        putJsonArray("targets") { }
                    })
                }
                ok(buildJsonObject {
                    putJsonArray("targets") { targets.forEach { add(it) } }
                }, world.relations.filter {
                    it.active && it.subject == subject && it.predicate == predicate
                }.map { relationFact(it.id) })
            }
            "relation.set" -> {
                val subject = args.requiredString("subject", "args")
                val predicate = args.requiredString("predicate", "args")
                val target = args.requiredString("object", "args")
                if (!world.objects.containsKey(subject)) {
                    return unknown("object_not_observed", buildJsonObject {
                        put("subject", subject)
                    })
                }
                val outcome = applyRelationSet(world, subject, predicate, target, clock())
                if (outcome.status == OpStatus.OK) {
                    touch(world)
                    store.save(world)
                }
                outcome
            }
            else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_op")
        }
    }

    /** 关系当前值的模拟更新；旧 active 关系不删除，只标 inactive 并进入历史。 */
    private fun applyRelationSet(
        world: BatchWorld,
        subject: String,
        predicate: String,
        target: String,
        now: Instant,
    ): OpOutcome {
        val previousTargets = world.relations
            .filter { it.active && it.subject == subject && it.predicate == predicate }
            .map { it.target }
        world.relations.filter { it.active && it.subject == subject && it.predicate == predicate }
            .forEach { it.active = false }
        val newId = "rel-set-${UUID.randomUUID()}"
        world.relations += WorldRelation(
            id = newId,
            subject = subject,
            predicate = predicate,
            target = target,
            active = true,
            source = "explicit_teaching",
            introducedLesson = audit.lessonId,
            acquiredAt = now.toString(),
            runId = audit.events.runId,
            previousTargets = previousTargets.toMutableList(),
            history = mutableListOf(
                WorldRelationHistory(
                    previousTargets = previousTargets,
                    newTarget = target,
                    source = "explicit_teaching",
                    introducedLesson = audit.lessonId,
                    acquiredAt = now.toString(),
                    runId = audit.events.runId,
                ),
            ),
        )
        return ok(buildJsonObject {
            put("subject", subject)
            put("predicate", predicate)
            put("object", target)
            putJsonArray("previous_targets") { previousTargets.forEach { add(it) } }
        }, listOf(relationFact(newId)))
    }

    // RULE:batch.action_sequence
    // 引入课程：preschool-009；作者：DeepSeek Harness；原因：动作顺序与前置条件；第24课 plan.run 复用本规则。
    // actions.run 先在校验副本上模拟全部步骤，通过后才一次性提交；失败不回写半套动作。
    private fun actionsRun(args: JsonObject, span: RuleSpan): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val steps = args["steps"] as? JsonArray
            ?: throw BatchArgumentException("args.steps 必须是数组")
        val working = BatchWorldCodec.deepCopy(world)
        val now = clock()
        val results = mutableListOf<JsonElement>()
        val details = mutableListOf<JsonElement>()
        val facts = mutableListOf<String>()
        audit.note(
            type = "action_prepare",
            description = "开始校验并模拟 ${steps.size} 个动作（尚未提交）",
            phase = "start",
            span = span,
        ) { put("step_count", steps.size) }

        steps.forEachIndexed { index, element ->
            val step = element as? JsonObject
                ?: throw BatchArgumentException("args.steps[$index] 必须是对象")
            val stepOp = step.requiredString("op", "args.steps[$index]")
            val stepArgs = step["args"] as? JsonObject
                ?: throw BatchArgumentException("args.steps[$index].args 必须是对象")
            if (stepOp != "attr.teach" && stepOp != "relation.set") {
                throw BatchArgumentException("actions.run 只支持 attr.teach/relation.set，实际为 $stepOp")
            }
            // 前置条件按“前面步骤已模拟执行”的状态检查；缺对象/属性视为未满足，不冒充通过。
            val unmet = checkRequires(working, step["requires"], "args.steps[$index].requires")
            if (unmet != null) {
                audit.note(
                    type = "action_abort",
                    description = "动作前置条件未满足，整批动作未提交",
                    phase = "failure",
                    span = span,
                ) {
                    put("failed_step", index)
                    put("requires", unmet)
                }
                return unknown("precondition_not_met", buildJsonObject {
                    put("failed_step", index)
                    put("requires", unmet)
                    putJsonArray("results") { results.forEach { add(it) } }
                })
            }
            val subRule = ruleIdFor(stepOp) ?: throw BatchArgumentException("未知动作：$stepOp")
            val outcome = audit.invokeRule(
                ruleId = subRule,
                op = stepOp,
                args = stepArgs,
                commitState = "prepared",
            ) {
                applyMutation(working, stepOp, stepArgs, now)
            }
            results += outcome.result
            facts += outcome.factIds
            details += buildJsonObject {
                put("op", stepOp)
                put("status", outcome.status)
                put("reason", outcome.reason?.let(::JsonPrimitive) ?: JsonNull)
                put("span_id", outcome.spanId?.let(::JsonPrimitive) ?: JsonNull)
            }
            if (outcome.status != OpStatus.OK) {
                audit.note(
                    type = "action_abort",
                    description = "动作模拟失败，整批动作未提交",
                    phase = "failure",
                    span = span,
                ) {
                    put("failed_step", index)
                    put("failed_status", outcome.status)
                }
                return OpOutcome(
                    status = OpStatus.UNKNOWN,
                    result = buildJsonObject {
                        put("failed_step", index)
                        putJsonArray("results") { results.forEach { add(it) } }
                    },
                    reason = outcome.reason ?: "action_not_committed",
                    factIds = outcome.factIds,
                )
            }
        }
        touch(working)
        store.save(working)
        audit.note(
            type = "action_commit",
            description = "动作全部通过并已提交：${steps.size} 步",
            phase = "success",
            span = span,
        ) { put("step_count", steps.size) }
        return ok(buildJsonObject {
            putJsonArray("results") { results.forEach { add(it) } }
            putJsonArray("steps") { details.forEach { add(it) } }
            put("committed", true)
        }, facts.distinct())
    }

    /** 执行一个已校验的模拟动作；仅供 actions.run 在副本上调用。 */
    private fun applyMutation(
        world: BatchWorld,
        op: String,
        args: JsonObject,
        now: Instant,
    ): OpOutcome = when (op) {
        "attr.teach" -> applyAttributeTeach(world, args, now)
        "relation.set" -> {
            val subject = args.requiredString("subject", "args")
            val predicate = args.requiredString("predicate", "args")
            val target = args.requiredString("object", "args")
            if (!world.objects.containsKey(subject)) {
                unknown("object_not_observed", buildJsonObject { put("subject", subject) })
            } else {
                applyRelationSet(world, subject, predicate, target, now)
            }
        }
        else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_action")
    }

    /** 前置条件列表；返回第一个未满足条件（含缺失对象/属性），全部满足返回 null。 */
    private fun checkRequires(world: BatchWorld, element: JsonElement?, context: String): JsonObject? {
        if (element == null || element is JsonNull) return null
        val array = element as? JsonArray
            ?: throw BatchArgumentException("$context 必须是数组")
        array.forEachIndexed { index, item ->
            val condition = item as? JsonObject
                ?: throw BatchArgumentException("$context[$index] 必须是对象")
            val id = condition.requiredString("id", "$context[$index]")
            val key = condition.requiredString("key", "$context[$index]")
            val value = condition.requiredPrimitive("value", "$context[$index]")
            val actual = world.objects[id]?.attributes?.get(key)
            if (actual != value) {
                return buildJsonObject {
                    put("index", index)
                    put("id", id)
                    put("key", key)
                    put("required_value", value)
                    put("actual", actual?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        }
        return null
    }

    // RULE:batch.count_pair
    // 引入课程：preschool-010；作者：DeepSeek Harness；原因：一一配对与点数必须去重、不凭空造对象。
    private fun countRule(op: String, args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        return when (op) {
            "count" -> {
                val idsElement = args["ids"]
                    ?: return unknown("ids_not_provided", buildJsonObject {
                        put("count", JsonNull)
                    })
                val requested = stringArray(idsElement, "args.ids").distinct()
                val unknownIds = requested.filterNot { world.objects.containsKey(it) }
                if (unknownIds.isNotEmpty()) {
                    // 2026-11-04 Codex 修正：未知就整体未知，不能再给出“已知子集的数量”。
                    // 独立抽查 count.unknown_object 发现旧实现 status=unknown 却带 count=1，容易被下游当成答案。
                    return unknown("object_not_observed", buildJsonObject {
                        putJsonArray("unknown_ids") { unknownIds.forEach { add(it) } }
                    })
                }
                ok(buildJsonObject { put("count", requested.size) }, requested.map(::objectFact))
            }
            "pair" -> {
                val leftElement = args["left"]
                val rightElement = args["right"]
                if (leftElement == null || rightElement == null) {
                    return unknown("pair_input_not_provided", buildJsonObject {
                        putJsonArray("pairs") { }
                    })
                }
                val left = stringArray(leftElement, "args.left").distinct()
                val right = stringArray(rightElement, "args.right").distinct()
                val unknownIds = (left + right).filterNot { world.objects.containsKey(it) }
                if (unknownIds.isNotEmpty()) {
                    return unknown("object_not_observed", buildJsonObject {
                        putJsonArray("unknown_ids") { unknownIds.distinct().sorted().forEach { add(it) } }
                    })
                }
                val paired = minOf(left.size, right.size)
                val pairs = (0 until paired).map { index -> left[index] to right[index] }
                return ok(buildJsonObject {
                    putJsonArray("pairs") {
                        pairs.forEach { (l, r) -> add(buildJsonArray { add(l); add(r) }) }
                    }
                    putJsonArray("unpaired_left") { left.drop(paired).forEach { add(it) } }
                    putJsonArray("unpaired_right") { right.drop(paired).forEach { add(it) } }
                }, (left + right).map(::objectFact))
            }
            else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_op")
        }
    }

    // RULE:batch.count_compare
    // 引入课程：preschool-011；作者：DeepSeek Harness；原因：比较已知集合数量，空集合明确为0，缺列表不是0。
    private fun countCompare(args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val leftElement = args["left"] ?: return unknown("left_not_provided")
        val rightElement = args["right"] ?: return unknown("right_not_provided")
        val left = stringArray(leftElement, "args.left").distinct()
        val right = stringArray(rightElement, "args.right").distinct()
        val unknownIds = (left + right).filterNot { world.objects.containsKey(it) }
        if (unknownIds.isNotEmpty()) {
            return unknown("object_not_observed", buildJsonObject {
                putJsonArray("unknown_ids") { unknownIds.distinct().sorted().forEach { add(it) } }
            })
        }
        val comparison = left.size.compareTo(right.size)
        return ok(buildJsonObject {
            put("comparison", when {
                comparison < 0 -> "less"
                comparison > 0 -> "greater"
                else -> "equal"
            })
            put("difference", left.size - right.size)
            put("left_count", left.size)
            put("right_count", right.size)
        }, (left + right).map(::objectFact))
    }

    // RULE:batch.arithmetic
    // 引入课程：preschool-012；作者：DeepSeek Harness；原因：十以内增减的通用数值运算，类型严格且不按题号写答案。
    private fun arithmetic(args: JsonObject): OpOutcome {
        val operator = args.optionalString("operator", "args")
            ?: return unknown("operator_not_provided")
        val left = strictNumber(args["a"], "args.a")
        val right = strictNumber(args["b"], "args.b")
        if (left == null || right == null) {
            return unknown("operand_not_provided", buildJsonObject {
                if (left == null) put("missing", "a")
                if (right == null) put("missing", "b")
            })
        }
        val value = when (operator) {
            "add" -> left.add(right)
            "sub" -> left.subtract(right)
            else -> throw BatchArgumentException("不支持的运算：$operator")
        }
        return ok(buildJsonObject { put("value", numberJson(value)) })
    }

    // RULE:batch.pattern_next
    // 引入课程：preschool-013；作者：DeepSeek Harness；原因：只按明确给定模式续排，不从短序列声称唯一规律。
    // 2026-11-04 Codex 修正：只有一个观测元素时无法区分“周期1”与“尚未看出规律”，
    // 首考 preschool-013-exam-030/031 正是这样被误判为可续排，因此要求至少两个元素的重复单位。
    private fun patternNext(args: JsonObject): OpOutcome {
        val patternElement = args["pattern"] as? JsonArray
        if (patternElement == null || patternElement.isEmpty()) {
            return unknown("pattern_not_provided")
        }
        // 单元素序列的“规律”不唯一：A 可能是周期1，也可能是更长周期的首个元素，不能外推。
        if (patternElement.size < 2) {
            return unknown("insufficient_pattern_evidence", buildJsonObject {
                put("observed_length", patternElement.size)
                put("required_min_length", 2)
            })
        }
        val countElement = args["count"]
            ?: return unknown("count_not_provided")
        val count = strictInt(countElement, "args.count")
        require(count in 0..1000) { "count 必须在0到1000之间" }
        val values = (0 until count).map { offset -> patternElement[offset % patternElement.size] }
        return ok(buildJsonObject {
            putJsonArray("values") { values.forEach { add(it) } }
        })
    }

    // RULE:batch.time_order
    // 引入课程：preschool-009（时序内核）；作者：DeepSeek Harness；第14课补时间表示；原因：只根据给定日期判断先后。
    // 这里不使用系统当前日期猜事实；非法/缺失日期保留为 unknown，不伪造“今天”。
    private fun timeRule(op: String, args: JsonObject): OpOutcome {
        return when (op) {
            "time.compare" -> {
                val leftText = args.optionalString("left", "args")
                    ?: return unknown("date_not_provided", buildJsonObject { put("missing", "left") })
                val rightText = args.optionalString("right", "args")
                    ?: return unknown("date_not_provided", buildJsonObject { put("missing", "right") })
                val left = parseDateOrNull(leftText)
                    ?: return unknown("invalid_date", buildJsonObject { put("left", leftText) })
                val right = parseDateOrNull(rightText)
                    ?: return unknown("invalid_date", buildJsonObject { put("right", rightText) })
                val comparison = left.compareTo(right)
                ok(buildJsonObject {
                    put("comparison", when {
                        comparison < 0 -> "less"
                        comparison > 0 -> "greater"
                        else -> "equal"
                    })
                    put("difference_days", ChronoUnit.DAYS.between(right, left))
                })
            }
            "time.relative" -> {
                val referenceText = args.optionalString("reference_date", "args")
                    ?: return unknown("date_not_provided", buildJsonObject { put("missing", "reference_date") })
                val reference = parseDateOrNull(referenceText)
                    ?: return unknown("invalid_date", buildJsonObject { put("reference_date", referenceText) })
                val offsetElement = args["offset_days"]
                    ?: return unknown("offset_not_provided")
                val offset = strictInt(offsetElement, "args.offset_days")
                ok(buildJsonObject { put("date", reference.plusDays(offset.toLong()).toString()) })
            }
            else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_op")
        }
    }

    // RULE:batch.observation_compare
    // 引入课程：preschool-015；作者：DeepSeek Harness；原因：报告给定观察的差异和单变量范围，不宣称因果定律。
    private fun observationCompare(args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val leftId = args.requiredString("left_id", "args")
        val rightId = args.requiredString("right_id", "args")
        val left = world.observations[leftId]
        val right = world.observations[rightId]
        val missing = buildList {
            if (left == null) add(leftId)
            if (right == null) add(rightId)
        }.distinct()
        if (missing.isNotEmpty()) {
            return unknown("observation_not_observed", buildJsonObject {
                putJsonArray("missing_ids") { missing.forEach { add(it) } }
            })
        }
        val leftObservation = requireNotNull(left)
        val rightObservation = requireNotNull(right)
        val conditionKeys = (leftObservation.conditions.keys + rightObservation.conditions.keys)
        val changedConditions = conditionKeys
            .filter { leftObservation.conditions[it] != rightObservation.conditions[it] }
            .sorted()
        val outcomeKeys = (leftObservation.outcome.keys + rightObservation.outcome.keys)
        val changedOutcomes = outcomeKeys
            .filter { leftObservation.outcome[it] != rightObservation.outcome[it] }
            .sorted()
        return ok(buildJsonObject {
            putJsonArray("changed_conditions") { changedConditions.forEach { add(it) } }
            putJsonArray("changed_outcomes") { changedOutcomes.forEach { add(it) } }
            put("controlled", changedConditions.size == 1)
            put("causal_claim", false)
        }, listOf(leftObservation.factId, rightObservation.factId))
    }

    // RULE:batch.sentence
    // 引入课程：preschool-016；作者：DeepSeek Harness；原因：只处理公布句式“对象ID 的 属性名 是 属性值”。
    private fun sentenceRule(op: String, args: JsonObject): OpOutcome {
        return when (op) {
            "sentence.parse" -> {
                // 解析只处理公布句式文本，不依赖某个世界已加载；render 才读取当前事实。
                val text = args.optionalString("text", "args")
                    ?: return unknown("text_not_provided")
                val match = SENTENCE_PATTERN.matchEntire(text)
                    ?: return unknown("unsupported_sentence", buildJsonObject { put("text", text) })
                val subject = match.groupValues[1]
                val key = match.groupValues[2]
                val value = match.groupValues[3]
                if (value.isBlank()) {
                    return unknown("unsupported_sentence", buildJsonObject { put("text", text) })
                }
                ok(buildJsonObject {
                    put("subject", subject)
                    put("key", key)
                    put("value", value)
                })
            }
            "sentence.render" -> {
                val world = currentWorld() ?: return missingWorld()
                val id = args.requiredString("id", "args")
                val key = args.requiredString("key", "args")
                val obj = world.objects[id] ?: return unknown(
                    "object_not_observed",
                    buildJsonObject { put("id", id); put("key", key) },
                )
                val value = obj.attributes[key] ?: return unknown(
                    "attribute_not_observed",
                    buildJsonObject { put("id", id); put("key", key) },
                    listOf(objectFact(id)),
                )
                ok(buildJsonObject {
                    put("text", "$id 的 $key 是 $value")
                }, listOf(attributeFact(id, key)))
            }
            else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_op")
        }
    }

    // RULE:batch.story
    // 引入课程：preschool-017；作者：DeepSeek Harness；原因：按结构化事件重放物品位置/持有者，缺信息不猜动机。
    private fun storyQuery(args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val objectId = args.requiredString("object", "args")
        val field = args.requiredString("field", "args")
        if (field != "holder" && field != "place") {
            throw BatchArgumentException("story.query.field 只能是 holder 或 place")
        }
        var holder: String? = null
        var place: String? = null
        var seen = false
        val facts = mutableListOf<String>()
        world.story.forEach { event ->
            if (event.objectId != objectId) return@forEach
            when (event.action) {
                "take" -> {
                    holder = event.actor
                    place = null
                    seen = true
                    facts += storyFact(event.id)
                }
                "give" -> {
                    val to = event.to ?: return unknown(
                        "missing_story_field",
                        buildJsonObject { put("event_id", event.id); put("missing", "to") },
                        facts,
                    )
                    holder = to
                    place = null
                    seen = true
                    facts += storyFact(event.id)
                }
                "place" -> {
                    val targetPlace = event.place ?: return unknown(
                        "missing_story_field",
                        buildJsonObject { put("event_id", event.id); put("missing", "place") },
                        facts,
                    )
                    place = targetPlace
                    holder = null
                    seen = true
                    facts += storyFact(event.id)
                }
                else -> Unit
            }
        }
        if (!seen) {
            return unknown("story_not_observed", buildJsonObject { put("object", objectId) })
        }
        val value = if (field == "holder") holder else place
        if (value == null) {
            return unknown(
                if (field == "holder") "holder_not_observed" else "place_not_observed",
                buildJsonObject { put("object", objectId); put("field", field) },
                facts,
            )
        }
        return ok(buildJsonObject { put("value", value) }, facts)
    }

    // RULE:batch.reference
    // 引入课程：preschool-018；作者：DeepSeek Harness；原因：唯一候选才解析，0或多个候选输出 need 并等待澄清。
    private fun referenceResolve(args: JsonObject): OpOutcome {
        val candidatesElement = args["candidates"]
            ?: return unknown("candidates_not_provided", buildJsonObject {
                putJsonArray("need") { add("candidates") }
            })
        val candidates = stringArray(candidatesElement, "args.candidates").distinct()
        if (candidates.size == 1) {
            return ok(buildJsonObject { put("id", candidates.single()) })
        }
        return unknown("reference_not_unique", buildJsonObject {
            putJsonArray("need") { add("unique_candidate") }
            putJsonArray("candidates") { candidates.forEach { add(it) } }
        })
    }

    // RULE:batch.policy
    // 引入课程：preschool-019；作者：DeepSeek Harness；原因：只用明确属性和 world.policies 选择规则；
    // 情绪表达也必须是显式属性+教材政策，不读心。多规则决策不同时返回 conflict。
    private fun policyDecide(args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val id = args.requiredString("id", "args")
        val obj = world.objects[id] ?: return unknown(
            "object_not_observed",
            buildJsonObject { put("id", id) },
        )
        val matched = mutableListOf<WorldPolicy>()
        var missingCondition = false
        world.policies.forEach { policy ->
            var allMatch = true
            policy.conditions.forEach { (key, value) ->
                val actual = obj.attributes[key]
                if (actual == null) {
                    missingCondition = true
                    allMatch = false
                } else if (actual != value) {
                    allMatch = false
                }
            }
            if (allMatch) matched += policy
        }
        if (matched.isEmpty()) {
            return unknown(
                if (missingCondition) "policy_condition_not_observed" else "policy_not_observed",
                buildJsonObject { put("id", id) },
            )
        }
        val decisions = matched.map { it.decision }.distinct()
        val policyIds = matched.map { it.id }.sorted()
        if (decisions.size > 1) {
            return OpOutcome(OpStatus.CONFLICT, buildJsonObject {
                put("decision", JsonNull)
                putJsonArray("decisions") { decisions.sorted().forEach { add(it) } }
                putJsonArray("policy_ids") { policyIds.forEach { add(it) } }
            }, "multiple_policy_decisions", matched.map { policyFact(it.id) })
        }
        return ok(buildJsonObject {
            put("decision", decisions.single())
            putJsonArray("policy_ids") { policyIds.forEach { add(it) } }
        }, matched.map { policyFact(it.id) } + matched.flatMap { policy ->
            policy.conditions.keys.map { attributeFact(id, it) }
        })
    }

    // RULE:batch.turn
    // 引入课程：preschool-020；作者：DeepSeek Harness；原因：轮流合作只按给定 turns 轮换，状态变化留痕。
    private fun turnNext(args: JsonObject, span: RuleSpan): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        if (world.turns.isEmpty()) return unknown("turns_not_provided")
        val current = args.optionalString("current", "args")
            ?: return unknown("current_turn_not_provided")
        val index = world.turns.indexOf(current)
        if (index < 0) {
            throw BatchArgumentException("当前角色不在 turns 中：$current")
        }
        val next = world.turns[(index + 1) % world.turns.size]
        world.currentTurn = next
        touch(world)
        store.save(world)
        audit.note(
            type = "turn_advanced",
            description = "轮次已从 $current 推进到 $next",
            phase = "success",
            span = span,
        ) {
            put("previous", current)
            put("next", next)
        }
        return ok(buildJsonObject {
            put("next", next)
            put("previous", current)
        }, listOf("turn:$next"))
    }

    // RULE:batch.compose
    // 引入课程：preschool-021；作者：DeepSeek Harness；原因：只满足给定数量和颜色轮换约束，不伪造审美结论。
    private fun compose(args: JsonObject): OpOutcome {
        val shapesElement = args["shapes"]
            ?: return unknown("shapes_not_provided")
        val shapes = shapesElement as? JsonObject
            ?: throw BatchArgumentException("args.shapes 必须是对象")
        val colorsElement = args["colors"] as? JsonArray
            ?: return unknown("colors_not_provided")
        val colors = colorsElement.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive
                ?: throw BatchArgumentException("args.colors[$index] 必须是字符串")
            require(primitive.isString && primitive.content.isNotBlank()) {
                "args.colors[$index] 必须是非空字符串"
            }
            primitive.content
        }
        if (colors.isEmpty()) return unknown("colors_not_provided")
        val counts = LinkedHashMap<String, Int>()
        var total = 0
        shapes.forEach { (shape, countElement) ->
            require(shape.isNotBlank()) { "args.shapes 的形状标签不能为空" }
            val count = strictInt(countElement, "args.shapes.$shape")
            require(count >= 0) { "args.shapes.$shape 不能为负数" }
            counts[shape] = count
            total += count
        }
        require(total <= 1000) { "组合总数不能超过1000" }
        val items = mutableListOf<JsonElement>()
        var colorIndex = 0
        counts.forEach { (shape, count) ->
            repeat(count) {
                val color = colors[colorIndex % colors.size]
                items += buildJsonObject { put("shape", shape); put("color", color) }
                colorIndex++
            }
        }
        return ok(buildJsonObject {
            putJsonArray("items") { items.forEach { add(it) } }
        })
    }

    // RULE:batch.claim
    // 引入课程：preschool-022；作者：DeepSeek Harness；原因：显式支持、否定、未知和冲突；新增证据不覆盖相反证据。
    private fun claimRule(op: String, args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        return when (op) {
            "claim.add" -> {
                val claimElement = args["claim"] as? JsonObject
                    ?: throw BatchArgumentException("args.claim 必须是对象")
                val claimId = claimElement.requiredString("id", "args.claim")
                if (world.claims.containsKey(claimId)) {
                    throw BatchArgumentException("证据ID已存在：$claimId")
                }
                val claim = WorldClaim(
                    id = claimId,
                    subject = claimElement.requiredString("subject", "args.claim"),
                    predicate = claimElement.requiredString("predicate", "args.claim"),
                    value = claimElement.requiredPrimitive("value", "args.claim"),
                    truth = claimElement.requiredBoolean("truth", "args.claim"),
                    active = true,
                    dependsOn = parseOptionalStringArray(claimElement["depends_on"], "args.claim.depends_on"),
                    source = "explicit_claim",
                    introducedLesson = audit.lessonId,
                    acquiredAt = clock().toString(),
                    runId = audit.events.runId,
                )
                world.claims[claimId] = claim
                touch(world)
                store.save(world)
                ok(buildJsonObject { put("claim_id", claimId) }, listOf(claimFact(claimId)))
            }
            "claim.evaluate" -> {
                val subject = args.requiredString("subject", "args")
                val predicate = args.requiredString("predicate", "args")
                val value = args.requiredPrimitive("value", "args")
                val matching = world.claims.values.filter {
                    it.active && it.subject == subject && it.predicate == predicate && it.value == value
                }
                val positive = matching.filter { it.truth }
                val negative = matching.filterNot { it.truth }
                val facts = matching.map { claimFact(it.id) }
                when {
                    positive.isNotEmpty() && negative.isNotEmpty() -> OpOutcome(
                        OpStatus.CONFLICT,
                        buildJsonObject {
                            put("truth", "conflict")
                            putJsonArray("claim_ids") {
                                facts.map { it.removePrefix("claim:") }.sorted().forEach { add(it) }
                            }
                        },
                        "conflicting_evidence",
                        facts,
                    )
                    positive.isNotEmpty() -> ok(buildJsonObject { put("truth", true) }, facts)
                    negative.isNotEmpty() -> ok(buildJsonObject { put("truth", false) }, facts)
                    else -> unknown("no_evidence", buildJsonObject { put("truth", JsonNull) })
                }
            }
            else -> OpOutcome(OpStatus.ERROR, reason = "unsupported_op")
        }
    }

    // RULE:batch.retract
    // 引入课程：preschool-023；作者：DeepSeek Harness；原因：撤销证据及依赖它的派生证据，不影响无关证据；
    // 被撤销记录保留在状态里，查询只看 active，不删除失败/更正证据。
    private fun claimRetract(args: JsonObject): OpOutcome {
        val world = currentWorld() ?: return missingWorld()
        val id = args.requiredString("id", "args")
        if (!world.claims.containsKey(id)) {
            return unknown("claim_not_observed", buildJsonObject { put("id", id) })
        }
        val toRetract = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!toRetract.add(current)) continue
            world.claims.values.forEach { claim ->
                if (claim.active && current in claim.dependsOn) queue.add(claim.id)
            }
        }
        val now = clock().toString()
        val retracted = mutableListOf<String>()
        toRetract.forEach { claimId ->
            val claim = world.claims[claimId] ?: return@forEach
            if (claim.active) {
                claim.active = false
                claim.retractedAt = now
                claim.retractedByRunId = audit.events.runId
                retracted += claimId
            }
        }
        if (retracted.isNotEmpty()) {
            touch(world)
            store.save(world)
        }
        return ok(buildJsonObject {
            putJsonArray("retracted_ids") { retracted.sorted().forEach { add(it) } }
        }, retracted.map(::claimFact))
    }

    // RULE:batch.missing_ask
    // 引入课程：preschool-023；作者：DeepSeek Harness；原因：信息不足时只问缺项并等待，不调用教师补答案。
    private fun missingAsk(args: JsonObject): OpOutcome {
        val fields = parseOptionalStringArray(args["fields"], "args.fields")
        val question = if (fields.isEmpty()) {
            "请提供需要补充的信息。"
        } else {
            "请补充：${fields.joinToString("、")}"
        }
        return unknown("missing_information", buildJsonObject {
            putJsonArray("need") { fields.forEach { add(it) } }
            put("question", question)
            put("action", "wait")
        })
    }

    /**
     * 综合迁移：复用已有操作并保留每个子调用的规则事件。
     *
     * 本函数不新增按第24课、题号或实体名的答案分支；status 取所有子步骤里最严重的状态，
     * results 保留每个子步骤的原始 result，steps 额外保留状态/原因/span 供报告定位。
     */
    private fun planRun(args: JsonObject): OpOutcome {
        val steps = args["steps"] as? JsonArray
            ?: throw BatchArgumentException("args.steps 必须是数组")
        val results = mutableListOf<JsonElement>()
        val details = mutableListOf<JsonElement>()
        val facts = mutableListOf<String>()
        var aggregate = OpStatus.OK
        steps.forEachIndexed { index, element ->
            val step = element as? JsonObject
                ?: throw BatchArgumentException("args.steps[$index] 必须是对象")
            val op = step.requiredString("op", "args.steps[$index]")
            val stepArgs = when (val raw = step["args"]) {
                null, is JsonNull -> JsonObject(emptyMap())
                is JsonObject -> raw
                else -> throw BatchArgumentException("args.steps[$index].args 必须是对象")
            }
            val outcome = execute(op, stepArgs)
            results += outcome.result
            facts += outcome.factIds
            details += buildJsonObject {
                put("op", op)
                put("status", outcome.status)
                put("reason", outcome.reason?.let(::JsonPrimitive) ?: JsonNull)
                put("span_id", outcome.spanId?.let(::JsonPrimitive) ?: JsonNull)
            }
            aggregate = mostSevere(aggregate, outcome.status)
        }
        val reason = when (aggregate) {
            OpStatus.ERROR -> "plan_step_error"
            OpStatus.CONFLICT -> "plan_step_conflict"
            OpStatus.UNKNOWN -> "plan_step_unknown"
            else -> null
        }
        return OpOutcome(
            status = aggregate,
            result = buildJsonObject {
                putJsonArray("results") { results.forEach { add(it) } }
                putJsonArray("steps") { details.forEach { add(it) } }
            },
            reason = reason,
            factIds = facts.distinct(),
        )
    }

    /** 状态优先级：error > conflict > unknown > ok；父计划不能把子步骤失败汇总成成功。 */
    private fun mostSevere(current: String, next: String): String {
        val rank = mapOf(OpStatus.OK to 0, OpStatus.UNKNOWN to 1, OpStatus.CONFLICT to 2, OpStatus.ERROR to 3)
        return if ((rank[next] ?: 3) > (rank[current] ?: 3)) next else current
    }

    // 以下为共享的类型解析、事实ID和提交工具；不包含按实体/题号的特判。

    private fun currentWorld(): BatchWorld? = store.load(audit.worldId)

    private fun missingWorld(): OpOutcome = unknown(
        "world_not_loaded",
        buildJsonObject { put("world_id", audit.worldId) },
    )

    private fun touch(world: BatchWorld) {
        world.revision += 1
        world.updatedAt = clock().toString()
        world.lastRunId = audit.events.runId
    }

    private fun ok(result: JsonObject, facts: List<String> = emptyList()) =
        OpOutcome(OpStatus.OK, result, null, facts)

    private fun unknown(
        reason: String,
        result: JsonObject = JsonObject(emptyMap()),
        facts: List<String> = emptyList(),
    ) = OpOutcome(OpStatus.UNKNOWN, result, reason, facts)

    private fun strictNumber(element: JsonElement?, context: String): BigDecimal? {
        if (element == null || element is JsonNull) return null
        val primitive = element as? JsonPrimitive
            ?: throw BatchArgumentException("$context 必须是JSON数字")
        require(!primitive.isString) { "$context 必须是JSON数字，不能是字符串" }
        return try {
            BigDecimal(primitive.content)
        } catch (_: NumberFormatException) {
            throw BatchArgumentException("$context 必须是JSON数字")
        }
    }

    private fun strictInt(element: JsonElement, context: String): Int {
        val number = strictNumber(element, context)
            ?: throw BatchArgumentException("$context 缺失")
        return try {
            number.intValueExact()
        } catch (_: ArithmeticException) {
            throw BatchArgumentException("$context 必须是整数")
        }
    }

    private fun stringArray(element: JsonElement, context: String): List<String> {
        val array = element as? JsonArray
            ?: throw BatchArgumentException("$context 必须是数组")
        return array.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive
                ?: throw BatchArgumentException("$context[$index] 必须是字符串")
            require(primitive.isString && primitive.content.isNotBlank()) {
                "$context[$index] 必须是非空字符串"
            }
            primitive.content
        }
    }

    private fun parseOptionalStringArray(element: JsonElement?, context: String): List<String> {
        if (element == null || element is JsonNull) return emptyList()
        return stringArray(element, context)
    }

    private fun parseDateOrNull(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun numberJson(value: BigDecimal): JsonPrimitive {
        val stripped = value.stripTrailingZeros()
        return if (stripped.scale() <= 0) {
            try {
                JsonPrimitive(stripped.longValueExact())
            } catch (_: ArithmeticException) {
                JsonPrimitive(stripped.toDouble())
            }
        } else {
            JsonPrimitive(stripped.toDouble())
        }
    }

    private fun quantity(element: JsonObject, context: String): Quantity? {
        val valueElement = element["value"] ?: return null
        val unitElement = element["unit"] ?: return null
        val unitPrimitive = unitElement as? JsonPrimitive
            ?: throw BatchArgumentException("$context.unit 必须是字符串")
        if (!unitPrimitive.isString || unitPrimitive.content.isBlank()) return null
        val value = strictNumber(valueElement, "$context.value") ?: return null
        return Quantity(value, unitPrimitive.content)
    }

    private fun objectFact(id: String): String = "object:$id"

    private fun attributeFact(id: String, key: String): String = "attr:$id:$key"

    private fun categoryFact(id: String, category: String): String = "category:$id:$category"

    private fun relationFact(id: String): String = "relation:$id"

    private fun claimFact(id: String): String = "claim:$id"

    private fun policyFact(id: String): String = "policy:$id"

    private fun storyFact(id: String): String = "story:$id"

    private data class Quantity(val value: BigDecimal, val unit: String)

    private data class Condition(val key: String, val value: String)

    companion object {
        // 只允许公布句式；值可以包含空格，但对象ID和属性名不能含空白。
        private val SENTENCE_PATTERN = Regex("^([^\\s]+)\\s+的\\s+([^\\s]+)\\s+是\\s+(.*)$")

        /** op 到规则 ID 的唯一映射；BatchCli 和报告使用同一份映射语义。 */
        fun ruleIdFor(op: String): String? = when (op) {
            "world.load" -> RuleIds.WORLD_LOAD
            "object.name", "object.findName", "object.same" -> RuleIds.OBJECT_IDENTITY
            "attr.get", "attr.find", "attr.same", "attr.teach" -> RuleIds.ATTRIBUTE
            "quantity.compare" -> RuleIds.QUANTITY_COMPARE
            "category.members" -> RuleIds.CATEGORY_MEMBERS
            "filter.all" -> RuleIds.FILTER_ALL
            "relation.query", "relation.set" -> RuleIds.RELATION
            "actions.run", "plan.run" -> RuleIds.ACTION_SEQUENCE
            "count", "pair" -> RuleIds.COUNT_PAIR
            "count.compare" -> RuleIds.COUNT_COMPARE
            "arithmetic" -> RuleIds.ARITHMETIC
            "pattern.next" -> RuleIds.PATTERN_NEXT
            "time.compare", "time.relative" -> RuleIds.TIME_ORDER
            "observation.compare" -> RuleIds.OBSERVATION_COMPARE
            "sentence.parse", "sentence.render" -> RuleIds.SENTENCE
            "story.query" -> RuleIds.STORY
            "reference.resolve" -> RuleIds.REFERENCE
            "policy.decide" -> RuleIds.POLICY
            "turn.next" -> RuleIds.TURN
            "compose" -> RuleIds.COMPOSE
            "claim.add", "claim.evaluate" -> RuleIds.CLAIM
            "claim.retract" -> RuleIds.RETRACT
            "missing.ask" -> RuleIds.MISSING_ASK
            else -> null
        }
    }
}
