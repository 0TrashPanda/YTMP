package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.OwnDeviceRequest
import dev.trashpanda.ytmp.protocol.OwnHostProof
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.core.RoomManager
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OwnDeviceTest {
    private class MemoryStore : OwnDevice.Store {
        val values = mutableMapOf<String, String>()

        override fun get(key: String) = synchronized(values) { values[key] }

        override fun put(values: Map<String, String?>) = synchronized(this.values) {
            values.forEach { (k, v) -> if (v == null) this.values.remove(k) else this.values[k] = v }
        }
    }

    private val phone = "http://192.168.1.23:8765"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var owner: String? = "anna@home"
    private var now = 1_800_000_000_000L
    private val sent = mutableListOf<Pair<String, OwnDeviceRequest>>()

    private fun device(store: OwnDevice.Store = MemoryStore()) = OwnDevice(
        store,
        owner = { owner },
        serverOf = { if (it.endsWith("@home")) "https://ytmp.home" else null },
        ownOrigins = { setOf(phone, "http://127.0.0.1:8765") },
        name = { "Pixel 7" },
        scope = scope,
        clock = { now },
        send = { url, _, body ->
            synchronized(sent) { sent += url to ProtocolJson.decodeFromString(OwnDeviceRequest.serializer(), body) }
            204
        },
    )

    private fun waitForSent(count: Int) = runBlocking {
        withTimeout(5_000) { while (synchronized(sent) { sent.size } < count) delay(10) }
        // And done with it (the next one may go).
        delay(100)
    }

    @Test
    fun `it signs only its own addresses, for its owner, with the key it gave the owner's server`() {
        val device = device()
        // No key yet: the owner's app hasn't been used.
        assertNull(device.proof(phone))
        device.usedBy("anna@home", "token")
        waitForSent(1)
        val (url, request) = sent.single()
        assertEquals(true, url.startsWith("https://ytmp.home/api/auth/devices/"))
        val key = AccountTokens.decodePublicKey(request.publicKey)

        val proof = assertNotNull(device.proof("$phone/"))
        val claims = assertNotNull(OwnHostProofs.verify(proof, key, now / 1000))
        assertEquals(phone, claims.origin)
        assertEquals(url.substringAfterLast('/'), claims.dev)
        assertEquals(OwnHostProofs.deviceOf(proof), claims.dev)
        // Each one is different.
        assertNotEquals(proof, device.proof(phone))
        // Not for another host's address (one that asks the phone for a proof for itself).
        assertNull(device.proof("http://192.168.1.66:8765"))
        assertNull(device.proof("not an address"))
        // Too old, tampered.
        assertNull(OwnHostProofs.verify(proof, key, now / 1000 + 600))
        assertNull(OwnHostProofs.verify(proof.dropLast(4) + "AAAA", key, now / 1000))
        // Without an owner (unlinked), or with another one, no proofs with the old key.
        owner = null
        assertNull(device.proof(phone))
        owner = "sam@home"
        assertNull(device.proof(phone))
    }

    @Test
    fun `it tells the server once a day, and a new owner gets a new key`() {
        val store = MemoryStore()
        val device = device(store)
        device.usedBy("anna@home", "token")
        waitForSent(1)
        device.usedBy("anna@home", "token")
        now += 3600_000
        device.usedBy("anna@home", "token")
        Thread.sleep(100)
        assertEquals(1, sent.size)
        now += 24 * 3600_000
        device.usedBy("anna@home", "token")
        waitForSent(2)
        assertEquals(sent[0], sent[1])

        // Kept across restarts.
        val again = device(store)
        assertNotNull(again.proof(phone))

        // Another account on the phone's app: a new key (anna's server no longer counts it).
        owner = "sam@home"
        again.usedBy("sam@home", "token")
        waitForSent(3)
        assertNotEquals(sent[0].first, sent[2].first)
        assertNotEquals(sent[0].second.publicKey, sent[2].second.publicKey)
        assertNotNull(again.proof(phone))
        // An account of a server the phone doesn't use: nothing to tell.
        again.usedBy("ben@elsewhere", "token")
        Thread.sleep(100)
        assertEquals(3, sent.size)
    }

    @Test
    fun `the host answers proofs for its own addresses only`() = testApplication {
        val device = device()
        device.usedBy("anna@home", "token")
        waitForSent(1)
        application {
            ytmpModule(
                RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob())),
                HostSources({ _, _ -> SearchPage(emptyList()) }),
                HostOptions(kind = HostKind.PHONE, ownDevice = device),
            )
        }
        val answer = client.get("/api/host/own-proof?origin=${java.net.URLEncoder.encode(phone, "UTF-8")}")
        assertEquals(HttpStatusCode.OK, answer.status)
        val proof = ProtocolJson.decodeFromString(OwnHostProof.serializer(), answer.bodyAsText()).proof
        assertEquals(phone, OwnHostProofs.verify(proof, AccountTokens.decodePublicKey(sent.single().second.publicKey), now / 1000)?.origin)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/host/own-proof?origin=http://192.168.1.66:8765").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/host/own-proof").status)
    }

    @Test
    fun `a host that isn't a phone has no proofs`() = testApplication {
        application {
            ytmpModule(RoomManager({ id -> "https://stream/$id" }, CoroutineScope(SupervisorJob())), HostSources({ _, _ -> SearchPage(emptyList()) }), HostOptions(kind = HostKind.SERVER))
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/host/own-proof?origin=http://localhost").status)
    }
}
