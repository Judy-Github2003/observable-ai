// 本文件新增实现：DeepSeek Harness，2026-11-04。
// 创建原因：提供唯一的批量入口，逐行独立处理请求、写结构化结果和全链路事件，并按 world_id 持久化状态。
// 本文件不创建教师、不读取 expected，也不把未执行的操作写成成功。
package logic

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * `--batch <requests.jsonl> --batch-out <directory>` 的执行器。
 *
 * 每个请求使用独立 run_id/Events，错误也输出一行 JSON 并继续下一个请求；世界状态保存在
 * directory/state，文件名是 world_id 的 SHA-256，因此请求里的 world_id 不能穿越目录。
 */
object BatchCli {
    /**
     * 正式入口：先写出结果文件，再把每行 JSON 打到 stdout。
     * 批量请求内部的业务错误不改变进程退出码；只有批级配置/文件故障才返回 1。
     */
    fun run(requestsPath: Path, outputDir: Path): Int = try {
        val results = execute(requestsPath, outputDir)
        results.forEach { println(it.toString()) }
        0
    } catch (error: Exception) {
        System.err.println("批量运行失败：${redact(error.message ?: error.javaClass.simpleName)}")
        1
    }

    /**
     * 可测试的批量执行核心：返回每行结果对象，同时写入 results.jsonl。
     *
     * catalogPath 为空时按 RuleCatalog 的默认搜索规则找 rules.json；测试可显式传入同一份文件。
     */
    fun execute(
        requestsPath: Path,
        outputDir: Path,
        catalogPath: Path? = null,
    ): List<JsonObject> {        require(Files.isRegularFile(requestsPath)) {
            "请求文件不存在：${requestsPath.toAbsolutePath()}"
        }
        Files.createDirectories(outputDir)
        val eventsPath = outputDir.resolve("events.jsonl")
        val resultsPath = outputDir.resolve("results.jsonl")
        // 本次运行的结果文件从头写；历史 events 保留追加，便于保留失败证据。
        Files.writeString(resultsPath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        val batchRunId = UUID.randomUUID().toString()
        val batchEvents = Events(eventsPath, runId = batchRunId, studentVersion = "BATCH-V1")
        val catalog = catalogPath?.let { RuleCatalog.load(it) } ?: RuleCatalog.loadDefault()
        catalog.verifyRequired()
        val store = BatchStateStore(outputDir.resolve("state"))
        val manifest = readAuditManifest()
        val startedAt = Instant.now().toString()
        batchEvents.record(
            type = "batch_started",
            actor = "harness",
            function = "BatchCli.execute",
            description = "批量运行开始",
            phase = "start",
        ) {
            put("lesson_batch", "preschool-001..024")
            put("requests_file", requestsPath.toAbsolutePath().normalize().toString())
            put("catalog_path", catalog.catalogPath?.toAbsolutePath()?.normalize()?.toString() ?: "")
            put("catalog_sha256", catalog.sha256)
            put("catalog_version", catalog.catalogVersion)
            put("audit_manifest", manifest)
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }

        val results = mutableListOf<JsonObject>()
        val statusCounts = linkedMapOf(
            OpStatus.OK to 0, OpStatus.UNKNOWN to 0, OpStatus.CONFLICT to 0, OpStatus.ERROR to 0,
        )
        Files.newBufferedReader(requestsPath, Charsets.UTF_8).use { reader ->
            var lineNumber = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                val result = processLine(line, lineNumber, eventsPath, catalog, store)
                results += result
                val status = result["status"]?.jsonPrimitive?.content ?: OpStatus.ERROR
                statusCounts[status] = (statusCounts[status] ?: 0) + 1
                Files.writeString(
                    resultsPath,
                    result.toString() + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
        }

        val completedAt = Instant.now().toString()
        batchEvents.record(
            type = "batch_completed",
            actor = "harness",
            function = "BatchCli.execute",
            description = "批量运行完成",
            phase = "success",
        ) {
            put("request_count", results.size)
            putJsonObject("status_counts") { statusCounts.forEach { (k, v) -> put(k, v) } }
            put("catalog_sha256", catalog.sha256)
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }
        val metadata = buildJsonObject {
            put("batch_run_id", batchRunId)
            put("started_at", startedAt)
            put("completed_at", completedAt)
            put("requests_file", requestsPath.toAbsolutePath().normalize().toString())
            put("results_file", resultsPath.toAbsolutePath().normalize().toString())
            put("events_file", eventsPath.toAbsolutePath().normalize().toString())
            put("state_directory", outputDir.resolve("state").toAbsolutePath().normalize().toString())
            put("request_count", results.size)
            putJsonObject("status_counts") { statusCounts.forEach { (k, v) -> put(k, v) } }
            put("catalog_path", catalog.catalogPath?.toAbsolutePath()?.normalize()?.toString() ?: "")
            put("catalog_sha256", catalog.sha256)
            put("catalog_version", catalog.catalogVersion)
            put("audit_manifest", manifest)
            put("teacher_invocation_count", 0)
            put("model_call_count", 0)
        }
        Files.writeString(outputDir.resolve("batch-metadata.json"), metadata.toString())
        return results
    }

    /** 一行请求的完整处理；任何异常都收敛成结构化 error 并保留 request_failed。 */
    private fun processLine(
        line: String,
        lineNumber: Int,
        eventsPath: Path,
        catalog: RuleCatalog,
        store: BatchStateStore,
    ): JsonObject {
        val requestEvents = Events(eventsPath, runId = UUID.randomUUID().toString(), studentVersion = "BATCH-V1")
        val parsed = try {
            if (line.isBlank()) null else Json.parseToJsonElement(line) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        val taskId = parsed?.stringOrEmpty("task_id") ?: ""
        val lessonId = parsed?.stringOrEmpty("lesson_id") ?: ""
        val worldId = parsed?.stringOrEmpty("world_id") ?: ""
        val op = parsed?.stringOrEmpty("op") ?: ""
        val audit = AuditTrail(
            events = requestEvents,
            catalog = catalog,
            taskId = taskId,
            lessonId = lessonId,
            worldId = worldId,
        )
        if (parsed == null) {
            audit.beginRequest(null, null)
            audit.failRequest("", "invalid_json", "第 $lineNumber 行不是 JSON 对象")
            return outerResult(taskId, lessonId, worldId, "", OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("error_code", "invalid_json") },
                "invalid_json",
            ), audit)
        }

        val argsElement = parsed["args"]
        audit.beginRequest(op, argsElement)
        if (op.isBlank()) {
            audit.failRequest("", "missing_op", "请求缺少 op")
            return outerResult(taskId, lessonId, worldId, "", OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("error_code", "missing_op") },
                "missing_op",
            ), audit)
        }
        if (worldId.isBlank()) {
            // 缺少 world 是未知而不是程序故障；仍写出完整请求链。
            val outcome = OpOutcome(
                OpStatus.UNKNOWN,
                buildJsonObject { put("world_id", worldId) },
                "world_not_provided",
            )
            audit.completeRequest(op, outcome)
            return outerResult(taskId, lessonId, worldId, op, outcome, audit)
        }
        val args = when (val raw = argsElement) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> {
                audit.failRequest(op, "invalid_argument", "args 必须是 JSON 对象")
                return outerResult(taskId, lessonId, worldId, op, OpOutcome(
                    OpStatus.ERROR,
                    buildJsonObject { put("error_code", "invalid_argument") },
                    "invalid_argument",
                ), audit)
            }
        }
        if (BatchStudent.ruleIdFor(op) == null) {
            audit.failRequest(op, "unsupported_op", "不支持的 op：$op")
            return outerResult(taskId, lessonId, worldId, op, OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("op", op) },
                "unsupported_op",
            ), audit)
        }

        return try {
            val student = BatchStudent(store, audit)
            val outcome = student.execute(op, args)
            if (outcome.status == OpStatus.ERROR) {
                // 规则层已写 rule_error；请求层用 failed 收尾，避免把执行故障写成 completed。
                audit.failRequest(op, outcome.reason ?: "operation_error", null)
            } else {
                audit.completeRequest(op, outcome)
            }
            outerResult(taskId, lessonId, worldId, op, outcome, audit)
        } catch (error: BatchArgumentException) {
            audit.failRequest(op, "invalid_argument", error.message)
            outerResult(taskId, lessonId, worldId, op, OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("error_code", "invalid_argument") },
                "invalid_argument",
            ), audit)
        } catch (error: IllegalArgumentException) {
            // 材料/状态校验里的 require 也属于参数错误，不能让报告误判成内部崩溃。
            audit.failRequest(op, "invalid_argument", error.message)
            outerResult(taskId, lessonId, worldId, op, OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("error_code", "invalid_argument") },
                "invalid_argument",
            ), audit)
        } catch (error: java.io.IOException) {
            audit.failRequest(op, "storage_error", error.message)
            outerResult(taskId, lessonId, worldId, op, OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("error_code", "storage_error") },
                "storage_error",
            ), audit)
        } catch (error: IllegalStateException) {
            audit.failRequest(op, "state_error", error.message)
            outerResult(taskId, lessonId, worldId, op, OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("error_code", "state_error") },
                "state_error",
            ), audit)
        } catch (error: Exception) {
            audit.failRequest(op, "internal_error", error.message)
            outerResult(taskId, lessonId, worldId, op, OpOutcome(
                OpStatus.ERROR,
                buildJsonObject { put("error_code", "internal_error") },
                "internal_error",
            ), audit)
        }
    }

    /** 固定外层字段；reason 为空时输出 JSON null，调用方可区分“无原因”和空字符串。 */
    private fun outerResult(
        taskId: String,
        lessonId: String,
        worldId: String,
        op: String,
        outcome: OpOutcome,
        audit: AuditTrail,
    ): JsonObject = buildJsonObject {
        put("task_id", taskId)
        put("lesson_id", lessonId)
        put("world_id", worldId)
        put("op", op)
        put("status", outcome.status)
        put("result", outcome.result)
        put("reason", outcome.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("run_id", audit.events.runId)
        putJsonArray("trace_ids") { audit.traceIds.forEach { add(it) } }
        put("teacher_invocation_count", 0)
        put("model_call_count", 0)
    }

    /**
     * 读取宿主 manifest；未提供或不可读时显式记录 unverified。
     * 本函数不推断代码已核验，也不把路径内容写成“已验证”。
     */
    private fun readAuditManifest(): JsonObject {
        val configured = System.getenv("LOGIC_AUDIT_MANIFEST")?.takeIf { it.isNotBlank() }
            ?: return buildJsonObject {
                put("status", "unverified")
                put("reason", "LOGIC_AUDIT_MANIFEST 未设置")
            }
        val text = try {
            if (configured.trimStart().startsWith("{")) {
                configured
            } else {
                Files.readString(Path.of(configured))
            }
        } catch (_: Exception) {
            return buildJsonObject {
                put("status", "unverified")
                put("reason", "manifest 不可读")
            }
        }
        return try {
            val parsed = Json.parseToJsonElement(text)
            if (parsed is JsonObject) {
                buildJsonObject {
                    put("status", "provided")
                    put("manifest", parsed)
                }
            } else {
                buildJsonObject {
                    put("status", "unverified")
                    put("reason", "manifest 不是 JSON 对象")
                }
            }
        } catch (_: IllegalArgumentException) {
            buildJsonObject {
                put("status", "unverified")
                put("reason", "manifest 不是合法 JSON")
            }
        }
    }

    private fun JsonObject.stringOrEmpty(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return if (primitive.isString) primitive.content else ""
    }
}
