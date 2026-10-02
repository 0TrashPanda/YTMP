package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.AccountIdentity
import dev.trashpanda.ytmp.protocol.AuthServerInfo
import dev.trashpanda.ytmp.protocol.AuthServerRef
import dev.trashpanda.ytmp.protocol.ProtocolJson
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

/** Which accounts a host accepts. See docs/implementation/auth.md. */
interface HostAuth {
    /** Auth servers whose accounts can join here. */
    fun servers(): List<AuthServerRef>

    /** The account in [token], if it's valid, from a trusted auth server, and meant for this host. */
    fun verify(token: String): AccountIdentity?
}

/** Guests only. */
object NoAuth : HostAuth {
    override fun servers() = emptyList<AuthServerRef>()

    override fun verify(token: String): AccountIdentity? = null
}

/**
 * Accepts account tokens signed by the auth servers it trusts, made for one of this host's
 * own origins. The origin can't be taken from the request (a Host header can say anything);
 * [isOwnOrigin] knows this host's real addresses.
 */
class TrustedAuthServers(
    private val isOwnOrigin: (origin: String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : HostAuth {
    private class Trusted(val ref: AuthServerRef, val key: PublicKey)

    private val trusted = ConcurrentHashMap<String, Trusted>()

    fun trust(ref: AuthServerRef, key: PublicKey) {
        trusted[ref.issuer] = Trusted(ref, key)
    }

    fun clear() = trusted.clear()

    override fun servers() = trusted.values.map { it.ref }

    override fun verify(token: String): AccountIdentity? {
        val claims = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000) ?: return null
        if (!isOwnOrigin(normalizeOrigin(claims.aud) ?: return null)) return null
        return AccountIdentity(claims.accountId, claims.name)
    }
}

/** `scheme://host[:port]` in lowercase without a default port, or null if [origin] isn't an http(s) origin. */
fun normalizeOrigin(origin: String): String? = runCatching {
    val uri = URI(origin.trim().trimEnd('/'))
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (!uri.path.isNullOrEmpty() || uri.query != null || uri.fragment != null || uri.userInfo != null) return null
    val port = uri.port.takeIf { it != -1 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
    val bracketed = if (':' in host && !host.startsWith("[")) "[$host]" else host
    "$scheme://$bracketed" + (port?.let { ":$it" } ?: "")
}.getOrNull()

/** `http://<address>:<port>` for loopback and every IPv4 address of this machine. */
fun localOrigins(port: Int): Set<String> {
    val addresses = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
    return (addresses + "127.0.0.1" + "localhost").map { "http://$it:$port" }.toSet()
}

/** Reads an auth server's [AuthServerInfo]. Blocking. */
fun fetchAuthServerInfo(url: String): AuthServerInfo {
    val connection = URI("${url.trimEnd('/')}/api/auth/info").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    try {
        if (connection.responseCode != 200) throw SourceException("$url answered ${connection.responseCode}")
        return ProtocolJson.decodeFromString(AuthServerInfo.serializer(), connection.inputStream.bufferedReader().readText())
    } finally {
        connection.disconnect()
    }
}
