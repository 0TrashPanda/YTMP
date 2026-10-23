package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomCodeStyle
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.HostOptions
import dev.trashpanda.ytmp.host.HostSources
import dev.trashpanda.ytmp.host.TrustedAuthServers
import dev.trashpanda.ytmp.host.ytmpModule
import dev.trashpanda.ytmp.protocol.AccountInfo
import dev.trashpanda.ytmp.protocol.AccountListResponse
import dev.trashpanda.ytmp.protocol.AdminRoomList
import dev.trashpanda.ytmp.protocol.AdminSettings
import dev.trashpanda.ytmp.protocol.AuditLogResponse
import dev.trashpanda.ytmp.protocol.BackupInfo
import dev.trashpanda.ytmp.protocol.AuthServerRef
import dev.trashpanda.ytmp.protocol.DisableAccountRequest
import dev.trashpanda.ytmp.protocol.CreateAccountRequest
import dev.trashpanda.ytmp.protocol.CreateRoomRequest
import dev.trashpanda.ytmp.protocol.CreateRoomResponse
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.HostTokenRequest
import dev.trashpanda.ytmp.protocol.HostTokenResponse
import dev.trashpanda.ytmp.protocol.InviteListResponse
import dev.trashpanda.ytmp.protocol.InviteResponse
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.ServerSettingOverrides
import dev.trashpanda.ytmp.protocol.SessionResponse
import dev.trashpanda.ytmp.protocol.SignupMode
import dev.trashpanda.ytmp.protocol.SignupRequest
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.UpdateAccountRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminApiTest {
    private val origin = "http://localhost"

    private class Server(val service: AccountService, val settings: ServerSettings, val rooms: RoomManager, val db: Database)

    /** A server as Main.kt makes it, with ytmp.toml saying [config]. */
    private fun ApplicationTestBuilder.setup(config: Config = Config(), dbFile: String = Files.createTempDirectory("ytmp").resolve("ytmp.db").toString()): Server {
        val db = Database.open(DatabaseConfig(path = dbFile))
        val accounts = Accounts(db)
        val key = accounts.signingKey()
        val settings = ServerSettings(accounts, config)
        val service = AccountService(accounts, "test.example", key, settings.values.signup, History(db), settings.values.trackingDefault, graph = SongGraph(db), isOwnOrigin = { it == origin })
        val auth = TrustedAuthServers(isOwnOrigin = { it == origin }).apply { trust(AuthServerRef(null, "test.example"), key.public) }
        val rooms = RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob()), settings.values.codeStyle(), settings.values.roomCodeLength)
        settings.apply = { _, new ->
            service.signup = new.signup
            service.trackingDefault = new.trackingDefault
            rooms.codeStyle = new.codeStyle()
            rooms.codeLength = new.roomCodeLength
        }
        application {
            ytmpModule(
                rooms, HostSources({ _, _ -> SearchPage(emptyList()) }),
                HostOptions(
                    kind = HostKind.SERVER, auth = auth, accountsOnly = { !settings.values.guests },
                    roomCreateDenied = { account ->
                        when (settings.values.roomCreate) {
                            "accounts" -> if (account == null) "Log in" else null
                            "admins" -> if (account?.id?.substringBefore('@')?.let(service.accounts::find)?.isAdmin == true) null else "Only admins"
                            else -> null
                        }
                    },
                ),
                extraApi = { service.routes(this); AdminApi(service, rooms, settings, audit = AuditLog(db), backups = Backups(db)).routes(this) },
            )
        }
        return Server(service, settings, rooms, db)
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json(ProtocolJson) } }

    private suspend fun HttpClient.send(method: String, path: String, session: String?, body: Any? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            session?.let(::bearerAuth)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(path, build)
            "PUT" -> put(path, build)
            "DELETE" -> delete(path, build)
            else -> post(path, build)
        }
    }

    private suspend fun HttpClient.signup(name: String, invite: String? = null): SessionResponse =
        send("POST", "/api/account/signup", null, SignupRequest(name, "password123", invite = invite)).body()

    /** The first account (the admin) and a second one, made by the admin. */
    private suspend fun HttpClient.adminAndAnna(): Pair<String, String> {
        val admin = signup("jonah").sessionToken
        send("POST", "/api/admin/accounts", admin, CreateAccountRequest("anna", "password123"))
        val anna = send("POST", "/api/account/login", null, dev.trashpanda.ytmp.protocol.LoginRequest("anna", "password123")).body<SessionResponse>().sessionToken
        return admin to anna
    }

    private suspend fun HttpClient.accounts(admin: String) = send("GET", "/api/admin/accounts", admin).body<AccountListResponse>().accounts

    @Test
    fun `a disabled account is logged out and can't log in until enabled, and the log says who did it`() = testApplication {
        setup()
        val client = jsonClient()
        val (admin, anna) = client.adminAndAnna()
        assertEquals(HttpStatusCode.OK, client.send("GET", "/api/account", anna).status)

        assertEquals(HttpStatusCode.OK, client.send("POST", "/api/admin/accounts/anna/disable", admin, DisableAccountRequest(minutes = 60)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/account", anna).status)
        val login = client.send("POST", "/api/account/login", null, dev.trashpanda.ytmp.protocol.LoginRequest("anna", "password123"))
        assertEquals(HttpStatusCode.Forbidden, login.status)
        assertTrue(login.body<String>().contains("disabled"))
        assertTrue(client.accounts(admin).single { it.username == "anna" }.disabledUntil > 0)

        // Not yourself, and not forever by accident: the admin stays.
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/api/admin/accounts/jonah/disable", admin, DisableAccountRequest()).status)

        assertEquals(HttpStatusCode.OK, client.send("POST", "/api/admin/accounts/anna/enable", admin).status)
        val again = client.send("POST", "/api/account/login", null, dev.trashpanda.ytmp.protocol.LoginRequest("anna", "password123"))
        assertEquals(HttpStatusCode.OK, again.status)
        val annaAgain = again.body<SessionResponse>().sessionToken

        val log = client.send("GET", "/api/admin/audit", admin).body<AuditLogResponse>().entries
        assertEquals(listOf("Enabled account", "Disabled account", "Created account"), log.map { it.action }.take(3))
        assertEquals("jonah", log.first().actor)
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/admin/audit", annaAgain).status)
    }

    @Test
    fun `a backup can be downloaded and uploaded to replace the database at the next start`() = testApplication {
        val dir = Files.createTempDirectory("ytmp-backup")
        val dbFile = dir.resolve("ytmp.db").toString()
        val server = setup(dbFile = dbFile)
        val client = jsonClient()
        val (admin, anna) = client.adminAndAnna()
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/admin/backup", anna).status)
        val backup = client.send("GET", "/api/admin/backup", admin)
        assertEquals(HttpStatusCode.OK, backup.status)
        val bytes = backup.body<ByteArray>()
        assertTrue(bytes.size > 4096 && String(bytes, 0, 15) == "SQLite format 3")

        // Not a backup: refused, nothing waits.
        val bad = client.post("/api/admin/restore") { bearerAuth(admin); setBody("not a database".toByteArray()) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertFalse(java.io.File("$dbFile.restore").exists())

        // A real one: accepted, kept for the next start, which swaps it in and keeps the old database aside.
        client.send("POST", "/api/admin/accounts", admin, CreateAccountRequest("ben", "password123"))
        val ok = client.post("/api/admin/restore") { bearerAuth(admin); setBody(bytes) }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(2, ok.body<BackupInfo>().accounts)
        assertTrue(java.io.File("$dbFile.restore").isFile)
        // The server stops (closing its database) and starts again.
        server.db.close()
        val reopened = Database.open(DatabaseConfig(path = dbFile))
        assertEquals(2, Accounts(reopened).count())
        assertFalse(java.io.File("$dbFile.restore").exists())
        assertTrue(java.io.File("$dbFile.before-restore").isFile)
        assertEquals(3, Accounts(Database.open(DatabaseConfig(path = "$dbFile.before-restore"))).count())
    }

    @Test
    fun `an admin can limit who creates rooms`() = testApplication {
        val server = setup()
        val client = jsonClient()
        val (admin, anna) = client.adminAndAnna()
        suspend fun hostToken(session: String) = client.send("POST", "/api/account/host-token", session, HostTokenRequest(origin)).body<HostTokenResponse>().token
        val adminToken = hostToken(admin)
        val annaToken = hostToken(anna)
        suspend fun create(token: String?) = client.send("POST", "/api/rooms", token, CreateRoomRequest("Party")).status
        assertEquals(HttpStatusCode.OK, create(annaToken))
        server.settings.change(ServerSettingOverrides(roomCreate = "admins"))
        assertEquals(HttpStatusCode.OK, create(adminToken))
        assertEquals(HttpStatusCode.Forbidden, create(annaToken))
        assertEquals(HttpStatusCode.Forbidden, create(null))
        server.settings.change(ServerSettingOverrides(roomCreate = "accounts"))
        assertEquals(HttpStatusCode.OK, create(annaToken))
        assertEquals(HttpStatusCode.Forbidden, create(null))
    }

    @Test
    fun `an admin makes someone else admin, but the last admin can't stop being one`() = testApplication {
        setup()
        val client = jsonClient()
        val (admin, anna) = client.adminAndAnna()

        assertEquals(HttpStatusCode.Forbidden, client.send("PUT", "/api/admin/accounts/jonah", anna, UpdateAccountRequest(isAdmin = false)).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("PUT", "/api/admin/accounts/jonah", admin, UpdateAccountRequest(isAdmin = false)).status)

        val made = client.send("PUT", "/api/admin/accounts/anna", admin, UpdateAccountRequest(isAdmin = true, displayName = "Anna B")).body<AccountInfo>()
        assertTrue(made.isAdmin)
        assertEquals("Anna B", made.displayName)
        // Anna can open the admin page now, and take it away from Jonah.
        assertEquals(HttpStatusCode.OK, client.send("PUT", "/api/admin/accounts/jonah", anna, UpdateAccountRequest(isAdmin = false)).status)
        assertEquals(listOf("anna"), client.accounts(anna).filter { it.isAdmin }.map { it.username })
        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/admin/accounts", admin).status)
    }

    @Test
    fun `the list shows who's logged in, and logging someone out ends their logins`() = testApplication {
        setup()
        val client = jsonClient()
        val (admin, anna) = client.adminAndAnna()

        val listed = client.accounts(admin).single { it.username == "anna" }
        assertEquals(1, listed.logins)
        assertTrue(listed.lastSeen != null && listed.createdAt > 0)

        assertEquals(HttpStatusCode.NoContent, client.send("POST", "/api/admin/accounts/anna/logout", admin).status)
        assertEquals(HttpStatusCode.Unauthorized, client.send("GET", "/api/account", anna).status)
        assertEquals(0, client.accounts(admin).single { it.username == "anna" }.logins)
    }

    @Test
    fun `deleting an account removes its history and rooms, and its name is free again`() = testApplication {
        val server = setup()
        val client = jsonClient()
        val (admin, anna) = client.adminAndAnna()
        server.service.recordPlay("anna", PlayReport(Song("ytm:1", "Song", emptyList(), null, 200_000, emptyList()), 1, 200_000, false, "SOLO", "Mine", true, "Anna", emptyList(), false))
        val token = client.send("POST", "/api/account/host-token", anna, HostTokenRequest(origin)).body<HostTokenResponse>().token
        val room = client.send("POST", "/api/rooms", token, CreateRoomRequest("Anna's")).body<CreateRoomResponse>()
        assertEquals(listOf(room.code), client.send("GET", "/api/admin/rooms", admin).body<AdminRoomList>().rooms.map { it.code })

        // Not the last admin.
        assertEquals(HttpStatusCode.BadRequest, client.send("DELETE", "/api/admin/accounts/jonah", admin).status)
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/api/admin/accounts/anna", admin).status)

        assertEquals(listOf("jonah"), client.accounts(admin).map { it.username })
        assertNull(server.rooms[room.code])
        val plays = server.db.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM plays").use { it.next(); it.getInt(1) } } }
        assertEquals(0, plays)
        assertEquals(HttpStatusCode.OK, client.send("POST", "/api/admin/accounts", admin, CreateAccountRequest("anna", "password123", isAdmin = true)).status)
        assertTrue(client.accounts(admin).single { it.username == "anna" }.isAdmin)
    }

    @Test
    fun `open invite links are listed and can be revoked`() = testApplication {
        setup()
        val client = jsonClient()
        val admin = client.signup("jonah").sessionToken
        val code = client.send("POST", "/api/admin/invites", admin).body<InviteResponse>().code
        val invite = client.send("GET", "/api/admin/invites", admin).body<InviteListResponse>().invites.single()
        assertEquals("jonah", invite.createdBy)

        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/api/admin/invites/${invite.id}", admin).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/api/account/signup", null, SignupRequest("anna", "password123", invite = code)).status)
        assertTrue(client.send("GET", "/api/admin/invites", admin).body<InviteListResponse>().invites.isEmpty())
    }

    @Test
    fun `settings changed on the admin page win over ytmp toml, work right away and are kept`() = testApplication {
        val dbFile = Files.createTempDirectory("ytmp").resolve("ytmp.db").toString()
        val config = Config(rooms = RoomsConfig(codeStyle = "digits", codeLength = 5))
        val server = setup(config, dbFile)
        val client = jsonClient()
        val admin = client.signup("jonah").sessionToken

        val start = client.send("GET", "/api/admin/settings", admin).body<AdminSettings>()
        assertEquals(SignupMode.INVITE, start.values.signup)
        assertEquals(5, start.file.roomCodeLength)
        assertNull(start.overrides.roomCodeLength)

        // Sign-up open and guests off, right away.
        val changed = client.send("PUT", "/api/admin/settings", admin, ServerSettingOverrides(signup = SignupMode.OPEN, guests = false, roomCodeLength = 6, roomCodeStyle = "LETTERS")).body<AdminSettings>()
        assertEquals(SignupMode.OPEN, changed.values.signup)
        assertEquals("letters", changed.overrides.roomCodeStyle)
        assertEquals(HttpStatusCode.OK, client.send("POST", "/api/account/signup", null, SignupRequest("anna", "password123")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/search?q=x").status)
        assertEquals(6, server.rooms.codeLength)
        assertEquals(RoomCodeStyle.LETTERS, server.rooms.codeStyle)

        // What can't be is refused, and nothing changes.
        assertEquals(HttpStatusCode.BadRequest, client.send("PUT", "/api/admin/settings", admin, changed.overrides.copy(roomCodeLength = 20)).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("PUT", "/api/admin/settings", admin, changed.overrides.copy(audioProxy = "sometimes")).status)
        assertEquals(HttpStatusCode.BadRequest, client.send("PUT", "/api/admin/settings", admin, changed.overrides.copy(trusted = listOf("ytmp.example.com"))).status)
        assertEquals(6, client.send("GET", "/api/admin/settings", admin).body<AdminSettings>().values.roomCodeLength)

        // After a restart.
        val again = ServerSettings(Accounts(Database.open(DatabaseConfig(path = dbFile))), config)
        assertEquals(SignupMode.OPEN, again.values.signup)
        assertFalse(again.values.guests)

        // Back to ytmp.toml's.
        val reset = client.send("PUT", "/api/admin/settings", admin, changed.overrides.copy(roomCodeLength = null)).body<AdminSettings>()
        assertEquals(5, reset.values.roomCodeLength)
        assertEquals(5, server.rooms.codeLength)
    }

    @Test
    fun `only an admin sees and closes rooms`() = testApplication {
        val server = setup()
        val client = jsonClient()
        val (admin, anna) = client.adminAndAnna()
        val room = client.send("POST", "/api/rooms", null, CreateRoomRequest("Party")).body<CreateRoomResponse>()

        assertEquals(HttpStatusCode.Forbidden, client.send("GET", "/api/admin/rooms", anna).status)
        val listed = client.send("GET", "/api/admin/rooms", admin).body<AdminRoomList>().rooms.single()
        assertEquals("Party", listed.name)
        assertNull(listed.owner)

        assertEquals(HttpStatusCode.Forbidden, client.send("DELETE", "/api/admin/rooms/${room.code}", anna).status)
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", "/api/admin/rooms/${room.code}", admin).status)
        assertNull(server.rooms[room.code])
        assertEquals(HttpStatusCode.NotFound, client.send("DELETE", "/api/admin/rooms/${room.code}", admin).status)
    }
}
