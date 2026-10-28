package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.protocol.AccountListResponse
import dev.trashpanda.ytmp.protocol.AdminAccountInfo
import dev.trashpanda.ytmp.protocol.AdminRoomList
import dev.trashpanda.ytmp.protocol.AuditLogResponse
import dev.trashpanda.ytmp.protocol.DisableAccountRequest
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
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import dev.trashpanda.ytmp.host.RadioCrawl
import dev.trashpanda.ytmp.protocol.RadioCrawlStatus
import io.ktor.server.response.header
import io.ktor.server.response.respondFile
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
    /** What admins do is written here (the admin page's Log). Null: not kept (tests). */
    private val audit: AuditLog? = null,
    /** Null: no backups here (tests). */
    private val backups: Backups? = null,
    /** Learning YouTube Music's radios of the songs here. Null: not here (tests). */
    private val crawl: RadioCrawl? = null,
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
            val admin = service.admin(call)
            val account = io { service.accountCall { accounts.create(request.username, request.password, request.displayName, admin = request.isAdmin) } }
            audit?.record(admin.username, "Created account", account.username, if (account.isAdmin) "server admin" else "")
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
            if (request.isAdmin != null) audit?.record(admin.username, if (account.isAdmin) "Made server admin" else "Removed server admin", account.username)
            if (request.displayName != null) audit?.record(admin.username, "Renamed account", account.username, "now \"${account.displayName}\"")
            call.respond(service.toInfo(account))
        }

        post("/admin/accounts/{username}/password") {
            val admin = service.admin(call)
            val request = call.receive<SetPasswordRequest>()
            val account = io {
                val account = call.target()
                service.accountCall { accounts.setPassword(account.id, request.password) }
                account
            }
            audit?.record(admin.username, "Set a new password", account.username)
            call.respond(HttpStatusCode.NoContent)
        }

        // Disable: for a while, or until enabled again. Logs it out everywhere; it can't log in meanwhile.
        post("/admin/accounts/{username}/disable") {
            val admin = service.admin(call)
            val request = call.receive<DisableAccountRequest>()
            if (request.minutes != null && request.minutes !in 1..MAX_DISABLE_MINUTES) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Disable for 1 minute to ${MAX_DISABLE_MINUTES / 60 / 24} days, or until you enable it")
            val account = io { call.target() }
            if (account.id == admin.id) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "You can't disable your own account")
            val until = request.minutes?.let { System.currentTimeMillis() + it * 60_000 } ?: Accounts.FOREVER
            io { service.accountCall { accounts.setDisabledUntil(account.id, until) } }
            audit?.record(admin.username, "Disabled account", account.username, request.minutes?.let { "for $it minutes" } ?: "until enabled")
            call.respond(service.toInfo(io { accounts.byId(account.id)!! }))
        }

        post("/admin/accounts/{username}/enable") {
            val admin = service.admin(call)
            val account = io { call.target() }
            io { service.accountCall { accounts.setDisabledUntil(account.id, 0) } }
            audit?.record(admin.username, "Enabled account", account.username)
            call.respond(service.toInfo(io { accounts.byId(account.id)!! }))
        }

        // Log out on every device (logins here; tokens rooms already have last until they expire).
        post("/admin/accounts/{username}/logout") {
            val admin = service.admin(call)
            val account = io { call.target() }
            io { accounts.endSessions(account.id) }
            audit?.record(admin.username, "Logged out everywhere", account.username)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/admin/accounts/{username}") {
            val admin = service.admin(call)
            val account = io { call.target() }
            io { service.accountCall { service.delete(account) } }
            audit?.record(admin.username, "Deleted account", account.username)
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
            audit?.record(admin.username, "Made an invite link")
            call.respond(InviteResponse(code, expires))
        }

        delete("/admin/invites/{id}") {
            val admin = service.admin(call)
            if (!io { accounts.deleteInvite(call.parameters["id"]!!) }) throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such invite link")
            audit?.record(admin.username, "Revoked an invite link")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/admin/radio-crawl") {
            service.admin(call)
            call.respond(crawl?.status() ?: throw notHere())
        }

        put("/admin/radio-crawl") {
            val admin = service.admin(call)
            val crawl = crawl ?: throw notHere()
            val wanted = call.receive<RadioCrawlStatus>()
            val before = crawl.status()
            val status = crawl.set(wanted.enabled, wanted.paceSeconds, wanted.refreshDays, historyDays = wanted.historyDays)
            if (before.enabled != status.enabled) audit?.record(admin.username, if (status.enabled) "Switched on learning radios" else "Switched off learning radios")
            call.respond(status)
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
            audit?.record(admin.username, "Changed the server settings", detail = new.toString())
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
            audit?.record(admin.username, "Closed room", room.code, room.name)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/admin/audit") {
            service.admin(call)
            call.respond(AuditLogResponse(audit?.recent().orEmpty()))
        }

        get("/admin/backup") {
            val admin = service.admin(call)
            val backups = backups?.takeIf { it.supported } ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Backups here are for SQLite. With PostgreSQL, use pg_dump.")
            val file = io { backups.create() }
            audit?.record(admin.username, "Downloaded a backup")
            val name = "ytmp-backup-${java.time.LocalDate.now()}.db"
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
            try {
                call.respondFile(file)
            } finally {
                file.delete()
            }
        }

        // The uploaded backup replaces the database when the server starts again.
        post("/admin/restore") {
            val admin = service.admin(call)
            val backups = backups?.takeIf { it.supported } ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Restoring here is for SQLite")
            val upload = call.receiveStream()
            val info = try {
                io { backups.stage(upload) }
            } catch (e: IllegalArgumentException) {
                throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "That isn't a YTMP backup")
            }
            audit?.record(admin.username, "Uploaded a backup to restore", detail = "${info.accounts} accounts, ${info.rooms} rooms; applies at the next start")
            call.respond(info)
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
                    backupSupported = backups?.supported == true,
                    restorePending = backups?.restorePending == true,
                ),
            )
        }
    }

    private fun list(): List<AdminAccountInfo> {
        val activity = accounts.activity()
        return accounts.list().map { account ->
            val info = service.toInfo(account)
            val seen = activity[account.id]
            AdminAccountInfo(info.id, info.username, info.displayName, info.isAdmin, seen?.createdAt ?: 0, seen?.lastSeen, seen?.logins ?: 0, account.disabledUntil)
        }
    }

    private fun ApplicationCall.target(): Accounts.Account =
        accounts.find(parameters["username"]!!) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such account")

    private fun notHere() = ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No server settings here")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val log = LoggerFactory.getLogger(AdminApi::class.java)
        /** 30 days. */
        const val MAX_DISABLE_MINUTES = 30L * 24 * 60
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
