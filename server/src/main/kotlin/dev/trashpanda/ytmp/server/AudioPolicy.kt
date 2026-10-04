package dev.trashpanda.ytmp.server

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.net.InetAddress

/**
 * Who may stream audio through the server (`[audio] proxy` in ytmp.toml). Normally clients
 * play YouTube's own stream links, which only work on the server's internet connection;
 * elsewhere they fall back to the server, which costs its bandwidth.
 */
enum class AudioProxyMode {
    /** Anyone whose direct link doesn't work. */
    ALWAYS,

    /** Only devices on the server's own network (speakers, phones at home); others get an error. */
    LAN,

    /** Nobody: direct links only (Chromecast and Sonos can't play then). */
    NEVER;

    fun allows(call: ApplicationCall): Boolean = when (this) {
        ALWAYS -> true
        LAN -> isLanAddress(clientAddress(call.request.origin.remoteAddress, call.request.headers[HttpHeaders.XForwardedFor]))
        NEVER -> false
    }

    companion object {
        fun parse(value: String): AudioProxyMode =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: error("audio.proxy must be always, lan or never, not \"$value\"")
    }
}

/**
 * The client's address. Behind a reverse proxy on this machine or network (e.g. Caddy) every
 * request comes from the proxy, so then it's the last X-Forwarded-For entry: the one the
 * proxy added. From anywhere else the header is ignored, so it can't be faked.
 */
fun clientAddress(peer: String, forwardedFor: String?): String {
    if (forwardedFor.isNullOrBlank() || !isLanAddress(peer)) return peer
    return forwardedFor.split(',').last().trim().ifEmpty { peer }
}

/** Loopback, private (10/8, 172.16/12, 192.168/16), link-local and IPv6 unique local addresses. */
fun isLanAddress(address: String): Boolean {
    if (address == "localhost") return true
    // Only literal IP addresses; never look up a host name.
    val literal = address.removePrefix("[").removeSuffix("]").substringBefore('%')
    if (!literal.all { it.isDigit() || it in "abcdefABCDEF.:" } || literal.isEmpty()) return false
    val ip = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
    if (ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress) return true
    // IPv6 unique local (fc00::/7), and IPv4-mapped IPv6 addresses of private IPv4 ones.
    val bytes = ip.address
    if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return true
    return false
}
