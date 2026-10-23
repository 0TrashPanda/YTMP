package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.protocol.AccountListResponse
import dev.trashpanda.ytmp.protocol.AdminAccountInfo
import dev.trashpanda.ytmp.protocol.AdminRoomList
import dev.trashpanda.ytmp.protocol.CreateAccountRequest
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.InviteInfo
import dev.trashpanda.ytmp.protocol.InviteListResponse
import dev.trashpanda.ytmp.protocol.InviteResponse
import dev.trashpanda.ytmp.protocol.ServerAbout
import dev.trashpanda.ytmp.protocol.ServerSettingOverrides
import dev.trashpanda.ytmp.protocol.SetPasswordRequest
import dev.trashpanda.ytmp.protocol.UpdateAccountRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

/**
 * The server admin's page (Settings → Server admin, docs/features/server-admin.md): accounts,
 * invite links, server settings, rooms and versions. Only for accounts that are server admin.
 * The song cache has its own routes (`/admin/cache`, see Main.kt).
 */
class AdminApi(
    private val service: AccountService,
    private val rooms: RoomManager,
    /** Null: no settings to change (tests). */
    private val settings: ServerSettings? = null,
    private val versions: Versions? = null,
) {
    private val accounts get() = service.accounts

    fun routes(route: Route) = with(route) {
        get("/admin/accounts") {
            service.admin(call)
            call.respond(AccountListResponse(io { list() }))
        }

        post("/admin/accounts") {
            service.admin(call)
            val request = call.receive<CreateAccountRequest>()
            val account = io { service.accountCall { accounts.create(request.username, request.password, request.displayName, admin = request.isAdmin) } }
            log.info("Account {} created on the admin page{}", account.username, if (account.isAdmin) " (server admin)" else "")
            call.respond(service.toInfo(account))
        }

        // Rename, make admin or not.
        put("/admin/accounts/{username}") {
            val admin = service.admin(call)
            val request = call.receive<UpdateAccountRequest>()
            val account = io {
                val account = call.target()
                service.accountCall {
                    request.displayName?.let { accounts.rename(account.id, it) }
                    request.isAdmin?.let { accounts.setAdmin(account.id, it) }
                }
                accounts.byId(account.id)!!
            }
            if (request.isAdmin != null) log.info("{}: {} is {} server admin", admin.username, account.username, if (account.isAdmin) "now" else "no longer")
            call.respond(service.toInfo(account))
        }

        post("/admin/accounts/{username}/password") {
            service.admin(call)
            val request = call.receive<SetPasswordRequest>()
            io {
                val account = call.target()
                service.accountCall { accounts.setPassword(account.id, request.password) }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // Log out on every device (logins here; tokens rooms already have last until they expire).
        post("/admin/accounts/{username}/logout") {
            service.admin(call)
            io { accounts.endSessions(call.target().id) }
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/admin/accounts/{username}") {
            service.admin(call)
            val account = io { call.target() }
            io { service.accountCall { service.delete(account) } }
            // Its rooms here go too: the account is gone, and someone could sign up with its name.
            val id = service.toInfo(account).id
            rooms.all().filter { it.ownerAccount == id }.forEach { rooms.close(it.code) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/admin/invites") {
            service.admin(call)
            val names = io { accounts.list().associate { it.id to it.username } }
            val invites = io { accounts.openInvites() }
            call.respond(InviteListResponse(invites.map { InviteInfo(it.id, names[it.createdBy] ?: "?", it.createdAt, it.expiresAt) }))
        }

        post("/admin/invites") {
            val admin = service.admin(call)
            val (code, expires) = io { accounts.newInvite(admin.id, AccountService.INVITE_LIFETIME.inWholeMilliseconds) }
            call.respond(InviteResponse(code, expires))
        }

        delete("/admin/invites/{id}") {
            service.admin(call)
            if (!io { accounts.deleteInvite(call.parameters["id"]!!) }) throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such invite link")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/admin/settings") {
            service.admin(call)
            call.respond(settings?.admin ?: throw notHere())
        }

        put("/admin/settings") {
            val admin = service.admin(call)
            val new = call.receive<ServerSettingOverrides>()
            val settings = settings ?: throw notHere()
            io {
                try {
                    settings.change(new)
                } catch (e: IllegalArgumentException) {
                    throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "Invalid")
                } catch (e: IllegalStateException) {
                    throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "Invalid")
                }
            }
            log.info("{} changed the server settings", admin.username)
            call.respond(settings.admin)
        }

        get("/admin/rooms") {
            service.admin(call)
            call.respond(AdminRoomList(rooms.all().map { it.adminInfo() }.sortedByDescending { it.lastActive }))
        }

        delete("/admin/rooms/{code}") {
            val admin = service.admin(call)
            val room = rooms[call.parameters["code"]!!] ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such room")
            rooms.close(room.code)
            log.info("{} closed room {} ({}) on the admin page", admin.username, room.code, room.name)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/admin/about") {
            service.admin(call)
            val module = versions?.module()
            call.respond(
                ServerAbout(
                    version = versions?.version,
                    latestVersion = versions?.latest(),
                    ytDlp = module?.ytDlp,
                    ytmusicapi = module?.ytmusicapi,
                    moduleOnline = module != null,
                    accounts = io { accounts.count() },
                    rooms = rooms.count,
                ),
            )
        }
    }

    private fun list(): List<AdminAccountInfo> {
        val activity = accounts.activity()
        return accounts.list().map { account ->
            val info = service.toInfo(account)
            val seen = activity[account.id]
            AdminAccountInfo(info.id, info.username, info.displayName, info.isAdmin, seen?.createdAt ?: 0, seen?.lastSeen, seen?.logins ?: 0)
        }
    }

    private fun ApplicationCall.target(): Accounts.Account =
        accounts.find(parameters["username"]!!) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such account")

    private fun notHere() = ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No server settings here")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val log = LoggerFactory.getLogger(AdminApi::class.java)
    }
}

/**
 * Versions for the admin page: this server's ([version], from the release it was built from),
 * the YouTube Music module's yt-dlp and ytmusicapi, and the newest YTMP release on GitHub
 * (looked up at most every few hours).
 */
class Versions(
    private val http: HttpClient,
    /** The module's address and key. */
    private val moduleUrl: String,
    private val moduleKey: String,
    /** Null: not a release build. */
    val version: String? = System.getenv("YTMP_VERSION")?.trim()?.removePrefix("v")?.ifEmpty { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Module(val ytDlp: String? = null, val ytmusicapi: String? = null)

    @Serializable
    private data class Release(val tag_name: String)

    @Volatile
    private var latest: Pair<String?, Long>? = null

    /** The module's versions, or null if it doesn't answer. */
    suspend fun module(): Module? = runCatching {
        val response = http.get("$moduleUrl/info") { if (moduleKey.isNotEmpty()) header("Authorization", "Bearer $moduleKey") }
        if (response.status.isSuccess()) json.decodeFromString(Module.serializer(), response.body<String>()) else null
    }.getOrNull()

    /** The newest release's version, or null if GitHub can't be asked. */
    suspend fun latest(): String? {
        latest?.let { (version, at) -> if (clock() - at < CHECK_EVERY.inWholeMilliseconds) return version }
        val version = runCatching {
            val response = http.get(LATEST_RELEASE) { header("Accept", "application/vnd.github+json") }
            if (response.status.isSuccess()) json.decodeFromString(Release.serializer(), response.body<String>()).tag_name.removePrefix("v") else null
        }.onFailure { log.info("Couldn't ask GitHub for the newest release: {}", it.message) }.getOrNull()
        latest = version to clock()
        return version
    }

    private companion object {
        val log = LoggerFactory.getLogger(Versions::class.java)
        val json = Json { ignoreUnknownKeys = true }
        val CHECK_EVERY = 6.hours
        const val LATEST_RELEASE = "https://api.github.com/repos/0TrashPanda/YTMP/releases/latest"
    }
}
