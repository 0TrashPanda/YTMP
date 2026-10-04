package dev.trashpanda.ytmp.host

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.trashpanda.ytmp.core.AccountIdentity
import dev.trashpanda.ytmp.core.Room
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SavedRoom
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.MovedRoomResponse
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RoomCopyRequest
import dev.trashpanda.ytmp.protocol.RoomHeartbeatRequest
import dev.trashpanda.ytmp.protocol.RoomHeartbeatResponse
import dev.trashpanda.ytmp.protocol.RoomHome
import dev.trashpanda.ytmp.protocol.RoomMovedAway
import dev.trashpanda.ytmp.protocol.RoomSnapshot
import dev.trashpanda.ytmp.protocol.SyncedRoomsResponse
import dev.trashpanda.ytmp.protocol.TakeRoomRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * This phone's part in syncing rooms with the account's server (docs/features/room-sync.md):
 * rooms that an account of the linked server owns are sent there whenever they're saved, the
 * phone checks in every few seconds, and a room that moved to another host is closed here
 * (changes made here meanwhile are dropped). The page lists your rooms on other hosts and can
 * move one here.
 *
 * Talking to the server needs a host token of the room's owner: the latest one the page or
 * a room connection used here is kept (they last 30 days).
 */
class RoomSync(
    private val context: Context,
    private val authLink: AuthLink,
    private val rooms: () -> RoomManager,
    private val lanUrl: () -> String?,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** This phone, as a place rooms live. */
    private val hostId: String = prefs.getString("hostId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("hostId", it).apply() }

    private val hostName: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private val home get() = RoomHome(HostKind.PHONE, hostId, hostName, lanUrl())

    /** An account token was accepted here: keep the newest per account. */
    fun remember(token: String, account: AccountIdentity) {
        if (prefs.getString("token:${account.id}", null) == token) return
        prefs.edit().putString("token:${account.id}", token).apply()
    }

    private fun token(account: String): String? = prefs.getString("token:$account", null)

    /** The linked server, if [account] is one of its accounts. */
    private fun serverFor(account: String?): String? {
        val server = authLink.auth.servers().firstOrNull() ?: return null
        return server.url?.takeIf { account != null && account.endsWith("@${server.issuer}") }
    }

    fun start() = scope.launch {
        while (isActive) {
            delay(HEARTBEAT)
            runCatching { heartbeat() }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /** A room was saved here: send it to its owner's server. */
    fun saved(room: SavedRoom) {
        val account = room.ownerAccount ?: return
        val server = serverFor(account) ?: return
        val token = token(account) ?: return
        val syncId = room.syncId ?: return
        scope.launch {
            val (status, body) = call("PUT", "$server/api/auth/rooms/$syncId", token, ProtocolJson.encodeToString(RoomCopyRequest.serializer(), RoomCopyRequest(home, room.epoch, room.toJson())))
                ?: return@launch
            if (status == 409) movedAway(ProtocolJson.decodeFromString(RoomMovedAway.serializer(), body))
            else if (status !in 200..299) Log.w(TAG, "Server didn't take room ${room.code}: $status")
        }
    }

    /** A room was closed here (not moved): the server forgets its copy. */
    fun closed(room: Room) {
        val account = room.ownerAccount ?: return
        val server = serverFor(account) ?: return
        val token = token(account) ?: return
        scope.launch { call("DELETE", "$server/api/auth/rooms/${room.syncId}?host=$hostId", token, null) }
    }

    /** Tells each account's server this phone is up, and closes rooms that moved away. */
    private suspend fun heartbeat() {
        val byAccount = rooms().all().filter { it.syncId != null && serverFor(it.ownerAccount) != null }.groupBy { it.ownerAccount!! }
        for ((account, mine) in byAccount) {
            val token = token(account) ?: continue
            val request = RoomHeartbeatRequest(home, mine.associate { it.syncId!! to it.epoch })
            val (status, body) = call("POST", "${serverFor(account)}/api/auth/rooms/heartbeat", token, ProtocolJson.encodeToString(RoomHeartbeatRequest.serializer(), request))
                ?: continue
            if (status != 200) continue
            ProtocolJson.decodeFromString(RoomHeartbeatResponse.serializer(), body).moved.forEach { movedAway(it) }
        }
    }

    private suspend fun movedAway(moved: RoomMovedAway) {
        val room = rooms().bySyncId(moved.syncId) ?: return
        Log.i(TAG, "Room ${room.code} moved to ${moved.url ?: "another host"}")
        rooms().movedAway(room.code, moved.url)
    }

    /** `GET /api/rooms/elsewhere` and `POST /api/rooms/elsewhere/{syncId}/move`, for this phone's own page. */
    fun routes(route: Route) = with(route) {
        fun ApplicationCall.pageToken(): Pair<String, String> {
            if (!isLoopback(this)) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the hosting device can do this")
            val token = bearerToken() ?: throw ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Log in to see your rooms")
            val account = authLink.auth.verify(token) ?: throw ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Log in to see your rooms")
            remember(token, account)
            val server = serverFor(account.id) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No server linked")
            return server to token
        }

        get("/rooms/elsewhere") {
            val (server, token) = call.pageToken()
            val (status, body) = call("GET", "$server/api/auth/rooms", token, null)
                ?: throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, "Can't reach $server")
            if (status != 200) throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, "$server answered $status")
            val all = ProtocolJson.decodeFromString(SyncedRoomsResponse.serializer(), body)
            call.respond(all.copy(rooms = all.rooms.filter { it.home.hostId != hostId }))
        }

        post("/rooms/elsewhere/{syncId}/move") {
            val (server, token) = call.pageToken()
            val syncId = call.parameters["syncId"]!!
            rooms().bySyncId(syncId)?.let { return@post call.respond(MovedRoomResponse(it.code)) }
            val (status, body) = call("POST", "$server/api/auth/rooms/$syncId/take", token, ProtocolJson.encodeToString(TakeRoomRequest.serializer(), TakeRoomRequest(home)))
                ?: throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, "Can't reach $server")
            if (status != 200) throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, errorMessage(body) ?: "$server answered $status")
            val snapshot = ProtocolJson.decodeFromString(RoomSnapshot.serializer(), body)
            val room = rooms().adopt(SavedRoom.fromJson(snapshot.room), snapshot.epoch)
            call.respond(MovedRoomResponse(room.code))
        }
    }

    private fun errorMessage(body: String): String? = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()

    /** One request to the server: status and body, or null when it can't be reached. */
    private suspend fun call(method: String, url: String, token: String, json: String?): Pair<Int, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            try {
                if (json != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(json.toByteArray()) }
                }
                val status = connection.responseCode
                val body = (if (status >= 400) connection.errorStream else connection.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                status to body
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.d(TAG, "$method $url: ${it.message}") }.getOrNull()
    }

    private companion object {
        const val TAG = "YtmpSync"
        val HEARTBEAT = 15.seconds
    }
}
