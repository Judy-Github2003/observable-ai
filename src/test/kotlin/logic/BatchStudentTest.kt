// 本文件新增测试：DeepSeek Harness，2026-11-04。
// 创建原因：用一个连贯场景检查批量入口的导入、未知、筛选、数量、动作事务、证据冲突/撤销、
// 规则决策、轮次、组合、plan.run 复用、重启持久化、trace 与安全状态文件名；不调用教师或外部模型。
package logic

import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 学前批量核心场景；代码本身不为某个实体、课号或题号写答案分支。 */
class BatchStudentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `batch scenario keeps evidence unknown until taught and preserves audit chain across restart`() {
        val catalogPath = RuleCatalog.findDefaultPath()
        assertNotNull(catalogPath, "测试需要项目内 rules.json")
        val output = temp.root.toPath().resolve("batch-out")
        val worldId = "../../escape/world-A"
        val requests = temp.root.toPath().resolve("requests-1.jsonl")

        val world = buildJsonObject {
            putJsonArray("objects") {
                add(objectJson("A", "苹果", mapOf("颜色" to "红色", "形状" to "圆形", "风险" to "烫"),
                    listOf("水果")))
                add(objectJson("B", "苹果", mapOf("颜色" to "蓝色"), listOf("水果")))
                add(objectJson("C", "书", mapOf("大小" to "小"), emptyList()))
            }
            putJsonArray("relations") {
                add(buildJsonObject {
                    put("id", "rel1"); put("subject", "A"); put("predicate", "inside"); put("object", "box")
                })
            }
            putJsonArray("claims") { }
            putJsonArray("observations") {
                add(observation("obs1", mapOf("开关" to "关"), mapOf("灯" to "灭")))
                add(observation("obs2", mapOf("开关" to "开"), mapOf("灯" to "亮")))
            }
            putJsonArray("story") {
                add(story("s1", "甲", "take", "球", null, null))
                add(story("s2", "甲", "place", "球", null, "桌子"))
            }
            putJsonArray("policies") {
                add(buildJsonObject {
                    put("id", "p1")
                    putJsonObject("when") { put("风险", "烫") }
                    put("decision", "停下求助")
                })
            }
            putJsonArray("turns") { add("甲"); add("乙") }
        }

        val lines = listOf(
            request("t01", "preschool-003", worldId, "world.load",
                buildJsonObject { put("source", "fixture"); put("world", world) }),
            request("t02", "preschool-001", worldId, "object.same",
                buildJsonObject { put("left", "A"); put("right", "B") }),
            request("t03", "preschool-002", worldId, "attr.get",
                buildJsonObject { put("id", "B"); put("key", "颜色") }),
            request("t04", "preschool-006", worldId, "filter.all",
                buildJsonObject {
                    putJsonArray("conditions") {
                        add(buildJsonObject { put("key", "颜色"); put("value", "红色") })
                        add(buildJsonObject { put("key", "形状"); put("value", "圆形") })
                    }
                    putJsonArray("ids") { add("A"); add("B"); add("C") }
                }),
            request("t05", "preschool-004", worldId, "quantity.compare",
                buildJsonObject {
                    putJsonObject("left") { put("value", 3); put("unit", "格") }
                    putJsonObject("right") { put("value", 5); put("unit", "格") }
                }),
            request("t06", "preschool-004", worldId, "quantity.compare",
                buildJsonObject {
                    putJsonObject("left") { put("value", 3); put("unit", "格") }
                    putJsonObject("right") { put("value", 2); put("unit", "厘米") }
                }),
            request("t07", "preschool-007", worldId, "relation.query",
                buildJsonObject { put("subject", "A"); put("predicate", "inside") }),
            request("t08", "preschool-009", worldId, "actions.run",
                buildJsonObject {
                    putJsonArray("steps") {
                        add(buildJsonObject {
                            put("op", "attr.teach")
                            putJsonObject("args") { put("id", "B"); put("key", "颜色"); put("value", "黄色") }
                            putJsonArray("requires") {
                                add(buildJsonObject {
                                    put("id", "A"); put("key", "颜色"); put("value", "红色")
                                })
                            }
                        })
                    }
                }),
            request("t09", "preschool-002", worldId, "attr.get",
                buildJsonObject { put("id", "B"); put("key", "颜色") }),
            request("t10", "preschool-022", worldId, "claim.add",
                buildJsonObject {
                    putJsonObject("claim") {
                        put("id", "c1"); put("subject", "A"); put("predicate", "颜色"); put("value", "红色")
                        put("truth", true)
                    }
                }),
            request("t11", "preschool-022", worldId, "claim.add",
                buildJsonObject {
                    putJsonObject("claim") {
                        put("id", "c2"); put("subject", "A"); put("predicate", "颜色"); put("value", "红色")
                        put("truth", false)
                    }
                }),
            request("t12", "preschool-022", worldId, "claim.evaluate",
                buildJsonObject { put("subject", "A"); put("predicate", "颜色"); put("value", "红色") }),
            request("t13", "preschool-023", worldId, "claim.retract",
                buildJsonObject { put("id", "c2") }),
            request("t14", "preschool-022", worldId, "claim.evaluate",
                buildJsonObject { put("subject", "A"); put("predicate", "颜色"); put("value", "红色") }),
            request("t15", "preschool-019", worldId, "policy.decide",
                buildJsonObject { put("id", "A") }),
            request("t16", "preschool-020", worldId, "turn.next",
                buildJsonObject { put("current", "甲") }),
            request("t17", "preschool-021", worldId, "compose",
                buildJsonObject {
                    putJsonObject("shapes") { put("圆形", 2); put("方形", 1) }
                    putJsonArray("colors") { add("红色"); add("蓝色") }
                }),
            request("t18", "preschool-024", worldId, "plan.run",
                buildJsonObject {
                    putJsonArray("steps") {
                        add(buildJsonObject {
                            put("op", "attr.teach")
                            putJsonObject("args") { put("id", "C"); put("key", "大小"); put("value", "大") }
                        })
                        add(buildJsonObject {
                            put("op", "count")
                            putJsonObject("args") { putJsonArray("ids") { add("A"); add("B"); add("C") } }
                        })
                    }
                }),
            request("t19", "preschool-014", worldId, "time.compare",
                buildJsonObject { put("left", "2026-11-01"); put("right", "2026-11-05") }),
            request("t20", "preschool-015", worldId, "observation.compare",
                buildJsonObject { put("left_id", "obs1"); put("right_id", "obs2") }),
            request("t21", "preschool-016", worldId, "sentence.parse",
                buildJsonObject { put("text", "A 的 颜色 是 红色") }),
            request("t22", "preschool-016", worldId, "sentence.render",
                buildJsonObject { put("id", "A"); put("key", "颜色") }),
        )
        Files.writeString(requests, lines.joinToString("\n") + "\n")

        val results = BatchCli.execute(requests, output, catalogPath)
        assertEquals(22, results.size)
        // 导入是完整提交；随后对象同一是“不同ID但同名”，不能合并身份。
        assertEquals("ok", results[0].str("status"))
        assertEquals(3, results[0].getValue("result").jsonObject.int("object_count"))
        val same = results[1].getValue("result").jsonObject
        assertFalse(same.bool("same_object"))
        assertTrue(same.bool("same_name"))

        // B 有颜色不是缺失；这里检查“缺对象属性”的未知由后续筛选承担。
        assertEquals("ok", results[2].str("status"))
        assertEquals("蓝色", results[2].getValue("result").jsonObject.str("value"))

        // A 同时满足红且圆；B 已知不满足；C 两项都缺，进 unknown_ids，不能算满足也不能算排除。
        assertEquals(listOf("A"), results[3].getValue("result").jsonObject.strings("ids"))
        assertEquals(listOf("C"), results[3].getValue("result").jsonObject.strings("unknown_ids"))

        // 同单位比较给左减右；单位不同保持 unknown，不猜换算。
        assertEquals("less", results[4].getValue("result").jsonObject.str("comparison"))
        assertEquals(-2, results[4].getValue("result").jsonObject.int("difference"))
        assertEquals("unknown", results[5].str("status"))
        assertEquals("unit_mismatch", results[5].str("reason"))

        assertEquals(listOf("box"), results[6].getValue("result").jsonObject.strings("targets"))

        // 动作事务：前置条件满足才提交，B 的颜色由显式教学变为黄色。
        assertEquals("ok", results[7].str("status"))
        assertTrue(results[7].getValue("result").jsonObject.bool("committed"))
        assertEquals("黄色", results[8].getValue("result").jsonObject.str("value"))

        // 同时正反证据是冲突；撤销明确否定证据后只剩支持证据。
        assertEquals("conflict", results[11].str("status"))
        assertEquals("conflict", results[11].getValue("result").jsonObject.str("truth"))
        assertEquals(listOf("c2"), results[12].getValue("result").jsonObject.strings("retracted_ids"))
        assertTrue(results[13].getValue("result").jsonObject.bool("truth"))

        // 规则决策、轮次、组合与时间都不读系统当前日期或审美偏好。
        assertEquals("停下求助", results[14].getValue("result").jsonObject.str("decision"))
        assertEquals("乙", results[15].getValue("result").jsonObject.str("next"))
        val composed = results[16].getValue("result").jsonObject.getValue("items").jsonArray
        assertEquals(3, composed.size)
        assertEquals(listOf("圆形", "圆形", "方形"), composed.map { it.jsonObject.str("shape") })
        assertEquals(listOf("红色", "蓝色", "红色"), composed.map { it.jsonObject.str("color") })

        // plan.run 直接复用已有操作，不写第24课答案分支；两个子结果都保留。
        assertEquals("ok", results[17].str("status"))
        assertEquals(2, results[17].getValue("result").jsonObject.getValue("results").jsonArray.size)
        assertEquals("less", results[18].getValue("result").jsonObject.str("comparison"))
        assertEquals(-4, results[18].getValue("result").jsonObject.int("difference_days"))

        assertEquals(listOf("开关"), results[19].getValue("result").jsonObject.strings("changed_conditions"))
        assertEquals(listOf("灯"), results[19].getValue("result").jsonObject.strings("changed_outcomes"))
        assertTrue(results[19].getValue("result").jsonObject.bool("controlled"))
        assertFalse(results[19].getValue("result").jsonObject.bool("causal_claim"))
        assertEquals("A", results[20].getValue("result").jsonObject.str("subject"))
        assertEquals("A 的 颜色 是 红色", results[21].getValue("result").jsonObject.str("text"))

        // 外层字段固定；每请求都有非空 trace，且没有教师/模型调用。
        results.forEach { result ->
            assertTrue(result.str("run_id").isNotBlank())
            assertTrue(result.getValue("trace_ids").jsonArray.isNotEmpty())
            assertEquals(0, result.int("teacher_invocation_count"))
            assertEquals(0, result.int("model_call_count"))
        }

        // world_id 不直接拼路径：state 下只有一个 64 位十六进制 SHA-256 文件。
        val stateDirectory = output.resolve("state")
        val stateFiles = Files.list(stateDirectory).use { stream -> stream.toList() }
        assertEquals(1, stateFiles.size)
        val stateName = stateFiles.single().fileName.toString()
        assertTrue(stateName.matches(Regex("[0-9a-f]{64}\\.json")), "state 文件名应为哈希：$stateName")
        assertEquals(worldStateFileName(worldId), stateName)
        assertFalse(stateFiles.single().toString().contains(".."))

        // 事件链：每个请求都有 received 和 completed/failed；规则来源、span、fact_ids 与 trace 可回查。
        val eventRows = Files.readAllLines(output.resolve("events.jsonl"))
            .map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(22, eventRows.count { it.str("event_type") == "request_received" })
        assertEquals(22, eventRows.count {
            it.str("event_type") == "request_completed" || it.str("event_type") == "request_failed"
        })
        assertTrue(eventRows.any { it.str("event_type") == "rule_enter" && it.str("rule_id") == RuleIds.ATTRIBUTE })
        assertTrue(eventRows.any { it.str("event_type") == "rule_exit" && it.str("rule_id") == RuleIds.ACTION_SEQUENCE })
        assertTrue(eventRows.any { it.str("event_type") == "action_commit" })
        assertTrue(eventRows.all { it["teacher_invocation_count"]?.jsonPrimitive?.int == 0 })
        assertTrue(eventRows.none { it.str("event_type") == "teacher_request" })
        assertTrue(eventRows.filter { it.str("event_type") == "rule_enter" }.all {
            it.str("source_anchor").startsWith("RULE:") && it.str("span_id").isNotBlank()
        })
        assertTrue(Files.exists(output.resolve("events.steps.log")))
        assertTrue(Files.readAllLines(output.resolve("events.steps.log")).isNotEmpty())

        // 重启：新进程式调用只带查询，不加新规则；旧世界从 state 文件重建。
        val restartRequests = temp.root.toPath().resolve("requests-2.jsonl")
        Files.writeString(restartRequests, listOf(
            request("r01", "preschool-002", worldId, "attr.get",
                buildJsonObject { put("id", "B"); put("key", "颜色") }),
            request("r02", "preschool-022", worldId, "claim.evaluate",
                buildJsonObject { put("subject", "A"); put("predicate", "颜色"); put("value", "红色") }),
            request("r03", "preschool-010", worldId, "count",
                buildJsonObject { putJsonArray("ids") { add("A"); add("B"); add("C") } }),
        ).joinToString("\n") + "\n")
        val restarted = BatchCli.execute(restartRequests, output, catalogPath)
        assertEquals(3, restarted.size)
        assertEquals("黄色", restarted[0].getValue("result").jsonObject.str("value"))
        assertTrue(restarted[1].getValue("result").jsonObject.bool("truth"))
        assertEquals(3, restarted[2].getValue("result").jsonObject.int("count"))
        // 第二次运行仍写同一个安全状态文件，而不是在 state 下创建新路径。
        val afterRestart = Files.list(stateDirectory).use { stream -> stream.toList() }
        assertEquals(1, afterRestart.size)
    }

    private fun objectJson(id: String, name: String, attributes: Map<String, String>, categories: List<String>) =
        buildJsonObject {
            put("id", id)
            put("name", name)
            putJsonObject("attributes") { attributes.forEach { (k, v) -> put(k, v) } }
            putJsonArray("categories") { categories.forEach { add(it) } }
        }

    private fun observation(id: String, conditions: Map<String, String>, outcome: Map<String, String>) =
        buildJsonObject {
            put("id", id)
            putJsonObject("conditions") { conditions.forEach { (k, v) -> put(k, v) } }
            putJsonObject("outcome") { outcome.forEach { (k, v) -> put(k, v) } }
        }

    private fun story(id: String, actor: String, action: String, objectId: String?, to: String?, place: String?) =
        buildJsonObject {
            put("id", id)
            put("actor", actor)
            put("action", action)
            objectId?.let { put("object", it) }
            to?.let { put("to", it) }
            place?.let { put("place", it) }
        }

    private fun request(
        taskId: String,
        lessonId: String,
        worldId: String,
        op: String,
        args: JsonObject,
    ): String = buildJsonObject {
        put("task_id", taskId)
        put("lesson_id", lessonId)
        put("world_id", worldId)
        put("op", op)
        put("args", args)
    }.toString()

    private fun JsonObject.str(key: String): String =
        (getValue(key) as JsonPrimitive).content

    private fun JsonObject.int(key: String): Int =
        (getValue(key) as JsonPrimitive).int

    private fun JsonObject.bool(key: String): Boolean =
        (getValue(key) as JsonPrimitive).boolean

    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map { (it as JsonPrimitive).content }
}
