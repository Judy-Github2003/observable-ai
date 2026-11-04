// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：实现学前第一课“对象与名称”的固定命令入口，课程说明见 experiments/lessons/preschool-001/lesson.md；
// 本课只处理显式对象 ID、名称、反向查询和身份比较，不调用教师、不联网、不理解自然问句。
// 局部审计修改：Codex，2026-11-04；补充实际输入/结果与已提交对象来源，参数失败归命令入口，避免误记未调用的存储函数。
package logic

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.sql.SQLException

/**
 * 第一课的固定命令执行器。
 *
 * execute 只接受课程约定的五种精确命令，返回紧凑 JSON 供 Main 原样输出；
 * 所有执行信息写 Events，不向 stdout 打印。对象知识只来自材料或“教”命令。
 */
class LessonOne(
    // 本课独立的对象记忆仓库，负责持久化和精确匹配。
    private val objects: ObjectNames,
    // 训练设施日志；成功、未知、参数错误和数据库错误都留下可追踪步骤。
    private val events: Events,
) {
    /**
     * 执行一条固定命令并返回 JSON 字符串。
     *
     * 参数错误统一抛 IllegalArgumentException，数据库错误原样抛 SQLException；
     * unknown 只表示“对象记忆中没有记录”，不表示现实世界不存在该对象。
     */
    fun execute(words: List<String>): String {
        // 先识别动词，仅用于日志的 actor 区分：材料/教学属于 harness，查询属于 student。
        val operation = words.firstOrNull()
        val actor = actorFor(operation)
        // 输入事件记录动词和原始参数；对象名称和 ID 不做 trim 或任何改写。
        events.record(
            type = "lesson_input",
            actor = actor,
            function = "LessonOne.execute",
            // Events 统一对描述做单行化和脱敏；在中文日志中也能知道这次具体执行了什么。
            description = "收到第1课命令：${words.joinToString(" ")}",
            phase = "info",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("operation", operation ?: "")
            putJsonArray("arguments") { words.drop(1).forEach { add(it) } }
            // 本课绝不调用教师；输入事件也明确写 0，避免读者误以为发生过教师请求。
            put("teacher_invocation_count", 0)
        }
        step("LessonOne.execute", "开始执行第1课命令", "start", actor)

        try {
            // 空命令无法分派，属于参数错误；在 try 内抛出，失败步骤会被记录后原样上抛。
            require(words.isNotEmpty()) { "课程命令不能为空。" }
            val command = words[0]
            return when (command) {
                "导入" -> importCommand(words)
                "教" -> teachCommand(words)
                "查" -> lookupCommand(words)
                "找" -> findByNameCommand(words)
                "比较" -> compareCommand(words)
                else -> throw IllegalArgumentException("未知第1课命令：$command")
            }
        } catch (error: IllegalArgumentException) {
            // 参数或材料错误统一归执行入口；参数校验可能尚未调用存储，不能误报 ObjectNames 函数已经执行失败。
            logFailure("LessonOne.execute", "INVALID_ARGUMENT", "第1课命令失败：参数或材料错误")
            throw error
        } catch (error: SQLException) {
            // 数据库错误原样上抛，不吞成 unknown，也不以“没查到”掩盖存储故障。
            logFailure(functionFor(operation), "OBJECT_MEMORY_ERROR", "第1课命令失败：对象记忆数据库错误")
            throw error
        }
    }

    /** 导入命令：读取材料并批量写入；成功 JSON 报告实际保存的对象及来源。 */
    private fun importCommand(words: List<String>): String {
        require(words.size == 2) { "导入命令格式：导入 <材料文件路径>" }
        require(words[1].isNotBlank()) { "材料文件路径不能为空。" }
        // Path.of 保留调用方给出的路径字符串；文件读取由 ObjectNames 负责并转换为参数错误。
        val materialPath = Path.of(words[1])
        step("ObjectNames.importMaterial", "开始读取并校验材料中的全部对象", "start", "harness")
        // importMaterial 返回时事务已经提交；此处之后才能记录成功。
        val report = objects.importMaterial(materialPath, events.runId)
        events.record(
            type = "object_material_imported",
            actor = "harness",
            function = "ObjectNames.importMaterial",
            description = "材料导入事务已提交",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("source", report.source)
            put("source_run_id", events.runId)
            put("saved_count", report.objects.size)
            putJsonArray("object_ids") { report.objects.forEach { add(it.objectId) } }
            // 保留本次已提交的名称及来源；日后同 ID 被显式重教，也能从历史事件复核旧观察。
            putJsonArray("objects") { report.objects.forEach { add(namedObjectJson(it)) } }
            put("teacher_invocation_count", 0)
        }
        step(
            "ObjectNames.importMaterial",
            "材料导入事务已提交，保存 ${report.objects.size} 个对象：${report.objects.joinToString { "${it.objectId}=${it.name}" }}",
            "success",
            "harness",
        )
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("operation", "导入")
            // source 和 source_run_id 让调用方知道这批对象来自哪份材料、哪次运行。
            put("source", report.source)
            put("source_run_id", events.runId)
            put("saved_count", report.objects.size)
            putJsonArray("object_ids") { report.objects.forEach { add(it.objectId) } }
            putJsonArray("objects") { report.objects.forEach { add(namedObjectJson(it)) } }
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /** 教命令：显式保存一个对象 ID 与名称；已有 ID 会被更新为本次来源。 */
    private fun teachCommand(words: List<String>): String {
        require(words.size == 3) { "教命令格式：教 <对象ID> <名称>" }
        require(words[1].isNotBlank()) { "对象ID不能为空。" }
        require(words[2].isNotBlank()) { "名称不能为空。" }
        step("ObjectNames.teach", "开始保存显式教学对象", "start", "harness")
        // teach 返回时 upsert 事务已提交；这里才写成功事件，不提前声称已保存。
        val saved = objects.teach(words[1], words[2], events.runId)
        events.record(
            type = "object_taught",
            actor = "harness",
            function = "ObjectNames.teach",
            description = "显式教学对象事务已提交",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_ID)
            putNamedObjectFields(saved)
            put("teacher_invocation_count", 0)
        }
        step("ObjectNames.teach", "显式教学已保存：${saved.objectId}=${saved.name}", "success", "harness")
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("operation", "教")
            put("saved_count", 1)
            // 平铺字段便于直接读取；object 子对象同时保留完整来源，二者来自同一条已提交记录。
            putNamedObjectFields(saved)
            put("object", namedObjectJson(saved))
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /** 查命令：按对象 ID 精确读取；缺失时返回 object_not_observed，不猜名称。 */
    private fun lookupCommand(words: List<String>): String {
        require(words.size == 2) { "查命令格式：查 <对象ID>" }
        require(words[1].isNotBlank()) { "对象ID不能为空。" }
        step("ObjectNames.findById", "开始按对象ID精确查询", "start", "student")
        val found = objects.findById(words[1])
        if (found == null) {
            // 未命中只说明该 ID 没有被材料或教学写入，不推断现实对象是否存在。
            events.record(
                type = "object_lookup",
                actor = "student",
                function = "ObjectNames.findById",
                description = "对象ID未在对象记忆中出现",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_ID)
                put("object_id", words[1])
                put("found", false)
                put("teacher_invocation_count", 0)
            }
            step("ObjectNames.findById", "对象 ${words[1]} 未在对象记忆中出现，返回 unknown", "info", "student")
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_ID)
                put("operation", "查")
                put("reason", "object_not_observed")
                put("object_id", words[1])
                put("teacher_invocation_count", 0)
            }.toString()
        }
        // 命中时带回原始来源和状态；status 是整次响应，object_status 才是记录状态。
        events.record(
            type = "object_lookup",
            actor = "student",
            function = "ObjectNames.findById",
            description = "对象ID命中对象记忆",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("found", true)
            putNamedObjectFields(found)
            put("teacher_invocation_count", 0)
        }
        step("ObjectNames.findById", "对象记忆命中：${found.objectId}=${found.name}；来源=${found.source}", "success", "student")
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("operation", "查")
            putNamedObjectFields(found)
            put("object", namedObjectJson(found))
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /** 找命令：按名称反向查询，返回按 ID 排序的列表；同名对象全部保留。 */
    private fun findByNameCommand(words: List<String>): String {
        require(words.size == 2) { "找命令格式：找 <名称>" }
        require(words[1].isNotBlank()) { "名称不能为空。" }
        step("ObjectNames.findByName", "开始按名称精确反向查询", "start", "student")
        // SQL 已按 object_id 排序；这里沿用该稳定顺序，不再按名称或写入时间重排。
        val matches = objects.findByName(words[1])
        val objectIds = matches.map { it.objectId }
        if (matches.isEmpty()) {
            // 空结果只表示该名称尚未被观察；不说“现实世界没有这个事物”。
            events.record(
                type = "object_name_lookup",
                actor = "student",
                function = "ObjectNames.findByName",
                description = "名称未在对象记忆中出现",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_ID)
                put("name", words[1])
                putJsonArray("object_ids") { }
                put("teacher_invocation_count", 0)
            }
            step("ObjectNames.findByName", "名称未在对象记忆中出现，返回 unknown", "info", "student")
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_ID)
                put("operation", "找")
                put("reason", "name_not_observed")
                put("name", words[1])
                putJsonArray("object_ids") { }
                put("match_count", 0)
                put("teacher_invocation_count", 0)
            }.toString()
        }
        events.record(
            type = "object_name_lookup",
            actor = "student",
            function = "ObjectNames.findByName",
            description = "名称反向查询命中对象记忆",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("name", words[1])
            putJsonArray("object_ids") { objectIds.forEach { add(it) } }
            put("match_count", matches.size)
            put("teacher_invocation_count", 0)
        }
        step("ObjectNames.findByName", "名称 ${words[1]} 命中 ${matches.size} 个对象：${objectIds.joinToString()}", "success", "student")
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("operation", "找")
            put("name", words[1])
            putJsonArray("object_ids") { objectIds.forEach { add(it) } }
            put("match_count", matches.size)
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /**
     * 比较命令：身份只看对象 ID，名称相同与否单独判断。
     *
     * 任一侧未观察就返回 unknown，不自动创建或“认识”未知 ID；同一个未知 ID 自我比较也不例外。
     */
    private fun compareCommand(words: List<String>): String {
        require(words.size == 3) { "比较命令格式：比较 <对象ID1> <对象ID2>" }
        require(words[1].isNotBlank()) { "对象ID1不能为空。" }
        require(words[2].isNotBlank()) { "对象ID2不能为空。" }
        step("LessonOne.compareCommand", "开始比较两个对象ID", "start", "student")
        step("ObjectNames.findById", "开始按对象ID读取待比较对象", "start", "student")
        // 两个 ID 完全相同时只读一次；读取本身不会写入或创建对象。
        val first = objects.findById(words[1])
        val second = if (words[1] == words[2]) first else objects.findById(words[2])
        // 缺失列表按输入顺序去重，避免同一个未知 ID 因自我比较而重复出现。
        val missing = buildList {
            if (first == null) add(words[1])
            if (second == null && words[2] != words[1]) add(words[2])
        }
        if (missing.isNotEmpty()) {
            // 未知参与方不产生 same_object/same_name，避免把未知 ID 当成已认识对象。
            events.record(
                type = "object_compare",
                actor = "student",
                function = "LessonOne.compareCommand",
                description = "比较请求包含未观察对象，返回 unknown",
                phase = "info",
            ) {
                put("lesson_id", PRESCHOOL_LESSON_ID)
                put("object_id1", words[1])
                put("object_id2", words[2])
                putJsonArray("missing_object_ids") { missing.forEach { add(it) } }
                put("teacher_invocation_count", 0)
            }
            step("LessonOne.compareCommand", "比较请求包含未观察对象，返回 unknown", "info", "student")
            return buildJsonObject {
                put("status", "unknown")
                put("lesson_id", PRESCHOOL_LESSON_ID)
                put("operation", "比较")
                put("reason", "object_not_observed")
                putJsonArray("missing_object_ids") { missing.forEach { add(it) } }
                put("teacher_invocation_count", 0)
            }.toString()
        }
        // missing 为空保证两个查询都命中；requireNotNull 只作为不可能路径的防御。
        val left = requireNotNull(first)
        val right = requireNotNull(second)
        // same_object 比较 ID 本身；同名（same_name）不能推出同一个对象。
        val sameObject = left.objectId == right.objectId
        val sameName = left.name == right.name
        events.record(
            type = "object_compare",
            actor = "student",
            function = "LessonOne.compareCommand",
            description = "对象比较完成",
            phase = "success",
        ) {
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("object_id1", left.objectId)
            put("object_id2", right.objectId)
            put("same_object", sameObject)
            put("same_name", sameName)
            put("teacher_invocation_count", 0)
        }
        step(
            "LessonOne.compareCommand",
            "对象 ${left.objectId} 与 ${right.objectId} 比较完成：同一对象=$sameObject，同名=$sameName",
            "success",
            "student",
        )
        return buildJsonObject {
            put("status", "ok")
            put("lesson_id", PRESCHOOL_LESSON_ID)
            put("operation", "比较")
            put("object_id1", left.objectId)
            put("object_id2", right.objectId)
            put("same_object", sameObject)
            put("same_name", sameName)
            put("teacher_invocation_count", 0)
        }.toString()
    }

    /** 把一条对象记录序列化为统一的 JSON 结构，供导入、查询和教学输出复用。 */
    private fun namedObjectJson(record: NamedObject) = buildJsonObject {
        // ID 与名称分开输出：同名对象可以有不同 ID，不能从名称反推身份。
        put("object_id", record.objectId)
        put("name", record.name)
        put("source", record.source)
        put("source_run_id", record.sourceRunId)
        put("recorded_at", record.recordedAt)
        put("status", record.status)
    }

    /** 把对象记录的平铺字段写入外层 JSON 或事件；record.status 使用 object_status 避免覆盖响应 status。 */
    private fun JsonObjectBuilder.putNamedObjectFields(record: NamedObject) {
        put("object_id", record.objectId)
        put("name", record.name)
        put("source", record.source)
        put("source_run_id", record.sourceRunId)
        put("recorded_at", record.recordedAt)
        put("object_status", record.status)
    }

    /** 固定命令的 actor：导入/教学由 harness 提供，查询由 student 发起。 */
    private fun actorFor(operation: String?): String = when (operation) {
        "导入", "教" -> "harness"
        "查", "找", "比较" -> "student"
        // 未知或空命令仍属于 harness 发起的调用，不能误记为学生查询。
        else -> "harness"
    }

    /** 数据库异常发生在存储调用期间，指向对应操作；参数失败单独归 execute，避免声称未执行的函数失败。 */
    private fun functionFor(operation: String?): String = when (operation) {
        "导入" -> "ObjectNames.importMaterial"
        "教" -> "ObjectNames.teach"
        "查" -> "ObjectNames.findById"
        "找" -> "ObjectNames.findByName"
        "比较" -> "LessonOne.compareCommand"
        else -> "LessonOne.execute"
    }

    /**
     * 写一条 event_type=step 的事件和对应中文步骤日志。
     *
     * 这里用 record 而不是 Events.step，是为了让每条步骤也带上课程标识和
     * teacher_invocation_count=0；字段仍完全兼容原有 step 事件读取方式。
     */
    private fun step(function: String, description: String, phase: String, actor: String) {
        events.record(
            type = "step",
            actor = actor,
            function = function,
            description = description,
            phase = phase,
        ) {
            put("lesson_id", PRESCHOOL_LESSON_ID)
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
                put("lesson_id", PRESCHOOL_LESSON_ID)
                put("error_code", errorCode)
                put("teacher_invocation_count", 0)
            }
        }
        runCatching { step(function, description, "failure", "harness") }
    }
}
