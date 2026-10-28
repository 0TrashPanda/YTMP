package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomHooks
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomRadio
import dev.trashpanda.ytmp.core.personalized
import dev.trashpanda.ytmp.core.RoomTimeouts
import dev.trashpanda.ytmp.host.CastOutputs
import dev.trashpanda.ytmp.host.HostOptions
import dev.trashpanda.ytmp.host.LyricsLookup
import dev.trashpanda.ytmp.host.SongFiles
import dev.trashpanda.ytmp.host.SongCache
import dev.trashpanda.ytmp.host.songCacheRoutes
import dev.trashpanda.ytmp.host.songsInUse
import dev.trashpanda.ytmp.host.MpdOutputs
import dev.trashpanda.ytmp.host.SonosOutputs
import dev.trashpanda.ytmp.host.LocalMusic
import dev.trashpanda.ytmp.host.ZoneOutputs
import dev.trashpanda.ytmp.host.OutputDrivers
import dev.trashpanda.ytmp.host.AccountBookmarks
import dev.trashpanda.ytmp.host.HostSources
import dev.trashpanda.ytmp.host.CachedSource
import dev.trashpanda.ytmp.host.PlayReporter
import dev.trashpanda.ytmp.host.TrustedAuthServers
import dev.trashpanda.ytmp.host.fetchAuthServerInfo
import dev.trashpanda.ytmp.host.localOrigins
import dev.trashpanda.ytmp.host.normalizeOrigin
import dev.trashpanda.ytmp.host.AccountTokens
import dev.trashpanda.ytmp.protocol.RadioHintsRequest
import dev.trashpanda.ytmp.protocol.RadioWay
import dev.trashpanda.ytmp.host.RadioCrawl
import dev.trashpanda.ytmp.protocol.AuthServerRef
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.trashpanda.ytmp.core.RadioSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import dev.trashpanda.ytmp.host.bearerToken
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

// Lazy: logging is set up from the config (see [setUpLogs]), so nothing may log before that.
private val log by lazy { LoggerFactory.getLogger("ytmp") }

/** Usage: `server [path/to/ytmp.toml]`, or set YTMP_CONFIG. */
fun main(args: Array<String>) {
    val configFile = (args.firstOrNull() ?: System.getenv("YTMP_CONFIG"))?.let(::File)
    val config = Config.load(configFile)
    setUpLogs(config.logs)

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
        val audioBase = { localAddress: java.net.InetAddress ->
            config.cast.audioBaseUrl.trimEnd('/').ifEmpty { "http://${localAddress.hostAddress}:${config.server.port}" }
        }
        // A TV shows the party screen in DashCast, an https page: it needs the server's https address.
        val publicUrl = normalizeOrigin(config.accounts.url)?.takeIf { it.startsWith("https://") }
        val casts = CastOutputs(this, pageBaseUrl = { localAddress -> publicUrl ?: audioBase(localAddress) }, audioBaseUrl = audioBase)
        casts.addConfigured(config.cast.devices)
        if (config.cast.discovery) casts.discover()
        val sonos = SonosOutputs(this, audioBase)
        config.sonos.devices.forEach(sonos::addHost)
        if (config.sonos.discovery) sonos.discover(keep = config.sonos.devices.toSet())
        val mpd = MpdOutputs(this, audioBase)
        mpd.addConfigured(config.mpd.devices)
        if (config.mpd.discovery) mpd.discover(keep = config.mpd.devices.map { MpdEntry.parse(it).id }.toSet())
        // Music a Raspberry Pi shares (with its zones file): the Local tab.
        val local = LocalMusic()
        val zones = ZoneOutputs(this, audioBase, mpd, local)
        for (url in config.zones.files) zones.addSource(url, url)
        if (config.zones.discovery) zones.discover()
        val outputs = OutputDrivers(this, listOf(casts, sonos, mpd, zones))

        val db = Database.open(config.database)
        val accounts = Accounts(db)
        // ytmp.toml's settings, with what the admin changed on the admin page over them.
        val settings = ServerSettings(accounts, config)
        val live = settings.values
        if (live.logLevel != config.logs.level.lowercase()) setLogLevel(live.logLevel)
        log.info("Streaming audio through the server: {}", live.proxyMode().name.lowercase())
        // The song graph learns from YouTube Music's radios too (docs/features/history-and-recommendations.md#song-graph).
        val graph = SongGraph(db)
        graph.buildIfNeeded(accounts)
        val cached = CachedSource(ytm, RadioSource { seed -> ytm.radio(seed).also { graph.learnRadio(seed, it) } }, ytm, ytm)
        // Songs played here, kept so they start at once without a YouTube lookup.
        val songCache = SongCache(config.cache.folder(config.database), live.cacheBytes, lookup = ytm, audio = ytm, scope = this)
        log.info("Song cache: {} ({} MB at most)", songCache.dir, songCache.maxBytes / (1024 * 1024))
        val history = History(db)
        // YouTube Music's radios of the songs here, learned slowly for the song graph (the admin switches it on):
        // the local music and song cache, and what everyone here played lately.
        val crawl = RadioCrawl(
            this,
            songs = { s ->
                val played = if (s.historyDays > 0) withContext(Dispatchers.IO) { history.played(null, System.currentTimeMillis() - s.historyDays * 86_400_000L) } else emptyList()
                local.ytmSongIds() + songCache.songIds().filter { it.startsWith("ytm:") } + played
            },
            lastFetched = { seeds -> withContext(Dispatchers.IO) { graph.lastFetched(seeds) } },
            fetch = { seed -> ytm.radio(seed) },
            learned = { seed, songs -> graph.learnRadio(seed, songs, way = RadioWay.CRAWL) },
            load = { RadioCrawl.loadSettings(accounts.setting("radio_crawl")) },
            save = { accounts.setSetting("radio_crawl", RadioCrawl.saveSettings(it)) },
        ).also { it.start() }
        val ownUrl = normalizeOrigin(config.accounts.url)
        val issuer = config.accounts.name.ifBlank {
            accounts.setting("issuer") ?: (ownUrl?.let { URI(it).host } ?: hostName()).also { accounts.setSetting("issuer", it) }
        }
        val key = accounts.signingKey()
        val isOwnOrigin = { origin: String -> origin == ownUrl || origin in localOrigins(config.server.port) }
        val service = AccountService(
            accounts, issuer, key, live.signup, history, live.trackingDefault,
            graph = graph, isOwnOrigin = isOwnOrigin, episodes = Episodes(db),
        )
        val auth = TrustedAuthServers(
            isOwnOrigin = isOwnOrigin,
            ownTemplates = service::roleTemplate,
            ownPlays = service::recordPlay,
            ownSimilar = { username, seed -> service.similar(username, seed) },
            ownEpisodes = TrustedAuthServers.OwnEpisodes(service::episodeProgress, service::reportEpisode),
        )
        auth.trust(AuthServerRef(url = null, issuer = issuer), key.public)
        var trusting = trustServers(auth, live.trusted, ownIssuer = issuer)
        if (ownUrl == null) log.warn("accounts.url (YTMP_URL) is not set: account logins only work on this machine's own addresses")
        log.info("Accounts: {} (sign-up: {}{})", issuer, live.signup.name.lowercase(), if (live.guests) "" else ", accounts only")

        val plays = PlayReporter(auth, this)
        val youtube = YoutubeLinks(accounts, config.ytm.key, ytm, this)
        /** The username of one of this server's own accounts, by account ID (`user@issuer`). */
        fun localUsername(accountId: String): String? = accountId.takeIf { it.endsWith("@$issuer") }?.substringBeforeLast('@')
        /** One of this server's own accounts, by account ID (`user@issuer`). */
        fun localAccount(accountId: String): String? = localUsername(accountId)?.let(accounts::find)?.id
        val rooms = RoomManager(
            local.streams(songCache.streams), this, live.codeStyle(), live.roomCodeLength, outputs = outputs.devices, store = JdbcRoomStore(db),
            // Guests' solo rooms only open in the browser that made them: drop them once unused for a while.
            // Party rooms whose admins are all gone for 5 minutes tell the others they can clone them.
            timeouts = RoomTimeouts(guestSoloRoom = 30.days, adminless = 5.minutes),
            radio = RoomRadio(
                cached,
                // A room with an owner account of this server: radio through that account's YouTube Music
                // (as they set it, or as the room's choice says). The graph keeps it as theirs.
                owner = { owner, seed, withAccount ->
                    val account = owner?.let(::localAccount)
                    val catalog = account?.let(youtube::of)
                    val you = if (withAccount == true) catalog?.takeIf { it.account() != null } else catalog?.personalized()
                    you?.radio(seed)?.also { graph.learnRadio(seed, it, accountId = account) }
                },
                // The song graph: the owner's listening (an account here) and everyone's.
                hints = { owner, seed, candidates, options ->
                    val hints = withContext(Dispatchers.IO) { service.hints(owner?.let(::localUsername), RadioHintsRequest(seed, candidates, options)) }
                    hints.copy(similar = hints.similar.filter { local.playable(it.id) })
                },
                ytmIdOf = local::ytmIdOf,
                library = local::radio,
                onDevice = { song -> LocalMusic.isLocal(song.id) || songCache.has(song.id) },
            ),
            hooks = RoomHooks(
                onPlayFinished = { play ->
                    plays.report(play)
                    youtube.report(play, ::localAccount)
                    songCache.finished(play)
                },
                onSongStarted = songCache::started,
            ),
            bookmarks = AccountBookmarks(auth, this),
        )
        rooms.startCleanup()
        songCache.inUse = { rooms.songsInUse() }
        val hub = RoomHub(db, rooms, issuer, ownUrl, serverName = issuer, verify = service::verifyOwnToken, openSetting = service::openElsewhere, defaultPassword = service::roomPassword)
        monitor.subscribe(ApplicationStopping) { runBlocking { rooms.saveAll() } }
        outputs.attach(rooms)
        // The admin page changed a setting: put it to work (guests and audio are read on each request).
        settings.apply = { old, new ->
            service.signup = new.signup
            service.trackingDefault = new.trackingDefault
            rooms.codeStyle = new.codeStyle()
            rooms.codeLength = new.roomCodeLength
            if (new.cacheBytes != old.cacheBytes) launch { songCache.setMaxBytes(new.cacheBytes) }
            if (new.logLevel != old.logLevel) setLogLevel(new.logLevel)
            if (new.trusted != old.trusted) {
                trusting.forEach { it.cancel() }
                auth.retain { it.url == null }
                trusting = trustServers(auth, new.trusted, ownIssuer = issuer)
            }
        }
        val admin = AdminApi(service, rooms, settings, Versions(http, config.ytm.url.trimEnd('/'), config.ytm.key), AuditLog(db), Backups(db), crawl)

        ytmpModule(
            rooms,
            HostSources(
                search = cached,
                suggestions = cached,
                similar = local.radio(cached),
                catalog = local.catalog(cached),
                lyrics = LyricsLookup({ song -> if (song.id.startsWith("ytm:")) ytm.lyrics(song.id) else null }),
                audio = local.audio(songCache.audio(ytm)),
                downloads = SongFiles(songCache.files(ytm)),
                cache = songCache,
                local = local,
                zones = zones,
                // Your account's YouTube Music, when you're logged in with an account of this server.
                // Also from the pages of rooms on other hosts, with a token just for that (see AccountApi.youtubeToken).
                personalFor = { call ->
                    val token = call.bearerToken()
                    val id = token?.let(auth::verify)?.id ?: token?.let { service.verifyYoutubeToken(it, call.request.httpMethod, call.request.path()) }
                    id?.let(::localAccount)?.let(youtube::of)
                },
                // Find similar and Related: the song graph of whoever asks (one of our accounts, or one
                // of a server we trust, which decides itself), else of everyone here.
                songGraph = { call, seed ->
                    val token = call.bearerToken()
                    val songs = when {
                        token != null && auth.verify(token) != null -> auth.similar(token, seed)
                        else -> {
                            val id = token?.let { service.verifyYoutubeToken(it, call.request.httpMethod, call.request.path()) }
                            withContext(Dispatchers.IO) { service.similar(id?.let(::localUsername), seed) }
                        }
                    }
                    songs.filter { local.playable(it.id) }
                },
            ),
            HostOptions(
                kind = HostKind.SERVER,
                auth = auth,
                supportsPrivateRooms = true,
                // No request is "the hosting device" on a server: behind a reverse proxy on the
                // same machine, every visitor comes from localhost. Solo rooms go by owner.
                isLocal = { false },
                mayProxyAudio = { call -> settings.values.proxyMode().allows(call) },
                accountsOnly = { !settings.values.guests },
                roomCreateDenied = { account ->
                    when (settings.values.roomCreate) {
                        "accounts" -> if (account == null) "Log in to create a room on this server" else null
                        "admins" -> if (account?.let { localUsername(it.id) }?.let(accounts::find)?.isAdmin == true) null else "Only server admins can create rooms on this server"
                        else -> null
                    }
                },
                crossOriginPersonal = true,
            ),
            webApp = File(config.server.frontend),
            extraApi = {
                service.routes(this)
                admin.routes(this)
                hub.routes(this)
                youtube.routes(this) { call -> service.sessionAccount(call)?.id }
                // Settings → Server admin: how full the song cache is, Clear cache.
                songCacheRoutes("/admin/cache", songCache, settings = null) { call -> service.sessionAccount(call)?.isAdmin == true }
            },
        )
    }.start(wait = true)
}

/** Fetches the public keys of other auth servers whose accounts may join here; retries until each works. */
private fun Application.trustServers(auth: TrustedAuthServers, urls: List<String>, ownIssuer: String): List<Job> =
    urls.map { url ->
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

/** info or debug, while running (the admin page). */
private fun setLogLevel(level: String) {
    (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as? ch.qos.logback.classic.Logger)?.level =
        ch.qos.logback.classic.Level.toLevel(level.uppercase(), ch.qos.logback.classic.Level.INFO)
    log.info("Log level: {}", level)
}

private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.lowercase()?.takeIf { it.isNotBlank() } ?: "ytmp"

/** Hands the [logs] settings to logback.xml, which reads them when the first logger is made. */
private fun setUpLogs(logs: LogsConfig) {
    System.setProperty("ytmp.log.level", logs.level.uppercase())
    System.setProperty("ytmp.log.days", logs.keepDays.coerceAtLeast(1).toString())
    System.setProperty("ytmp.log.maxMb", logs.maxMb.coerceAtLeast(5).toString())
    if (logs.dir.isBlank()) {
        System.setProperty("logback.configurationFile", "logback-console.xml")
    } else {
        System.setProperty("ytmp.log.dir", File(logs.dir).absolutePath)
        log.info("Logs in {} ({} days, at most {} MB)", File(logs.dir).absolutePath, logs.keepDays, logs.maxMb)
    }
}
