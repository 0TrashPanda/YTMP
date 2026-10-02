package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.AccountTokens
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.host.bearerToken
import dev.trashpanda.ytmp.host.normalizeOrigin
import dev.trashpanda.ytmp.protocol.AccountInfo
import dev.trashpanda.ytmp.protocol.AccountListResponse
import dev.trashpanda.ytmp.protocol.AuthServerInfo
import dev.trashpanda.ytmp.protocol.ChangePasswordRequest
import dev.trashpanda.ytmp.protocol.CreateAccountRequest
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HostTokenRequest
import dev.trashpanda.ytmp.protocol.HostTokenResponse
import dev.trashpanda.ytmp.protocol.InviteResponse
import dev.trashpanda.ytmp.protocol.LoginRequest
import dev.trashpanda.ytmp.protocol.SessionResponse
import dev.trashpanda.ytmp.protocol.SetPasswordRequest
import dev.trashpanda.ytmp.protocol.SignupMode
import dev.trashpanda.ytmp.protocol.SignupRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.response.header
import io.ktor.http.HttpHeaders
import dev.trashpanda.ytmp.protocol.AccountSettings
import dev.trashpanda.ytmp.protocol.HistoryPage
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.core.DefaultRoles
import dev.trashpanda.ytmp.core.Permissions
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * This server as an auth server: accounts, logins, and the tokens other hosts accept.
 * Only the server's own pages use these endpoints (no CORS): a host page gets its token
 * through the `/connect` page, so passwords are only typed on the auth server.
 */
class AccountService(
    val accounts: Accounts,
    val issuer: String,
    private val key: KeyPair,
    private val signup: SignupMode,
    private val history: History,
    /** Whether new accounts keep a listening history until they change it. */
    private val trackingDefault: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val throttle = LoginThrottle(clock)

    val info: AuthServerInfo get() = AuthServerInfo(issuer, AccountTokens.encodeKey(key.public), signup, needsAdmin = accounts.count() == 0)

    fun toInfo(account: Accounts.Account) = AccountInfo("${account.username}@$issuer", account.username, account.displayName, account.isAdmin)

    fun hostToken(account: Accounts.Account, origin: String): HostTokenResponse {
        val now = clock() / 1000
        val exp = now + HOST_TOKEN_LIFETIME.inWholeSeconds
        val claims = AccountTokens.Claims(issuer, account.username, account.displayName, origin, now, exp, hide = settings(account).hideFromOthers)
        return HostTokenResponse(AccountTokens.sign(claims, key.private), exp * 1000, toInfo(account))
    }

    /** The account's role template, or the default roles. */
    fun roleTemplate(account: Accounts.Account): RoleTemplate =
        accounts.data(account.id, "role_template")
            ?.let { runCatching { ProtocolJson.decodeFromString(RoleTemplate.serializer(), it) }.getOrNull() }
            ?.let { Permissions.sanitize(Permissions.upgrade(it)) }
            ?: DefaultRoles.template

    fun settings(account: Accounts.Account): AccountSettings =
        accounts.data(account.id, "settings")
            ?.let { runCatching { ProtocolJson.decodeFromString(AccountSettings.serializer(), it) }.getOrNull() }
            ?: AccountSettings(tracking = trackingDefault, hideFromOthers = false)

    /** A host reports a song [username] heard; kept only if they track their history. */
    fun recordPlay(username: String, report: PlayReport) {
        val account = accounts.find(username) ?: return
        if (settings(account).tracking) history.add(account.id, report)
    }

    /** For a host checking a token of ours: the template of [username]. */
    fun roleTemplate(username: String): RoleTemplate? = accounts.find(username)?.let(::roleTemplate)

    fun routes(route: Route) = with(route) {
        get("/auth/info") { call.respond(io { info }) }

        // A host creating a room for an account asks for the account's roles, with the
        // host token the account gave it (it doesn't have to be for this server's origin).
        get("/auth/role-template") {
            val claims = call.ownToken() ?: throw notLoggedIn()
            call.respond(io { roleTemplate(claims.sub) } ?: throw notLoggedIn())
        }

        // A host sends a song the account heard, with the host token the account gave it.
        post("/auth/plays") {
            val claims = call.ownToken() ?: throw notLoggedIn()
            val report = call.receive<PlayReport>()
            io { recordPlay(claims.sub, report) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/account/settings") {
            val account = call.account()
            call.respond(io { settings(account) })
        }

        put("/account/settings") {
            val account = call.account()
            val settings = call.receive<AccountSettings>()
            io { accounts.setData(account.id, "settings", ProtocolJson.encodeToString(AccountSettings.serializer(), settings)) }
            call.respond(settings)
        }

        get("/account/history") {
            val account = call.account()
            val params = call.request.queryParameters
            val limit = (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
            val (plays, more) = io { history.page(account.id, params["before"]?.toLongOrNull(), limit, params["q"]) }
            call.respond(HistoryPage(plays, more))
        }

        get("/account/history/export") {
            val account = call.account()
            val plays = io { history.all(account.id) }
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"ytmp-history-${account.username}.json\"")
            call.respond(plays)
        }

        delete("/account/history/{id}") {
            val account = call.account()
            if (!io { history.delete(account.id, call.parameters["id"]!!) }) throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Not in your history")
            call.respond(HttpStatusCode.NoContent)
        }

        // ?from=&to= (ms) deletes a time range; ?all=true deletes everything.
        delete("/account/history") {
            val account = call.account()
            val params = call.request.queryParameters
            val from = params["from"]?.toLongOrNull()
            val to = params["to"]?.toLongOrNull()
            if (from == null && to == null && params["all"] != "true") throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Say what to delete")
            val count = io { history.deleteRange(account.id, from, to) }
            log.info("{} deleted {} plays from their history", account.username, count)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/account/role-template") {
            val account = call.account()
            call.respond(io { roleTemplate(account) })
        }

        put("/account/role-template") {
            val account = call.account()
            val template = Permissions.sanitize(call.receive<RoleTemplate>()).copy(knownPermissions = Permission.entries)
            io { accounts.setData(account.id, "role_template", ProtocolJson.encodeToString(RoleTemplate.serializer(), template)) }
            call.respond(template)
        }

        post("/account/signup") {
            val request = call.receive<SignupRequest>()
            val account = io {
                val first = accounts.count() == 0
                if (!first && signup == SignupMode.ADMIN) throw forbidden("Only the server admin can create accounts here")
                if (!first && signup == SignupMode.INVITE && request.invite.isNullOrBlank()) throw forbidden("You need an invite link to sign up here")
                accountCall { accounts.create(request.username, request.password, request.displayName, admin = null, invite = request.invite.takeIf { signup != SignupMode.OPEN }) }
            }
            log.info("New account {}{}", account.username, if (account.isAdmin) " (server admin)" else "")
            call.respond(SessionResponse(io { accounts.newSession(account.id) }, toInfo(account)))
        }

        post("/account/login") {
            val request = call.receive<LoginRequest>()
            val name = request.username.trim().lowercase()
            if (!throttle.allowed(name)) throw ApiException(HttpStatusCode.TooManyRequests, ErrorCode.PERMISSION_DENIED, "Too many attempts. Try again in a few minutes.")
            val account = io { accounts.login(name, request.password) }
            if (account == null) {
                throttle.failed(name)
                throw ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Wrong username or password")
            }
            throttle.succeeded(name)
            call.respond(SessionResponse(io { accounts.newSession(account.id) }, toInfo(account)))
        }

        post("/account/logout") {
            call.bearerToken()?.let { io { accounts.endSession(it) } }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/account") { call.respond(toInfo(call.account())) }

        post("/account/password") {
            val account = call.account()
            val request = call.receive<ChangePasswordRequest>()
            io {
                if (accounts.login(account.username, request.currentPassword) == null) throw forbidden("Your current password is wrong")
                accountCall { accounts.setPassword(account.id, request.newPassword) }
            }
            // Setting a password logs out everywhere; this device gets a new session.
            call.respond(SessionResponse(io { accounts.newSession(account.id) }, toInfo(account)))
        }

        post("/account/host-token") {
            val account = call.account()
            val origin = normalizeOrigin(call.receive<HostTokenRequest>().origin)
                ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Not a web address")
            call.respond(hostToken(account, origin))
        }

        get("/admin/accounts") {
            call.admin()
            call.respond(AccountListResponse(io { accounts.list() }.map(::toInfo)))
        }

        post("/admin/accounts") {
            call.admin()
            val request = call.receive<CreateAccountRequest>()
            val account = io { accountCall { accounts.create(request.username, request.password, request.displayName, admin = false) } }
            call.respond(toInfo(account))
        }

        post("/admin/accounts/{username}/password") {
            call.admin()
            val request = call.receive<SetPasswordRequest>()
            io {
                val account = accounts.find(call.parameters["username"]!!) ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such account")
                accountCall { accounts.setPassword(account.id, request.password) }
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/admin/invites") {
            val admin = call.admin()
            val (code, expires) = io { accounts.newInvite(admin.id, INVITE_LIFETIME.inWholeMilliseconds) }
            call.respond(InviteResponse(code, expires))
        }
    }

    private suspend fun ApplicationCall.account(): Accounts.Account {
        val token = bearerToken() ?: throw notLoggedIn()
        return io { accounts.sessionAccount(token) } ?: throw notLoggedIn()
    }

    /** A host token signed by us (made for any host), from `Authorization: Bearer`. */
    private fun ApplicationCall.ownToken(): AccountTokens.Claims? =
        bearerToken()?.let { AccountTokens.verify(it, { iss -> if (iss == issuer) key.public else null }, clock() / 1000) }

    private suspend fun ApplicationCall.admin(): Accounts.Account =
        account().takeIf { it.isAdmin } ?: throw forbidden("Only the server admin can do this")

    private fun <T> accountCall(block: () -> T): T = try {
        block()
    } catch (e: Accounts.AccountException) {
        throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "Invalid")
    }

    private fun notLoggedIn() = ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Not logged in")

    private fun forbidden(message: String) = ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, message)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val log = LoggerFactory.getLogger(AccountService::class.java)
        val HOST_TOKEN_LIFETIME = 30.days
        val INVITE_LIFETIME = 7.days
    }
}

/** Slows down password guessing: after 5 wrong passwords for a username, wait 15 minutes. */
class LoginThrottle(private val clock: () -> Long) {
    private val failures = ConcurrentHashMap<String, List<Long>>()

    fun allowed(username: String): Boolean = recent(username).size < MAX_FAILURES

    fun failed(username: String) {
        failures[username] = recent(username) + clock()
    }

    fun succeeded(username: String) {
        failures.remove(username)
    }

    private fun recent(username: String): List<Long> =
        failures[username].orEmpty().filter { clock() - it < WINDOW.inWholeMilliseconds }

    private companion object {
        const val MAX_FAILURES = 5
        val WINDOW = 15.minutes
    }
}
