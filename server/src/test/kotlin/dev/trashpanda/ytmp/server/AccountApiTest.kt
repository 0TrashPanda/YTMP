package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.AccountTokens
import dev.trashpanda.ytmp.host.HostOptions
import dev.trashpanda.ytmp.host.TrustedAuthServers
import dev.trashpanda.ytmp.host.ytmpModule
import dev.trashpanda.ytmp.protocol.AccountListResponse
import dev.trashpanda.ytmp.protocol.AuthServerInfo
import dev.trashpanda.ytmp.protocol.AuthServerRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.HostInfo
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.HostTokenRequest
import dev.trashpanda.ytmp.protocol.HostTokenResponse
import dev.trashpanda.ytmp.protocol.InviteResponse
import dev.trashpanda.ytmp.protocol.LoginRequest
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.SessionResponse
import dev.trashpanda.ytmp.protocol.SignupMode
import dev.trashpanda.ytmp.protocol.SignupRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountApiTest {
    /** testApplication's pages live at http://localhost. */
    private val origin = "http://localhost"

    private fun ApplicationTestBuilder.setup(signup: SignupMode = SignupMode.INVITE): AccountService {
        val db = Database.open(DatabaseConfig(path = Files.createTempDirectory("ytmp").resolve("ytmp.db").toString()))
        val accounts = Accounts(db)
        val key = accounts.signingKey()
        val service = AccountService(accounts, "test.example", key, signup)
        val auth = TrustedAuthServers(isOwnOrigin = { it == origin }, ownTemplates = service::roleTemplate).apply { trust(AuthServerRef(null, "test.example"), key.public) }
        application {
            val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, { emptyList() }, audio = null, webApp = null, HostOptions(kind = HostKind.SERVER, auth = auth), extraApi = { service.routes(this) })
        }
        return service
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(WebSockets)
    }

    private suspend fun HttpClient.postJson(path: String, body: Any, session: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        session?.let(::bearerAuth)
        setBody(body)
    }

    @Test
    fun `the first account is the admin, after that sign-up needs an invite`() = testApplication {
        setup()
        val client = jsonClient()
        assertTrue(client.get("/api/auth/info").body<AuthServerInfo>().needsAdmin)

        val admin = client.postJson("/api/account/signup", SignupRequest("Jonah", "correct horse", "Jonah")).body<SessionResponse>()
        assertEquals("jonah@test.example", admin.account.id)
        assertTrue(admin.account.isAdmin)

        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/account/signup", SignupRequest("anna", "password123")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/admin/invites", "", session = "nope").status)

        val invite = client.postJson("/api/admin/invites", "", session = admin.sessionToken).body<InviteResponse>()
        val anna = client.postJson("/api/account/signup", SignupRequest("anna", "password123", invite = invite.code)).body<SessionResponse>()
        assertEquals(false, anna.account.isAdmin)
        // An invite works once.
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/account/signup", SignupRequest("bob", "password123", invite = invite.code)).status)
        // Only the admin lists accounts.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/admin/accounts") { bearerAuth(anna.sessionToken) }.status)
        assertEquals(listOf("anna", "jonah"), client.get("/api/admin/accounts") { bearerAuth(admin.sessionToken) }.body<AccountListResponse>().accounts.map { it.username })
    }

    @Test
    fun `wrong passwords are refused, and guessing is slowed down`() = testApplication {
        setup(SignupMode.OPEN)
        val client = jsonClient()
        client.postJson("/api/account/signup", SignupRequest("anna", "password123"))

        assertEquals(HttpStatusCode.OK, client.postJson("/api/account/login", LoginRequest("ANNA", "password123")).status)
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/account/login", LoginRequest("anna", "wrong")).status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/api/account/login", LoginRequest("anna", "password123")).status)
    }

    @Test
    fun `a host token makes you the room owner, on any device`() = testApplication {
        setup(SignupMode.OPEN)
        val client = jsonClient()
        val session = client.postJson("/api/account/signup", SignupRequest("anna", "password123", "Anna B")).body<SessionResponse>().sessionToken
        assertEquals(listOf(AuthServerRef(null, "test.example")), client.get("/api/host").body<HostInfo>().authServers)

        val token = client.postJson("/api/account/host-token", HostTokenRequest("HTTP://LOCALHOST/"), session).body<HostTokenResponse>().token
        val created = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(CreateRoomRequest("Party"))
        }.body<CreateRoomResponse>()

        // Another device: no owner token, but the same account.
        client.webSocket("/ws") {
            send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), ClientMessage.Hello(PROTOCOL_VERSION, created.code, "phone", null, null, accountToken = token))))
            val welcome = assertIs<ServerMessage.Welcome>(ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText()))
            assertEquals("anna@test.example", welcome.accountId)
            val me = welcome.state.participants.single()
            assertEquals("Anna B", me.name)
            assertEquals("anna@test.example", me.accountId)
            assertTrue(me.isOwner)
        }

        // A token made for another host's page is not accepted here: that page's host could replay it.
        val other = client.postJson("/api/account/host-token", HostTokenRequest("http://192.168.1.50:8765"), session).body<HostTokenResponse>().token
        client.webSocket("/ws") {
            send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), ClientMessage.Hello(PROTOCOL_VERSION, created.code, "evil", null, null, accountToken = other))))
            val welcome = assertIs<ServerMessage.Welcome>(ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText()))
            assertNull(welcome.accountId)
            assertEquals(false, welcome.state.participants.first { it.id == welcome.participantId }.isOwner)
        }
    }

    @Test
    fun `rooms you create start with the roles saved to your account`() = testApplication {
        setup(SignupMode.OPEN)
        val client = jsonClient()
        val session = client.postJson("/api/account/signup", SignupRequest("anna", "password123")).body<SessionResponse>().sessionToken
        val template = RoleTemplate(
            listOf(Role("boss", "Boss", "#ffaa00", Permission.entries), Role("crowd", "Crowd", "#00aaff", listOf(Permission.ADD_SONGS))),
            RoomSettings("crowd", "crowd"),
        )
        assertEquals(template, client.put("/api/account/role-template") {
            contentType(ContentType.Application.Json)
            bearerAuth(session)
            setBody(template)
        }.body<RoleTemplate>())

        val token = client.postJson("/api/account/host-token", HostTokenRequest(origin), session).body<HostTokenResponse>().token
        // What a host on another machine would do with the token:
        assertEquals(template, client.get("/api/auth/role-template") { bearerAuth(token) }.body<RoleTemplate>())

        val created = client.post("/api/rooms") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(CreateRoomRequest("Party"))
        }.body<CreateRoomResponse>()
        client.webSocket("/ws") {
            send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), ClientMessage.Hello(PROTOCOL_VERSION, created.code, "Guest", null, null))))
            val welcome = assertIs<ServerMessage.Welcome>(ProtocolJson.decodeFromString(ServerMessage.serializer(), (incoming.receive() as Frame.Text).readText()))
            assertEquals(template.roles, welcome.state.roles)
            assertEquals("crowd", welcome.state.participants.single().roleId)
        }
    }

    @Test
    fun `tokens from an untrusted key or with a bad signature are refused`() {
        val key = AccountTokens.newKeyPair()
        val now = System.currentTimeMillis() / 1000
        val token = AccountTokens.sign(AccountTokens.Claims("a", "anna", "Anna", origin, now, now + 60), key.private)
        val auth = TrustedAuthServers(isOwnOrigin = { it == origin })
        assertNull(auth.verify(token))

        auth.trust(AuthServerRef("https://a", "a"), key.public)
        assertEquals("anna@a", auth.verify(token)?.id)
        val tampered = token.split('.').let { (h, p, s) -> "$h.${p.dropLast(2)}xx.$s" }
        assertNull(auth.verify(tampered))
        assertNull(auth.verify(AccountTokens.sign(AccountTokens.Claims("a", "anna", "Anna", origin, now - 120, now - 60), key.private)))
    }
}
