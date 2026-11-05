// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2a 最小能力注册表；只允许 candidate，不做激活/验证/压缩。
// 修改：DeepSeek Harness，2026-11-04，B0 M2b-1a 增加 v1→v2 事务迁移（只加 verification_runs/activations 两张空表），
//       不改旧四表、不改 capabilities.status 的 candidate 约束，也不在本步写入验证或激活记录。
// 修改：DeepSeek Harness，2026-11-04，B0 M2b-1c 增加 appendVerificationRun：验证记录与 verified 事件同事务追加。
// 修改：DeepSeek Harness，2026-11-04，B0 M2b-2a 增加验证/激活读取与 appendActivation；激活不修改 capabilities.status。
// 修改：DeepSeek Harness，2026-11-05，B0 M2c-1a 增加 schema v3 撤销表与撤销读写；旧四表、status 约束与历史记录不变。
// 修改：DeepSeek Harness，2026-11-05，B0 M3-1a 增加 activeCapabilities()：只返回激活且依赖健康的能力快照。
// 修改：DeepSeek Harness，2026-11-05，B0 M4-1a 增加 capabilityInventory()：供 L1 成本审计读取完整能力定义。
package logic.capability

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant

private const val MAX_ID_BYTES = 256
private const val MAX_CONSTANT_BYTES = 65_536
private const val MAX_PROVENANCE_BYTES = 65_536

internal class RegistryException(val code: String, message: String = code) : RuntimeException(message)

internal data class RegistryResult(
    val ok: Boolean,
    val code: String? = null,
    val definition: CapabilityDefinition? = null,
    val contentHash: String? = null,
    val bodyHash: String? = null,
)

internal interface CapabilityRegistry : CapabilityResolver {
    fun propose(def: CapabilityDefinition, provenanceJson: String = "{}"): RegistryResult
    fun inspect(id: String, version: Int): RegistryResult
}

/**
 * SQLite 注册表：capabilities/bodies/dependencies/events 四表。
 * 只写 candidate；读取时从 canonical IR 重新解析并复算两种 hash，绝不信任库里存的 hash。
 */
internal class SqliteCapabilityRegistry(private val dbPath: Path) : CapabilityRegistry, AutoCloseable {
    private val connection: Connection

    init {
        connection = try {
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        } catch (error: SQLException) {
            throw RegistryException("registry_open_failed", error.message ?: "sqlite open failed")
        }
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        initializeSchema()
    }

    private fun initializeSchema() {
        val version = readUserVersion()
        when (version) {
            // 空库：一次性建好 v1 旧四表、v2 验证/激活表、v3 撤销表，直接落在 user_version=3。
            0 -> inSchemaTransaction("schema_init_failed") {
                createV1Tables()
                createV2Tables()
                createV3Tables()
                writeUserVersion(3)
            }
            // v1 旧库：补 v2、v3 阶段表并推进版本号；失败必须整体回滚，不能留下“表建了一半”的库。
            1 -> inSchemaTransaction("schema_migration_failed") {
                createV2Tables()
                createV3Tables()
                writeUserVersion(3)
            }
            // v2 库：只补撤销表；验证/激活历史原样保留。
            2 -> inSchemaTransaction("schema_migration_failed") {
                createV3Tables()
                writeUserVersion(3)
            }
            // 已是 v3：正常打开，不重复执行迁移（重复 CREATE 会因表名冲突失败）。
            3 -> Unit
            else -> throw RegistryException("unsupported_schema_version", "user_version=$version")
        }
    }

    private fun readUserVersion(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }

    // PRAGMA user_version 的写入也走 SQLite pager，因此和 DDL 处于同一事务，回滚时版本号一并退回。
    private fun writeUserVersion(version: Int) {
        connection.createStatement().use { it.execute("PRAGMA user_version = $version") }
    }

    /**
     * 建表/迁移的统一事务壳：成功才 commit；任一句失败都 rollback 并把原因归为给定错误码。
     * 迁移过程不改动 capabilities/bodies/dependencies/events 的任何行。
     */
    private inline fun inSchemaTransaction(errorCode: String, block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (error: Exception) {
            try {
                connection.rollback()
            } catch (_: Exception) {
                // 回滚本身失败时保留原始错误码；连接随后会被关闭，不能假装迁移成功。
            }
            throw RegistryException(errorCode, error.message ?: errorCode)
        } finally {
            connection.autoCommit = true
        }
    }

    private fun createV1Tables() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE capabilities (
                    capability_id TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK(version > 0),
                    content_hash TEXT NOT NULL,
                    semantics_version TEXT NOT NULL,
                    inputs_json TEXT NOT NULL,
                    output_json TEXT NOT NULL,
                    body_hash TEXT NOT NULL,
                    provenance_json TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status = 'candidate'),
                    created_at TEXT NOT NULL,
                    PRIMARY KEY (capability_id, version)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE bodies (
                    body_hash TEXT PRIMARY KEY,
                    canonical_ir_json TEXT NOT NULL,
                    byte_size INTEGER NOT NULL CHECK(byte_size >= 0)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE dependencies (
                    from_id TEXT NOT NULL,
                    from_version INTEGER NOT NULL,
                    to_id TEXT NOT NULL,
                    to_version INTEGER NOT NULL,
                    to_content_hash TEXT NOT NULL,
                    PRIMARY KEY(from_id, from_version, to_id, to_version),
                    FOREIGN KEY (from_id, from_version) REFERENCES capabilities(capability_id, version),
                    FOREIGN KEY (to_id, to_version) REFERENCES capabilities(capability_id, version)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE events (
                    event_seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    kind TEXT NOT NULL,
                    capability_id TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v2 新增两表，字段/约束按 B0 任务书固定契约：
     * verification_runs 保存“某能力某版本跑过哪套可信用例、结果如何”；activations 保存据此产生的激活记录。
     * 两表只追加，本步不写任何行；旧四表与 status CHECK 保持不变。
     */
    private fun createV2Tables() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE verification_runs (
                    run_id TEXT PRIMARY KEY,
                    capability_id TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK(version > 0),
                    content_hash TEXT NOT NULL,
                    suite_id TEXT NOT NULL,
                    suite_version INTEGER NOT NULL CHECK(suite_version > 0),
                    suite_hash TEXT NOT NULL,
                    case_count INTEGER NOT NULL CHECK(case_count >= 0),
                    passed_count INTEGER NOT NULL CHECK(passed_count >= 0),
                    failed_count INTEGER NOT NULL CHECK(failed_count >= 0),
                    verdict TEXT NOT NULL CHECK(verdict IN ('pass','fail')),
                    source TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(capability_id,version) REFERENCES capabilities(capability_id,version)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE activations (
                    activation_id TEXT PRIMARY KEY,
                    capability_id TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK(version > 0),
                    content_hash TEXT NOT NULL,
                    verification_run_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(capability_id,version) REFERENCES capabilities(capability_id,version),
                    FOREIGN KEY(verification_run_id) REFERENCES verification_runs(run_id)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * v3 撤销表：只追加。撤销不删除旧能力、不覆盖验证/激活记录，
     * 因此历史审计仍可读取该版本，正式入口则根据本表拒绝执行。
     */
    private fun createV3Tables() {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE revocations (
                    revoke_id TEXT PRIMARY KEY,
                    capability_id TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK(version > 0),
                    content_hash TEXT NOT NULL,
                    reason_code TEXT NOT NULL,
                    evidence_ref TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY(capability_id,version) REFERENCES capabilities(capability_id,version)
                )
                """.trimIndent(),
            )
        }
    }

    override fun propose(def: CapabilityDefinition, provenanceJson: String): RegistryResult {
        structuralError(def)?.let { return RegistryResult(false, it) }
        // 2026-11-04 审查修正：签名必须可经 CanonicalJson → decodeType 往返，避免 propose 成功但 inspect 失败。
        for (type in def.inputs.values + def.output) {
            val roundtrip = try {
                CapabilityJson.decodeType(CanonicalJson.type(type))
            } catch (_: Exception) {
                return RegistryResult(false, "invalid_type")
            }
            if (roundtrip != type) return RegistryResult(false, "invalid_type")
        }
        validateProvenance(provenanceJson)?.let { return RegistryResult(false, it) }
        val bodyHash = try { CanonicalJson.astHash(def.body) } catch (_: Exception) { return RegistryResult(false, "invalid_definition") }
        val contentHash = try { def.contentHash() } catch (_: Exception) { return RegistryResult(false, "invalid_definition") }
        val canonicalBody = try { CanonicalJson.expr(def.body) } catch (_: Exception) { return RegistryResult(false, "invalid_definition") }
        if (canonicalBody.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return RegistryResult(false, "definition_too_large")

        val existing = existingRow(def.id, def.version)
        if (existing != null) {
            // 2026-11-04 审查修正：幂等路径先做完整性检查，损坏记录不能被 already_exists 掩盖。
            val inspected = inspect(def.id, def.version)
            if (!inspected.ok) return RegistryResult(false, inspected.code ?: "invalid_stored_definition")
            return if (inspected.contentHash == contentHash) {
                RegistryResult(true, "already_exists", def, contentHash, bodyHash)
            } else {
                RegistryResult(false, "immutable_version_conflict")
            }
        }
        readBodyRaw(bodyHash)?.let { stored ->
            if (stored != canonicalBody) return RegistryResult(false, "body_corruption")
        }

        val dependencies = try { extractDependencies(def.body) } catch (error: RegistryException) {
            return RegistryResult(false, error.code)
        }
        for ((key, pinnedHash) in dependencies) {
            val dependency = inspect(key.first, key.second)
            if (!dependency.ok) {
                return RegistryResult(false, if (dependency.code == "not_found") "missing_dependency" else dependency.code)
            }
            val actual = dependency.definition?.contentHash() ?: return RegistryResult(false, "invalid_dependency_definition")
            if (actual != pinnedHash) return RegistryResult(false, "dependency_hash_mismatch")
        }

        val checked = CapabilityTypeChecker(this).check(def)
        if (!checked.ok) return RegistryResult(false, checked.code ?: "type_error")

        connection.autoCommit = false
        try {
            insertBody(bodyHash, def.body)
            connection.prepareStatement(
                "INSERT INTO capabilities(capability_id,version,content_hash,semantics_version,inputs_json,output_json,body_hash,provenance_json,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, def.id)
                statement.setInt(2, def.version)
                statement.setString(3, contentHash)
                statement.setString(4, IR_SEMANTICS_VERSION)
                statement.setString(5, encodeTypes(def.inputs))
                statement.setString(6, CanonicalJson.type(def.output))
                statement.setString(7, bodyHash)
                statement.setString(8, provenanceJson)
                statement.setString(9, "candidate")
                statement.setString(10, Instant.now().toString())
                statement.executeUpdate()
            }
            for ((key, pinnedHash) in dependencies) {
                connection.prepareStatement(
                    "INSERT INTO dependencies(from_id,from_version,to_id,to_version,to_content_hash) VALUES(?,?,?,?,?)",
                ).use { statement ->
                    statement.setString(1, def.id)
                    statement.setInt(2, def.version)
                    statement.setString(3, key.first)
                    statement.setInt(4, key.second)
                    statement.setString(5, pinnedHash)
                    statement.executeUpdate()
                }
            }
            connection.prepareStatement(
                "INSERT INTO events(kind,capability_id,version,payload_json,created_at) VALUES(?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, "proposed")
                statement.setString(2, def.id)
                statement.setInt(3, def.version)
                statement.setString(4, buildJsonObject { put("content_hash", contentHash); put("body_hash", bodyHash) }.toString())
                statement.setString(5, Instant.now().toString())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            return RegistryResult(false, "transaction_failed", null)
        } finally {
            connection.autoCommit = true
        }
        return RegistryResult(true, null, def, contentHash, bodyHash)
    }

    /**
     * 完整检查：先校验本行（inspectSelf），再逐层校验依赖。
     * 拆成两步是为了在失败时区分“根自身损坏”和“传递依赖损坏”，后者才能报 dependency_invalid + 路径。
     */
    override fun inspect(id: String, version: Int): RegistryResult {
        val self = inspectSelf(id, version)
        if (!self.ok) return self
        return try {
            val definition = self.definition ?: return RegistryResult(false, "invalid_stored_definition")
            for ((key, pinned) in extractDependencies(definition.body)) {
                val dependency = inspect(key.first, key.second)
                if (!dependency.ok) return RegistryResult(false, dependency.code)
                if (dependency.definition?.contentHash() != pinned) return RegistryResult(false, "dependency_hash_mismatch")
            }
            self
        } catch (_: RegistryException) {
            RegistryResult(false, "invalid_stored_definition")
        } catch (_: Exception) {
            RegistryResult(false, "invalid_stored_definition")
        }
    }

    /** M2c-1b：只校验本行（签名/正文/两种哈希/依赖表与正文一致），不递归检查依赖。 */
    internal fun inspectSelf(id: String, version: Int): RegistryResult {
        val row = try {
            readRow(id, version) ?: return RegistryResult(false, "not_found")
        } catch (_: Exception) {
            return RegistryResult(false, "invalid_stored_definition")
        }
        if (row.semanticsVersion != IR_SEMANTICS_VERSION) return RegistryResult(false, "unsupported_ir_semantics_version")
        return try {
            val bodyText = readBody(row.bodyHash) ?: return RegistryResult(false, "missing_body")
            val body = CapabilityJson.decode(bodyText)
            if (CanonicalJson.astHash(body) != row.bodyHash) return RegistryResult(false, "body_hash_mismatch")
            val definition = CapabilityDefinition(id, version, row.inputs, row.output, body)
            if (definition.contentHash() != row.contentHash) return RegistryResult(false, "content_hash_mismatch")
            val recorded = readDependencies(id, version)
            val extracted = extractDependencies(body)
            if (recorded != extracted) return RegistryResult(false, "dependency_table_mismatch")
            RegistryResult(true, null, definition, row.contentHash, row.bodyHash)
        } catch (_: RegistryException) {
            RegistryResult(false, "invalid_stored_definition")
        } catch (_: Exception) {
            RegistryResult(false, "invalid_stored_definition")
        }
    }

    override fun resolve(id: String, version: Int): CapabilityDefinition? = inspect(id, version).definition

    /**
     * M2b-1c：把一次验证运行与对应事件放在同一事务里追加。
     * 只做追加，不更新旧行；任何一句失败都整笔回滚，不能出现“有 run 没有事件”的半写状态。
     * 调用方必须先 `inspect` 成功并拿到与库内一致的 contentHash，本方法不再重复执行能力。
     */
    internal fun appendVerificationRun(run: VerificationRunRow, payloadJson: String): RegistryResult {
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "INSERT INTO verification_runs(run_id,capability_id,version,content_hash,suite_id,suite_version,suite_hash,case_count,passed_count,failed_count,verdict,source,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, run.runId)
                statement.setString(2, run.capabilityId)
                statement.setInt(3, run.capabilityVersion)
                statement.setString(4, run.contentHash)
                statement.setString(5, run.suiteId)
                statement.setInt(6, run.suiteVersion)
                statement.setString(7, run.suiteHash)
                statement.setInt(8, run.caseCount)
                statement.setInt(9, run.passedCount)
                statement.setInt(10, run.failedCount)
                statement.setString(11, run.verdict)
                statement.setString(12, run.source)
                statement.setString(13, Instant.now().toString())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO events(kind,capability_id,version,payload_json,created_at) VALUES(?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, "verified")
                statement.setString(2, run.capabilityId)
                statement.setInt(3, run.capabilityVersion)
                statement.setString(4, payloadJson)
                statement.setString(5, Instant.now().toString())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (error: Exception) {
            try {
                connection.rollback()
            } catch (_: Exception) {
                // 回滚失败也要返回 transaction_failed；连接随后由调用方 close。
            }
            return RegistryResult(false, "transaction_failed", null)
        } finally {
            connection.autoCommit = true
        }
        return RegistryResult(true)
    }

    /** M2b-2a：按 run_id 读取验证记录，供激活门禁核对 (id,version,content_hash,verdict,suite) 是否一致。 */
    internal fun readVerificationRun(runId: String): VerificationRunSnapshot? =
        connection.prepareStatement(
            "SELECT capability_id,version,content_hash,suite_id,suite_version,suite_hash,case_count,passed_count,failed_count,verdict,source,created_at FROM verification_runs WHERE run_id=?",
        ).use { statement ->
            statement.setString(1, runId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                VerificationRunSnapshot(
                    runId = runId,
                    capabilityId = rows.getString(1),
                    capabilityVersion = rows.getInt(2),
                    contentHash = rows.getString(3),
                    suiteId = rows.getString(4),
                    suiteVersion = rows.getInt(5),
                    suiteHash = rows.getString(6),
                    caseCount = rows.getInt(7),
                    passedCount = rows.getInt(8),
                    failedCount = rows.getInt(9),
                    verdict = rows.getString(10),
                    source = rows.getString(11),
                    createdAt = rows.getString(12),
                )
            }
        }

    /** M2b-2a：读取某能力版本已有的激活记录；重复激活的幂等判断以此为准。 */
    internal fun readActivation(capabilityId: String, capabilityVersion: Int): ActivationSnapshot? =
        connection.prepareStatement(
            "SELECT activation_id,content_hash,verification_run_id FROM activations WHERE capability_id=? AND version=? ORDER BY created_at,activation_id LIMIT 1",
        ).use { statement ->
            statement.setString(1, capabilityId)
            statement.setInt(2, capabilityVersion)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else ActivationSnapshot(rows.getString(1), rows.getString(2), rows.getString(3))
            }
        }

    /** M2b-2a：读取依赖表，用于逐层检查“每个传递依赖都已激活且哈希一致”。 */
    internal fun recordedDependencies(capabilityId: String, capabilityVersion: Int): List<DependencyPin> {
        val result = ArrayList<DependencyPin>()
        connection.prepareStatement(
            "SELECT to_id,to_version,to_content_hash FROM dependencies WHERE from_id=? AND from_version=? ORDER BY to_id,to_version",
        ).use { statement ->
            statement.setString(1, capabilityId)
            statement.setInt(2, capabilityVersion)
            statement.executeQuery().use { rows ->
                while (rows.next()) result += DependencyPin(rows.getString(1), rows.getInt(2), rows.getString(3))
            }
        }
        return result
    }

    /** M2b-2a：激活记录与 activated 事件同事务追加；只追加，不触碰 capabilities.status。 */
    internal fun appendActivation(row: ActivationRow, payloadJson: String): RegistryResult {
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "INSERT INTO activations(activation_id,capability_id,version,content_hash,verification_run_id,created_at) VALUES(?,?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, row.activationId)
                statement.setString(2, row.capabilityId)
                statement.setInt(3, row.capabilityVersion)
                statement.setString(4, row.contentHash)
                statement.setString(5, row.verificationRunId)
                statement.setString(6, Instant.now().toString())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO events(kind,capability_id,version,payload_json,created_at) VALUES(?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, "activated")
                statement.setString(2, row.capabilityId)
                statement.setInt(3, row.capabilityVersion)
                statement.setString(4, payloadJson)
                statement.setString(5, Instant.now().toString())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (_: Exception) {
            try {
                connection.rollback()
            } catch (_: Exception) {
                // 同 appendVerificationRun：回滚失败仍返回 transaction_failed，不假装成功。
            }
            return RegistryResult(false, "transaction_failed", null)
        } finally {
            connection.autoCommit = true
        }
        return RegistryResult(true)
    }

    /**
     * M4-1a：返回库内全部能力的完整定义；任一能力不可检查则整体失败。
     * 用完整 `inspect`（含依赖校验），避免在损坏库上计算成本或统计。
     */
    internal fun capabilityInventory(): CapabilityInventory {
        val keys = LinkedHashSet<Pair<String, Int>>()
        connection.prepareStatement("SELECT capability_id,version FROM capabilities ORDER BY capability_id,version").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) keys += rows.getString(1) to rows.getInt(2)
            }
        }
        val definitions = ArrayList<CapabilityDefinition>(keys.size)
        for ((id, version) in keys) {
            val inspected = inspect(id, version)
            if (!inspected.ok) return CapabilityInventory.Failed(inspected.code ?: "inspection_failed")
            definitions += inspected.definition ?: return CapabilityInventory.Failed("invalid_stored_definition")
        }
        return CapabilityInventory.Ok(definitions)
    }

    /**
     * M3-1a：返回当前“逻辑上已激活且依赖链健康”的能力快照，供组合器使用。
     * 只读；撤销、验证失效、依赖失效或正文损坏的能力不会出现在结果里。
     */
    internal fun activeCapabilities(): List<CapabilityDefinition> {
        val keys = LinkedHashSet<Pair<String, Int>>()
        connection.prepareStatement("SELECT capability_id,version FROM activations ORDER BY capability_id,version").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) keys += rows.getString(1) to rows.getInt(2)
            }
        }
        val result = ArrayList<CapabilityDefinition>(keys.size)
        for ((id, version) in keys) {
            if (readRevocation(id, version) != null) continue
            val self = inspectSelf(id, version)
            val contentHash = self.contentHash
            val definition = self.definition
            if (!self.ok || contentHash == null || definition == null) continue
            val activation = readActivation(id, version) ?: continue
            if (activation.contentHash != contentHash) continue
            val run = readVerificationRun(activation.verificationRunId) ?: continue
            if (run.verdict != "pass") continue
            if (run.capabilityId != id || run.capabilityVersion != version || run.contentHash != contentHash) continue
            if (ActivationGate(this).check(id, version) != null) continue
            result += definition
        }
        return result
    }

    /** M2c-1a：读取某能力版本是否已被撤销；正式入口与激活门禁据此拒绝，历史审计仍可 inspect。 */
    internal fun readRevocation(capabilityId: String, capabilityVersion: Int): RevocationSnapshot? =
        connection.prepareStatement(
            "SELECT revoke_id,content_hash,reason_code,evidence_ref,created_at FROM revocations WHERE capability_id=? AND version=? ORDER BY created_at,revoke_id LIMIT 1",
        ).use { statement ->
            statement.setString(1, capabilityId)
            statement.setInt(2, capabilityVersion)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    null
                } else {
                    RevocationSnapshot(
                        revokeId = rows.getString(1),
                        capabilityId = capabilityId,
                        capabilityVersion = capabilityVersion,
                        contentHash = rows.getString(2),
                        reasonCode = rows.getString(3),
                        evidenceRef = rows.getString(4),
                        createdAt = rows.getString(5),
                    )
                }
            }
        }

    /** M2c-1a：撤销记录与 revoked 事件同事务追加；不删除、不更新任何旧行。 */
    internal fun appendRevocation(row: RevocationRow, payloadJson: String): RegistryResult {
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "INSERT INTO revocations(revoke_id,capability_id,version,content_hash,reason_code,evidence_ref,created_at) VALUES(?,?,?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, row.revokeId)
                statement.setString(2, row.capabilityId)
                statement.setInt(3, row.capabilityVersion)
                statement.setString(4, row.contentHash)
                statement.setString(5, row.reasonCode)
                statement.setString(6, row.evidenceRef)
                statement.setString(7, Instant.now().toString())
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO events(kind,capability_id,version,payload_json,created_at) VALUES(?,?,?,?,?)",
            ).use { statement ->
                statement.setString(1, "revoked")
                statement.setString(2, row.capabilityId)
                statement.setInt(3, row.capabilityVersion)
                statement.setString(4, payloadJson)
                statement.setString(5, Instant.now().toString())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (_: Exception) {
            try {
                connection.rollback()
            } catch (_: Exception) {
                // 与验证/激活写入一致：回滚失败返回 transaction_failed，不假装成功。
            }
            return RegistryResult(false, "transaction_failed", null)
        } finally {
            connection.autoCommit = true
        }
        return RegistryResult(true)
    }

    override fun close() {
        connection.close()
    }

    // ---------- 内部 ----------

    private fun structuralError(def: CapabilityDefinition): String? {
        if (def.id.isBlank() || def.version <= 0) return "invalid_signature"
        if (def.id.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES) return "invalid_signature"
        if (def.inputs.keys.any { it.isBlank() || it.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES }) return "invalid_signature"
        typeError(def.output, 0)?.let { return it }
        for ((_, type) in def.inputs) typeError(type, 0)?.let { return it }
        // 迭代遍历，避免递归在极深 AST 上栈溢出。
        val stack = ArrayDeque<Pair<Expr, Int>>()
        stack.addLast(def.body to 1)
        var nodes = 0
        while (stack.isNotEmpty()) {
            val (expr, depth) = stack.removeLast()
            nodes++
            if (nodes > MAX_AST_NODES) return "ast_node_limit"
            if (depth > 32) return "ast_depth_limit"
            when (expr) {
                is Expr.Arg -> typeError(expr.type, depth)?.let { return it }
                is Expr.Const -> {
                    val value = expr.value
                    if (value is CapValue.Str && value.value.toByteArray(Charsets.UTF_8).size > MAX_CONSTANT_BYTES) {
                        return "definition_too_large"
                    }
                }
                is Expr.Field -> stack.addLast(expr.record to depth + 1)
                is Expr.Eq -> { stack.addLast(expr.left to depth + 1); stack.addLast(expr.right to depth + 1) }
                is Expr.And -> { stack.addLast(expr.left to depth + 1); stack.addLast(expr.right to depth + 1) }
                is Expr.Or -> { stack.addLast(expr.left to depth + 1); stack.addLast(expr.right to depth + 1) }
                is Expr.Not -> stack.addLast(expr.value to depth + 1)
                is Expr.If -> {
                    stack.addLast(expr.condition to depth + 1)
                    stack.addLast(expr.whenTrue to depth + 1)
                    stack.addLast(expr.whenFalse to depth + 1)
                }
                is Expr.Let -> { stack.addLast(expr.value to depth + 1); stack.addLast(expr.body to depth + 1) }
                is Expr.Call -> expr.args.values.forEach { stack.addLast(it to depth + 1) }
                is Expr.Ref -> Unit
            }
        }
        return null
    }

    private fun typeError(type: CapType, depth: Int): String? {
        if (depth > 32) return "ast_depth_limit"
        val record = type as? CapType.Record ?: return null
        // 2026-11-04 审查修正：与 CapabilityJson.decodeType 对齐，拒绝空 Record 与空白字段名。
        if (record.fields.isEmpty()) return "invalid_type"
        if (record.fields.keys.any { it.isBlank() }) return "invalid_type"
        if (record.fields.size > MAX_RECORD_FIELDS) return "record_field_limit"
        for (field in record.fields.values) typeError(field, depth + 1)?.let { return it }
        return null
    }

    private data class Row(val contentHash: String, val bodyHash: String, val inputs: Map<String, CapType>, val output: CapType, val semanticsVersion: String)

    private fun existingRow(id: String, version: Int): String? =
        connection.prepareStatement("SELECT content_hash FROM capabilities WHERE capability_id=? AND version=?").use { statement ->
            statement.setString(1, id); statement.setInt(2, version)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun readRow(id: String, version: Int): Row? =
        connection.prepareStatement("SELECT content_hash,body_hash,inputs_json,output_json,semantics_version FROM capabilities WHERE capability_id=? AND version=?").use { statement ->
            statement.setString(1, id); statement.setInt(2, version)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                Row(rows.getString(1), rows.getString(2), decodeTypes(rows.getString(3)), CapabilityJson.decodeType(rows.getString(4)), rows.getString(5))
            }
        }

    private fun readBody(bodyHash: String): String? =
        connection.prepareStatement("SELECT canonical_ir_json FROM bodies WHERE body_hash=?").use { statement ->
            statement.setString(1, bodyHash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun readDependencies(id: String, version: Int): Map<Pair<String, Int>, String> {
        val result = LinkedHashMap<Pair<String, Int>, String>()
        connection.prepareStatement("SELECT to_id,to_version,to_content_hash FROM dependencies WHERE from_id=? AND from_version=? ORDER BY to_id,to_version").use { statement ->
            statement.setString(1, id); statement.setInt(2, version)
            statement.executeQuery().use { rows ->
                while (rows.next()) result[rows.getString(1) to rows.getInt(2)] = rows.getString(3)
            }
        }
        return result
    }

    private fun extractDependencies(body: Expr): Map<Pair<String, Int>, String> {
        val result = LinkedHashMap<Pair<String, Int>, String>()
        val stack = ArrayDeque<Expr>()
        stack.addLast(body)
        while (stack.isNotEmpty()) {
            when (val expr = stack.removeLast()) {
                is Expr.Call -> {
                    val key = expr.id to expr.version
                    val previous = result.put(key, expr.bodyHash)
                    if (previous != null && previous != expr.bodyHash) throw RegistryException("dependency_conflict")
                    expr.args.values.forEach { stack.addLast(it) }
                }
                is Expr.Field -> stack.addLast(expr.record)
                is Expr.Eq -> { stack.addLast(expr.left); stack.addLast(expr.right) }
                is Expr.And -> { stack.addLast(expr.left); stack.addLast(expr.right) }
                is Expr.Or -> { stack.addLast(expr.left); stack.addLast(expr.right) }
                is Expr.Not -> stack.addLast(expr.value)
                is Expr.If -> { stack.addLast(expr.condition); stack.addLast(expr.whenTrue); stack.addLast(expr.whenFalse) }
                is Expr.Let -> { stack.addLast(expr.value); stack.addLast(expr.body) }
                else -> Unit
            }
        }
        return result
    }

    private fun validateProvenance(text: String): String? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_PROVENANCE_BYTES) return "invalid_provenance"
        return try {
            if (Json.parseToJsonElement(text) !is JsonObject) "invalid_provenance" else null
        } catch (_: Exception) {
            "invalid_provenance"
        }
    }

    private fun readBodyRaw(bodyHash: String): String? =
        connection.prepareStatement("SELECT canonical_ir_json FROM bodies WHERE body_hash=?").use { statement ->
            statement.setString(1, bodyHash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    private fun insertBody(bodyHash: String, body: Expr) {
        val canonical = CanonicalJson.expr(body)
        connection.prepareStatement(
            "INSERT OR IGNORE INTO bodies(body_hash,canonical_ir_json,byte_size) VALUES(?,?,?)",
        ).use { statement ->
            statement.setString(1, bodyHash)
            statement.setString(2, canonical)
            statement.setInt(3, canonical.toByteArray(Charsets.UTF_8).size)
            statement.executeUpdate()
        }
    }

    private fun encodeTypes(types: Map<String, CapType>): String = buildJsonObject {
        types.toSortedMap().forEach { (name, type) -> put(name, Json.parseToJsonElement(CanonicalJson.type(type))) }
    }.toString()

    private fun decodeTypes(text: String): Map<String, CapType> {
        val obj = Json.parseToJsonElement(text).jsonObject
        return obj.mapValues { CapabilityJson.decodeType(it.value.toString()) }
    }
}
