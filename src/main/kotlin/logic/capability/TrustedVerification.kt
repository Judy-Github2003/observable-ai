// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2b-1b 宿主可信验证套件与纯内存验证引擎；本步不写数据库、不激活能力。
// 信任边界：套件由宿主机代码登记（TrustedSuiteRegistry），被验证能力只是数据，不能自己声明 source 或 verdict。
package logic.capability

/** 套件哈希的域分隔版本；套件内容或规范化规则变化时必须升版本，避免新旧哈希混用。 */
const val VERIFICATION_SUITE_HASH_VERSION = "verification-suite-v1"

/** 单个套件允许的最大用例数，防止宿主误传超大夹具拖垮预算。 */
const val MAX_SUITE_CASES = 256

/** 用例 ID / 套件 ID 的最大字节数，与注册表 ID 限额保持一致。 */
const val MAX_VERIFICATION_ID_BYTES = 256

/** 验证来源只能由宿主登记时指定；能力定义、provenance 或被验证程序都不能传入。 */
enum class VerificationSource(val wireName: String) {
    HOST_FIXTURE("host_fixture"),
    IMPLEMENTER_RUN("implementer_run"),
}

/**
 * 单条验证用例：固定输入 + 预期结果。
 * 输入值本身是已类型化的 CapValue，不携带“我是可信测试”这类标记。
 */
data class VerificationCase(
    val caseId: String,
    val inputs: Map<String, CapValue>,
    val expected: EvalResult,
)

/**
 * 不可变验证套件。构造后 cases 是只读副本，suiteHash 在构造时从规范化逐例内容算出。
 * 用例顺序参与哈希：顺序影响报告编号，必须可复现，不能靠 Set 重排。
 */
class TrustedVerificationSuite private constructor(
    val suiteId: String,
    val suiteVersion: Int,
    cases: List<VerificationCase>,
) {
    val cases: List<VerificationCase> = cases.toList()

    /** 规范化序列化后取 SHA-256；只依赖字段排序，不依赖 toString() 或 Map 插入顺序。 */
    val suiteHash: String = sha256("$VERIFICATION_SUITE_HASH_VERSION\n${canonicalSuite()}")

    companion object {
        /**
         * 宿主登记套件的唯一入口。这里只做结构性校验；空套件、重复用例、覆盖不足等语义问题
         * 留给引擎给出可审计的失败码，而不是在构造时抛异常掩盖。
         */
        fun create(suiteId: String, suiteVersion: Int, cases: List<VerificationCase>): TrustedVerificationSuite {
            require(suiteId.isNotBlank() && suiteId.toByteArray(Charsets.UTF_8).size <= MAX_VERIFICATION_ID_BYTES) {
                "invalid_suite_id"
            }
            require(suiteVersion > 0) { "invalid_suite_version" }
            require(cases.size <= MAX_SUITE_CASES) { "suite_too_large" }
            for (case in cases) {
                require(case.caseId.isNotBlank() && case.caseId.toByteArray(Charsets.UTF_8).size <= MAX_VERIFICATION_ID_BYTES) {
                    "invalid_case_id"
                }
            }
            val suite = TrustedVerificationSuite(suiteId, suiteVersion, cases)
            require(suite.canonicalSuite().toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "suite_too_large" }
            return suite
        }
    }

    private fun canonicalSuite(): String = buildString {
        append("{\"suite_id\":").append(CanonicalJson.string(suiteId))
        append(",\"suite_version\":").append(suiteVersion)
        append(",\"cases\":[")
        cases.forEachIndexed { index, case ->
            if (index > 0) append(',')
            append("{\"case_id\":").append(CanonicalJson.string(case.caseId))
            append(",\"inputs\":{")
            case.inputs.toSortedMap().entries.forEachIndexed { argumentIndex, (name, value) ->
                if (argumentIndex > 0) append(',')
                append(CanonicalJson.string(name)).append(':').append(encodeValue(value))
            }
            append("},\"expected\":").append(encodeExpected(case.expected)).append('}')
        }
        append("]}")
    }
}

/**
 * 宿主登记表：套件 ID + 版本不可变，来源在这里盖章。
 * 被验证能力拿不到这张表，因此无法把 source 伪造成 host_fixture。
 */
data class RegisteredVerificationSuite(val suite: TrustedVerificationSuite, val source: VerificationSource)

class TrustedSuiteRegistry {
    private val entries = LinkedHashMap<Pair<String, Int>, RegisteredVerificationSuite>()

    /**
     * 登记套件。同 ID/版本重复登记且内容相同视为幂等；内容不同则拒绝，避免同一版本出现两个哈希。
     */
    fun register(
        suite: TrustedVerificationSuite,
        source: VerificationSource = VerificationSource.HOST_FIXTURE,
    ): RegisteredVerificationSuite {
        val key = suite.suiteId to suite.suiteVersion
        val existing = entries[key]
        if (existing != null) {
            require(existing.suite.suiteHash == suite.suiteHash) { "suite_version_conflict" }
            require(existing.source == source) { "suite_source_conflict" }
            return existing
        }
        return RegisteredVerificationSuite(suite, source).also { entries[key] = it }
    }

    fun find(suiteId: String, suiteVersion: Int): RegisteredVerificationSuite? = entries[suiteId to suiteVersion]

    /** 供审计/测试枚举已登记套件；返回副本，避免外部改动登记内容。 */
    fun registered(): List<RegisteredVerificationSuite> = entries.values.toList()
}

/** 能力检查端口：先把库内/内存中的定义校验成“可执行且依赖锁定”的形态，再交给解释器。 */
fun interface CapabilityInspector {
    fun inspect(capabilityId: String, capabilityVersion: Int): Inspection
}

sealed interface Inspection {
    data class Ok(val definition: CapabilityDefinition, val contentHash: String) : Inspection
    data class Unavailable(val code: String) : Inspection
}

/**
 * 内存检查器：解析定义 → 类型检查（含 Call 的版本/哈希/参数锁定）→ 复算 contentHash。
 * 与 DB 路径的 `inspect` 不同，这里不校验库内持久化字段，只服务于尚未入库的候选。
 */
class ResolverInspector(private val resolver: CapabilityResolver) : CapabilityInspector {
    override fun inspect(capabilityId: String, capabilityVersion: Int): Inspection {
        val definition = resolver.resolve(capabilityId, capabilityVersion) ?: return Inspection.Unavailable("not_found")
        val checked = CapabilityTypeChecker(resolver).check(definition)
        if (!checked.ok) return Inspection.Unavailable(checked.code ?: "type_error")
        val hash = try {
            definition.contentHash()
        } catch (_: Exception) {
            return Inspection.Unavailable("invalid_definition")
        }
        return Inspection.Ok(definition, hash)
    }
}

/** 逐例判定状态；PASS 之外的任何状态都使本次验证不得为 pass。 */
enum class VerificationCaseStatus {
    PASS,
    WRONG_VALUE,
    ACTUAL_ERROR,
    UNKNOWN_MISMATCH,
    INVALID_EXPECTATION,
}

data class VerificationCaseResult(
    val caseId: String,
    val expected: EvalResult,
    val actual: EvalResult,
    val status: VerificationCaseStatus,
    /** 只有“预期与实际都是确定 Bool3 且相等”的独立输入才计入覆盖度。 */
    val countedAsDefinite: Boolean,
)

enum class VerificationVerdict { PASS, FAIL }

/**
 * 内存验证结果。verdict 只由执行器计算；调用方无法传入 passed/verdict 绕过。
 * contentHash 在能力不可检查时为 null，此时不会产生任何可入库的通过记录。
 */
data class VerificationOutcome(
    val capabilityId: String,
    val capabilityVersion: Int,
    val contentHash: String?,
    val suiteId: String,
    val suiteVersion: Int,
    val suiteHash: String,
    val source: VerificationSource?,
    val verdict: VerificationVerdict,
    val code: String?,
    val caseCount: Int,
    val passedCount: Int,
    val failedCount: Int,
    val definiteCount: Int,
    val trueCount: Int,
    val falseCount: Int,
    val cases: List<VerificationCaseResult>,
) {
    val ok: Boolean get() = verdict == VerificationVerdict.PASS
}

/**
 * 可信验证引擎：
 * 1) 套件必须已在宿主登记表中，source/suiteHash 都取登记记录；
 * 2) 能力先经 CapabilityInspector 检查（类型、依赖 pin、哈希）；
 * 3) 再逐例调用 M1 解释器，预算受 EvalBudget 限制；
 * 4) Bool3 能力至少 4 个独立确定用例、至少 1 真 1 假，否则 suite_inadequate。
 */
class TrustedVerificationEngine(
    private val resolver: CapabilityResolver,
    private val suites: TrustedSuiteRegistry,
    private val inspector: CapabilityInspector = ResolverInspector(resolver),
) {
    private val interpreter = CapabilityInterpreter(resolver)

    fun verify(
        capabilityId: String,
        capabilityVersion: Int,
        suiteId: String,
        suiteVersion: Int,
        budget: EvalBudget = EvalBudget(),
    ): VerificationOutcome {
        val registered = suites.find(suiteId, suiteVersion)
            ?: return failure(capabilityId, capabilityVersion, suiteId, suiteVersion, "", null, "unknown_suite")
        val suite = registered.suite
        val inspection = try {
            inspector.inspect(capabilityId, capabilityVersion)
        } catch (_: Exception) {
            Inspection.Unavailable("inspection_failed")
        }
        // 无法证明“定义完整且依赖锁定”时直接拒绝，不能拿一个可能被改过的 body 去跑测试。
        if (inspection is Inspection.Unavailable) {
            return failure(capabilityId, capabilityVersion, suiteId, suiteVersion, suite.suiteHash, registered.source, inspection.code)
        }
        val definition = (inspection as Inspection.Ok).definition
        val contentHash = inspection.contentHash

        // 最小版本只验证 Bool3 预测能力；其它输出类型的覆盖度规则尚未定义，不冒充通过。
        if (definition.output != CapType.Bool3) {
            return failure(capabilityId, capabilityVersion, suiteId, suiteVersion, suite.suiteHash, registered.source, "unsupported_output_type", contentHash)
        }
        if (suite.cases.isEmpty()) {
            return failure(capabilityId, capabilityVersion, suiteId, suiteVersion, suite.suiteHash, registered.source, "suite_empty", contentHash)
        }
        val duplicateIds = suite.cases.groupingBy { it.caseId }.eachCount().any { it.value > 1 }
        if (duplicateIds) {
            return failure(capabilityId, capabilityVersion, suiteId, suiteVersion, suite.suiteHash, registered.source, "duplicate_case_id", contentHash)
        }

        val results = ArrayList<VerificationCaseResult>(suite.cases.size)
        // 覆盖度按“独立输入”去重：同一输入重复写多条不能凑够 4 条确定用例。
        val definiteInputs = LinkedHashSet<String>()
        var trueCount = 0
        var falseCount = 0
        for (case in suite.cases) {
            // 用例输入是类型化值，解释器要求 EvalResult；统一包成 Known，避免调用方直接注入 Unknown 输入冒充测试。
            val arguments = case.inputs.mapValues { (_, value) -> EvalResult.Known(value) }
            val actual = try {
                interpreter.execute(definition, arguments, budget)
            } catch (_: Exception) {
                // 解释器本应返回 Error 而不是抛异常；真抛了也要留下可审计的失败，而不是中断整次验证。
                EvalResult.Error("interpreter_exception")
            }
            val status = judge(case.expected, actual)
            var definite = false
            if (status == VerificationCaseStatus.PASS) {
                val expectedBool = (case.expected as? EvalResult.Known)?.value as? CapValue.Bool
                val actualBool = (actual as? EvalResult.Known)?.value as? CapValue.Bool
                if (expectedBool != null && actualBool != null && expectedBool == actualBool) {
                    if (definiteInputs.add(canonicalInputs(case.inputs))) {
                        definite = true
                        if (actualBool.value) trueCount++ else falseCount++
                    }
                }
            }
            results += VerificationCaseResult(case.caseId, case.expected, actual, status, definite)
        }

        val passed = results.count { it.status == VerificationCaseStatus.PASS }
        val failed = results.size - passed
        val code = when {
            results.any { it.status == VerificationCaseStatus.INVALID_EXPECTATION } -> "invalid_expectation"
            failed > 0 -> "case_failed"
            definiteInputs.size < 4 || trueCount < 1 || falseCount < 1 -> "suite_inadequate"
            else -> null
        }
        return VerificationOutcome(
            capabilityId = capabilityId,
            capabilityVersion = capabilityVersion,
            contentHash = contentHash,
            suiteId = suite.suiteId,
            suiteVersion = suite.suiteVersion,
            suiteHash = suite.suiteHash,
            source = registered.source,
            verdict = if (code == null) VerificationVerdict.PASS else VerificationVerdict.FAIL,
            code = code,
            caseCount = results.size,
            passedCount = passed,
            failedCount = failed,
            definiteCount = definiteInputs.size,
            trueCount = trueCount,
            falseCount = falseCount,
            cases = results,
        )
    }

    private fun failure(
        capabilityId: String,
        capabilityVersion: Int,
        suiteId: String,
        suiteVersion: Int,
        suiteHash: String,
        source: VerificationSource?,
        code: String,
        contentHash: String? = null,
    ) = VerificationOutcome(
        capabilityId = capabilityId,
        capabilityVersion = capabilityVersion,
        contentHash = contentHash,
        suiteId = suiteId,
        suiteVersion = suiteVersion,
        suiteHash = suiteHash,
        source = source,
        verdict = VerificationVerdict.FAIL,
        code = code,
        caseCount = 0,
        passedCount = 0,
        failedCount = 0,
        definiteCount = 0,
        trueCount = 0,
        falseCount = 0,
        cases = emptyList(),
    )

    /**
     * 逐例语义：
     * - 预期 Error 是非法的（错误不是可验证的成功输出），一律 invalid_expectation；
     * - 实际 Error 一律失败，即使预期也是 Error；
     * - 两边都是 Known 才做精确比较，Unknown 绝不当 false；
     * - 预期 Unknown 且实际 Unknown 算“匹配”，但不计入确定覆盖度。
     */
    private fun judge(expected: EvalResult, actual: EvalResult): VerificationCaseStatus = when {
        expected is EvalResult.Error -> VerificationCaseStatus.INVALID_EXPECTATION
        actual is EvalResult.Error -> VerificationCaseStatus.ACTUAL_ERROR
        expected is EvalResult.Known && actual is EvalResult.Known ->
            if (expected == actual) VerificationCaseStatus.PASS else VerificationCaseStatus.WRONG_VALUE
        expected is EvalResult.Unknown && actual is EvalResult.Unknown -> VerificationCaseStatus.PASS
        else -> VerificationCaseStatus.UNKNOWN_MISMATCH
    }

    private fun canonicalInputs(inputs: Map<String, CapValue>): String = buildString {
        append('{')
        inputs.toSortedMap().entries.forEachIndexed { index, (name, value) ->
            if (index > 0) append(',')
            append(CanonicalJson.string(name)).append(':').append(encodeValue(value))
        }
        append('}')
    }
}

/** 值的规范化编码：Date/大小写等由调用方负责，这里只保证 Map 字段顺序稳定、类型可区分。 */
internal fun encodeValue(value: CapValue): String = when (value) {
    is CapValue.Bool -> "{\"kind\":\"Bool3\",\"value\":\"${value.value}\"}"
    is CapValue.Str -> "{\"kind\":\"String\",\"value\":${CanonicalJson.string(value.value)}}"
    is CapValue.I64 -> "{\"kind\":\"Int64\",\"value\":\"${value.value}\"}"
    is CapValue.Rec -> "{\"kind\":\"Record\",\"fields\":{" +
        value.fields.toSortedMap().entries.joinToString(",") { (name, child) ->
            CanonicalJson.string(name) + ":" + encodeValue(child)
        } + "}}"
}

/** 预期结果的规范化编码；Unknown 带原因，Error 带错误码，避免“看起来都失败”被混为一谈。 */
internal fun encodeExpected(result: EvalResult): String = when (result) {
    is EvalResult.Known -> "{\"result\":\"Known\",\"value\":${encodeValue(result.value)}}"
    is EvalResult.Unknown -> "{\"result\":\"Unknown\",\"reason\":${CanonicalJson.string(result.reason)}}"
    is EvalResult.Error -> "{\"result\":\"Error\",\"code\":${CanonicalJson.string(result.code)}}"
}
