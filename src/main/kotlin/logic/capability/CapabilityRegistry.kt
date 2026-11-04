// 本文件实现：DeepSeek Harness（本会话），2026-11-04。
// 原因：B0 M2a 最小能力注册表；只允许 candidate，不做激活/验证/压缩。
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
        val version = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
        when (version) {
            0 -> {
                connection.autoCommit = false
                try {
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
                    connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
                    connection.commit()
                } catch (error: Exception) {
                    connection.rollback()
                    throw RegistryException("schema_init_failed", error.message ?: "schema init failed")
                } finally {
                    connection.autoCommit = true
                }
            }
            1 -> Unit
            else -> throw RegistryException("unsupported_schema_version", "user_version=$version")
        }
    }

    override fun propose(def: CapabilityDefinition, provenanceJson: String): RegistryResult {
        structuralError(def)?.let { return RegistryResult(false, it) }
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

    override fun inspect(id: String, version: Int): RegistryResult {
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
            for ((key, pinned) in extracted) {
                val dependency = inspect(key.first, key.second)
                if (!dependency.ok) return RegistryResult(false, dependency.code)
                if (dependency.definition?.contentHash() != pinned) return RegistryResult(false, "dependency_hash_mismatch")
            }
            RegistryResult(true, null, definition, row.contentHash, row.bodyHash)
        } catch (_: RegistryException) {
            RegistryResult(false, "invalid_stored_definition")
        } catch (_: Exception) {
            RegistryResult(false, "invalid_stored_definition")
        }
    }

    override fun resolve(id: String, version: Int): CapabilityDefinition? = inspect(id, version).definition

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
