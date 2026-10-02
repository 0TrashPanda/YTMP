package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomStore
import dev.trashpanda.ytmp.core.SavedRoom
import org.slf4j.LoggerFactory

/** Rooms in the server database: one row per room, its state as JSON. */
class JdbcRoomStore(private val db: Database) : RoomStore {
    override fun loadAll(): List<SavedRoom> = db.use { c ->
        c.createStatement().use { statement ->
            val rows = statement.executeQuery("SELECT code, data FROM rooms")
            buildList {
                while (rows.next()) {
                    runCatching { add(SavedRoom.fromJson(rows.getString("data"))) }
                        .onFailure { log.warn("Skipping room {}: can't read it", rows.getString("code"), it) }
                }
            }
        }
    }

    override fun save(room: SavedRoom) = db.use { c ->
        c.prepareStatement(
            "INSERT INTO rooms (code, data, updated_at) VALUES (?, ?, ?) " +
                "ON CONFLICT (code) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at",
        ).use {
            it.setString(1, room.code)
            it.setString(2, room.toJson())
            it.setLong(3, System.currentTimeMillis())
            it.executeUpdate()
        }
        Unit
    }

    override fun delete(code: String) = db.use { c ->
        c.prepareStatement("DELETE FROM rooms WHERE code = ?").use {
            it.setString(1, code)
            it.executeUpdate()
        }
        Unit
    }

    private companion object {
        val log = LoggerFactory.getLogger(JdbcRoomStore::class.java)
    }
}
