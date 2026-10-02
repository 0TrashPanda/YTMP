package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.CastOutputs
import dev.trashpanda.ytmp.host.HostOptions
import dev.trashpanda.ytmp.host.PlayReporter
import dev.trashpanda.ytmp.host.TrustedAuthServers
import dev.trashpanda.ytmp.host.fetchAuthServerInfo
import dev.trashpanda.ytmp.host.localOrigins
import dev.trashpanda.ytmp.host.normalizeOrigin
import dev.trashpanda.ytmp.host.AccountTokens
import dev.trashpanda.ytmp.protocol.AuthServerRef
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import dev.trashpanda.ytmp.host.ytmpModule
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.ProtocolJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.URI

private val log = LoggerFactory.getLogger("ytmp")

/** Usage: `server [path/to/ytmp.toml]`, or set YTMP_CONFIG. */
fun main(args: Array<String>) {
    val configFile = (args.firstOrNull() ?: System.getenv("YTMP_CONFIG"))?.let(::File)
    val config = Config.load(configFile)

    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // Resolving a stream can take a few seconds; audio streams stay open for minutes.
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }
    val ytm = RemoteSourceModule(http, config.ytm.url.trimEnd('/'), config.ytm.key)

    embeddedServer(Netty, port = config.server.port, host = config.server.host) {
        val casts = CastOutputs(this) { localAddress ->
            config.cast.audioBaseUrl.trimEnd('/').ifEmpty { "http://${localAddress.hostAddress}:${config.server.port}" }
        }
        casts.addConfigured(config.cast.devices)
        if (config.cast.discovery) casts.discover()

        val db = Database.open(config.database)
        val accounts = Accounts(db)
        val ownUrl = normalizeOrigin(config.accounts.url)
        val issuer = config.accounts.name.ifBlank {
            accounts.setting("issuer") ?: (ownUrl?.let { URI(it).host } ?: hostName()).also { accounts.setSetting("issuer", it) }
        }
        val key = accounts.signingKey()
        val service = AccountService(accounts, issuer, key, config.accounts.signupMode(), History(db), config.accounts.trackingDefault)
        val auth = TrustedAuthServers(
            isOwnOrigin = { it == ownUrl || it in localOrigins(config.server.port) },
            ownTemplates = service::roleTemplate,
            ownPlays = service::recordPlay,
        )
        auth.trust(AuthServerRef(url = null, issuer = issuer), key.public)
        trustServers(auth, config.accounts.trusted, ownIssuer = issuer)
        if (ownUrl == null) log.warn("accounts.url (YTMP_URL) is not set: account logins only work on this machine's own addresses")
        log.info("Accounts: {} (sign-up: {})", issuer, config.accounts.signup)

        val plays = PlayReporter(auth, this)
        val rooms = RoomManager(
            ytm, this, config.rooms.style(), config.rooms.codeLength, outputs = casts.devices, store = JdbcRoomStore(db),
            onPlayFinished = plays::report,
        )
        rooms.startCleanup()
        monitor.subscribe(ApplicationStopping) { runBlocking { rooms.saveAll() } }
        casts.attach(rooms)

        ytmpModule(
            rooms,
            search = ytm,
            audio = ytm,
            webApp = File(config.server.frontend),
            HostOptions(kind = HostKind.SERVER, auth = auth),
            extraApi = { service.routes(this) },
        )
    }.start(wait = true)
}

/** Fetches the public keys of other auth servers whose accounts may join here; retries until each works. */
private fun Application.trustServers(auth: TrustedAuthServers, urls: List<String>, ownIssuer: String) {
    for (url in urls) {
        launch(Dispatchers.IO) {
            while (true) {
                val info = runCatching { fetchAuthServerInfo(url) }
                    .onFailure { log.warn("Can't reach trusted server {}: {}", url, it.message) }
                    .getOrNull()
                if (info != null) {
                    if (info.issuer != ownIssuer) auth.trust(AuthServerRef(url.trimEnd('/'), info.issuer), AccountTokens.decodePublicKey(info.publicKey))
                    log.info("Trusting accounts of {} ({})", info.issuer, url)
                    return@launch
                }
                delay(5.minutes)
            }
        }
    }
}

private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: "ytmp"
