// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：实现学前第二课“颜色与简单分组”的固定命令入口，说明见 experiments/lessons/preschool-002/lesson.md。
// 本课只处理显式对象 ID、属性查询/分组/比较和显式补教，不调用教师、不联网、不把缺失属性当成否定。
package logic

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.sql.SQLException

/**
 * 第二课的固定命令执行器。
 *
 * 命令形式统一为“动词＋属性名＋参数”，属性名只是数据参数：
 * 查 <属性名> <对象ID>、找 <属性名> <属性值>、
 * 比较 <属性名> <对象ID1> <对象ID2>、教 <属性名> <对象ID> <属性值>。
 * 因此后续“大小”“形状”可复用同一执行路径，不需要新增按属性名硬编码的分支。
 */
class LessonTwo(
    // 第二课独立的对象与属性仓库，负责持久化和精确匹配。
    private val memory: AttributeMemory,
    // 训练设施日志；开始、成功、未知、参数错误和数据库错误分别留痕。
    private val events: Events,
) {
    /**
     * 执行一条固定命令并返回紧凑 JSON。
     *
     * 参数错误统一抛 IllegalArgumentException，数据库错误原样抛 SQLException；
     * unknown 只表示“课程记忆中没有记录”，不表示现实对象或属性不存在。
     */
    fun execute(words: List<String>): String {
        // 动词只用于日志 actor：导入/教学由 harness 发起，查询由 student 发起。
        val operation = words.firstOrNull()
        val actor = actorFor(operation)
        events.record(
            type = "lesson_input",
            actor = actor,
            function = "LessonTwo.execute",
            description = "收到第2课命令：${words.joinToString(" ")}",
            phase = "info",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("operation", operation ?: "")
            putJsonArray("arguments") { words.drop(1).forEach { add(it) } }
            // 第二课全部本地执行，输入事件明确记录未调用教师或模型。
            put("teacher_invocation_count", 0)
        }
        step("LessonTwo.execute", "开始执行第2课命令", "start", actor)

        try {
            require(words.isNotEmpty()) { "课程命令不能为空。" }
            val command = words[0]
            return when (command) {
                "导入" -> importCommand(words)
                "教" -> teachCommand(words)
                "查" -> lookupCommand(words)
                "找" -> findCommand(words)
                "比较" -> compareCommand(words)
                else -> throw IllegalArgumentException("未知第2课命令：$command")
            }
        } catch (error: IllegalArgumentException) {
            // 参数或材料错误统一归执行入口，避免把尚未调用的存储函数记成已执行失败。
            logFailure("LessonTwo.execute", "INVALID_ARGUMENT", "第2课命令失败：参数或材料错误")
            throw error
        } catch (error: SQLException) {
            // 数据库故障不能吞成 unknown，也不以“没有记录”掩盖存储错误。
            logFailure(functionFor(operation), "ATTRIBUTE_MEMORY_ERROR", "第2课命令失败：属性记忆数据库错误")
            throw error
        }
    }

    /** 导入命令：读取材料并批量写入对象和属性；成功 JSON 报告实际保存内容。 */
    private fun importCommand(words: List<String>): String {
        require(words.size == 2) { "导入命令格式：导入 <材料文件路径>" }
        require(words[1].isNotBlank()) { "材料文件路径不能为空。" }
        val materialPath = Path.of(words[1])
        step("AttributeMemory.importMaterial", "开始读取并校验材料中的对象与属性", "start", "harness")
        // 返回时对象和属性已在同一事务中提交；此处之后才记录成功。
        val report = memory.importMaterial(materialPath, events.runId)
        val attributesByObject = report.attributes.groupBy { it.objectId }
        events.record(
            type = "attribute_material_imported",
            actor = "harness",
            function = "AttributeMemory.importMaterial",
            description = "材料导入事务已提交",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("source", report.source)
            put("source_run_id", events.runId)
            put("saved_count", report.objects.size)
            put("attribute_count", report.attributes.size)
            putJsonArray("object_ids") { report.objects.forEach { add(it.objectId) } }
            putJsonArray("objects") {
                report.objects.forEach { add(objectJson(it, attributesByObject[it.objectId].orEmpty())) }
            }
            putJsonArray("attributes") { report.attributes.forEach { add(attributeJson(it)) } }
            put("teacher_invocation_count", 0)
        }
        step(
            "AttributeMemory.importMaterial",
            "材料导入事务已提交，保存 ${report.objects.size} 个对象、${report.attributes.size} 条属性",
            "success",
            "harness",
        )
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("operation", "导入")
            put("source", report.source)
            put("source_run_id", events.runId)
            put("saved_count", report.objects.size)
            put("attribute_count", report.attributes.size)
            putJsonArray("object_ids") { report.objects.forEach { add(it.objectId) } }
            putJsonArray("objects") {
                report.objects.forEach { add(objectJson(it, attributesByObject[it.objectId].orEmpty())) }
            }
            putJsonArray("attributes") { report.attributes.forEach { add(attributeJson(it)) } }
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /**
     * 显式教学命令：给已知对象补一个属性值。
     *
     * 与查询分开记录，来源写 explicit_teaching；E 的黄色只能来自这里，
     * 不能在导入材料、默认值或 unknown 分支中预置。
     */
    private fun teachCommand(words: List<String>): String {
        require(words.size == 4) { "教命令格式：教 <属性名> <对象ID> <属性值>" }
        // 参数顺序固定为属性名、对象 ID、属性值，避免把“教 颜色 A 红色”误读为对象名教学。
        val attributeName = words[1]
        val objectId = words[2]
        val attributeValue = words[3]
        require(attributeName.isNotBlank()) { "属性名不能为空。" }
        require(objectId.isNotBlank()) { "对象ID不能为空。" }
        require(attributeValue.isNotBlank()) { "属性值不能为空。" }
        step("AttributeMemory.teachAttribute", "开始保存显式教学属性", "start", "harness")
        val saved = memory.teachAttribute(objectId, attributeName, attributeValue, events.runId)
        events.record(
            type = "attribute_taught",
            actor = "harness",
            function = "AttributeMemory.teachAttribute",
            description = "显式教学属性事务已提交",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("object_id", saved.objectId)
            put("attribute_name", saved.attributeName)
            put("attribute_value", saved.attributeValue)
            put("source", saved.source)
            put("source_run_id", saved.sourceRunId)
            put("recorded_at", saved.recordedAt)
            put("attribute_status", saved.status)
            put("teacher_invocation_count", 0)
        }
        step(
            "AttributeMemory.teachAttribute",
            "显式教学已保存：${saved.objectId} 的 ${saved.attributeName}=${saved.attributeValue}",
            "success",
            "harness",
        )
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("operation", "教")
            put("saved_count", 1)
            put("object_id", saved.objectId)
            put("attribute_name", saved.attributeName)
            put("attribute_value", saved.attributeValue)
            put("source", saved.source)
            put("source_run_id", saved.sourceRunId)
            put("recorded_at", saved.recordedAt)
            put("attribute_status", saved.status)
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /**
     * 属性查询命令：按对象 ID 和属性名读取。
     *
     * 对象未知与对象已知但属性缺失是两种不同 unknown：
     * 前者是 object_not_observed，后者是 attribute_not_observed，都不能猜值或落到“假”。
     */
    private fun lookupCommand(words: List<String>): String {
        require(words.size == 3) { "查命令格式：查 <属性名> <对象ID>" }
        val attributeName = words[1]
        val objectId = words[2]
        require(attributeName.isNotBlank()) { "属性名不能为空。" }
        require(objectId.isNotBlank()) { "对象ID不能为空。" }

        step("AttributeMemory.findObject", "开始按对象ID读取属性查询对象", "start", "student")
        val foundObject = memory.findObject(objectId)
        if (foundObject == null) {
            // 只说明该 ID 没有对象记录，不推断现实中是否存在该对象。
            events.record(
                type = "attribute_lookup",
                actor = "student",
                function = "AttributeMemory.findObject",
                description = "属性查询的对象ID未在对象记忆中出现",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("object_id", objectId)
                put("attribute_name", attributeName)
                put("found", false)
                put("teacher_invocation_count", 0)
            }
            step("AttributeMemory.findObject", "对象 $objectId 未出现，属性查询返回 unknown", "info", "student")
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("operation", "查")
                put("reason", "object_not_observed")
                put("object_id", objectId)
                put("attribute_name", attributeName)
                put("teacher_invocation_count", 0)
            }.toString()
        }

        step("AttributeMemory.findAttribute", "开始按对象ID和属性名精确查询属性值", "start", "student")
        val foundAttribute = memory.findAttribute(objectId, attributeName)
        if (foundAttribute == null) {
            // 对象已知但该属性没有记录：unknown 表示“尚未教过”，不是“没有这个属性”。
            events.record(
                type = "attribute_lookup",
                actor = "student",
                function = "AttributeMemory.findAttribute",
                description = "对象已知但属性未在属性记忆中记录",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("object_id", objectId)
                put("name", foundObject.name)
                put("attribute_name", attributeName)
                put("found", false)
                put("teacher_invocation_count", 0)
            }
            step(
                "AttributeMemory.findAttribute",
                "对象 $objectId=${foundObject.name} 的 $attributeName 未记录，返回 unknown",
                "info",
                "student",
            )
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("operation", "查")
                put("reason", "attribute_not_observed")
                put("object_id", objectId)
                put("name", foundObject.name)
                put("attribute_name", attributeName)
                put("teacher_invocation_count", 0)
            }.toString()
        }

        events.record(
            type = "attribute_lookup",
            actor = "student",
            function = "AttributeMemory.findAttribute",
            description = "对象属性查询命中属性记忆",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("object_id", foundObject.objectId)
            put("name", foundObject.name)
            put("attribute_name", foundAttribute.attributeName)
            put("attribute_value", foundAttribute.attributeValue)
            put("source", foundAttribute.source)
            put("source_run_id", foundAttribute.sourceRunId)
            put("recorded_at", foundAttribute.recordedAt)
            put("attribute_status", foundAttribute.status)
            put("teacher_invocation_count", 0)
        }
        step(
            "AttributeMemory.findAttribute",
            "属性命中：${foundObject.objectId}=${foundObject.name} 的 " +
                "${foundAttribute.attributeName}=${foundAttribute.attributeValue}",
            "success",
            "student",
        )
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("operation", "查")
            put("object_id", foundObject.objectId)
            put("name", foundObject.name)
            put("attribute_name", foundAttribute.attributeName)
            put("attribute_value", foundAttribute.attributeValue)
            put("source", foundAttribute.source)
            put("source_run_id", foundAttribute.sourceRunId)
            put("recorded_at", foundAttribute.recordedAt)
            put("attribute_status", foundAttribute.status)
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /**
     * 分组查询命令：按属性名和值找出全部已记录对象。
     *
     * 空结果返回 attribute_value_not_observed，保留“尚未记录”的未知语义；
     * 不能把空列表说成“没有对象具有该值”，因为其他对象的属性可能也缺失。
     */
    private fun findCommand(words: List<String>): String {
        require(words.size == 3) { "找命令格式：找 <属性名> <属性值>" }
        val attributeName = words[1]
        val attributeValue = words[2]
        require(attributeName.isNotBlank()) { "属性名不能为空。" }
        require(attributeValue.isNotBlank()) { "属性值不能为空。" }

        step("AttributeMemory.findByAttributeValue", "开始按属性名和属性值反查对象", "start", "student")
        val matches = memory.findByAttributeValue(attributeName, attributeValue)
        val objectIds = matches.map { it.objectId }
        if (matches.isEmpty()) {
            events.record(
                type = "attribute_search",
                actor = "student",
                function = "AttributeMemory.findByAttributeValue",
                description = "属性值未在属性记忆中命中",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("attribute_name", attributeName)
                put("attribute_value", attributeValue)
                putJsonArray("object_ids") { }
                put("match_count", 0)
                put("teacher_invocation_count", 0)
            }
            step(
                "AttributeMemory.findByAttributeValue",
                "$attributeName=$attributeValue 未命中，返回 unknown（不表示否定）",
                "info",
                "student",
            )
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("operation", "找")
                put("reason", "attribute_value_not_observed")
                put("attribute_name", attributeName)
                put("attribute_value", attributeValue)
                putJsonArray("object_ids") { }
                put("match_count", 0)
                put("teacher_invocation_count", 0)
            }.toString()
        }

        events.record(
            type = "attribute_search",
            actor = "student",
            function = "AttributeMemory.findByAttributeValue",
            description = "属性分组查询命中属性记忆",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("attribute_name", attributeName)
            put("attribute_value", attributeValue)
            putJsonArray("object_ids") { objectIds.forEach { add(it) } }
            put("match_count", matches.size)
            put("teacher_invocation_count", 0)
        }
        step(
            "AttributeMemory.findByAttributeValue",
            "$attributeName=$attributeValue 命中 ${matches.size} 个对象：${objectIds.joinToString()}",
            "success",
            "student",
        )
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("operation", "找")
            put("attribute_name", attributeName)
            put("attribute_value", attributeValue)
            putJsonArray("object_ids") { objectIds.forEach { add(it) } }
            putJsonArray("objects") { matches.forEach { add(matchJson(it)) } }
            put("match_count", matches.size)
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /**
     * 属性值比较命令：分别读取两个已知对象同一属性的值。
     *
     * 对象缺失或属性缺失都返回 unknown，不把缺失值当成不同值；
     * same_value 只表示两条属性记录的值相同，与对象身份、名称是否相同无关。
     */
    private fun compareCommand(words: List<String>): String {
        require(words.size == 4) { "比较命令格式：比较 <属性名> <对象ID1> <对象ID2>" }
        val attributeName = words[1]
        val objectId1 = words[2]
        val objectId2 = words[3]
        require(attributeName.isNotBlank()) { "属性名不能为空。" }
        require(objectId1.isNotBlank()) { "对象ID1不能为空。" }
        require(objectId2.isNotBlank()) { "对象ID2不能为空。" }

        step("LessonTwo.compareCommand", "开始比较两个对象的同一属性值", "start", "student")
        step("AttributeMemory.findObject", "开始读取待比较对象", "start", "student")
        // 两个 ID 相同只读一次；读取不会创建对象或属性。
        val firstObject = memory.findObject(objectId1)
        val secondObject = if (objectId1 == objectId2) firstObject else memory.findObject(objectId2)
        val missingObjects = buildList {
            if (firstObject == null) add(objectId1)
            if (secondObject == null && objectId2 != objectId1) add(objectId2)
        }
        if (missingObjects.isNotEmpty()) {
            events.record(
                type = "attribute_compare",
                actor = "student",
                function = "LessonTwo.compareCommand",
                description = "比较请求包含未观察对象，返回 unknown",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("attribute_name", attributeName)
                put("object_id1", objectId1)
                put("object_id2", objectId2)
                putJsonArray("missing_object_ids") { missingObjects.forEach { add(it) } }
                put("teacher_invocation_count", 0)
            }
            step("LessonTwo.compareCommand", "比较请求包含未观察对象，返回 unknown", "info", "student")
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("operation", "比较")
                put("reason", "object_not_observed")
                put("attribute_name", attributeName)
                put("object_id1", objectId1)
                put("object_id2", objectId2)
                putJsonArray("missing_object_ids") { missingObjects.forEach { add(it) } }
                put("teacher_invocation_count", 0)
            }.toString()
        }

        step("AttributeMemory.findAttribute", "开始读取两个对象的待比较属性", "start", "student")
        val firstAttribute = memory.findAttribute(objectId1, attributeName)
        val secondAttribute = if (objectId1 == objectId2) {
            firstAttribute
        } else {
            memory.findAttribute(objectId2, attributeName)
        }
        val missingAttributes = buildList {
            if (firstAttribute == null) add(objectId1)
            if (secondAttribute == null && objectId2 != objectId1) add(objectId2)
        }
        if (missingAttributes.isNotEmpty()) {
            // 一侧属性缺失时不能比较出 same_value=false；unknown 保留“尚未记录”的状态。
            events.record(
                type = "attribute_compare",
                actor = "student",
                function = "LessonTwo.compareCommand",
                description = "比较请求包含未记录属性，返回 unknown",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("attribute_name", attributeName)
                put("object_id1", objectId1)
                put("object_id2", objectId2)
                putJsonArray("missing_attribute_object_ids") { missingAttributes.forEach { add(it) } }
                put("teacher_invocation_count", 0)
            }
            step(
                "LessonTwo.compareCommand",
                "比较请求包含未记录属性，返回 unknown，缺失对象：${missingAttributes.joinToString()}",
                "info",
                "student",
            )
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("operation", "比较")
                put("reason", "attribute_not_observed")
                put("attribute_name", attributeName)
                put("object_id1", objectId1)
                put("object_id2", objectId2)
                putJsonArray("missing_attribute_object_ids") { missingAttributes.forEach { add(it) } }
                put("teacher_invocation_count", 0)
            }.toString()
        }

        // missing 为空保证两侧对象与属性都存在；requireNotNull 只防御不可能路径。
        val leftObject = requireNotNull(firstObject)
        val rightObject = requireNotNull(secondObject)
        val left = requireNotNull(firstAttribute)
        val right = requireNotNull(secondAttribute)
        val sameValue = left.attributeValue == right.attributeValue
        events.record(
            type = "attribute_compare",
            actor = "student",
            function = "LessonTwo.compareCommand",
            description = "属性值比较完成",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("attribute_name", attributeName)
            put("object_id1", leftObject.objectId)
            put("object_id2", rightObject.objectId)
            put("value1", left.attributeValue)
            put("value2", right.attributeValue)
            put("same_value", sameValue)
            put("teacher_invocation_count", 0)
        }
        step(
            "LessonTwo.compareCommand",
            "${leftObject.objectId} 的 $attributeName=${left.attributeValue} 与 " +
                "${rightObject.objectId} 的 $attributeName=${right.attributeValue} 比较完成：值相同=$sameValue",
            "success",
            "student",
        )
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("operation", "比较")
            put("attribute_name", attributeName)
            put("object_id1", leftObject.objectId)
            put("object_id2", rightObject.objectId)
            put("name1", leftObject.name)
            put("name2", rightObject.name)
            put("value1", left.attributeValue)
            put("value2", right.attributeValue)
            put("same_value", sameValue)
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /** 导入结果中的对象：名称和该对象当前已有属性一起输出，便于核对 E 没有颜色。 */
    private fun objectJson(record: LessonObject, attributes: List<ObjectAttribute>) = buildJsonObject {
        put("object_id", record.objectId)
        put("name", record.name)
        putJsonObject("attributes") {
            attributes.forEach { put(it.attributeName, it.attributeValue) }
        }
    }

    /** 一条属性记录的统一 JSON 结构，保留来源和当前状态。 */
    private fun attributeJson(record: ObjectAttribute) = buildJsonObject {
        put("object_id", record.objectId)
        put("attribute_name", record.attributeName)
        put("attribute_value", record.attributeValue)
        put("source", record.source)
        put("source_run_id", record.sourceRunId)
        put("recorded_at", record.recordedAt)
        put("attribute_status", record.status)
    }

    /** 分组命中项：对象名称与属性记录一起返回，但名称不参与属性匹配。 */
    private fun matchJson(match: AttributeMatch) = buildJsonObject {
        put("object_id", match.objectId)
        put("name", match.name)
        put("attribute_name", match.attribute.attributeName)
        put("attribute_value", match.attribute.attributeValue)
        put("source", match.attribute.source)
        put("source_run_id", match.attribute.sourceRunId)
        put("recorded_at", match.attribute.recordedAt)
        put("attribute_status", match.attribute.status)
    }

    /** 固定命令的 actor：导入/教学由 harness 提供，查询由 student 发起。 */
    private fun actorFor(operation: String?): String = when (operation) {
        "导入", "教" -> "harness"
        "查", "找", "比较" -> "student"
        else -> "harness"
    }

    /** 数据库异常发生在存储调用期间，指向对应操作；参数失败归 execute。 */
    private fun functionFor(operation: String?): String = when (operation) {
        "导入" -> "AttributeMemory.importMaterial"
        "教" -> "AttributeMemory.teachAttribute"
        "查" -> "AttributeMemory.findAttribute"
        "找" -> "AttributeMemory.findByAttributeValue"
        "比较" -> "LessonTwo.compareCommand"
        else -> "LessonTwo.execute"
    }

    /** 写一条 event_type=step 的事件和中文步骤；保留课程标识与零教师调用字段。 */
    private fun step(function: String, description: String, phase: String, actor: String) {
        events.record(
            type = "step",
            actor = actor,
            function = function,
            description = description,
            phase = phase,
        ) {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("teacher_invocation_count", 0)
        }
    }

    /** 失败日志尽力写入，但不允许日志异常覆盖原始参数错误或数据库异常。 */
    private fun logFailure(function: String, errorCode: String, description: String) {
        runCatching {
            events.record(
                type = "error",
                actor = "harness",
                function = function,
                description = description,
                phase = "failure",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
                put("error_code", errorCode)
                put("teacher_invocation_count", 0)
            }
        }
        runCatching { step(function, description, "failure", "harness") }
    }
}
