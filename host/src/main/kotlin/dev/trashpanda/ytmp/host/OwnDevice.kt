package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.OwnDeviceRequest
import dev.trashpanda.ytmp.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * A phone's signed word that a page address ([Claims.origin]) is one of its own, so its owner's
 * server can tell that a host is one of the account's own phones without taking the host's word
 * for it (docs/implementation/auth.md#your-own-phones). ES256 like [AccountTokens], with the
 * phone's own key ([OwnDevice]), which the server knows from the phone's own app.
 */
object OwnHostProofs {
    @Serializable
    data class Claims(
        /** The phone ([OwnDevice]'s id; the server has its key). */
        val dev: String,
        /** The page address it's for, e.g. `http://192.168.1.23:8765`: one of the phone's own. */
        val origin: String,
        /** Seconds since the epoch. */
        val iat: Long,
        /** Random: each proof works once. */
        val jti: String,
    )

    private const val HEADER = """{"alg":"ES256","typ":"ytmp-own-host"}"""
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** How old (or how far ahead, for a phone's clock) a proof may be. */
    const val MAX_AGE_SECONDS = 300L

    fun sign(claims: Claims, key: PrivateKey): String {
        val signingInput = encoder.encodeToString(HEADER.toByteArray()) + "." +
            encoder.encodeToString(ProtocolJson.encodeToString(Claims.serializer(), claims).toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(signingInput.toByteArray())
            sign()
        }
        return signingInput + "." + encoder.encodeToString(AccountTokens.derToRaw(der))
    }

    /** The phone [proof] says it is from, without checking anything (to find its key). */
    fun deviceOf(proof: String): String? = runCatching {
        val parts = proof.split('.')
        if (parts.size != 3) return null
        ProtocolJson.decodeFromString(Claims.serializer(), String(decoder.decode(parts[1]))).dev
    }.getOrNull()

    /** The claims of [proof] if [key] signed it and it's recent. The caller still checks [Claims.origin] and [Claims.jti]. */
    fun verify(proof: String, key: PublicKey, nowSeconds: Long): Claims? = runCatching {
        val parts = proof.split('.')
        if (parts.size != 3) return null
        if (ProtocolJson.parseToJsonElement(String(decoder.decode(parts[0]))) != ProtocolJson.parseToJsonElement(HEADER)) return null
        val claims = ProtocolJson.decodeFromString(Claims.serializer(), String(decoder.decode(parts[1])))
        val valid = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray())
            verify(AccountTokens.rawToDer(decoder.decode(parts[2])))
        }
        claims.takeIf { valid && abs(nowSeconds - it.iat) <= MAX_AGE_SECONDS }
    }.getOrNull()
}

/**
 * A phone's key for proving it's one of its owner's own phones ([OwnHostProofs]). It's made for
 * one account: a new owner gets a new key, so the last owner's server no longer counts it as
 * theirs. The owner's server learns the public key from the phone's own app ([usedBy], with a
 * host token for the app's own pages, which only reach the account's own phones), and the phone
 * signs only its own addresses ([proof]), so a host elsewhere can't pass on its proof.
 */
class OwnDevice(
    private val store: Store,
    /** The phone's owner (`user@issuer`), or null. */
    private val owner: () -> String?,
    /** The address of [account]'s server, when this phone uses it for accounts. */
    private val serverOf: (account: String) -> String?,
    /** This phone's own page addresses (its IP addresses, loopback), like `http://192.168.1.23:8765`. */
    private val ownOrigins: () -> Set<String>,
    /** What the phone is called, e.g. `Pixel 7`. */
    private val name: () -> String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Sends the key to the server: PUT [url] with [token]; answers the HTTP status. */
    private val send: (url: String, token: String, body: String) -> Int = ::putJson,
) {
    /** Where the key is kept (the phone's preferences). */
    interface Store {
        fun get(key: String): String?

        fun put(values: Map<String, String?>)
    }

    private class Key(val id: String, val account: String, val pair: KeyPair)

    @Volatile
    private var key: Key? = load()

    private val sending = AtomicBoolean(false)

    /** A proof that [origin] is this phone, or null when it's not one of its own addresses or it has no owner. */
    fun proof(origin: String): String? {
        val normalized = normalizeOrigin(origin) ?: return null
        if (normalized !in ownOrigins()) return null
        val key = key ?: return null
        if (key.account != owner()) return null
        val claims = OwnHostProofs.Claims(key.id, normalized, clock() / 1000, randomId())
        return OwnHostProofs.sign(claims, key.pair.private)
    }

    /**
     * The phone's own page proved [account] with [token]: tells that account's server this
     * phone's key, when it hasn't for a while (or the key is new).
     */
    fun usedBy(account: String, token: String) {
        val server = serverOf(account) ?: return
        val key = keyFor(account)
        val sentAt = store.get(SENT_AT)?.toLongOrNull() ?: 0
        if (store.get(SENT_ID) == key.id && clock() - sentAt < RESEND_MS) return
        if (!sending.compareAndSet(false, true)) return
        val body = ProtocolJson.encodeToString(OwnDeviceRequest.serializer(), OwnDeviceRequest(name(), AccountTokens.encodeKey(key.pair.public)))
        scope.launch(Dispatchers.IO) {
            try {
                val status = send("${server.trimEnd('/')}/api/auth/devices/${key.id}", token, body)
                if (status in 200..299) {
                    store.put(mapOf(SENT_ID to key.id, SENT_AT to clock().toString()))
                    log.info("{} knows this phone is theirs", account)
                } else {
                    log.info("{} didn't take this phone's key ({})", server, status)
                }
            } catch (e: Exception) {
                log.info("Couldn't tell {} this phone's key: {}", server, e.message)
            } finally {
                sending.set(false)
            }
        }
    }

    /** The key for [account]: the kept one, or a new one when it was made for someone else. */
    @Synchronized
    private fun keyFor(account: String): Key {
        key?.takeIf { it.account == account }?.let { return it }
        val made = Key(UUID.randomUUID().toString(), account, AccountTokens.newKeyPair())
        store.put(
            mapOf(
                ID to made.id, ACCOUNT to account,
                PUBLIC to AccountTokens.encodeKey(made.pair.public), PRIVATE to AccountTokens.encodeKey(made.pair.private),
                SENT_ID to null, SENT_AT to null,
            ),
        )
        key = made
        return made
    }

    private fun load(): Key? = runCatching {
        val id = store.get(ID) ?: return null
        val account = store.get(ACCOUNT) ?: return null
        val pair = KeyPair(AccountTokens.decodePublicKey(store.get(PUBLIC)!!), AccountTokens.decodePrivateKey(store.get(PRIVATE)!!))
        Key(id, account, pair)
    }.getOrNull()

    private companion object {
        val log = LoggerFactory.getLogger(OwnDevice::class.java)
        const val ID = "id"
        const val ACCOUNT = "account"
        const val PUBLIC = "public"
        const val PRIVATE = "private"
        const val SENT_ID = "sentId"
        const val SENT_AT = "sentAt"

        /** The server forgets phones it hasn't heard of for a long time; this keeps it fresh. */
        const val RESEND_MS = 24 * 3600_000L

        private val random = SecureRandom()

        fun randomId(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}

private fun putJson(url: String, token: String, body: String): Int {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "PUT"
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.doOutput = true
    connection.setRequestProperty("Authorization", "Bearer $token")
    connection.setRequestProperty("Content-Type", "application/json")
    return try {
        connection.outputStream.use { it.write(body.toByteArray()) }
        connection.responseCode
    } finally {
        connection.disconnect()
    }
}
