package dev.trashpanda.ytmp.host

import android.content.Context
import android.util.Log
import dev.trashpanda.ytmp.protocol.AuthServerRef
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.LinkAuthServerRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The YTMP server this phone uses for accounts (see docs/features/accounts.md). Without one,
 * only guests join the phone's rooms. Remembered with the server's public key, so tokens
 * can be checked without reaching the server.
 */
class AuthLink(context: Context, port: Int) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    val auth = TrustedAuthServers(isOwnOrigin = { it in localOrigins(port) })

    /** The linked server, e.g. `https://ytmp.example.com`, or null. */
    val url: String? get() = auth.servers().firstOrNull()?.url

    init {
        val url = prefs.getString("url", null)
        val issuer = prefs.getString("issuer", null)
        val key = prefs.getString("key", null)
        if (url != null && issuer != null && key != null) {
            runCatching { auth.trust(AuthServerRef(url, issuer), AccountTokens.decodePublicKey(key)) }
                .onFailure { Log.w(TAG, "Stored auth server key is unreadable", it) }
        }
    }

    /** Links [url] (after reading its public key), or unlinks when null. */
    suspend fun link(url: String?) {
        if (url == null) {
            auth.clear()
            prefs.edit().clear().apply()
            return
        }
        val base = normalizeOrigin(url) ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Enter the server's address, like https://ytmp.example.com")
        val info = withContext(Dispatchers.IO) {
            runCatching { fetchAuthServerInfo(base) }.getOrElse {
                throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, "Can't reach a YTMP server at $base")
            }
        }
        val key = AccountTokens.decodePublicKey(info.publicKey)
        auth.clear()
        auth.trust(AuthServerRef(base, info.issuer), key)
        prefs.edit().putString("url", base).putString("issuer", info.issuer).putString("key", info.publicKey).apply()
        Log.i(TAG, "Using $base (${info.issuer}) for accounts")
    }

    /** `PUT /api/host/auth-server`, only from the phone itself. */
    fun routes(route: Route) = with(route) {
        put("/host/auth-server") {
            if (!isLoopback(call)) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Only the hosting device can do this")
            link(call.receive<LinkAuthServerRequest>().url?.trim()?.ifEmpty { null })
            call.respond(HttpStatusCode.NoContent)
        }
    }

    private companion object {
        const val TAG = "YtmpAuth"
    }
}
