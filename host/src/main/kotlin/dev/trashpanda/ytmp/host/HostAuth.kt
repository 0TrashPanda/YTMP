package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.AccountIdentity
import dev.trashpanda.ytmp.protocol.AuthServerInfo
import dev.trashpanda.ytmp.protocol.AuthServerRef
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.GraphFull
import dev.trashpanda.ytmp.protocol.RadioFetchReport
import dev.trashpanda.ytmp.protocol.RadioHints
import dev.trashpanda.ytmp.protocol.RadioHintsRequest
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.EpisodeProgress
import dev.trashpanda.ytmp.protocol.EpisodeProgressReport
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.Song
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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

    /** The role template of the account in [token] (already verified), from its auth server. Null: use the defaults. */
    suspend fun roleTemplate(token: String): RoleTemplate? = null

    /** Sends a finished song to the listening history of the account in [token]. */
    suspend fun reportPlay(token: String, report: PlayReport) {}

    /**
     * Songs that go with [seedSongId] in the song graph of the auth server of the account in
     * [token] (from that account's listening where the server allows it). Empty when there are none.
     */
    suspend fun similar(token: String, seedSongId: String): List<Song> = emptyList()

    /**
     * What the song graph of the auth server of the account in [token] says about a radio ([RadioHints]).
     * Only [RadioHints.similar] from a server that doesn't know more.
     */
    suspend fun radioHints(token: String, request: RadioHintsRequest): RadioHints? = RadioHints(similar(token, request.seed))

    /** The whole song graph as the account in [token] sees it, from its auth server (a phone's copy); null when it can't be had. */
    suspend fun graphFull(token: String): GraphFull? = null

    /**
     * Tells the account in [token]'s auth server about a YouTube Music radio this host fetched
     * (its song graph keeps it). False when it didn't take it (not reachable, or an older server).
     */
    suspend fun reportRadio(token: String, report: RadioFetchReport): Boolean = false

    /** Where the account in [token] is in an episode, from its auth server (null: nowhere, or not shared with this host). */
    suspend fun episodeProgress(token: String, episodeId: String): EpisodeProgress? = null

    /** Tells the account in [token]'s auth server where it is in an episode. */
    suspend fun reportEpisode(token: String, report: EpisodeProgressReport) {}
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
    /** Templates of this server's own accounts (by username), read without HTTP. */
    private val ownTemplates: ((username: String) -> RoleTemplate?)? = null,
    /** Stores plays of this server's own accounts (by username), without HTTP. */
    private val ownPlays: ((username: String, report: PlayReport) -> Unit)? = null,
    /** The song graph of this server, for its own accounts (by username), without HTTP. */
    private val ownSimilar: ((username: String, seedSongId: String) -> List<Song>)? = null,
    /** Episode progress of this server's own accounts (by username), without HTTP. */
    private val ownEpisodes: OwnEpisodes? = null,
) : HostAuth {
    /** A server's own accounts' episode progress ([episodeProgress], [reportEpisode]). */
    class OwnEpisodes(
        val progress: (username: String, episodeId: String) -> EpisodeProgress?,
        val report: (username: String, report: EpisodeProgressReport) -> Unit,
    )

    private class Trusted(val ref: AuthServerRef, val key: PublicKey)

    private val trusted = ConcurrentHashMap<String, Trusted>()

    fun trust(ref: AuthServerRef, key: PublicKey) {
        trusted[ref.issuer] = Trusted(ref, key)
    }

    fun clear() = trusted.clear()

    /** Stops trusting the auth servers [keep] says no to. */
    fun retain(keep: (AuthServerRef) -> Boolean) {
        trusted.values.removeIf { !keep(it.ref) }
    }

    override fun servers() = trusted.values.map { it.ref }

    /** What [token] says, if it's valid and from a trusted auth server (whatever host it's for). */
    fun claims(token: String): AccountTokens.Claims? = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000)

    override fun verify(token: String): AccountIdentity? {
        val claims = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000) ?: return null
        // A token for something narrower (your YouTube Music) isn't a login.
        if (claims.scope != null) return null
        if (!isOwnOrigin(normalizeOrigin(claims.aud) ?: return null)) return null
        return AccountIdentity(claims.accountId, claims.name, hideFromHistory = claims.hide)
    }

    override suspend fun reportPlay(token: String, report: PlayReport) = withContext(Dispatchers.IO) {
        val claims = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000) ?: return@withContext
        val server = trusted[claims.iss]?.ref ?: return@withContext
        runCatching {
            val url = server.url
            if (url == null) ownPlays?.invoke(claims.sub, report) else postPlay(url, token, report)
        }.onFailure { log.warn("Couldn't add a play to the history of {}: {}", claims.accountId, it.message) }
        Unit
    }

    override suspend fun roleTemplate(token: String): RoleTemplate? = withContext(Dispatchers.IO) {
        val claims = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000) ?: return@withContext null
        val server = trusted[claims.iss]?.ref ?: return@withContext null
        runCatching {
            val url = server.url
            if (url == null) ownTemplates?.invoke(claims.sub) else fetchRoleTemplate(url, token)
        }.onFailure { log.warn("Couldn't get the role template of {}: {}", claims.accountId, it.message) }.getOrNull()
    }

    override suspend fun similar(token: String, seedSongId: String): List<Song> = withContext(Dispatchers.IO) {
        val claims = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000) ?: return@withContext emptyList()
        if (claims.scope != null) return@withContext emptyList()
        val server = trusted[claims.iss]?.ref ?: return@withContext emptyList()
        runCatching {
            val url = server.url
            if (url == null) ownSimilar?.invoke(claims.sub, seedSongId).orEmpty() else fetchSimilar(url, token, seedSongId)
        }.onFailure { log.warn("No songs from the song graph of {}: {}", claims.iss, it.message) }.getOrDefault(emptyList())
    }

    /** Null: the server couldn't be asked (the phone then uses its copy of the graph). */
    override suspend fun radioHints(token: String, request: RadioHintsRequest): RadioHints? = withContext(Dispatchers.IO) {
        val claims = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000) ?: return@withContext RadioHints(emptyList())
        if (claims.scope != null) return@withContext RadioHints(emptyList())
        val server = trusted[claims.iss]?.ref ?: return@withContext RadioHints(emptyList())
        val url = server.url ?: return@withContext RadioHints(ownSimilar?.invoke(claims.sub, request.seed).orEmpty())
        runCatching { fetchHints(url, token, request) }
            .recoverCatching { e -> if (e is NotThere) RadioHints(fetchSimilar(url, token, request.seed)) else throw e }
            .onFailure { log.warn("No songs from the song graph of {}: {}", claims.iss, it.message) }
            .getOrNull()
    }

    override suspend fun graphFull(token: String): GraphFull? = withContext(Dispatchers.IO) {
        val (claims, server) = ownerOf(token) ?: return@withContext null
        val url = server.url ?: return@withContext null
        runCatching {
            val connection = URI("${url.trimEnd('/')}/api/auth/graph").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            try {
                if (connection.responseCode != 200) throw SourceException("$url answered ${connection.responseCode}")
                connection.inputStream.bufferedReader().use { ProtocolJson.decodeFromString(GraphFull.serializer(), it.readText()) }
            } finally {
                connection.disconnect()
            }
        }.onFailure { log.info("No copy of the song graph from {}: {}", claims.iss, it.message) }.getOrNull()
    }

    override suspend fun reportRadio(token: String, report: RadioFetchReport): Boolean = withContext(Dispatchers.IO) {
        val (claims, server) = ownerOf(token) ?: return@withContext false
        val url = server.url ?: return@withContext false
        runCatching { postJson(url, "/api/auth/radio-fetch", token, ProtocolJson.encodeToString(RadioFetchReport.serializer(), report)) }
            .onFailure { e -> log.info("Couldn't send a radio to {}: {}", claims.iss, e.message) }
            .isSuccess
    }

    override suspend fun episodeProgress(token: String, episodeId: String): EpisodeProgress? = withContext(Dispatchers.IO) {
        val (claims, server) = ownerOf(token) ?: return@withContext null
        runCatching {
            val url = server.url
            if (url == null) ownEpisodes?.progress?.invoke(claims.sub, episodeId) else fetchEpisodeProgress(url, token, episodeId)
        }.onFailure { log.warn("No episode progress from {}: {}", claims.iss, it.message) }.getOrNull()
    }

    override suspend fun reportEpisode(token: String, report: EpisodeProgressReport) = withContext(Dispatchers.IO) {
        val (claims, server) = ownerOf(token) ?: return@withContext
        runCatching {
            val url = server.url
            if (url == null) ownEpisodes?.report?.invoke(claims.sub, report) else postJson(url, "/api/auth/episodes", token, ProtocolJson.encodeToString(EpisodeProgressReport.serializer(), report))
        }.onFailure { log.warn("Couldn't save episode progress for {}: {}", claims.accountId, it.message) }
        Unit
    }

    /** The account in [token] (a login, not a scoped token) and its trusted auth server. */
    private fun ownerOf(token: String): Pair<AccountTokens.Claims, AuthServerRef>? {
        val claims = AccountTokens.verify(token, { trusted[it]?.key }, clock() / 1000)?.takeIf { it.scope == null } ?: return null
        return trusted[claims.iss]?.ref?.let { claims to it }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TrustedAuthServers::class.java)
    }
}

/** Where an account is in an episode, from its auth server (null: nowhere). Blocking. */
private fun fetchEpisodeProgress(url: String, token: String, episodeId: String): EpisodeProgress? {
    val connection = URI("${url.trimEnd('/')}/api/auth/episodes/${URLEncoder.encode(episodeId, Charsets.UTF_8)}").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.setRequestProperty("Authorization", "Bearer $token")
    try {
        return when (connection.responseCode) {
            200 -> ProtocolJson.decodeFromString(EpisodeProgress.serializer(), connection.inputStream.bufferedReader().readText())
            204 -> null
            else -> throw SourceException("$url answered ${connection.responseCode}")
        }
    } finally {
        connection.disconnect()
    }
}

/** POSTs [json] to [path] on an auth server, with a host token of one of its accounts. Blocking. */
private fun postJson(url: String, path: String, token: String, json: String) {
    val connection = URI("${url.trimEnd('/')}$path").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Content-Type", "application/json")
    try {
        connection.outputStream.use { it.write(json.toByteArray()) }
        if (connection.responseCode !in 200..299) throw SourceException("$url answered ${connection.responseCode}")
    } finally {
        connection.disconnect()
    }
}

/** An auth server that doesn't have an endpoint (an older YTMP). */
private class NotThere(url: String) : SourceException("$url doesn't know this yet")

/** What the song graph of an auth server says about a radio, with a host token of one of its accounts. Blocking. */
private fun fetchHints(url: String, token: String, request: RadioHintsRequest): RadioHints {
    val connection = URI("${url.trimEnd('/')}/api/auth/radio-hints").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Content-Type", "application/json")
    try {
        connection.outputStream.use { it.write(ProtocolJson.encodeToString(RadioHintsRequest.serializer(), request).toByteArray()) }
        if (connection.responseCode == 404 || connection.responseCode == 405) throw NotThere(url)
        if (connection.responseCode != 200) throw SourceException("$url answered ${connection.responseCode}")
        return ProtocolJson.decodeFromString(RadioHints.serializer(), connection.inputStream.bufferedReader().readText())
    } finally {
        connection.disconnect()
    }
}

/** Songs that go with [seedSongId] in the song graph of an auth server, with a host token of one of its accounts. Blocking. */
private fun fetchSimilar(url: String, token: String, seedSongId: String): List<Song> {
    val connection = URI("${url.trimEnd('/')}/api/auth/radio?id=${URLEncoder.encode(seedSongId, Charsets.UTF_8)}").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.setRequestProperty("Authorization", "Bearer $token")
    try {
        if (connection.responseCode != 200) throw SourceException("$url answered ${connection.responseCode}")
        return ProtocolJson.decodeFromString(SearchResponse.serializer(), connection.inputStream.bufferedReader().readText()).items
    } finally {
        connection.disconnect()
    }
}

/** Sends a play to an account's auth server, with a host token of that account. Blocking. */
private fun postPlay(url: String, token: String, report: PlayReport) {
    val connection = URI("${url.trimEnd('/')}/api/auth/plays").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Content-Type", "application/json")
    try {
        connection.outputStream.use { it.write(ProtocolJson.encodeToString(PlayReport.serializer(), report).toByteArray()) }
        if (connection.responseCode !in 200..299) throw SourceException("$url answered ${connection.responseCode}")
    } finally {
        connection.disconnect()
    }
}

/** Reads an account's role template from its auth server, with a host token of that account. Blocking. */
private fun fetchRoleTemplate(url: String, token: String): RoleTemplate {
    val connection = URI("${url.trimEnd('/')}/api/auth/role-template").toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.setRequestProperty("Authorization", "Bearer $token")
    try {
        if (connection.responseCode != 200) throw SourceException("$url answered ${connection.responseCode}")
        return ProtocolJson.decodeFromString(RoleTemplate.serializer(), connection.inputStream.bufferedReader().readText())
    } finally {
        connection.disconnect()
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
