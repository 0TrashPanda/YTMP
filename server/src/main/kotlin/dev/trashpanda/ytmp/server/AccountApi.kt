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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val throttle = LoginThrottle(clock)

    val info: AuthServerInfo get() = AuthServerInfo(issuer, AccountTokens.encodeKey(key.public), signup, needsAdmin = accounts.count() == 0)

    fun toInfo(account: Accounts.Account) = AccountInfo("${account.username}@$issuer", account.username, account.displayName, account.isAdmin)

    fun hostToken(account: Accounts.Account, origin: String): HostTokenResponse {
        val now = clock() / 1000
        val exp = now + HOST_TOKEN_LIFETIME.inWholeSeconds
        val claims = AccountTokens.Claims(issuer, account.username, account.displayName, origin, now, exp)
        return HostTokenResponse(AccountTokens.sign(claims, key.private), exp * 1000, toInfo(account))
    }

    fun routes(route: Route) = with(route) {
        get("/auth/info") { call.respond(io { info }) }

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
