package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.Event
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
import io.ktor.client.request.get
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
            ytmpModule(rooms, search, ytm = null, frontendDir = null)
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
    fun `joining an unknown room is rejected`() = testApplication {
        setup()
        jsonClient().webSocket("/ws") {
            sendMessage(ClientMessage.Hello(PROTOCOL_VERSION, "ZZZZ", "Anna", null, null))
            assertEquals(ServerMessage.Rejected(RejectReason.ROOM_NOT_FOUND), receiveMessage())
        }
    }

    @Test
    fun `search goes to the sources`() = testApplication {
        setup()
        val result = jsonClient().get("/api/search?q=daft").body<SearchResponse>()
        assertEquals(listOf(song), result.items)
        assertEquals(HttpStatusCode.BadRequest, jsonClient().get("/api/search?q=").status)
    }
}
