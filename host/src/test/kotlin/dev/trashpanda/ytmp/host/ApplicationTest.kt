package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.HostInfo
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.RoomListResponse
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.SearchResponse
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApplicationTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())
    private val search = SongSearch { listOf(song) }

    private fun ApplicationTestBuilder.setup() {
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, search, audio = null, webApp = null, HostOptions(kind = HostKind.SERVER))
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
            ytmpModule(rooms, search, audio = null, webApp = dir, HostOptions(kind = HostKind.SERVER))
        }
        val page = client.get("/room/ABCD")
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("no-cache", page.headers[io.ktor.http.HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.NotFound, client.get("/_app/immutable/old.js").status)
        assertEquals(HttpStatusCode.OK, client.get("/_app/immutable/app.abc.js").status)
    }

    @Test
    fun `search goes to the sources`() = testApplication {
        setup()
        val result = jsonClient().get("/api/search?q=daft").body<SearchResponse>()
        assertEquals(listOf(song), result.items)
        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/api/search?q=").status)
    }

    /** Phone mode. Requests with the "X-Remote" header count as coming from another device. */
    private fun ApplicationTestBuilder.setupPhone() {
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(
                rooms, search, audio = null, webApp = null,
                HostOptions(
                    kind = HostKind.PHONE,
                    shareUrl = { "http://192.168.1.23:8765" },
                    localOnlyRoomManagement = true,
                    supportsPrivateRooms = true,
                    isLocal = { it.request.headers["X-Remote"] == null },
                ),
            )
        }
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
    fun `a server has no solo rooms and never lists rooms`() = testApplication {
        setup()
        val client = jsonClient()
        val solo = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            setBody(CreateRoomRequest("Solo", RoomVisibility.PRIVATE))
        }
        assertEquals(HttpStatusCode.BadRequest, solo.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/rooms").status)
    }
}
