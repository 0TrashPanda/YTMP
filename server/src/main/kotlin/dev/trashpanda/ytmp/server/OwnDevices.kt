package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.AccountTokens
import dev.trashpanda.ytmp.host.OwnHostProofs
import dev.trashpanda.ytmp.host.normalizeOrigin
import dev.trashpanda.ytmp.protocol.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.interfaces.ECPublicKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Each account's own phones and the keys they prove themselves with
 * (docs/implementation/auth.md#your-own-phones), kept as `devices` account data. A phone tells
 * its key from its own app ([remember]); `/connect` asks whether a host is one of them ([check]).
 */
class OwnDevices(private val accounts: Accounts, private val clock: () -> Long = System::currentTimeMillis) {
    @Serializable
    data class Device(val id: String, val name: String, val publicKey: String, val seen: Long)

    /** Proofs already used, until they'd be too old anyway. */
    private val used = ConcurrentHashMap<String, Long>()

    fun list(accountId: String): List<Device> =
        accounts.data(accountId, KEY)
            ?.let { runCatching { ProtocolJson.decodeFromString(ListSerializer(Device.serializer()), it) }.getOrNull() }
            .orEmpty()
            .filter { clock() - it.seen < FORGET_MS }

    /** Keeps [publicKey] as [accountId]'s phone [id] (a new key replaces the old one). False when the key isn't a P-256 public key. */
    @Synchronized
    fun remember(accountId: String, id: String, name: String, publicKey: String): Boolean {
        val key = runCatching { AccountTokens.decodePublicKey(publicKey) }.getOrNull() as? ECPublicKey ?: return false
        if (key.params.curve.field.fieldSize != 256 || id.length !in 1..64 || !id.all { it.isLetterOrDigit() || it == '-' }) return false
        val device = Device(id, name.trim().take(64).ifEmpty { "Phone" }, publicKey, clock())
        val devices = (list(accountId).filter { it.id != id } + device).sortedByDescending { it.seen }.take(MAX_DEVICES)
        accounts.setData(accountId, KEY, ProtocolJson.encodeToString(ListSerializer(Device.serializer()), devices))
        return true
    }

    /**
     * The phone of [accountId] whose [proof] says [origin] is its own address, or null: an
     * unknown phone (or another account's), another address, an old proof, or one used before.
     */
    fun check(accountId: String, origin: String, proof: String): Device? {
        val wanted = normalizeOrigin(origin) ?: return null
        val deviceId = OwnHostProofs.deviceOf(proof) ?: return null
        val device = list(accountId).firstOrNull { it.id == deviceId } ?: return null
        val key = runCatching { AccountTokens.decodePublicKey(device.publicKey) }.getOrNull() ?: return null
        val now = clock()
        val claims = OwnHostProofs.verify(proof, key, now / 1000) ?: return null
        if (claims.origin != wanted || claims.dev != device.id) return null
        used.values.removeIf { it < now }
        if (used.putIfAbsent(claims.jti, now + 2 * OwnHostProofs.MAX_AGE_SECONDS * 1000) != null) return null
        return device
    }

    private companion object {
        const val KEY = "devices"
        const val MAX_DEVICES = 20

        /** A phone that hasn't checked in for this long no longer counts (it does once a day while used). */
        const val FORGET_MS = 90 * 24 * 3600_000L
    }
}
