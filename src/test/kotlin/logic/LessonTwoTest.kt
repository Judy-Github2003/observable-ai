// 本文件新增测试：DeepSeek Harness，2026-11-04。
// 创建原因：为学前第二课补一项连贯核心场景，覆盖导入、未知、分组、属性比较、显式补教、
// 重启持久化，以及属性名作为数据参数复用于其他属性；不调用教师或外部模型。
package logic

import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** 第二课行为：对象已知但属性缺失仍 unknown，属性记录可显式补教并重开复用。 */
class LessonTwoTest {
    // 每个测试独立临时目录；课程库和日志不会触碰正式 runs 目录。
    @get:Rule val temp = TemporaryFolder()

    /**
     * 场景：导入正式第二课材料，A/C 红、D 绿、E 无颜色；
     * 查询 E 为 unknown，分组不把 D 算作红色，比较缺失属性仍 unknown；
     * 显式教 E 黄色并重启后仍可查询；再用“大小”验证属性名是通用参数。
     */
    @Test fun `attributes stay unknown until taught and color groups do not merge identities`() {
        val db = temp.root.toPath().resolve("lesson-two.db")
        val log = temp.root.toPath().resolve("lesson-two-events.jsonl")
        val material = materialFile()

        val events1 = Events(log, studentVersion = "PRESCHOOL-002")
        val imported = json(
            LessonTwo(AttributeMemory(db), events1).execute(listOf("导入", material.toString())),
        )
        assertEquals("ok", imported.str("status"))
        assertEquals(PRESCHOOL_LESSON_TWO_ID, imported.str("lesson_id"))
        assertEquals(5, imported.int("saved_count"))
        // E 没有颜色，因此材料只产生 A-D 四条属性；不能额外生成默认值。
        assertEquals(4, imported.int("attribute_count"))
        assertEquals(listOf("A", "B", "C", "D", "E"), imported.strings("object_ids"))
        assertEquals("researcher_given_training_world", imported.str("source"))
        assertEquals(events1.runId, imported.str("source_run_id"))
        assertEquals(0, imported.int("teacher_invocation_count"))

        // 重开数据库模拟新一次运行；对象和属性都应从本地库读出。
        val events2 = Events(log, studentVersion = "PRESCHOOL-002")
        val lesson = LessonTwo(AttributeMemory(db), events2)

        val a = json(lesson.execute(listOf("查", "颜色", "A")))
        assertEquals("ok", a.str("status"))
        assertEquals("A", a.str("object_id"))
        assertEquals("苹果", a.str("name"))
        assertEquals("颜色", a.str("attribute_name"))
        assertEquals("红色", a.str("attribute_value"))
        assertEquals("given_in_training_world", a.str("attribute_status"))
        assertEquals(events1.runId, a.str("source_run_id"))
        assertEquals(0, a.int("teacher_invocation_count"))

        // A 与 D 同名“苹果”，但 D 的颜色是材料明确给出的绿色，不能从 A 推出所有苹果红。
        val d = json(lesson.execute(listOf("查", "颜色", "D")))
        assertEquals("ok", d.str("status"))
        assertEquals("苹果", d.str("name"))
        assertEquals("绿色", d.str("attribute_value"))

        // E 的对象和名称已知，但颜色没有记录：unknown 是“未记录”，不是否定。
        val eUnknown = json(lesson.execute(listOf("查", "颜色", "E")))
        assertEquals("unknown", eUnknown.str("status"))
        assertEquals("attribute_not_observed", eUnknown.str("reason"))
        assertEquals("E", eUnknown.str("object_id"))
        assertEquals("书", eUnknown.str("name"))
        assertEquals("颜色", eUnknown.str("attribute_name"))
        assertFalse(eUnknown.containsKey("attribute_value"))

        // 分组只返回真正记录为红色的 A、C；D 绿色、E 缺失都不得被算入。
        val red = json(lesson.execute(listOf("找", "颜色", "红色")))
        assertEquals("ok", red.str("status"))
        assertEquals(listOf("A", "C"), red.strings("object_ids"))
        assertEquals(2, red.int("match_count"))

        // 未教 E 之前，黄色没有任何记录；空结果保持 unknown，不能表述为“没有黄色对象”。
        val yellowUnknown = json(lesson.execute(listOf("找", "颜色", "黄色")))
        assertEquals("unknown", yellowUnknown.str("status"))
        assertEquals("attribute_value_not_observed", yellowUnknown.str("reason"))
        assertTrue(yellowUnknown.strings("object_ids").isEmpty())
        assertEquals(0, yellowUnknown.int("match_count"))

        // 比较只看属性值：A/C 同色为 true，A/D 不同色为 false，不涉及对象身份合并。
        val aVersusC = json(lesson.execute(listOf("比较", "颜色", "A", "C")))
        assertEquals("ok", aVersusC.str("status"))
        assertTrue(aVersusC.bool("same_value"))
        val aVersusD = json(lesson.execute(listOf("比较", "颜色", "A", "D")))
        assertEquals("ok", aVersusD.str("status"))
        assertFalse(aVersusD.bool("same_value"))

        // 一侧属性缺失时返回 unknown，不能伪造 same_value=false；未知对象另用 object_not_observed。
        val aVersusE = json(lesson.execute(listOf("比较", "颜色", "A", "E")))
        assertEquals("unknown", aVersusE.str("status"))
        assertEquals("attribute_not_observed", aVersusE.str("reason"))
        assertEquals(listOf("E"), aVersusE.strings("missing_attribute_object_ids"))
        assertFalse(aVersusE.containsKey("same_value"))
        val aVersusZ = json(lesson.execute(listOf("比较", "颜色", "A", "Z")))
        assertEquals("unknown", aVersusZ.str("status"))
        assertEquals("object_not_observed", aVersusZ.str("reason"))
        assertEquals(listOf("Z"), aVersusZ.strings("missing_object_ids"))

        // 第二阶段才显式补教 E 的颜色；来源和运行标识可追溯到这次教学。
        val taught = json(lesson.execute(listOf("教", "颜色", "E", "黄色")))
        assertEquals("ok", taught.str("status"))
        assertEquals("E", taught.str("object_id"))
        assertEquals("颜色", taught.str("attribute_name"))
        assertEquals("黄色", taught.str("attribute_value"))
        assertEquals("explicit_teaching", taught.str("attribute_status"))
        assertEquals("researcher_explicit_teaching", taught.str("source"))
        assertEquals(events2.runId, taught.str("source_run_id"))
        assertEquals(0, taught.int("teacher_invocation_count"))

        // 属性不能为未知对象凭空创建身份；失败后 Z 仍未被观察。
        assertFailsWith<IllegalArgumentException> {
            lesson.execute(listOf("教", "颜色", "Z", "红色"))
        }
        assertEquals("unknown", json(lesson.execute(listOf("查", "颜色", "Z"))).str("status"))

        // 再次重开：E 的黄色来自显式教学，A 的红色和来源保持不变。
        val events3 = Events(log, studentVersion = "PRESCHOOL-002")
        val reopened = LessonTwo(AttributeMemory(db), events3)
        val e = json(reopened.execute(listOf("查", "颜色", "E")))
        assertEquals("ok", e.str("status"))
        assertEquals("书", e.str("name"))
        assertEquals("黄色", e.str("attribute_value"))
        assertEquals("explicit_teaching", e.str("attribute_status"))
        assertEquals(events2.runId, e.str("source_run_id"))
        val aStillGiven = json(reopened.execute(listOf("查", "颜色", "A")))
        assertEquals("红色", aStillGiven.str("attribute_value"))
        assertEquals("given_in_training_world", aStillGiven.str("attribute_status"))
        val yellow = json(reopened.execute(listOf("找", "颜色", "黄色")))
        assertEquals(listOf("E"), yellow.strings("object_ids"))

        // 属性名不是颜色专用分支：同一命令用“大小”即可保存和查询新属性。
        val sizeTaught = json(reopened.execute(listOf("教", "大小", "A", "大")))
        assertEquals("ok", sizeTaught.str("status"))
        val size = json(reopened.execute(listOf("查", "大小", "A")))
        assertEquals("ok", size.str("status"))
        assertEquals("大", size.str("attribute_value"))
        assertEquals("explicit_teaching", size.str("attribute_status"))

        // 本课全部事件都记录零教师调用，且不存在任何教师请求事件。
        val rows = Files.readAllLines(log).map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it["teacher_invocation_count"]?.jsonPrimitive?.int == 0 })
        assertTrue(rows.none { it["event_type"]?.jsonPrimitive?.content == "teacher_request" })
    }

    /** 写入正式第二课材料；E 故意不带 attributes 字段，验证属性可省略且不会补默认值。 */
    private fun materialFile(): Path {
        val path = temp.root.toPath().resolve("lesson-two-material.json")
        val names = listOf("A" to "苹果", "B" to "杯子", "C" to "球", "D" to "苹果", "E" to "书")
        val colors = mapOf("A" to "红色", "B" to "蓝色", "C" to "红色", "D" to "绿色")
        val content = buildJsonObject {
            put("lesson_id", PRESCHOOL_LESSON_TWO_ID)
            put("source", "researcher_given_training_world")
            putJsonArray("objects") {
                names.forEach { (id, name) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                        colors[id]?.let { color ->
                            putJsonObject("attributes") { put("颜色", color) }
                        }
                    })
                }
            }
        }
        Files.writeString(path, content.toString())
        return path
    }

    /** 把 execute 返回的紧凑 JSON 解析为对象，便于逐字段断言。 */
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** 读取字符串字段；字段缺失时直接让测试失败。 */
    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    /** 读取整数字段；用于保存数量和教师调用计数。 */
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    /** 读取布尔字段；用于属性值比较。 */
    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    /** 读取字符串数组；用于对象 ID、缺失列表和分组结果。 */
    private fun JsonObject.strings(key: String): List<String> =
        getValue(key).jsonArray.map { it.jsonPrimitive.content }
}
