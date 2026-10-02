package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomStore
import dev.trashpanda.ytmp.core.SavedRoom
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLDecoder
import java.sql.Connection
import java.sql.DriverManager

/**
 * Rooms in the server database, SQLite or PostgreSQL. Each room is one row with its state as
 * JSON. The SQL is the same for both. [RoomManager][dev.trashpanda.ytmp.core.RoomManager]
 * calls this one at a time, so one connection is enough.
 */
class JdbcRoomStore(private val url: String, private val user: String? = null, private val password: String? = null) : RoomStore {
    private var connection: Connection? = null

    init {
        connection().createStatement().use {
            it.executeUpdate("CREATE TABLE IF NOT EXISTS rooms (code VARCHAR(16) PRIMARY KEY, data TEXT NOT NULL, updated_at BIGINT NOT NULL)")
        }
    }

    override fun loadAll(): List<SavedRoom> = connection().createStatement().use { statement ->
        val rows = statement.executeQuery("SELECT code, data FROM rooms")
        buildList {
            while (rows.next()) {
                runCatching { add(SavedRoom.fromJson(rows.getString("data"))) }
                    .onFailure { log.warn("Skipping room {}: can't read it", rows.getString("code"), it) }
            }
        }
    }

    override fun save(room: SavedRoom) {
        connection().prepareStatement(
            "INSERT INTO rooms (code, data, updated_at) VALUES (?, ?, ?) " +
                "ON CONFLICT (code) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at",
        ).use {
            it.setString(1, room.code)
            it.setString(2, room.toJson())
            it.setLong(3, System.currentTimeMillis())
            it.executeUpdate()
        }
    }

    override fun delete(code: String) {
        connection().prepareStatement("DELETE FROM rooms WHERE code = ?").use {
            it.setString(1, code)
            it.executeUpdate()
        }
    }

    /** The open connection, reopened if it was lost (e.g. PostgreSQL restarted). */
    private fun connection(): Connection {
        connection?.takeIf { !it.isClosed && it.isValid(2) }?.let { return it }
        connection?.runCatching { close() }
        return DriverManager.getConnection(url, user, password).also { connection = it }
    }

    companion object {
        private val log = LoggerFactory.getLogger(JdbcRoomStore::class.java)

        fun open(config: DatabaseConfig): JdbcRoomStore = when (config.type.lowercase()) {
            "sqlite" -> {
                File(config.path).absoluteFile.parentFile?.mkdirs()
                JdbcRoomStore("jdbc:sqlite:${config.path}").apply {
                    connection().createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
                }
            }
            "postgres", "postgresql" -> {
                // Accept the usual postgresql://user:pass@host/db form as well as a JDBC URL.
                val url = config.url.removePrefix("jdbc:").replaceFirst(Regex("^postgres(ql)?://"), "")
                val userInfo = url.substringBeforeLast('@', "").takeIf { '@' in url }
                fun decode(part: String) = URLDecoder.decode(part, Charsets.UTF_8)
                val user = userInfo?.substringBefore(':')?.let(::decode)
                val password = userInfo?.takeIf { ':' in it }?.substringAfter(':')?.let(::decode)
                JdbcRoomStore("jdbc:postgresql://${url.substringAfterLast('@')}", user, password)
            }
            else -> error("Unknown database type '${config.type}' (use sqlite or postgres)")
        }
    }
}
