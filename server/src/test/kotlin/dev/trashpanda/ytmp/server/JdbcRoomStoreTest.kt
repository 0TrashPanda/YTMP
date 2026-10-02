package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.SavedMember
import dev.trashpanda.ytmp.core.SavedRoom
import dev.trashpanda.ytmp.protocol.RoomVisibility
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class JdbcRoomStoreTest {
    private fun room(code: String, name: String) = SavedRoom(
        code = code,
        name = name,
        ownerToken = "owner",
        visibility = RoomVisibility.PUBLIC,
        members = listOf(SavedMember("p1", "token", "Anna", isOwner = true)),
        queue = emptyList(),
        history = emptyList(),
        current = null,
        positionMs = 1234,
        lastActive = 42,
    )

    private fun stores(): List<() -> JdbcRoomStore> {
        val dir = Files.createTempDirectory("ytmp")
        val sqlite = { JdbcRoomStore(Database.open(DatabaseConfig(type = "sqlite", path = dir.resolve("sub/ytmp.db").toString()))) }
        // Set YTMP_TEST_POSTGRES (e.g. postgresql://ytmp:ytmp@localhost/ytmp) to also test PostgreSQL.
        val postgres = System.getenv("YTMP_TEST_POSTGRES")?.let { url -> { JdbcRoomStore(Database.open(DatabaseConfig(type = "postgres", url = url))) } }
        return listOfNotNull(sqlite, postgres)
    }

    @Test
    fun `rooms are saved, replaced and deleted, and survive reopening`() {
        for (open in stores()) {
            open().apply { loadAll().forEach { delete(it.code) } }

            val store = open()
            store.save(room("ABCD", "First"))
            store.save(room("EFGH", "Other"))
            store.save(room("ABCD", "Renamed"))
            store.delete("EFGH")

            assertEquals(listOf(room("ABCD", "Renamed")), open().loadAll())
        }
    }
}
