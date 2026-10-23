package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.protocol.AuditEntry
import org.slf4j.LoggerFactory

/**
 * What the server's admins did (accounts, invites, settings, rooms, backups), for the admin
 * page's *Log* (docs/features/server-admin.md). Kept in the database; the newest [KEPT] stay.
 */
class AuditLog(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** [actor] did [action] to [target], with a few words of [detail]. Never fails the action itself. */
    fun record(actor: String, action: String, target: String = "", detail: String = "") {
        log.info("{}: {} {} {}", actor, action, target, detail)
        runCatching {
            db.transaction { c ->
                c.prepareStatement("INSERT INTO audit_log (id, at, actor, action, target, detail) VALUES (?, ?, ?, ?, ?, ?)").use {
                    it.setString(1, Ids.random(12))
                    it.setLong(2, clock())
                    it.setString(3, actor.take(64))
                    it.setString(4, action.take(64))
                    it.setString(5, target.take(128))
                    it.setString(6, detail.take(500))
                    it.executeUpdate()
                }
                c.prepareStatement("DELETE FROM audit_log WHERE at < (SELECT MIN(at) FROM (SELECT at FROM audit_log ORDER BY at DESC LIMIT ?) AS newest)").use {
                    it.setInt(1, KEPT)
                    it.executeUpdate()
                }
            }
        }.onFailure { log.warn("Couldn't write the admin log: {}", it.message) }
    }

    /** The newest entries, newest first. */
    fun recent(limit: Int = 200): List<AuditEntry> = db.use { c ->
        c.prepareStatement("SELECT at, actor, action, target, detail FROM audit_log ORDER BY at DESC LIMIT ?").use {
            it.setInt(1, limit)
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(AuditEntry(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5))) }
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AuditLog::class.java)
        const val KEPT = 1000
    }
}
