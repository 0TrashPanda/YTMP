package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.core.AccountIdentity
import dev.trashpanda.ytmp.core.SearchSuggestions
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.HomeSection
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.PodcastSavedRequest
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import dev.trashpanda.ytmp.protocol.AuthServerRef
import dev.trashpanda.ytmp.protocol.YoutubeAccountStatus
import dev.trashpanda.ytmp.protocol.YoutubePersonalizeSetting
import dev.trashpanda.ytmp.protocol.CreatePlaylistRequest
import dev.trashpanda.ytmp.protocol.CreatePlaylistResponse
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.RenamePlaylistRequest
import dev.trashpanda.ytmp.protocol.MovePlaylistItemRequest
import dev.trashpanda.ytmp.protocol.RemovePlaylistItemsRequest
import dev.trashpanda.ytmp.protocol.PlaylistItemRef
import dev.trashpanda.ytmp.protocol.MyPlaylists
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.SaveSongsRequest
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.RoomClone
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.HostInfo
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.RoomListResponse
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchSection
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.wireName
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.SuggestionsResponse
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import dev.trashpanda.ytmp.protocol.ScreenPairing
import dev.trashpanda.ytmp.protocol.ScreenLinked
import dev.trashpanda.ytmp.protocol.ScreenLinkRequest
import dev.trashpanda.ytmp.protocol.ScreenCastRequest
import dev.trashpanda.ytmp.protocol.ScreenUnlinkRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import dev.trashpanda.ytmp.protocol.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApplicationTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())
    /** Answers with the song, in a section named after the type it was asked for. */
    private val search = SongSearch { _, type -> SearchPage(listOf(SearchSection(type.wireName, type, listOf(SearchItem.SongResult(song))))) }

    private fun ApplicationTestBuilder.setup() {
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search), HostOptions(kind = HostKind.SERVER))
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(WebSockets)
    }

    private suspend fun DefaultClientWebSocketSession.sendMessage(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    private suspend fun DefaultClientWebSocketSession.receiveMessage(): ServerMessage =
        ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText())

    @Test
    fun `find similar mixes in the song graph of whoever asks`() = testApplication {
        fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef(null, "Artist")), null, 200_000, emptyList())
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(
                rooms,
                HostSources(
                    search,
                    similar = { seed -> if (seed == "local:x") throw SourceException("not on YouTube Music") else listOf(song(seed), song("r1"), song("r2"), song("r3")) },
                    songGraph = { call, seed -> if (call.request.headers[HttpHeaders.Authorization] == "Bearer anna") listOf(song("$seed-g1"), song("r2")) else emptyList() },
                ),
                HostOptions(kind = HostKind.SERVER),
            )
        }
        val client = createClient { install(ContentNegotiation) { json(ProtocolJson) } }
        suspend fun similar(id: String, token: String?) = client.get("/api/similar?id=$id") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

        assertEquals(listOf("r1", "a-g1", "r2", "r3"), similar("a", "anna").body<dev.trashpanda.ytmp.protocol.SearchResponse>().items.map { it.id })
        assertEquals(listOf("r1", "r2", "r3"), similar("a", null).body<dev.trashpanda.ytmp.protocol.SearchResponse>().items.map { it.id })
        // Not on YouTube Music (a local file): the song graph alone, if it knows the song.
        assertEquals(listOf("local:x-g1", "r2"), similar("local:x", "anna").body<dev.trashpanda.ytmp.protocol.SearchResponse>().items.map { it.id })
        assertEquals(HttpStatusCode.BadGateway, similar("local:x", null).status)
    }

    @Test
    fun `an accounts-only host lets nobody in without an account`() = testApplication {
        val auth = object : HostAuth {
            override fun servers() = listOf(AuthServerRef("https://accounts.example", "accounts.example"))
            override fun verify(token: String) = if (token == "anna") AccountIdentity("anna@accounts.example", "Anna") else null
        }
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search), HostOptions(kind = HostKind.SERVER, auth = auth, accountsOnly = { true }))
        }
        val client = jsonClient()
        fun io.ktor.client.request.HttpRequestBuilder.asAnna() = header(HttpHeaders.Authorization, "Bearer anna")

        // What's needed to log in still works, and says it's accounts only.
        assertEquals(true, client.get("/api/host").body<HostInfo>().accountsOnly)
        // Everything else needs an account.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/search?q=daft").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/rooms") { contentType(ContentType.Application.Json); setBody(CreateRoomRequest("Party")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/search?q=daft") { header(HttpHeaders.Authorization, "Bearer nobody") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=daft") { asAnna() }.status)
        val created = client.post("/api/rooms") { asAnna(); contentType(ContentType.Application.Json); setBody(CreateRoomRequest("Party")) }.body<CreateRoomResponse>()

        // Joining: a guest is turned away, an account gets in.
        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Guest", null, null))
            assertEquals(RejectReason.ACCOUNT_REQUIRED, assertIs<ServerMessage.Rejected>(receiveMessage()).reason)
        }
        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Anna", null, created.ownerToken, accountToken = "anna"))
            assertEquals("anna@accounts.example", assertIs<ServerMessage.Welcome>(receiveMessage()).accountId)
        }
    }

    @Test
    fun `a TV becomes a party screen with a code entered in the room, also on an accounts-only host`() = testApplication {
        val auth = object : HostAuth {
            override fun servers() = listOf(AuthServerRef("https://accounts.example", "accounts.example"))
            override fun verify(token: String) = if (token == "anna") AccountIdentity("anna@accounts.example", "Anna") else null
        }
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search), HostOptions(kind = HostKind.SERVER, auth = auth, accountsOnly = { true }))
        }
        val client = jsonClient()
        val created = client.post("/api/rooms") {
            header(HttpHeaders.Authorization, "Bearer anna"); contentType(ContentType.Application.Json); setBody(CreateRoomRequest("Party"))
        }.body<CreateRoomResponse>()
        var annaToken = ""
        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Anna", null, created.ownerToken, accountToken = "anna"))
            annaToken = assertIs<ServerMessage.Welcome>(receiveMessage()).guestToken
        }

        // The TV (no account) gets a code and waits.
        val pairing = client.post("/api/screens").body<ScreenPairing>()
        assertEquals(6, pairing.code.length)
        val linked = coroutineScope {
            val waiting = async { client.get("/api/screens/${pairing.secret}") }
            // A wrong code, or someone who isn't in the room, doesn't link it.
            val wrong = if (pairing.code == "000000") "111111" else "000000"
            assertEquals(HttpStatusCode.NotFound, client.post("/api/screens/link") { contentType(ContentType.Application.Json); setBody(ScreenLinkRequest(wrong, created.code, annaToken)) }.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/screens/link") { contentType(ContentType.Application.Json); setBody(ScreenLinkRequest(pairing.code, created.code, "nobody")) }.status)
            // Anna enters it (spaces are fine).
            val code = pairing.code.chunked(3).joinToString(" ")
            assertEquals(HttpStatusCode.NoContent, client.post("/api/screens/link") { contentType(ContentType.Application.Json); setBody(ScreenLinkRequest(code, created.code, annaToken)) }.status)
            waiting.await().body<ScreenLinked>()
        }
        assertEquals(created.code, linked.roomCode)

        // It joins without an account, as a screen that only watches.
        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Living room TV", linked.guestToken, null))
            val welcome = assertIs<ServerMessage.Welcome>(receiveMessage())
            val me = welcome.state.participants.single { it.id == welcome.participantId }
            assertEquals(true, me.screen)
            assertEquals("Party screen", me.name)
            sendMessage(ClientMessage.CommandMessage("c1", Command.AddSongs(listOf(song), QueuePosition.END)))
            var result: ServerMessage
            do result = receiveMessage() while (result !is ServerMessage.Result)
            assertEquals(ErrorCode.PERMISSION_DENIED, result.error?.code)
        }
        // The code is used up.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/screens/${pairing.secret}").status)
    }

    @Test
    fun `the app shows the party screen on your own TV, which plays and leaves again`() = testApplication {
        setup()
        val client = jsonClient()
        val created = client.post("/api/rooms") { contentType(ContentType.Application.Json); setBody(CreateRoomRequest("Party")) }.body<CreateRoomResponse>()
        var annaToken = ""
        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Anna", null, created.ownerToken))
            annaToken = assertIs<ServerMessage.Welcome>(receiveMessage()).guestToken
        }
        suspend fun cast(by: String) = client.post("/api/screens/cast") { contentType(ContentType.Application.Json); setBody(ScreenCastRequest(created.code, by, "Living room TV")) }
        assertEquals(HttpStatusCode.NotFound, cast("nobody").status)
        val linked = cast(annaToken).body<ScreenLinked>()

        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Party screen", linked.guestToken, null))
            val welcome = assertIs<ServerMessage.Welcome>(receiveMessage())
            val me = welcome.state.participants.single { it.id == welcome.participantId }
            assertEquals(true, me.screen)
            assertEquals("Living room TV", me.name)
            assertTrue(Permission.LISTEN_LOCALLY in me.allow)
        }
        suspend fun unlink(token: String) = client.post("/api/screens/unlink") { contentType(ContentType.Application.Json); setBody(ScreenUnlinkRequest(created.code, token)) }.status
        assertEquals(HttpStatusCode.NotFound, unlink(annaToken))
        assertEquals(HttpStatusCode.NoContent, unlink(linked.guestToken))
        assertEquals(HttpStatusCode.NotFound, unlink(linked.guestToken))
    }

    @Test
    fun `a room can have a code of your own choosing, once`() = testApplication {
        setup()
        val client = jsonClient()
        suspend fun create(code: String?) = client.post("/api/rooms") { contentType(ContentType.Application.Json); setBody(CreateRoomRequest("Party", code = code)) }

        val created = create(" party1 ").body<CreateRoomResponse>()
        assertEquals("PARTY1", created.code)
        assertTrue(created.url!!.endsWith("/room/PARTY1"))
        assertEquals(HttpStatusCode.OK, client.get("/api/rooms/party1").status)
        // Taken (any case), too short, not just letters and digits.
        assertEquals(HttpStatusCode.Conflict, create("Party1").status)
        assertEquals(HttpStatusCode.BadRequest, create("AB").status)
        assertEquals(HttpStatusCode.BadRequest, create("MY ROOM").status)
        assertEquals(HttpStatusCode.BadRequest, create("ABCDEFGHIJKLM").status)
        // Empty: a random one, as before.
        assertEquals(4, create("").body<CreateRoomResponse>().code.length)
    }

    @Test
    fun `create a room, join it and add a song`() = testApplication {
        setup()
        val client = jsonClient()

        val created = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Party"))
        }.body<CreateRoomResponse>()
        assertEquals(4, created.code.length)
        assertEquals(HttpStatusCode.OK, client.get("/api/rooms/${created.code.lowercase()}").status)

        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Anna", null, created.ownerToken))
            val welcome = assertIs<ServerMessage.Welcome>(receiveMessage())
            assertEquals("Party", welcome.state.room.name)

            sendMessage(ClientMessage.CommandMessage("c1", Command.AddSongs(listOf(song), QueuePosition.END)))
            val events = mutableListOf<Event>()
            while (events.none { it is Event.StreamReady }) {
                when (val message = receiveMessage()) {
                    is ServerMessage.EventMessage -> events += message.event
                    is ServerMessage.Result -> assertEquals(null, message.error)
                    else -> Unit
                }
            }
            val owner = events.filterIsInstance<Event.ParticipantJoined>().single().participant
            assertEquals(true, owner.isOwner)
            assertEquals("https://stream/ytm:abc", events.filterIsInstance<Event.StreamReady>().single().streamUrl)
        }
    }

    @Test
    fun `a clone starts with the copied queue and comes with its link`() = testApplication {
        setup()
        val client = jsonClient()
        val queued = QueueItem("i1", song, "p1", "Ben", 0, null)
        val clone = RoomClone(queue = listOf(queued), history = emptyList(), current = queued.copy(itemId = "i0"), positionMs = 42_000)

        val created = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Party (copy)", clone = clone))
        }.body<CreateRoomResponse>()

        assertEquals("http://localhost/room/${created.code}", created.url)
        client.webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Anna", null, created.ownerToken))
            val welcome = assertIs<ServerMessage.Welcome>(receiveMessage())
            assertEquals(listOf("i1"), welcome.state.queue.map { it.itemId })
            assertEquals("i0", welcome.state.nowPlaying?.item?.itemId)
            assertEquals(false, welcome.state.playback.playing)
            assertEquals(42_000, welcome.state.playback.positionMs)
        }
    }

    @Test
    fun `a second connection can attach to a participant and follow the room`() = testApplication {
        setup()
        val client = jsonClient()
        val created = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Party"))
        }.body<CreateRoomResponse>()

        client.webSocket("/ws") {
            val page = this
            page.sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Anna", null, created.ownerToken))
            val welcome = assertIs<ServerMessage.Welcome>(page.receiveMessage())

            client.webSocket("/ws") {
                sendMessage(ClientMessage.Attach(PROTOCOL_VERSION, created.code, welcome.guestToken))
                val attached = assertIs<ServerMessage.Welcome>(receiveMessage())
                assertEquals(welcome.participantId, attached.participantId)
                assertEquals(true, attached.state.participants.single().online)

                // Commands from the attached connection act as Anna, and both connections get the events.
                sendMessage(ClientMessage.CommandMessage("n1", Command.AddSongs(listOf(song), QueuePosition.END)))
                var gotResult = false
                var gotStream = false
                while (!gotResult || !gotStream) {
                    when (val message = receiveMessage()) {
                        is ServerMessage.Result -> { assertEquals("n1", message.id); assertEquals(null, message.error); gotResult = true }
                        is ServerMessage.EventMessage -> if (message.event is Event.StreamReady) gotStream = true
                        else -> Unit
                    }
                }
                while (true) {
                    val message = page.receiveMessage()
                    assertIs<ServerMessage.EventMessage>(message)
                    // Attaching didn't change Anna's participant (no ParticipantUpdated for her).
                    assertEquals(false, message.event is Event.ParticipantUpdated)
                    if (message.event is Event.StreamReady) break
                }
            }
        }

        client.webSocket("/ws") {
            sendMessage(ClientMessage.Attach(PROTOCOL_VERSION, created.code, "not-a-token"))
            assertEquals(ServerMessage.Rejected(RejectReason.KICKED), receiveMessage())
        }
    }

    @Test
    fun `joining an unknown room is rejected`() = testApplication {
        setup()
        jsonClient().webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, "ZZZZ", "Anna", null, null))
            assertEquals(ServerMessage.Rejected(RejectReason.ROOM_NOT_FOUND), receiveMessage())
        }
    }

    @Test
    fun `web app falls back to index html but missing files are 404`() = testApplication {
        val dir = kotlin.io.path.createTempDirectory("web").toFile().apply {
            resolve("index.html").writeText("<html>app</html>")
            resolve("_app/immutable").mkdirs()
            resolve("_app/immutable/app.abc.js").writeText("js")
            deleteOnExit()
        }
        application {
            val rooms = RoomManager({ "x" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search), HostOptions(kind = HostKind.SERVER), webApp = dir)
        }
        val page = client.get("/room/ABCD")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("no-cache", page.headers[io.ktor.http.HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.NotFound, client.get("/_app/immutable/old.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/_app/immutable/app.abc.js").status)
    }

    @Test
    fun `a host that doesn't stream audio for this client says so`() = testApplication {
        application {
            val rooms = RoomManager({ "x" }, CoroutineScope(SupervisorJob()))
            val audio = AudioProxy { call, _, _ -> call.respondText("audio") }
            ytmpModule(rooms, HostSources(search, audio = audio), HostOptions(kind = HostKind.SERVER, mayProxyAudio = { it.request.headers["X-Test-Lan"] == "1" }))
        }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/audio/ytm:abc").status)
        assertEquals("audio", client.get("/api/audio/ytm:abc") { header("X-Test-Lan", "1") }.bodyAsText())
    }

    @Test
    fun `a download is the song as a file, named after it`() = testApplication {
        val asked = mutableListOf<String>()
        application {
            val rooms = RoomManager({ "x" }, CoroutineScope(SupervisorJob()))
            // Not an m4a: passed on as it came.
            val files = SongFiles(object : AudioFiles {
                override suspend fun download(songId: String, to: java.io.File, started: (type: String, size: Long?) -> Unit): String {
                    asked += songId
                    to.writeText("audio")
                    return "audio/webm"
                }
            })
            ytmpModule(rooms, HostSources(search, downloads = files), HostOptions(kind = HostKind.SERVER, mayProxyAudio = { it.request.headers["X-Test-Lan"] == "1" }))
        }
        val client = jsonClient()
        suspend fun download(song: Song, lan: Boolean = true) = client.post("/api/download") {
            contentType(ContentType.Application.Json)
            if (lan) header("X-Test-Lan", "1")
            setBody(song)
        }
        val ok = download(song.copy(title = "Één"))
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("audio", ok.bodyAsText())
        assertEquals("audio/webm", ok.headers[HttpHeaders.ContentType])
        assertEquals("attachment; filename=\"Daft Punk - __n.webm\"; filename*=UTF-8''Daft%20Punk%20-%20%C3%89%C3%A9n.webm", ok.headers[HttpHeaders.ContentDisposition])
        assertEquals(HttpStatusCode.Forbidden, download(song, lan = false).status)
        assertEquals(HttpStatusCode.BadRequest, download(song.copy(id = "local:x:s:1")).status)
        assertEquals(listOf(song.id), asked)
    }

    @Test
    fun `search goes to the sources`() = testApplication {
        setup()
        val all = jsonClient().get("/api/search?q=daft").body<SearchPage>()
        assertEquals(SearchType.ALL, all.sections.single().type)
        assertEquals(listOf<SearchItem>(SearchItem.SongResult(song)), all.sections.single().items)
        val playlists = jsonClient().get("/api/search?q=daft&type=community_playlists").body<SearchPage>()
        assertEquals(SearchType.COMMUNITY_PLAYLISTS, playlists.sections.single().type)
        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/api/search?q=daft&type=profiles").status)
        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/api/search?q=").status)
    }

    @Test
    fun `suggestions while typing`() = testApplication {
        application {
            val rooms = RoomManager({ "x" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search, suggestions = { q -> listOf("$q punk", "$q punk one more time") }), HostOptions(kind = HostKind.SERVER))
        }
        assertEquals(listOf("daft punk", "daft punk one more time"), jsonClient().get("/api/search/suggestions?q=daft").body<SuggestionsResponse>().items)
        assertEquals(emptyList(), jsonClient().get("/api/search/suggestions?q=%20").body<SuggestionsResponse>().items)
    }

    @Test
    fun `no suggestion source means no suggestions`() = testApplication {
        setup()
        assertEquals(emptyList(), jsonClient().get("/api/search/suggestions?q=daft").body<SuggestionsResponse>().items)
    }

    /** Phone mode. Requests with the "X-Remote" header count as coming from another device. */
    /** Tokens are "token-<username>", for accounts of "home". */
    private val testAuth = object : HostAuth {
        override fun servers() = listOf(AuthServerRef("https://home.example", "home"))
        override fun verify(token: String) = token.removePrefix("token-").takeIf { it != token }?.let { AccountIdentity("$it@home", it) }
    }

    private fun ApplicationTestBuilder.setupPhone(
        catalog: CatalogSource? = null,
        personal: PersonalCatalog? = null,
        suggestions: SearchSuggestions? = null,
        deviceOwner: () -> String? = { null },
        onLocalAccount: (AccountIdentity) -> Unit = {},
    ) {
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(
                rooms,
                HostSources(search, catalog = catalog, personal = personal, suggestions = suggestions),
                HostOptions(
                    kind = HostKind.PHONE,
                    shareUrl = { "http://192.168.1.23:8765" },
                    localOnlyRoomManagement = true,
                    supportsPrivateRooms = true,
                    isLocal = { it.request.headers["X-Remote"] == null },
                    auth = testAuth,
                    deviceOwner = deviceOwner,
                    onLocalAccount = onLocalAccount,
                ),
            )
        }
    }

    private fun home(title: String) = HomePage(listOf(HomeSection(title, emptyList())))

    @Test
    fun `a song by its ID, for a pasted link`() = testApplication {
        application {
            val rooms = RoomManager({ "x" }, CoroutineScope(SupervisorJob()))
            val catalog = object : CatalogSource by generalCatalog {
                override suspend fun song(id: String) = if (id == song.id) song else throw SourceException("YouTube Music doesn't have that song")
            }
            ytmpModule(rooms, HostSources(search, catalog = catalog), HostOptions(kind = HostKind.SERVER))
        }
        assertEquals(song, jsonClient().get("/api/songs/ytm:abc").body<Song>())
        assertEquals(HttpStatusCode.BadGateway, client.get("/api/songs/ytm:other").status)
    }

    @Test
    fun `no song lookups where the source has none`() = testApplication {
        application {
            val rooms = RoomManager({ "x" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search, catalog = generalCatalog), HostOptions(kind = HostKind.SERVER))
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/songs/ytm:abc").status)
    }

    private val generalCatalog = object : CatalogSource {
        override suspend fun artist(id: String) = error("no")
        override suspend fun album(id: String) = error("no")
        override suspend fun playlist(id: String) = error("no")
        override suspend fun podcast(id: String) = error("no")
        override suspend fun home() = home("General")
    }

    private class Owner(var account: YoutubeAccount?) : PersonalCatalog {
        override suspend fun account() = account
        override suspend fun home() = HomePage(listOf(HomeSection("Mine", emptyList())))
        override suspend fun library() = HomePage(listOf(HomeSection("Playlists", emptyList())))
        override suspend fun playlist(id: String, more: String?) = PlaylistPage(id, "Liked music", null, null, emptyList(), emptyList())
        val liked = mutableSetOf<String>()
        val saved = mutableListOf<Pair<String, List<String>>>()
        override suspend fun liked(songId: String) = songId in liked
        override suspend fun setLiked(songId: String, liked: Boolean) { if (liked) this.liked += songId else this.liked -= songId }
        override suspend fun ownPlaylists() = listOf(PlaylistSummary("PLmine", "Mine", emptyList()))
        override suspend fun addToPlaylist(playlistId: String, songIds: List<String>) { saved += playlistId to songIds }
        override suspend fun createPlaylist(title: String, songIds: List<String>) = "PLnew".also { saved += it to songIds }
        override suspend fun signOut() { account = null }

        val ratings = mutableMapOf<String, LikeStatus>()
        val edits = mutableListOf<String>()
        override suspend fun rating(songId: String) = ratings[songId] ?: LikeStatus(songId in liked)
        override suspend fun setRating(songId: String, rating: LikeStatus) {
            ratings[songId] = rating
            if (rating.liked) liked += songId else liked -= songId
        }
        val podcasts = mutableSetOf<String>()
        override suspend fun podcast(id: String) = PodcastPage(id, "A podcast", null, null, emptyList(), emptyList(), saved = id in podcasts)
        override suspend fun setPodcastSaved(id: String, saved: Boolean) { if (saved) podcasts += id else podcasts -= id }
        override suspend fun removeFromPlaylist(playlistId: String, items: List<PlaylistItemRef>) { edits += "remove $playlistId ${items.joinToString { it.itemId }}" }
        override suspend fun movePlaylistItem(playlistId: String, itemId: String, beforeItemId: String?) { edits += "move $playlistId $itemId before $beforeItemId" }
        override suspend fun renamePlaylist(playlistId: String, title: String) { edits += "rename $playlistId $title" }
        override suspend fun deletePlaylist(playlistId: String) { edits += "delete $playlistId" }

        var personalize = true
        var searchFails = false
        override suspend fun personalize() = personalize
        override suspend fun setPersonalize(on: Boolean) { personalize = on }
        override suspend fun search(query: String, type: SearchType): SearchPage {
            if (searchFails) error("expired")
            return SearchPage(listOf(SearchSection("Mine", type, emptyList())))
        }
        override suspend fun suggestions(query: String) = listOf("$query (mine)")
    }

    @Test
    fun `the phone's owner searches with their YouTube Music account, guests without`() = testApplication {
        val owner = Owner(YoutubeAccount("Jonah", "@jonah", null))
        setupPhone(generalCatalog, owner, suggestions = { q -> listOf("$q (anyone)") })
        val client = jsonClient()
        suspend fun searchTitle(remote: Boolean = false) =
            client.get("/api/search?q=daft") { if (remote) header("X-Remote", "1") }.body<SearchPage>().sections.single().title
        suspend fun suggestion(remote: Boolean = false) =
            client.get("/api/search/suggestions?q=daft") { if (remote) header("X-Remote", "1") }.body<SuggestionsResponse>().items.single()

        // On by default.
        assertEquals(true, client.get("/api/me/youtube").body<YoutubeAccountStatus>().personalize)
        assertEquals("Mine", searchTitle())
        assertEquals("daft (mine)", suggestion())
        assertEquals("all", searchTitle(remote = true))
        assertEquals("daft (anyone)", suggestion(remote = true))
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/me/youtube/personalize") { header("X-Remote", "1"); contentType(ContentType.Application.Json); setBody(YoutubePersonalizeSetting(false)) }.status)

        // An expired sign-in searches without it.
        owner.searchFails = true
        assertEquals("all", searchTitle())
        owner.searchFails = false

        // Turned off.
        assertEquals(HttpStatusCode.NoContent, client.put("/api/me/youtube/personalize") { contentType(ContentType.Application.Json); setBody(YoutubePersonalizeSetting(false)) }.status)
        assertEquals(false, client.get("/api/me/youtube").body<YoutubeAccountStatus>().personalize)
        assertEquals("all", searchTitle())
        assertEquals("daft (anyone)", suggestion())

        // On again, but signed out.
        owner.personalize = true
        owner.account = null
        assertEquals("all", searchTitle())
    }

    @Test
    fun `subscribing to a podcast`() = testApplication {
        val owner = Owner(YoutubeAccount("Jonah", "@jonah", null))
        setupPhone(generalCatalog, owner)
        val client = jsonClient()
        assertEquals(false, client.get("/api/me/podcasts/MPSPabc").body<PodcastPage>().saved)
        assertEquals(HttpStatusCode.NoContent, client.put("/api/me/podcasts/MPSPabc/saved") { contentType(ContentType.Application.Json); setBody(PodcastSavedRequest(true)) }.status)
        assertEquals(true, client.get("/api/me/podcasts/MPSPabc").body<PodcastPage>().saved)
        assertEquals(setOf("MPSPabc"), owner.podcasts)
    }

    @Test
    fun `dislikes, and editing your own playlists`() = testApplication {
        val owner = Owner(YoutubeAccount("Jonah", "@jonah", null))
        setupPhone(generalCatalog, owner)
        val client = jsonClient()
        fun json(b: io.ktor.client.request.HttpRequestBuilder) = b.contentType(ContentType.Application.Json)

        assertEquals(HttpStatusCode.NoContent, client.put("/api/me/likes/ytm:abc") { json(this); setBody(LikeStatus(liked = false, disliked = true)) }.status)
        assertEquals(LikeStatus(liked = false, disliked = true), client.get("/api/me/likes/ytm:abc").body<LikeStatus>())
        // Never both.
        client.put("/api/me/likes/ytm:abc") { json(this); setBody(LikeStatus(liked = true, disliked = true)) }
        assertEquals(LikeStatus(liked = true, disliked = false), client.get("/api/me/likes/ytm:abc").body<LikeStatus>())

        client.post("/api/me/playlists/PLmine/remove") { json(this); setBody(RemovePlaylistItemsRequest(listOf(PlaylistItemRef("ytm:abc", "S1")))) }
        client.post("/api/me/playlists/PLmine/move") { json(this); setBody(MovePlaylistItemRequest("S2", null)) }
        client.put("/api/me/playlists/PLmine") { json(this); setBody(RenamePlaylistRequest("  New  ")) }
        client.delete("/api/me/playlists/PLmine")
        assertEquals(listOf("remove PLmine S1", "move PLmine S2 before null", "rename PLmine New", "delete PLmine"), owner.edits)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/me/playlists/PLmine") { json(this); setBody(RenamePlaylistRequest(" ")) }.status)
        // Not for guests on the phone.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/me/playlists/PLmine") { header("X-Remote", "1") }.status)
    }

    @Test
    fun `your own search and radio, for pages of other hosts`() = testApplication {
        val owner = Owner(YoutubeAccount("Jonah", "@jonah", null))
        setupPhone(generalCatalog, owner)
        val client = jsonClient()

        assertEquals("Mine", client.get("/api/me/search?q=daft").body<SearchPage>().sections.single().title)
        assertEquals(listOf("daft (mine)"), client.get("/api/me/search/suggestions?q=daft").body<SuggestionsResponse>().items)
        // Guests: none of the owner's.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/me/search?q=daft") { header("X-Remote", "1") }.status)
    }

    @Test
    fun `the phone's owner, logged in on another device, uses the phone's YouTube Music there too`() = testApplication {
        val youtube = Owner(YoutubeAccount("Jonah", "@jonah", null))
        var owner: String? = null
        setupPhone(generalCatalog, youtube, deviceOwner = { owner }, onLocalAccount = { owner = it.id })
        val client = jsonClient()
        suspend fun library(token: String?) = client.get("/api/me/library") {
            header("X-Remote", "1")
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }.status

        // The phone's own page, logged in as jonah, makes jonah the phone's owner.
        client.get("/api/me/youtube") { header(HttpHeaders.Authorization, "Bearer token-jonah") }
        assertEquals("jonah@home", owner)

        assertEquals(HttpStatusCode.OK, library("token-jonah"))
        assertEquals("Mine", client.get("/api/home") { header("X-Remote", "1"); header(HttpHeaders.Authorization, "Bearer token-jonah") }.body<HomePage>().sections.single().title)
        // Another account, or a guest: nothing of the owner's.
        assertEquals(HttpStatusCode.Forbidden, library("token-sam"))
        assertEquals(HttpStatusCode.Forbidden, library(null))
        // A remote device can't make itself the owner.
        client.get("/api/me/youtube") { header("X-Remote", "1"); header(HttpHeaders.Authorization, "Bearer token-sam") }
        assertEquals("jonah@home", owner)
    }

    @Test
    fun `the YouTube Music account of a phone's owner is only for the phone itself`() = testApplication {
        val owner = Owner(YoutubeAccount("Jonah", "@jonah", null))
        setupPhone(generalCatalog, owner)
        val client = jsonClient()
        fun remote(builder: io.ktor.client.request.HttpRequestBuilder) = builder.header("X-Remote", "1")

        assertEquals(YoutubeAccountStatus(true, owner.account), client.get("/api/me/youtube").body<YoutubeAccountStatus>())
        assertEquals("Mine", client.get("/api/home").body<HomePage>().sections.single().title)
        assertEquals("Playlists", client.get("/api/me/library").body<HomePage>().sections.single().title)
        assertEquals("Liked music", client.get("/api/me/playlists/LM").body<PlaylistPage>().title)

        // A guest in a room on this phone: nothing of the owner's.
        assertEquals(YoutubeAccountStatus(false, null), client.get("/api/me/youtube") { remote(this) }.body<YoutubeAccountStatus>())
        assertEquals("General", client.get("/api/home") { remote(this) }.body<HomePage>().sections.single().title)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/me/library") { remote(this) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/me/playlists/LM") { remote(this) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/me/youtube") { remote(this) }.status)

        // Likes and saving to playlists: the phone itself only.
        assertEquals(HttpStatusCode.NoContent, client.put("/api/me/likes/ytm:abc") { contentType(ContentType.Application.Json); setBody(LikeStatus(true)) }.status)
        assertEquals(LikeStatus(true), client.get("/api/me/likes/ytm:abc").body<LikeStatus>())
        assertEquals(listOf("PLmine"), client.get("/api/me/playlists").body<MyPlaylists>().playlists.map { it.id })
        assertEquals(HttpStatusCode.NoContent, client.post("/api/me/playlists/PLmine/songs") { contentType(ContentType.Application.Json); setBody(SaveSongsRequest(listOf("ytm:abc"))) }.status)
        assertEquals("PLnew", client.post("/api/me/playlists") { contentType(ContentType.Application.Json); setBody(CreatePlaylistRequest("New", listOf("ytm:abc"))) }.body<CreatePlaylistResponse>().id)
        assertEquals(listOf("PLmine" to listOf("ytm:abc"), "PLnew" to listOf("ytm:abc")), owner.saved)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/me/likes/ytm:abc") { remote(this) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/me/likes/ytm:abc") { remote(this); contentType(ContentType.Application.Json); setBody(LikeStatus(false)) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/me/playlists") { remote(this) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/me/playlists/PLmine/songs") { remote(this); contentType(ContentType.Application.Json); setBody(SaveSongsRequest(listOf("ytm:x"))) }.status)
        assertEquals(setOf("ytm:abc"), owner.liked)

        // Signed out: the general home again.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/me/youtube").status)
        assertEquals(YoutubeAccountStatus(true, null), client.get("/api/me/youtube").body<YoutubeAccountStatus>())
        assertEquals("General", client.get("/api/home").body<HomePage>().sections.single().title)
    }

    @Test
    fun `on a phone only the phone itself can create, list and close rooms`() = testApplication {
        setupPhone()
        val client = jsonClient()

        val local = client.get("/api/host").body<HostInfo>()
        assertEquals(true, local.canCreateRooms)
        assertEquals("http://192.168.1.23:8765", local.shareUrl)
        assertEquals(false, client.get("/api/host") { header("X-Remote", "1") }.body<HostInfo>().canCreateRooms)

        val remoteCreate = client.post("/api/rooms") {
            header("X-Remote", "1")
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Nope"))
        }
        assertEquals(HttpStatusCode.Forbidden, remoteCreate.status)

        val room = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Party"))
        }.body<CreateRoomResponse>()
        assertEquals(listOf(room.code), client.get("/api/rooms").body<RoomListResponse>().rooms.map { it.code })
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/rooms") { header("X-Remote", "1") }.status)

        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/rooms/${room.code}") { header("X-Remote", "1") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rooms/${room.code}").status)
        assertEquals(emptyList(), client.get("/api/rooms").body<RoomListResponse>().rooms)
    }

    @Test
    fun `a private room is invisible to other devices`() = testApplication {
        setupPhone()
        val client = jsonClient()
        val room = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Solo", RoomVisibility.PRIVATE))
        }.body<CreateRoomResponse>()

        assertEquals(HttpStatusCode.OK, client.get("/api/rooms/${room.code}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/rooms/${room.code}") { header("X-Remote", "1") }.status)
        client.webSocket("/ws", request = { header("X-Remote", "1") }) {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Friend", null, null))
            assertEquals(ServerMessage.Rejected(RejectReason.PRIVATE_ROOM), receiveMessage())
        }
    }

    @Test
    fun `a host without solo rooms refuses them, and lists no rooms without an account`() = testApplication {
        setup()
        val client = jsonClient()
        val solo = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Solo", RoomVisibility.PRIVATE))
        }
        assertEquals(HttpStatusCode.BadRequest, solo.status)
        assertEquals(emptyList(), client.get("/api/rooms").body<RoomListResponse>().rooms)
    }

    @Test
    fun `a solo room on a server is only there for its owner`() = testApplication {
        application {
            val rooms = RoomManager({ "x" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search), HostOptions(kind = HostKind.SERVER, supportsPrivateRooms = true, isLocal = { false }))
        }
        val client = jsonClient()
        val created = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Solo", RoomVisibility.PRIVATE))
        }.body<CreateRoomResponse>()

        assertEquals(HttpStatusCode.NotFound, client.get("/api/rooms/${created.code}").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/rooms/${created.code}") { header(OWNER_TOKEN_HEADER, created.ownerToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/rooms/${created.code}").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rooms/${created.code}") { header(OWNER_TOKEN_HEADER, created.ownerToken) }.status)
    }
}
