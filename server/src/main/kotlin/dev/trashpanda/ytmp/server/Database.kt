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
class Database private constructor(private val url: String, private val user: String?, private val password: String?, val sqlitePath: String? = null) {
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
    private fun migrate() {
        val from = migrateInTransaction()
        // SQLite keeps freed space in the file until it's compacted, which can't happen in a transaction.
        if (sqlitePath != null && from < COMPACTED_AT && MIGRATIONS.size >= COMPACTED_AT) {
            use { c -> c.createStatement().use { it.executeUpdate("VACUUM") } }
            log.info("Database compacted")
        }
    }

    /** Runs the migrations; returns the version it was at before. */
    private fun migrateInTransaction(): Int = transaction { c ->
        c.createStatement().use { it.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)") }
        val current = c.createStatement().use { s -> s.executeQuery("SELECT MAX(version) FROM schema_version").use { if (it.next()) it.getInt(1) else 0 } }
        for ((index, statements) in MIGRATIONS.withIndex()) {
            val version = index + 1
            if (version <= current) continue
            // "sqlite:" / "postgres:" in front: only for that database.
            c.createStatement().use { s ->
                for (statement in statements) {
                    when {
                        statement.startsWith(SQLITE_ONLY) -> if (sqlitePath != null) s.executeUpdate(statement.removePrefix(SQLITE_ONLY))
                        statement.startsWith(POSTGRES_ONLY) -> if (sqlitePath == null) s.executeUpdate(statement.removePrefix(POSTGRES_ONLY))
                        else -> s.executeUpdate(statement)
                    }
                }
            }
            c.prepareStatement("INSERT INTO schema_version (version) VALUES (?)").use { it.setInt(1, version); it.executeUpdate() }
            log.info("Database migrated to version {}", version)
        }
        current
    }

    /** Closes the connection (SQLite then folds its journal into the file). */
    @Synchronized
    fun close() {
        connection?.runCatching { close() }
        connection = null
    }

    /** A consistent copy of the whole database in [to] (SQLite only; PostgreSQL has pg_dump). */
    fun backupTo(to: File) {
        check(sqlitePath != null) { "Backups from here are for SQLite; PostgreSQL has pg_dump" }
        to.delete()
        use { c -> c.createStatement().use { it.execute("VACUUM INTO '${to.absolutePath.replace("'", "''")}'") } }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Database::class.java)

        /** What a backup file holds. */
        data class BackupInfo(val accounts: Int, val rooms: Int)

        /** The file a restore waits in until the server starts again. */
        fun restoreFile(path: String) = File("$path.restore")

        /**
         * Checks that [file] is a backup of this server (a YTMP SQLite database no newer than this
         * version), and keeps it for the next start, which swaps it in ([applyPendingRestore]).
         */
        fun stageRestore(path: String, file: File): BackupInfo {
            val info = try {
                DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
                    fun count(sql: String) = c.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } }
                    val version = count("SELECT MAX(version) FROM schema_version")
                    require(version <= MIGRATIONS.size) { "This backup is from a newer YTMP (database version $version). Update this server first." }
                    BackupInfo(count("SELECT COUNT(*) FROM accounts"), count("SELECT COUNT(*) FROM rooms"))
                }
            } catch (e: java.sql.SQLException) {
                throw IllegalArgumentException("That isn't a YTMP backup")
            }
            val target = restoreFile(path)
            target.delete()
            if (!file.renameTo(target)) file.copyTo(target, overwrite = true).also { file.delete() }
            return info
        }

        /** A restore waiting from before: the database goes aside (kept as `….before-restore`) and the backup takes its place. */
        private fun applyPendingRestore(path: String) {
            val waiting = restoreFile(path).takeIf { it.isFile } ?: return
            val db = File(path)
            val aside = File("$path.before-restore")
            if (db.isFile) {
                // With its journal, if the server stopped without closing the database.
                for (suffix in listOf("", "-wal", "-shm")) File("$aside$suffix").delete()
                for (suffix in listOf("", "-wal", "-shm")) File("$path$suffix").takeIf { it.exists() }?.renameTo(File("$aside$suffix"))
            }
            for (suffix in listOf("-wal", "-shm")) File("$path$suffix").delete()
            waiting.renameTo(db)
            log.info("Restored the database from a backup; the old one is kept as {}", aside)
        }

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
            // 3: things saved to an account (role template, later preferences, …), as JSON
            listOf("CREATE TABLE account_data (account_id VARCHAR(32) NOT NULL, name VARCHAR(64) NOT NULL, value TEXT NOT NULL, PRIMARY KEY (account_id, name))"),
            // 4: listening history
            listOf(
                """CREATE TABLE plays (
                    id VARCHAR(32) PRIMARY KEY,
                    account_id VARCHAR(32) NOT NULL,
                    played_at BIGINT NOT NULL,
                    song_id VARCHAR(256) NOT NULL,
                    title VARCHAR(512) NOT NULL,
                    artists VARCHAR(512) NOT NULL,
                    song TEXT NOT NULL,
                    heard_ms BIGINT NOT NULL,
                    skipped BOOLEAN NOT NULL,
                    room_code VARCHAR(16) NOT NULL,
                    room_name VARCHAR(128) NOT NULL,
                    added_by_me BOOLEAN NOT NULL,
                    added_by_name VARCHAR(128) NOT NULL,
                    listened_with TEXT NOT NULL,
                    shared BOOLEAN NOT NULL
                )""",
                "CREATE INDEX plays_account_time ON plays (account_id, played_at)",
            ),
            // 5: copies of accounts' rooms that live on another host (a phone), see RoomHub
            listOf(
                """CREATE TABLE room_copies (
                    sync_id VARCHAR(64) PRIMARY KEY,
                    account VARCHAR(160) NOT NULL,
                    home TEXT NOT NULL,
                    epoch INTEGER NOT NULL,
                    data TEXT NOT NULL,
                    last_seen BIGINT NOT NULL
                )""",
                "CREATE INDEX room_copies_account ON room_copies (account)",
            ),
            // 6: the song graph, see SongGraph (account_id is '' for the server's and YouTube Music's edges)
            listOf(
                "CREATE TABLE songs (id VARCHAR(32) PRIMARY KEY, match_key VARCHAR(600) NOT NULL, duration_ms BIGINT NOT NULL)",
                "CREATE INDEX songs_match_key ON songs (match_key)",
                "CREATE TABLE song_sources (source_id VARCHAR(256) PRIMARY KEY, song_id VARCHAR(32) NOT NULL, data TEXT NOT NULL, updated_at BIGINT NOT NULL)",
                "CREATE INDEX song_sources_song ON song_sources (song_id)",
                """CREATE TABLE song_edges (
                    from_song VARCHAR(32) NOT NULL,
                    to_song VARCHAR(32) NOT NULL,
                    scope VARCHAR(16) NOT NULL,
                    account_id VARCHAR(32) NOT NULL,
                    solo_plays INTEGER NOT NULL,
                    shared_plays INTEGER NOT NULL,
                    skips INTEGER NOT NULL,
                    ytm_rank INTEGER,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (from_song, to_song, scope, account_id)
                )""",
                "CREATE INDEX song_edges_from ON song_edges (from_song, scope, account_id)",
                "CREATE INDEX song_edges_to ON song_edges (to_song, scope, account_id)",
                // The same play heard by several accounts (counted once in the server's graph).
                "CREATE INDEX plays_room_time ON plays (room_code, played_at)",
            ),
            // 7: how far accounts got in podcast episodes, see Episodes
            listOf(
                """CREATE TABLE episode_progress (
                    account_id VARCHAR(32) NOT NULL,
                    episode_id VARCHAR(256) NOT NULL,
                    position_ms BIGINT NOT NULL,
                    duration_ms BIGINT NOT NULL,
                    played BOOLEAN NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (account_id, episode_id)
                )""",
            ),
            // 8: accounts can be disabled (0: not; -1: until an admin enables it; else until that time), and what admins did
            listOf(
                "ALTER TABLE accounts ADD COLUMN disabled_until BIGINT NOT NULL DEFAULT 0",
                "CREATE TABLE audit_log (id VARCHAR(32) PRIMARY KEY, at BIGINT NOT NULL, actor VARCHAR(64) NOT NULL, action VARCHAR(64) NOT NULL, target VARCHAR(128) NOT NULL, detail TEXT NOT NULL)",
                "CREATE INDEX audit_log_at ON audit_log (at)",
            ),
            // 9: the song graph keeps what it learns from raw: every radio fetched (when, how, as whom),
            // and plays imported from Google Takeout (import_id; '' for plays in YTMP), see SongGraph
            listOf(
                """CREATE TABLE radio_fetches (
                    id VARCHAR(32) PRIMARY KEY,
                    seed VARCHAR(256) NOT NULL,
                    fetched_at BIGINT NOT NULL,
                    way VARCHAR(16) NOT NULL,
                    account_id VARCHAR(32) NOT NULL,
                    reported_by VARCHAR(32) NOT NULL,
                    songs TEXT NOT NULL
                )""",
                "CREATE INDEX radio_fetches_seed ON radio_fetches (seed, fetched_at)",
                "ALTER TABLE song_edges ADD COLUMN seen INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE song_edges ADD COLUMN rank_sum INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE plays ADD COLUMN import_id VARCHAR(32) NOT NULL DEFAULT ''",
                """CREATE TABLE history_imports (
                    id VARCHAR(32) PRIMARY KEY,
                    account_id VARCHAR(32) NOT NULL,
                    at BIGINT NOT NULL,
                    plays INTEGER NOT NULL,
                    duplicates INTEGER NOT NULL,
                    first_at BIGINT NOT NULL,
                    last_at BIGINT NOT NULL
                )""",
                "CREATE INDEX plays_account_song ON plays (account_id, song_id)",
            ),
            // 10: a Takeout link that happened once counts fully only once a radio confirms it (SongGraph.confirm)
            listOf("ALTER TABLE song_edges ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 0"),
            // 11: the song graph in less space, the same data: everyone's Takeout links are worked out from each
            // account's when read (not kept twice), song_edges_from goes (the key starts with from_song), and on
            // SQLite the edges live in their key's tree (WITHOUT ROWID: no second copy of the key).
            listOf(
                "DELETE FROM song_edges WHERE scope = 'server_imported'",
                "DROP INDEX song_edges_from",
                """${SQLITE_ONLY}CREATE TABLE song_edges_compact (
                    from_song VARCHAR(32) NOT NULL,
                    to_song VARCHAR(32) NOT NULL,
                    scope VARCHAR(16) NOT NULL,
                    account_id VARCHAR(32) NOT NULL,
                    solo_plays INTEGER NOT NULL,
                    shared_plays INTEGER NOT NULL,
                    skips INTEGER NOT NULL,
                    ytm_rank INTEGER,
                    updated_at BIGINT NOT NULL,
                    seen INTEGER NOT NULL DEFAULT 0,
                    rank_sum INTEGER NOT NULL DEFAULT 0,
                    confirmed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (from_song, to_song, scope, account_id)
                ) WITHOUT ROWID""",
                """${SQLITE_ONLY}INSERT INTO song_edges_compact (from_song, to_song, scope, account_id, solo_plays, shared_plays, skips, ytm_rank, updated_at, seen, rank_sum, confirmed)
                    SELECT from_song, to_song, scope, account_id, solo_plays, shared_plays, skips, ytm_rank, updated_at, seen, rank_sum, confirmed FROM song_edges""",
                "${SQLITE_ONLY}DROP TABLE song_edges",
                "${SQLITE_ONLY}ALTER TABLE song_edges_compact RENAME TO song_edges",
                "${SQLITE_ONLY}CREATE INDEX song_edges_to ON song_edges (to_song, scope, account_id)",
            ),
            // 12: a play reported twice (the same person in a room from two devices) is in the history once
            listOf(
                """DELETE FROM plays WHERE id NOT IN (
                    SELECT MIN(id) FROM plays GROUP BY account_id, song_id, played_at, room_code
                )""",
            ),
        )

        /** Migration 11 freed a lot of space: compact the file once after it. */
        private const val COMPACTED_AT = 11
        private const val SQLITE_ONLY = "sqlite:"
        private const val POSTGRES_ONLY = "postgres:"

        fun open(config: DatabaseConfig): Database = when (config.type.lowercase()) {
            "sqlite" -> {
                File(config.path).absoluteFile.parentFile?.mkdirs()
                applyPendingRestore(config.path)
                Database("jdbc:sqlite:${config.path}", null, null, config.path).apply {
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
