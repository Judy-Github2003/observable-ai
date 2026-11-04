// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：V2 公开开发测试，覆盖 E1–E5 与边界；只测公开契约，不包含闭卷隐藏世界。
package logic.discovery

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryScenarioTest {

    // ---------- 测试脚手架 ----------

    private fun request(op: String, world: String, args: JsonObject = buildJsonObject { }): JsonObject =
        buildJsonObject {
            put("task_id", UUID.randomUUID().toString())
            put("world_id", world)
            put("op", op)
            put("args", args)
        }

    private fun runWith(stateDir: Path, requests: List<JsonObject>): List<JsonObject> {
        Files.createDirectories(stateDir)
        val file = stateDir.resolve("requests-${UUID.randomUUID()}.jsonl")
        Files.writeString(file, requests.joinToString("\n") { it.toString() }, Charsets.UTF_8)
        val output = mutableListOf<String>()
        runDiscoveryCli(arrayOf(file.toString(), stateDir.toString())) { output += it }
        return output.map { discoveryJson.parseToJsonElement(it).jsonObject }
    }

    private fun run(requests: List<JsonObject>): List<JsonObject> =
        runWith(Files.createTempDirectory("discovery-test"), requests)

    private fun JsonObject.result(): JsonObject = this["result"]!!.jsonObject
    private fun JsonObject.status(): String = this["status"]!!.jsonPrimitive.content
    private fun JsonObject.reason(): String? = (this["reason"] as? JsonPrimitive)?.content
    private fun JsonObject.int(key: String): Int = result()[key]!!.jsonPrimitive.int
    private fun JsonObject.text(key: String): String = result()[key]!!.jsonPrimitive.content
    private fun JsonObject.array(key: String): JsonArray = result()[key]!!.jsonArray

    private fun JsonObject.ruleFields(): List<String> =
        result()["rule"]!!.jsonObject["clauses"]!!.jsonArray
            .map { it.jsonObject["field"]!!.jsonPrimitive.content }

    private fun schema(vararg fields: Pair<String, List<String>>): JsonObject = buildJsonObject {
        fields.forEach { (field, values) ->
            putJsonArray(field) { values.forEach { add(it) } }
        }
    }

    private fun features(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun example(id: String, features: JsonObject, label: Boolean, evidence: String): JsonObject =
        buildJsonObject {
            put("id", id)
            put("features", features)
            put("label", label)
            put("evidence_id", evidence)
        }

    private fun poolObject(id: String, features: JsonObject): JsonObject = buildJsonObject {
        put("id", id)
        put("features", features)
    }

    private fun startArgs(
        schemaJson: JsonObject,
        examples: List<JsonObject>,
        pool: List<JsonObject>,
    ): JsonObject = buildJsonObject {
        put("feature_schema", schemaJson)
        putJsonArray("known_examples") { examples.forEach { add(it) } }
        putJsonArray("query_pool") { pool.forEach { add(it) } }
    }

    private fun observeArgs(objectId: String, label: Boolean, evidence: String): JsonObject = buildJsonObject {
        put("object_id", objectId)
        put("label", label)
        put("evidence_id", evidence)
    }

    private fun predictArgs(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        put("features", features(*pairs))
    }

    // E1 的两色两形世界，A=true、B=false，C/D 待问。
    private fun e1Start(world: String = "w-e1"): JsonObject = request(
        "discovery.start", world,
        startArgs(
            schema("颜色" to listOf("红色", "蓝色"), "形状" to listOf("圆形", "方形")),
            listOf(
                example("A", features("颜色" to "红色", "形状" to "圆形"), true, "e-A"),
                example("B", features("颜色" to "蓝色", "形状" to "方形"), false, "e-B"),
            ),
            listOf(
                poolObject("C", features("颜色" to "红色", "形状" to "方形")),
                poolObject("D", features("颜色" to "蓝色", "形状" to "圆形")),
            ),
        ),
    )

    // ---------- E1：基本归纳与跨对象泛化 ----------

    @Test
    fun `E1 keeps multiple candidates then induces rule and predicts new objects`() {
        val responses = run(
            listOf(
                e1Start(),
                request("discovery.hypotheses", "w-e1"),
                request("discovery.next_query", "w-e1"),
                request("discovery.observe", "w-e1", observeArgs("C", false, "e-C")),
                request("discovery.next_query", "w-e1"),
                request("discovery.observe", "w-e1", observeArgs("D", false, "e-D")),
                request("discovery.finalize", "w-e1"),
                request("discovery.predict", "w-e1", predictArgs("颜色" to "红色", "形状" to "圆形")),
                request("discovery.predict", "w-e1", predictArgs("颜色" to "蓝色", "形状" to "方形")),
                request("discovery.predict", "w-e1", predictArgs("颜色" to "红色", "形状" to "方形")),
                request("discovery.predict", "w-e1", predictArgs("颜色" to "蓝色", "形状" to "圆形")),
            ),
        )
        assertEquals("initialized", responses[0].text("state"))
        assertEquals(3, responses[0].int("surviving_count"), "A/B 后应仍有 3 个非等价候选")
        assertEquals(3, responses[1].int("count"))

        val firstQuery = responses[2]
        assertEquals("ok", firstQuery.status())
        assertTrue(firstQuery.text("object_id") in listOf("C", "D"), "应选择能区分候选的对象")

        // C=false 后候选收缩为 2：形状==圆形、颜色==红色 AND 形状==圆形
        assertEquals(2, responses[3].int("candidate_count"))

        val secondQuery = responses[4]
        assertEquals("D", secondQuery.text("object_id"))

        assertEquals(1, responses[5].int("candidate_count"))

        val finalize = responses[6]
        assertEquals("ok", finalize.status())
        assertEquals("promoted", finalize.text("status"))
        assertEquals(setOf("颜色", "形状"), finalize.ruleFields().toSet())
        assertEquals(4, finalize.int("domain_size"))

        assertEquals("true", responses[7].text("label"))
        assertEquals("false", responses[8].text("label"))
        assertEquals("false", responses[9].text("label"))
        assertEquals("false", responses[10].text("label"))
    }

    // ---------- E2：信息不足，必须拒答 ----------

    @Test
    fun `E2 refuses to promote when query pool cannot discriminate`() {
        val responses = run(
            listOf(
                request(
                    "discovery.start", "w-e2",
                    startArgs(
                        schema("颜色" to listOf("红色", "蓝色"), "形状" to listOf("圆形", "方形")),
                        listOf(
                            example("A", features("颜色" to "红色", "形状" to "圆形"), true, "e-A"),
                            example("B", features("颜色" to "蓝色", "形状" to "方形"), false, "e-B"),
                        ),
                        emptyList(),
                    ),
                ),
                request("discovery.next_query", "w-e2"),
                request("discovery.finalize", "w-e2"),
            ),
        )
        assertEquals("unknown", responses[1].status())
        assertEquals("no_discriminating_query", responses[1].reason())

        assertEquals("unknown", responses[2].status())
        assertEquals("insufficient_evidence", responses[2].reason())
        assertTrue(responses[2].int("class_count") >= 2, "应报告多个非等价候选")
    }

    // ---------- E3：证据冲突 ----------

    @Test
    fun `E3 conflicting labels block promotion and keep both evidences`() {
        val responses = run(
            listOf(
                request(
                    "discovery.start", "w-e3",
                    startArgs(
                        schema("颜色" to listOf("红色", "蓝色"), "形状" to listOf("圆形", "方形")),
                        listOf(example("A", features("颜色" to "红色", "形状" to "圆形"), true, "e-A")),
                        listOf(poolObject("B", features("颜色" to "蓝色", "形状" to "方形"))),
                    ),
                ),
                request("discovery.observe", "w-e3", observeArgs("A", false, "e-A2")),
                request("discovery.finalize", "w-e3"),
                request("discovery.status", "w-e3"),
            ),
        )
        assertEquals("conflict", responses[1].status())
        assertEquals("conflicting_evidence", responses[1].reason())
        assertEquals("conflict", responses[2].status())
        assertEquals("conflicting_evidence", responses[2].reason())
        assertFalse(responses[3].result()["promoted"]!!.jsonPrimitive.boolean)
    }

    // ---------- E4：换世界，无关属性不得进入规则 ----------

    @Test
    fun `E4 learns a different world and ignores an unrelated attribute`() {
        val world = "w-e4"
        val responses = run(
            listOf(
                request(
                    "discovery.start", world,
                    startArgs(
                        schema(
                            "颜色" to listOf("绿色", "黄色"),
                            "形状" to listOf("三角形", "正方形"),
                            "大小" to listOf("大", "小"),
                        ),
                        listOf(
                            example("p", features("颜色" to "绿色", "形状" to "三角形", "大小" to "大"), true, "e-P"),
                            example("n", features("颜色" to "黄色", "形状" to "正方形", "大小" to "大"), false, "e-N"),
                        ),
                        listOf(
                            poolObject("q1", features("颜色" to "绿色", "形状" to "三角形", "大小" to "小")),
                            poolObject("q2", features("颜色" to "绿色", "形状" to "正方形", "大小" to "小")),
                            poolObject("q3", features("颜色" to "黄色", "形状" to "三角形", "大小" to "小")),
                        ),
                    ),
                ),
                request("discovery.next_query", world),
                request("discovery.observe", world, observeArgs("q1", true, "e-q1")),
                request("discovery.next_query", world),
                request("discovery.observe", world, observeArgs("q2", false, "e-q2")),
                request("discovery.next_query", world),
                request("discovery.observe", world, observeArgs("q3", false, "e-q3")),
                request("discovery.finalize", world),
                request("discovery.predict", world, predictArgs("颜色" to "绿色", "形状" to "三角形", "大小" to "小")),
                request("discovery.predict", world, predictArgs("颜色" to "绿色", "形状" to "正方形", "大小" to "大")),
            ),
        )
        assertEquals("q1", responses[1].text("object_id"))
        assertEquals("q2", responses[3].text("object_id"))
        assertEquals("q3", responses[5].text("object_id"))
        val finalize = responses[7]
        assertEquals("promoted", finalize.text("status"))
        assertEquals(setOf("颜色", "形状"), finalize.ruleFields().toSet(), "无关属性不能进入规则")
        assertEquals("true", responses[8].text("label"))
        assertEquals("false", responses[9].text("label"))
    }

    // ---------- E5：反例推翻候选，淘汰可溯源 ----------

    @Test
    fun `E5 counterexample eliminates candidate with evidence reference`() {
        val world = "w-e5"
        val responses = run(
            listOf(
                request(
                    "discovery.start", world,
                    startArgs(
                        schema("颜色" to listOf("红色", "蓝色"), "形状" to listOf("圆形", "方形")),
                        listOf(example("A", features("颜色" to "红色", "形状" to "圆形"), true, "e-A")),
                        listOf(
                            poolObject("B", features("颜色" to "红色", "形状" to "方形")),
                            poolObject("C", features("颜色" to "蓝色", "形状" to "圆形")),
                        ),
                    ),
                ),
                request("discovery.next_query", world),
                request("discovery.observe", world, observeArgs("B", false, "e-B")),
                request("discovery.status", world),
            ),
        )
        assertEquals("B", responses[1].text("object_id"))
        assertEquals(2, responses[2].int("candidate_count"))
        val eliminated = responses[2].array("eliminated").map { it.jsonPrimitive.content }
        assertTrue(eliminated.isNotEmpty())

        val status = responses[3].result()
        assertFalse(status["promoted"]!!.jsonPrimitive.boolean)
        assertEquals(2, status["candidate_count"]!!.jsonPrimitive.int)
    }

    // ---------- 边界：非法输入、重复证据、重启 ----------

    @Test
    fun `rejects invalid schema and duplicate evidence`() {
        val badSchema = request(
            "discovery.start", "w-bad",
            startArgs(
                buildJsonObject { putJsonArray("颜色") { } },
                emptyList(), emptyList(),
            ),
        )
        assertEquals("error", run(listOf(badSchema))[0].status())
        assertEquals("invalid_input", run(listOf(badSchema))[0].reason())

        val duplicate = request(
            "discovery.start", "w-dup",
            startArgs(
                schema("颜色" to listOf("红色", "蓝色")),
                listOf(
                    example("A", features("颜色" to "红色"), true, "e-1"),
                    example("B", features("颜色" to "蓝色"), false, "e-1"),
                ),
                emptyList(),
            ),
        )
        assertEquals("invalid_input", run(listOf(duplicate))[0].reason())
    }

    @Test
    fun `state survives restart and predict abstains on missing attribute`() {
        val stateDir = Files.createTempDirectory("discovery-restart")
        val first = runWith(
            stateDir,
            listOf(
                e1Start("w-restart"),
                request("discovery.next_query", "w-restart"),
                request("discovery.observe", "w-restart", observeArgs("C", false, "e-C")),
            ),
        )
        assertEquals("ok", first[2].status())

        // 新进程语义：重新调用 runDiscoveryCli，只靠 state.json 恢复。
        val second = runWith(
            stateDir,
            listOf(
                request("discovery.status", "w-restart"),
                request("discovery.hypotheses", "w-restart"),
            ),
        )
        assertEquals(2, second[0].int("candidate_count"))
        assertEquals(2, second[1].int("count"))

        // 未晋升时 predict 必须拒答。
        val third = runWith(
            stateDir,
            listOf(request("discovery.predict", "w-restart", predictArgs("颜色" to "红色", "形状" to "圆形"))),
        )
        assertEquals("unknown", third[0].status())
        assertEquals("rule_not_promoted", third[0].reason())
    }

    @Test
    fun `promoted rule survives restart and predicts offline`() {
        val stateDir = Files.createTempDirectory("discovery-promoted-restart")
        runWith(
            stateDir,
            listOf(
                e1Start("w-promoted"),
                request("discovery.observe", "w-promoted", observeArgs("C", false, "e-C")),
                request("discovery.observe", "w-promoted", observeArgs("D", false, "e-D")),
                request("discovery.finalize", "w-promoted"),
            ),
        )
        val second = runWith(
            stateDir,
            listOf(
                request("discovery.status", "w-promoted"),
                request("discovery.predict", "w-promoted", predictArgs("颜色" to "红色", "形状" to "圆形")),
                request("discovery.predict", "w-promoted", predictArgs("颜色" to "蓝色", "形状" to "方形")),
            ),
        )
        assertTrue(second[0].result()["promoted"]!!.jsonPrimitive.boolean)
        assertEquals("true", second[1].text("label"))
        assertEquals("false", second[2].text("label"))
    }

    @Test
    fun `audit trail records generation query elimination promotion and execution`() {
        val stateDir = Files.createTempDirectory("discovery-audit")
        runWith(
            stateDir,
            listOf(
                e1Start("w-audit"),
                request("discovery.next_query", "w-audit"),
                request("discovery.observe", "w-audit", observeArgs("C", false, "e-C")),
                request("discovery.observe", "w-audit", observeArgs("D", false, "e-D")),
                request("discovery.finalize", "w-audit"),
                request("discovery.predict", "w-audit", predictArgs("颜色" to "红色", "形状" to "圆形")),
            ),
        )
        val events = Files.readAllLines(stateDir.resolve("events.jsonl"))
            .filter { it.isNotBlank() }
            .map { discoveryJson.parseToJsonElement(it).jsonObject["event"]!!.jsonPrimitive.content }
            .toSet()
        assertTrue("hypotheses_generated" in events)
        assertTrue("observation_received" in events)
        assertTrue("hypothesis_eliminated" in events)
        assertTrue("query_selected" in events)
        assertTrue("rule_promoted" in events)
        assertTrue("rule_executed" in events)
    }

    @Test
    fun `promoted rule returns unknown when required attribute is missing`() {
        val world = "w-missing"
        val responses = run(
            listOf(
                request(
                    "discovery.start", world,
                    startArgs(
                        schema("颜色" to listOf("红色", "蓝色"), "形状" to listOf("圆形", "方形")),
                        listOf(
                            example("A", features("颜色" to "红色", "形状" to "圆形"), true, "e-A"),
                            example("B", features("颜色" to "蓝色", "形状" to "方形"), false, "e-B"),
                        ),
                        listOf(
                            poolObject("C", features("颜色" to "红色", "形状" to "方形")),
                            poolObject("D", features("颜色" to "蓝色", "形状" to "圆形")),
                        ),
                    ),
                ),
                request("discovery.observe", world, observeArgs("C", false, "e-C")),
                request("discovery.observe", world, observeArgs("D", false, "e-D")),
                request("discovery.finalize", world),
                request("discovery.predict", world, predictArgs("颜色" to "红色")),
            ),
        )
        assertEquals("promoted", responses[3].text("status"))
        assertEquals("unknown", responses[4].status())
        assertEquals("missing_attribute", responses[4].reason())
        assertEquals("unknown", responses[4].text("label"))
    }
}
