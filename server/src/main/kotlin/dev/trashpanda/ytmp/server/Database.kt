package dev.trashpanda.ytmp.server

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLDecoder
import java.sql.Connection
import java.sql.DriverManager

/**
 * The server database, SQLite or PostgreSQL, through one connection that is used by one
 * caller at a time. Plenty for a group of friends, and SQLite wants a single writer anyway.
 * Queries are plain SQL that both understand. See docs/implementation/storage.md.
 */
class Database private constructor(private val url: String, private val user: String?, private val password: String?) {
    private var connection: Connection? = null

    /** Runs [block] with the connection; nobody else uses it meanwhile. */
    @Synchronized
    fun <T> use(block: (Connection) -> T): T = block(connection())

    /** Like [use], in a transaction: all of [block] is saved, or none of it. */
    @Synchronized
    fun <T> transaction(block: (Connection) -> T): T {
        val c = connection()
        c.autoCommit = false
        try {
            return block(c).also { c.commit() }
        } catch (e: Throwable) {
            runCatching { c.rollback() }
            throw e
        } finally {
            runCatching { c.autoCommit = true }
        }
    }

    /** The open connection, reopened if it was lost (e.g. PostgreSQL restarted). */
    private fun connection(): Connection {
        connection?.takeIf { !it.isClosed && it.isValid(2) }?.let { return it }
        connection?.runCatching { close() }
        return DriverManager.getConnection(url, user, password).also { connection = it }
    }

    /** Brings the schema up to date: runs every migration newer than the stored version, in order. */
    private fun migrate() = transaction { c ->
        c.createStatement().use { it.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)") }
        val current = c.createStatement().use { s -> s.executeQuery("SELECT MAX(version) FROM schema_version").use { if (it.next()) it.getInt(1) else 0 } }
        for ((index, statements) in MIGRATIONS.withIndex()) {
            val version = index + 1
            if (version <= current) continue
            c.createStatement().use { s -> statements.forEach(s::executeUpdate) }
            c.prepareStatement("INSERT INTO schema_version (version) VALUES (?)").use { it.setInt(1, version); it.executeUpdate() }
            log.info("Database migrated to version {}", version)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Database::class.java)

        /** Add new migrations at the end; never change one that has shipped. */
        private val MIGRATIONS: List<List<String>> = listOf(
            // 1: rooms (may already exist from before migrations were tracked)
            listOf("CREATE TABLE IF NOT EXISTS rooms (code VARCHAR(16) PRIMARY KEY, data TEXT NOT NULL, updated_at BIGINT NOT NULL)"),
            // 2: accounts
            listOf(
                """CREATE TABLE accounts (
                    id VARCHAR(32) PRIMARY KEY,
                    username VARCHAR(32) NOT NULL UNIQUE,
                    display_name VARCHAR(64) NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    is_admin BOOLEAN NOT NULL,
                    created_at BIGINT NOT NULL
                )""",
                "CREATE TABLE sessions (token_hash VARCHAR(64) PRIMARY KEY, account_id VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, last_used BIGINT NOT NULL)",
                "CREATE INDEX sessions_account ON sessions (account_id)",
                "CREATE TABLE invites (code_hash VARCHAR(64) PRIMARY KEY, created_by VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, used_by VARCHAR(32))",
                "CREATE TABLE settings (name VARCHAR(64) PRIMARY KEY, value TEXT NOT NULL)",
            ),
        )

        fun open(config: DatabaseConfig): Database = when (config.type.lowercase()) {
            "sqlite" -> {
                File(config.path).absoluteFile.parentFile?.mkdirs()
                Database("jdbc:sqlite:${config.path}", null, null).apply {
                    use { c -> c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") } }
                }
            }
            "postgres", "postgresql" -> {
                // Accept the usual postgresql://user:pass@host/db form as well as a JDBC URL.
                val url = config.url.removePrefix("jdbc:").replaceFirst(Regex("^postgres(ql)?://"), "")
                val userInfo = url.substringBeforeLast('@', "").takeIf { '@' in url }
                fun decode(part: String) = URLDecoder.decode(part, Charsets.UTF_8)
                val user = userInfo?.substringBefore(':')?.let(::decode)
                val password = userInfo?.takeIf { ':' in it }?.substringAfter(':')?.let(::decode)
                Database("jdbc:postgresql://${url.substringAfterLast('@')}", user, password)
            }
            else -> error("Unknown database type '${config.type}' (use sqlite or postgres)")
        }.apply { migrate() }
    }
}
