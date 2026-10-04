package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.Room
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SavedRoom
import dev.trashpanda.ytmp.host.AccountTokens
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.host.bearerToken
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.MovedRoomResponse
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RoomCopyRequest
import dev.trashpanda.ytmp.protocol.RoomHeartbeatRequest
import dev.trashpanda.ytmp.protocol.RoomHeartbeatResponse
import dev.trashpanda.ytmp.protocol.RoomHome
import dev.trashpanda.ytmp.protocol.RoomMovedAway
import dev.trashpanda.ytmp.protocol.RoomOpenMode
import dev.trashpanda.ytmp.protocol.RoomSnapshot
import dev.trashpanda.ytmp.protocol.SyncedRoom
import dev.trashpanda.ytmp.protocol.SyncedRoomsResponse
import dev.trashpanda.ytmp.protocol.TakeRoomRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Where each account's rooms live (docs/features/room-sync.md). A room lives on one host at a
 * time: this server, or one of the account's phones. Rooms here are in [rooms]; for rooms on
 * a phone, the phone sends its latest state, kept here as a copy. A room moves when it's
 * opened on another host ([take]): it gets a higher epoch there, and its old home closes it
 * when it hears so (right away here; a phone at its next heartbeat or save). The highest epoch
 * wins; changes the old home made in the meantime are dropped.
 */
class RoomHub(
    private val db: Database,
    private val rooms: RoomManager,
    private val issuer: String,
    /** This server's public address (accounts.url), for links to rooms here. */
    private val ownUrl: String?,
    /** The server's name, shown as where a room lives. */
    private val serverName: String,
    /** Checks a host token of one of this server's accounts (for any host): the claims, or null. */
    private val verify: (String) -> AccountTokens.Claims?,
    /** The account's setting for opening rooms elsewhere. */
    private val openSetting: (username: String) -> RoomOpenMode,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val serverHome get() = RoomHome(HostKind.SERVER, SERVER_ID, serverName, ownUrl)

    private class Copy(val syncId: String, val account: String, val home: RoomHome, val epoch: Int, val data: String, val lastSeen: Long) {
        val saved: SavedRoom get() = SavedRoom.fromJson(data)
    }

    // --- what hosts and pages ask -------------------------------------------------------

    /** All of [account]'s rooms: the ones here, and copies of the ones elsewhere. */
    suspend fun list(account: String, username: String): SyncedRoomsResponse = mutex.withLock {
        val here = rooms.all().filter { it.ownerAccount == account && it.syncId != null }.map { room ->
            val saved = room.save()
            synced(saved, room.code, serverHome, room.epoch, clock(), saved.playing)
        }
        val elsewhere = io { copies(account) }.mapNotNull { copy ->
            runCatching { copy.saved }.getOrNull()?.let { synced(it, it.code, copy.home, copy.epoch, copy.lastSeen, it.playing) }
        }
        SyncedRoomsResponse(io { openSetting(username) }, here + elsewhere)
    }

    /**
     * A host's latest state of a room that lives there. Null when it's taken; else where the
     * room is now (the host closes its copy: it moved, or another host has a newer one).
     */
    suspend fun put(account: String, syncId: String, request: RoomCopyRequest): RoomMovedAway? = mutex.withLock {
        val saved = runCatching { SavedRoom.fromJson(request.room) }.getOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Unreadable room")
        if (saved.ownerAccount != account || saved.syncId != syncId) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Not your room")
        movedAway(account, syncId, request.home, request.epoch)?.let { return@withLock it }
        // Newer than the room here (it moved there while this server didn't know): this one goes.
        rooms.bySyncId(syncId)?.let { here -> rooms.movedAway(here.code, roomUrl(request.home, saved.code)) }
        io { saveCopy(Copy(syncId, account, request.home, request.epoch, request.room, clock())) }
        null
    }

    /** A host is up with these rooms (sync ID -> epoch): the ones that moved away. */
    suspend fun heartbeat(account: String, request: RoomHeartbeatRequest): RoomHeartbeatResponse = mutex.withLock {
        val moved = request.rooms.mapNotNull { (syncId, epoch) -> movedAway(account, syncId, request.home, epoch) }
        io { touch(account, request.home.hostId, clock()) }
        RoomHeartbeatResponse(moved)
    }

    /** A host closed one of its rooms (not moved): forget the copy. */
    suspend fun closed(account: String, syncId: String, hostId: String) = mutex.withLock {
        io { deleteCopy(syncId, account, hostId) }
    }

    /** Moves a room to [home]: its state, with the epoch it has there from now on. */
    suspend fun take(account: String, syncId: String, home: RoomHome): RoomSnapshot = mutex.withLock {
        val here = rooms.bySyncId(syncId)?.takeIf { it.ownerAccount == account }
        // Already here.
        if (here != null && home.hostId == SERVER_ID) return@withLock RoomSnapshot(here.epoch, here.save().toJson())
        val copy = if (here == null) io { copy(syncId) }?.takeIf { it.account == account } else null
        val saved = here?.save() ?: copy?.saved ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
        val epoch = (here?.epoch ?: copy!!.epoch) + 1
        val json = saved.toJson()
        if (home.hostId == SERVER_ID) {
            // Here: a room on a phone continues on this server. The phone closes it when it checks in.
            val room = rooms.adopt(saved, epoch)
            io { deleteCopy(syncId, account, copy?.home?.hostId ?: "") }
            log.info("Room {} of {} moved to this server", room.code, account)
        } else {
            // To a phone: from here (closed now) or from another phone (closed when it checks in).
            if (here != null) rooms.movedAway(here.code, roomUrl(home, here.code))
            io { saveCopy(Copy(syncId, account, home, epoch, json, clock())) }
            log.info("Room {} of {} moved to {}", saved.code, account, home.hostName)
        }
        RoomSnapshot(epoch, json)
    }

    /** Null if [epoch] at [home] is the real room; else where the room is now. */
    private suspend fun movedAway(account: String, syncId: String, home: RoomHome, epoch: Int): RoomMovedAway? {
        rooms.bySyncId(syncId)?.takeIf { it.ownerAccount == account }?.let { here ->
            return if (epoch > here.epoch) null else RoomMovedAway(syncId, roomUrl(serverHome, here.code))
        }
        val copy = io { copy(syncId) }?.takeIf { it.account == account } ?: return null
        if (epoch > copy.epoch || (epoch == copy.epoch && copy.home.hostId == home.hostId)) return null
        return RoomMovedAway(syncId, roomUrl(copy.home, runCatching { copy.saved.code }.getOrDefault("")))
    }

    private fun synced(saved: SavedRoom, code: String, home: RoomHome, epoch: Int, lastSeen: Long, playing: Boolean) = SyncedRoom(
        syncId = saved.syncId!!,
        code = code,
        name = saved.name,
        visibility = saved.visibility,
        home = home,
        epoch = epoch,
        lastSeen = lastSeen,
        playing = playing,
        nowPlaying = saved.current?.song?.title,
        openElsewhere = saved.settings?.openElsewhere,
    )

    private fun roomUrl(home: RoomHome, code: String): String? = home.url?.let { "${it.trimEnd('/')}/room/$code" }

    // --- routes -------------------------------------------------------------------------

    fun routes(route: Route) = with(route) {
        fun ApplicationCall.owner(): AccountTokens.Claims =
            bearerToken()?.let(verify) ?: throw ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Log in to see your rooms")
        fun AccountTokens.Claims.account() = "$sub@$issuer"

        // Hosts (a phone), with a host token of the room's owner.
        get("/auth/rooms") {
            val me = call.owner()
            call.respond(list(me.account(), me.sub))
        }
        put("/auth/rooms/{syncId}") {
            val me = call.owner()
            val moved = put(me.account(), call.parameters["syncId"]!!, call.receive<RoomCopyRequest>())
            if (moved == null) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.Conflict, moved)
        }
        delete("/auth/rooms/{syncId}") {
            val me = call.owner()
            closed(me.account(), call.parameters["syncId"]!!, call.request.queryParameters["host"].orEmpty())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/auth/rooms/heartbeat") {
            val me = call.owner()
            call.respond(heartbeat(me.account(), call.receive<RoomHeartbeatRequest>()))
        }
        post("/auth/rooms/{syncId}/take") {
            val me = call.owner()
            call.respond(take(me.account(), call.parameters["syncId"]!!, call.receive<TakeRoomRequest>().home))
        }

        // This server's own pages: your rooms that live elsewhere, and moving one here.
        get("/rooms/elsewhere") {
            val me = call.owner()
            val all = list(me.account(), me.sub)
            call.respond(all.copy(rooms = all.rooms.filter { it.home.hostId != SERVER_ID }))
        }
        post("/rooms/elsewhere/{syncId}/move") {
            val me = call.owner()
            val syncId = call.parameters["syncId"]!!
            take(me.account(), syncId, serverHome)
            val room = rooms.bySyncId(syncId) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Room not found")
            call.respond(MovedRoomResponse(room.code))
        }
    }

    // --- storage ------------------------------------------------------------------------

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun copies(account: String): List<Copy> = db.use { c ->
        c.prepareStatement("SELECT sync_id, account, home, epoch, data, last_seen FROM room_copies WHERE account = ?").use {
            it.setString(1, account)
            val rows = it.executeQuery()
            buildList { while (rows.next()) add(rows.toCopy()) }
        }
    }

    private fun copy(syncId: String): Copy? = db.use { c ->
        c.prepareStatement("SELECT sync_id, account, home, epoch, data, last_seen FROM room_copies WHERE sync_id = ?").use {
            it.setString(1, syncId)
            val rows = it.executeQuery()
            if (rows.next()) rows.toCopy() else null
        }
    }

    private fun java.sql.ResultSet.toCopy() = Copy(
        getString("sync_id"), getString("account"), ProtocolJson.decodeFromString(RoomHome.serializer(), getString("home")),
        getInt("epoch"), getString("data"), getLong("last_seen"),
    )

    private fun saveCopy(copy: Copy) = db.use { c ->
        c.prepareStatement(
            "INSERT INTO room_copies (sync_id, account, home, epoch, data, last_seen) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (sync_id) DO UPDATE SET account = excluded.account, home = excluded.home, epoch = excluded.epoch, " +
                "data = excluded.data, last_seen = excluded.last_seen",
        ).use {
            it.setString(1, copy.syncId)
            it.setString(2, copy.account)
            it.setString(3, ProtocolJson.encodeToString(RoomHome.serializer(), copy.home))
            it.setInt(4, copy.epoch)
            it.setString(5, copy.data)
            it.setLong(6, copy.lastSeen)
            it.executeUpdate()
        }
        Unit
    }

    /** A host checked in: its copies were seen now. */
    private fun touch(account: String, hostId: String, now: Long) = db.use { c ->
        // The host ID is inside the home JSON; a few rows per account, so filter here.
        for (copy in copies(account).filter { it.home.hostId == hostId }) {
            c.prepareStatement("UPDATE room_copies SET last_seen = ? WHERE sync_id = ?").use {
                it.setLong(1, now)
                it.setString(2, copy.syncId)
                it.executeUpdate()
            }
        }
    }

    private fun deleteCopy(syncId: String, account: String, hostId: String) = db.use { c ->
        val copy = copy(syncId)?.takeIf { it.account == account && it.home.hostId == hostId } ?: return@use
        c.prepareStatement("DELETE FROM room_copies WHERE sync_id = ?").use {
            it.setString(1, copy.syncId)
            it.executeUpdate()
        }
    }

    companion object {
        const val SERVER_ID = "server"
        private val log = LoggerFactory.getLogger(RoomHub::class.java)
    }
}
