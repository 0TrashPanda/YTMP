package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.AccountTokens
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.host.bearerToken
import dev.trashpanda.ytmp.host.normalizeOrigin
import dev.trashpanda.ytmp.protocol.AccountInfo
import dev.trashpanda.ytmp.protocol.AuthServerInfo
import dev.trashpanda.ytmp.protocol.ChangePasswordRequest
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HostTokenRequest
import dev.trashpanda.ytmp.protocol.HostTokenResponse
import dev.trashpanda.ytmp.protocol.LoginRequest
import dev.trashpanda.ytmp.protocol.SessionResponse
import dev.trashpanda.ytmp.protocol.SignupMode
import dev.trashpanda.ytmp.protocol.SignupRequest
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.response.header
import io.ktor.http.HttpHeaders
import dev.trashpanda.ytmp.protocol.AccountSettings
import dev.trashpanda.ytmp.protocol.RoomOpenMode
import dev.trashpanda.ytmp.protocol.RoomPasswordRequest
import dev.trashpanda.ytmp.protocol.HistoryPage
import dev.trashpanda.ytmp.protocol.HistoryRadio
import dev.trashpanda.ytmp.protocol.EpisodeProgress
import dev.trashpanda.ytmp.protocol.EpisodeProgressList
import dev.trashpanda.ytmp.protocol.EpisodeProgressReport
import dev.trashpanda.ytmp.protocol.MarkEpisodeRequest
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.route
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.core.DefaultRoles
import dev.trashpanda.ytmp.core.Permissions
import dev.trashpanda.ytmp.core.RoomPasswords
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import dev.trashpanda.ytmp.host.Takeout
import dev.trashpanda.ytmp.protocol.GraphBuildStatus
import dev.trashpanda.ytmp.protocol.GraphFull
import dev.trashpanda.ytmp.protocol.GraphTuning
import dev.trashpanda.ytmp.protocol.HistoryImportList
import dev.trashpanda.ytmp.protocol.RadioFetchReport
import dev.trashpanda.ytmp.protocol.RadioHints
import dev.trashpanda.ytmp.protocol.RadioHintsRequest
import io.ktor.server.request.receiveStream
import java.time.ZoneId
import java.io.File
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * This server as an auth server: accounts, logins, and the tokens other hosts accept.
 * Only the server's own pages use these endpoints (no CORS): a host page gets its token
 * through the `/connect` page, so passwords are only typed on the auth server. Except your
 * listening history: the pages of your phone's app and of rooms on other hosts read it too
 * (see [historyAccount]).
 */
class AccountService(
    val accounts: Accounts,
    val issuer: String,
    private val key: KeyPair,
    signup: SignupMode,
    private val history: History,
    /** Whether new accounts keep a listening history until they change it. */
    trackingDefault: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The song graph, learned from the history. */
    private val graph: SongGraph? = null,
    /** This server's own page origins: their tokens only ever reach this server (see [historyAccount]). */
    private val isOwnOrigin: (String) -> Boolean = { false },
    /** How far accounts got in podcast episodes. */
    private val episodes: Episodes? = null,
) {
    private val throttle = LoginThrottle(clock)

    /** Who may create accounts; can change while running (the admin page). */
    @Volatile
    var signup: SignupMode = signup

    /** Whether new accounts keep a listening history until they change it. */
    @Volatile
    var trackingDefault: Boolean = trackingDefault

    val info: AuthServerInfo get() = AuthServerInfo(issuer, AccountTokens.encodeKey(key.public), signup, needsAdmin = accounts.count() == 0)

    fun toInfo(account: Accounts.Account) = AccountInfo("${account.username}@$issuer", account.username, account.displayName, account.isAdmin)

    fun hostToken(account: Accounts.Account, origin: String): HostTokenResponse {
        val now = clock() / 1000
        val exp = now + HOST_TOKEN_LIFETIME.inWholeSeconds
        val claims = AccountTokens.Claims(issuer, account.username, account.displayName, origin, now, exp, hide = settings(account).hideFromOthers)
        return HostTokenResponse(AccountTokens.sign(claims, key.private), exp * 1000, toInfo(account))
    }

    /** The account's role template (or the default roles), with its default room password (hashed). */
    fun roleTemplate(account: Accounts.Account): RoleTemplate =
        (
            accounts.data(account.id, "role_template")
                ?.let { runCatching { ProtocolJson.decodeFromString(RoleTemplate.serializer(), it) }.getOrNull() }
                ?.let { Permissions.sanitize(Permissions.upgrade(it)) }
                ?: DefaultRoles.template
            ).copy(roomPassword = roomPassword(account))

    /** The account's default room password, hashed ([RoomPasswords]), or null. */
    fun roomPassword(account: Accounts.Account): String? = accounts.data(account.id, "room_password")

    fun roomPassword(username: String): String? = accounts.find(username)?.let(::roomPassword)

    fun settings(account: Accounts.Account): AccountSettings =
        accounts.data(account.id, "settings")
            ?.let { runCatching { ProtocolJson.decodeFromString(AccountSettings.serializer(), it) }.getOrNull() }
            ?.copy(hasRoomPassword = roomPassword(account) != null)
            ?: AccountSettings(tracking = trackingDefault, hideFromOthers = false, hasRoomPassword = roomPassword(account) != null)

    /** The account's preferences (see the `/account/preferences` route). */
    fun preferences(account: Accounts.Account): JsonObject =
        accounts.data(account.id, "preferences")?.let { runCatching { ProtocolJson.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: JsonObject(emptyMap())

    /** A host reports a song [username] heard; kept only if they track their history (and learned by the song graph). */
    fun recordPlay(username: String, report: PlayReport) {
        val account = accounts.find(username) ?: return
        if (!settings(account).tracking) return
        val graph = graph
        if (graph == null) {
            history.add(account.id, report)
            return
        }
        // One at a time: the song graph counts a play heard by several accounts once.
        synchronized(graph) {
            // Reported twice (the same person in the room twice, a retry): the graph learns it once.
            if (!history.add(account.id, report)) return
            runCatching { graph.played(account.id, report) }.onFailure { log.warn("The song graph couldn't learn a play: {}", it.message) }
        }
    }

    /** A host says where [username] (one of ours) is in an episode. */
    fun reportEpisode(username: String, report: EpisodeProgressReport) {
        val account = accounts.find(username) ?: return
        episodes?.report(account.id, report.id, report.positionMs, report.durationMs)
    }

    /** Where [username] (one of ours) is in an episode, for a host resuming it. */
    fun episodeProgress(username: String, episodeId: String): EpisodeProgress? {
        val account = accounts.find(username) ?: return null
        return episodes?.progress(account.id, listOf(episodeId))?.firstOrNull()
    }

    /**
     * Songs that go with [seedSongId] in the song graph, for [username] (one of ours) as far as
     * their settings allow, or with [personal] false (or no account) only from everyone's
     * listening and YouTube Music's radios.
     */
    fun similar(username: String?, seedSongId: String, personal: Boolean = true): List<Song> {
        val graph = graph ?: return emptyList()
        val account = username?.let(accounts::find)?.takeIf { personal }
        return graph.similar(seedSongId, account?.id, account?.let(::settings)?.historyRadio ?: HistoryRadio.ALL, tuning = account?.let(::tuning) ?: GraphTuning())
    }

    /** How much each part of the song graph counts for [account] (the graph view's sliders). */
    fun tuning(account: Accounts.Account): GraphTuning =
        accounts.data(account.id, "graph_tuning")?.let { runCatching { ProtocolJson.decodeFromString(GraphTuning.serializer(), it) }.getOrNull() } ?: GraphTuning()

    /**
     * What the song graph says about a radio for [username] (one of ours): its songs, and which of
     * the radio's songs they heard or would rather not. With [personal] false (or no account),
     * only everyone's listening and YouTube Music's radios, and nothing about what they heard.
     */
    fun hints(username: String?, request: RadioHintsRequest, personal: Boolean = true): RadioHints {
        val graph = graph ?: return RadioHints(emptyList())
        val account = username?.let(accounts::find)?.takeIf { personal }
        val use = account?.let(::settings)?.historyRadio ?: HistoryRadio.ALL
        return graph.hints(request.seed, account?.id, request.candidates.take(200), request.options, use, account?.let(::tuning) ?: GraphTuning(), request.limit.coerceIn(1, 200))
    }

    /** Where [account]'s upload [id] (in parts) is put together, in the temp folder. */
    private fun uploadFile(account: Accounts.Account, id: String?): File {
        if (id == null || !id.matches(Regex("[A-Za-z0-9]{8,40}"))) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Which upload?")
        return File(System.getProperty("java.io.tmpdir"), "ytmp-upload-${account.id}-$id")
    }

    /** The whole song graph as [username] (one of ours) sees it, for their phone's copy; only everyone's when not [personal]. */
    fun graphFull(username: String, personal: Boolean): GraphFull? {
        val graph = graph ?: return null
        val account = accounts.find(username)?.takeIf { personal }
        val use = account?.let(::settings)?.historyRadio ?: HistoryRadio.ALL
        return graph.full(account?.id, use, account?.let(::tuning) ?: GraphTuning())
    }

    /** A host (a phone) fetched a YouTube Music radio for [username] (one of ours): the graph learns from it. */
    fun radioFetched(username: String, report: RadioFetchReport) {
        val account = accounts.find(username) ?: return
        graph?.learnRadio(report.seed, report.songs.take(SongGraph.MAX_RADIO_SONGS + 1), account.id.takeIf { report.withAccount }, report.way, reportedBy = account.id)
    }

    /** Deletes [account] and everything kept for it (the admin page). */
    fun delete(account: Accounts.Account) {
        accounts.delete(account.id, toInfo(account).id)
        graph?.rebuildSoon()
        log.info("Account {} deleted", account.username)
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

        // A host asks for songs that go with a song (for a radio, or Find similar), with the
        // host token the account gave it. From your own phone (its app's pages) and this server,
        // also from your own listening; other hosts get what everyone here listens to.
        get("/auth/radio") {
            val claims = call.ownToken() ?: throw notLoggedIn()
            val songId = call.request.queryParameters["id"]?.trim().orEmpty()
            if (songId.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Missing id")
            call.respond(SearchResponse(io { similar(claims.sub, songId, personal = mayReadHistory(claims)) }))
        }

        // The same with the radio's choices, and what the graph says about YouTube Music's songs in it.
        post("/auth/radio-hints") {
            val claims = call.ownToken() ?: throw notLoggedIn()
            val request = call.receive<RadioHintsRequest>()
            call.respond(io { hints(claims.sub, request, personal = mayReadHistory(claims)) })
        }

        // A phone's copy of the whole graph as the account sees it (radios without this server).
        get("/auth/graph") {
            val claims = call.ownToken() ?: throw notLoggedIn()
            call.respond(io { graphFull(claims.sub, personal = mayReadHistory(claims)) } ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No song graph here"))
        }

        // A phone fetched a YouTube Music radio (in a room, or to learn from): the graph keeps it.
        post("/auth/radio-fetch") {
            val claims = call.ownToken() ?: throw notLoggedIn()
            val report = call.receive<RadioFetchReport>()
            io { radioFetched(claims.sub, report) }
            call.respond(HttpStatusCode.NoContent)
        }

        // The graph view (also from the pages of your phone's app, like the history): around a
        // song (?seed=), or your most played songs; and how much each part counts for you.
        route("/account/graph") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Put)
            }
            get {
                val account = call.historyAccount()
                val graph = graph ?: throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No song graph here")
                val seed = call.request.queryParameters["seed"]?.trim()?.takeIf { it.isNotEmpty() }
                call.respond(io { graph.view(account.id, seed, tuning(account), settings(account).historyRadio) })
            }
            get("/tuning") {
                val account = call.historyAccount()
                call.respond(io { tuning(account) })
            }
            // Whether the graph is being built again, and how far it got (the progress bar). Without
            // a login: checking one needs the database, which the rebuild holds; this says nothing personal.
            get("/status") {
                call.respond(graph?.buildStatus() ?: GraphBuildStatus(building = false))
            }
            put("/tuning") {
                val account = call.historyAccount()
                val tuning = call.receive<GraphTuning>().sane()
                io { accounts.setData(account.id, "graph_tuning", ProtocolJson.encodeToString(GraphTuning.serializer(), tuning)) }
                call.respond(tuning)
            }
        }

        // Google Takeout exports of your YouTube Music history (the zip, or the watch history in it).
        route("/account/imports") {
            get {
                val account = call.account()
                call.respond(HistoryImportList(io { history.imports(account.id) }))
            }
            // A big export comes in parts first (a reverse proxy often takes at most 1 MB at once):
            // ?upload=<id>&part=<n>, from 0; then POST without a body and ?upload=<id> imports it.
            post("/part") {
                val account = call.account()
                val file = uploadFile(account, call.request.queryParameters["upload"])
                val part = call.request.queryParameters["part"]?.toIntOrNull() ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Which part?")
                val bytes = call.receiveStream().use { it.readNBytes(MAX_UPLOAD_PART + 1) }
                if (bytes.size > MAX_UPLOAD_PART) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "A part is at most ${MAX_UPLOAD_PART / 1024} KB")
                io {
                    if (part == 0) file.delete()
                    if (file.length() + bytes.size > MAX_UPLOAD) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "That's too big for a Takeout export")
                    file.appendBytes(bytes)
                }
                call.respond(HttpStatusCode.NoContent)
            }
            post {
                val account = call.account()
                val parts = call.request.queryParameters["upload"]?.let { uploadFile(account, it) }
                val whole = if (parts == null) call.receiveStream() else null
                // The export's own times are in the time zone of whoever made it (the HTML says "CEST", not which one): the page's.
                val zone = call.request.queryParameters["tz"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
                val made = try {
                    io {
                        if (parts != null) {
                            if (!parts.isFile) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Nothing was uploaded")
                            try {
                                parts.inputStream().buffered().use { history.importTakeout(account.id, Takeout.read(it, zone)) }
                            } finally {
                                parts.delete()
                            }
                        } else {
                            history.importTakeout(account.id, Takeout.read(whole!!, zone))
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "That isn't a Takeout export")
                }
                log.info("{} imported {} plays from Takeout ({} already in their history)", account.username, made.plays, made.duplicates)
                if (made.plays > 0) graph?.rebuildSoon()
                call.respond(made)
            }
            delete("/{id}") {
                val account = call.account()
                if (!io { history.deleteImport(account.id, call.parameters["id"]!!) }) throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "No such import")
                graph?.rebuildSoon()
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // A host says where the account is in a podcast episode (paused, skipped, ended), with any
        // host token of the account; reading it back (to resume) takes one that only reaches the
        // account's own devices, like the history.
        post("/auth/episodes") {
            val claims = call.ownToken() ?: throw notLoggedIn()
            val report = call.receive<EpisodeProgressReport>()
            io { reportEpisode(claims.sub, report) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/auth/episodes/{id}") {
            val claims = call.ownToken()?.takeIf(::mayReadHistory) ?: throw notLoggedIn()
            val progress = io { episodeProgress(claims.sub, call.parameters["id"]!!) }
            if (progress == null) call.respond(HttpStatusCode.NoContent) else call.respond(progress)
        }

        // Played marks on podcast pages, also from the pages of your phone's app and of rooms on
        // other hosts (CORS), like the history.
        route("/account/episodes") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Put)
            }
            get {
                val account = call.historyAccount()
                val ids = call.request.queryParameters["ids"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                call.respond(EpisodeProgressList(io { episodes?.progress(account.id, ids).orEmpty() }))
            }
            put("/{id}") {
                val account = call.historyAccount()
                val played = call.receive<MarkEpisodeRequest>().played
                io { episodes?.mark(account.id, call.parameters["id"]!!, played) }
                call.respond(HttpStatusCode.NoContent)
            }
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
            val settings = call.receive<AccountSettings>().copy(hasRoomPassword = false)
            io { accounts.setData(account.id, "settings", ProtocolJson.encodeToString(AccountSettings.serializer(), settings)) }
            call.respond(io { settings(account) })
        }

        // Your default room password: rooms you create start with it (kept hashed).
        put("/account/room-password") {
            val account = call.account()
            val password = call.receive<RoomPasswordRequest>().password
            if (password != null && !RoomPasswords.valid(password)) {
                throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "A password is 1–${RoomPasswords.MAX_LENGTH} characters")
            }
            io {
                if (password == null) accounts.deleteData(account.id, "room_password")
                else accounts.setData(account.id, "room_password", RoomPasswords.hash(password))
            }
            call.respond(io { settings(account) })
        }

        // Your preferences (a JSON object; each feature of the web app has its own keys, like
        // "songMenu"): from every page that may read your history (CORS), so they're the same
        // in rooms everywhere. A PUT sets the keys it has (null removes one) and keeps the rest.
        route("/account/preferences") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Put)
            }
            get {
                val account = call.historyAccount()
                call.respond(io { preferences(account) })
            }
            put {
                val account = call.historyAccount()
                val change = runCatching { ProtocolJson.parseToJsonElement(call.receiveText()) as JsonObject }.getOrNull()
                    ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Preferences are a JSON object")
                val merged = io {
                    synchronized(this@AccountService) {
                        val next = JsonObject((preferences(account) + change).filterValues { it !is JsonNull })
                        val text = next.toString()
                        if (text.length > MAX_PREFERENCES) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Too many preferences")
                        accounts.setData(account.id, "preferences", text)
                        next
                    }
                }
                call.respond(merged)
            }
        }

        // Your history. Reading it and deleting single songs also works from the pages of your
        // phone's app and of rooms on other hosts (CORS); the rest only here, logged in.
        route("/account/history") {
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.Authorization)
                allowMethod(HttpMethod.Delete)
            }
            get {
                val account = call.historyAccount()
                val params = call.request.queryParameters
                val limit = (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val (plays, more) = io { history.page(account.id, params["before"]?.toLongOrNull(), limit, params["q"]) }
                call.respond(HistoryPage(plays, more))
            }

            get("/export") {
                val account = call.account()
                val plays = io { history.all(account.id) }
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"ytmp-history-${account.username}.json\"")
                call.respond(plays)
            }

            delete("/{id}") {
                val account = call.historyAccount()
                if (!io { history.delete(account.id, call.parameters["id"]!!) }) throw ApiException(HttpStatusCode.NotFound, ErrorCode.NOT_FOUND, "Not in your history")
                graph?.rebuildSoon()
                call.respond(HttpStatusCode.NoContent)
            }

            // ?from=&to= (ms) deletes a time range; ?all=true deletes everything.
            delete {
                val account = call.account()
                val params = call.request.queryParameters
                val from = params["from"]?.toLongOrNull()
                val to = params["to"]?.toLongOrNull()
                if (from == null && to == null && params["all"] != "true") throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Say what to delete")
                val count = io { history.deleteRange(account.id, from, to) }
                log.info("{} deleted {} plays from their history", account.username, count)
                graph?.rebuildSoon()
                call.respond(HttpStatusCode.NoContent)
            }
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
            if (account.disabledAt(clock())) throw forbidden(disabledMessage(account))
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

        // Your YouTube Music here, for the pages of rooms on other hosts (they call this server).
        post("/account/youtube-token") {
            call.respond(youtubeToken(call.account()))
        }

        // Your listening history here, for the pages of rooms on other hosts (they call this server).
        post("/account/history-token") {
            call.respond(scopedToken(call.account(), HISTORY_SCOPE))
        }

        post("/account/host-token") {
            val account = call.account()
            val origin = normalizeOrigin(call.receive<HostTokenRequest>().origin)
                ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Not a web address")
            call.respond(hostToken(account, origin))
        }
    }

    /** The account of the login session in `Authorization: Bearer` (this server's own pages), if any. Not a host token. */
    suspend fun sessionAccount(call: ApplicationCall): Accounts.Account? =
        call.bearerToken()?.let { token -> io { accounts.sessionAccount(token) } }

    private suspend fun ApplicationCall.account(): Accounts.Account {
        val token = bearerToken() ?: throw notLoggedIn()
        return io { accounts.sessionAccount(token) } ?: throw notLoggedIn()
    }

    /**
     * Whose history this request may read: a login here, a token just for that ([HISTORY_SCOPE],
     * for the pages of rooms on other hosts), or a host token made for your phone's own app
     * ([PHONE_APP_ORIGIN], or the debug app's) or this server's pages: those never reach another host. A host token
     * for another host is not enough: every host you join has one.
     */
    private suspend fun ApplicationCall.historyAccount(): Accounts.Account {
        val token = bearerToken() ?: throw notLoggedIn()
        io { accounts.sessionAccount(token) }?.let { return it }
        val claims = AccountTokens.verify(token, { iss -> if (iss == issuer) key.public else null }, clock() / 1000) ?: throw notLoggedIn()
        val allowed = claims.scope == HISTORY_SCOPE || (claims.scope == null && mayReadHistory(claims))
        return io { accounts.find(claims.sub) }?.takeIf { allowed } ?: throw notLoggedIn()
    }

    /** A host token that only reaches the account's own devices: for the phone's own app, or this server's pages. */
    private fun mayReadHistory(claims: AccountTokens.Claims): Boolean {
        val origin = normalizeOrigin(claims.aud) ?: return false
        return origin == PHONE_APP_ORIGIN || origin == PHONE_DEBUG_APP_ORIGIN || isOwnOrigin(origin)
    }

    /** A host token signed by us (made for any host), from `Authorization: Bearer`. */
    private fun ApplicationCall.ownToken(): AccountTokens.Claims? = bearerToken()?.let(::verifyOwnToken)

    /** A host token of one of this server's accounts, for any host (they act for the account here). */
    fun verifyOwnToken(token: String): AccountTokens.Claims? =
        AccountTokens.verify(token, { iss -> if (iss == issuer) key.public else null }, clock() / 1000)?.takeIf { it.scope == null }

    /**
     * A token for one of this server's accounts' YouTube Music only (see [youtubeToken]), and
     * only for what such a token may do: read, like and dislike, subscribe to podcasts, save songs to playlists. Not
     * removing, moving, renaming or deleting, changing settings or signing out.
     */
    fun verifyYoutubeToken(token: String, method: HttpMethod, path: String): String? {
        val claims = AccountTokens.verify(token, { iss -> if (iss == issuer) key.public else null }, clock() / 1000) ?: return null
        if (claims.scope != YOUTUBE_SCOPE) return null
        val allowed = when (method) {
            HttpMethod.Get -> path.startsWith("/api/me/")
            HttpMethod.Put -> path.startsWith("/api/me/likes/") || (path.startsWith("/api/me/podcasts/") && path.endsWith("/saved"))
            HttpMethod.Post -> path == "/api/me/playlists" || (path.startsWith("/api/me/playlists/") && path.endsWith("/songs"))
            else -> false
        }
        return claims.accountId.takeIf { allowed }
    }

    /** A token for [account]'s YouTube Music on this server, for pages of other hosts (30 days). */
    fun youtubeToken(account: Accounts.Account): HostTokenResponse = scopedToken(account, YOUTUBE_SCOPE)

    /** A token for one thing of [account] on this server ([scope]), for pages of other hosts (30 days). */
    private fun scopedToken(account: Accounts.Account, scope: String): HostTokenResponse {
        val now = clock() / 1000
        val exp = now + HOST_TOKEN_LIFETIME.inWholeSeconds
        val claims = AccountTokens.Claims(issuer, account.username, account.displayName, "", now, exp, scope = scope)
        return HostTokenResponse(AccountTokens.sign(claims, key.private), exp * 1000, toInfo(account))
    }

    /** The account's setting for opening its rooms on another host. */
    fun openElsewhere(username: String): RoomOpenMode = accounts.find(username)?.let(::settings)?.openElsewhere ?: RoomOpenMode.ASK

    /** The server admin logged in here, or throws. */
    suspend fun admin(call: ApplicationCall): Accounts.Account =
        call.account().takeIf { it.isAdmin } ?: throw forbidden("Only the server admin can do this")

    fun <T> accountCall(block: () -> T): T = try {
        block()
    } catch (e: Accounts.AccountException) {
        throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, e.message ?: "Invalid")
    }

    private fun notLoggedIn() = ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Not logged in")

    /** Why a disabled account can't log in. */
    private fun disabledMessage(account: Accounts.Account): String {
        if (account.disabledUntil == Accounts.FOREVER) return "This account is disabled. Ask a server admin."
        val minutes = ((account.disabledUntil - clock()) / 60_000).coerceAtLeast(1)
        return "This account is disabled for another ${if (minutes >= 120) "${minutes / 60} hours" else "$minutes minutes"}."
    }

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

/** [AccountTokens.Claims.scope] of tokens that are only for an account's YouTube Music on its server. */
const val YOUTUBE_SCOPE = "youtube"

/** [AccountTokens.Claims.scope] of tokens that are only for reading an account's listening history (and deleting songs from it). */
const val HISTORY_SCOPE = "history"

/** How big an account's preferences may get (characters of JSON). */
private const val MAX_PREFERENCES = 64 * 1024

/** Where the app's own pages are, on every phone (android/.../LocalHost.kt): tokens for it only reach your own phone. */
const val PHONE_APP_ORIGIN = "http://127.0.0.1:8765"

/** The same for "YTMP debug", which installs next to the app with its host on its own port. */
const val PHONE_DEBUG_APP_ORIGIN = "http://127.0.0.1:8766"

/** Tuning within sensible bounds (the sliders' ranges). */
private fun GraphTuning.sane() = GraphTuning(
    personal = personal.coerceIn(0.0, 5.0),
    server = server.coerceIn(0.0, 5.0),
    imported = imported.coerceIn(0.0, 5.0),
    ytm = ytm.coerceIn(0.0, 5.0),
    ytmAccount = ytmAccount.coerceIn(0.0, 5.0),
    radioNear = radioNear.coerceIn(0.0, 5.0),
    listenNear = listenNear.coerceIn(0.0, 5.0),
    oneOff = oneOff.coerceIn(0.0, 1.0),
    party = party.coerceIn(0.0, 2.0),
    skip = skip.coerceIn(0.0, 5.0),
    reverse = reverse.coerceIn(0.0, 2.0),
    secondHop = secondHop.coerceIn(0.0, 1.0),
    halfLifeDays = halfLifeDays.coerceIn(0.0, 3650.0),
    radioDepth = radioDepth.coerceIn(1, SongGraph.MAX_RADIO_SONGS),
)

/** An upload in parts: each at most this big (a reverse proxy's usual 1 MB limit, with room to spare), and all of it. */
private const val MAX_UPLOAD_PART = 512 * 1024
private const val MAX_UPLOAD = 500L * 1024 * 1024
