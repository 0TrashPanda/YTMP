package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ProtocolJson
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Account tokens: JWTs signed with ES256 (ECDSA P-256), so a host can check them with the
 * auth server's public key alone. Plain JDK crypto, which Android has too.
 * See docs/implementation/auth.md.
 */
object AccountTokens {
    @Serializable
    data class Claims(
        /** The auth server ([dev.trashpanda.ytmp.protocol.AuthServerInfo.issuer]). */
        val iss: String,
        /** Username. */
        val sub: String,
        /** Display name. */
        val name: String,
        /** Origin of the host page the token is for, e.g. `http://192.168.1.23:8765`. */
        val aud: String,
        /** Seconds since the epoch. */
        val iat: Long,
        val exp: Long,
    ) {
        val accountId get() = "$sub@$iss"
    }

    private const val HEADER = """{"alg":"ES256","typ":"JWT"}"""
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun encodeKey(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun encodeKey(key: PrivateKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun decodePublicKey(base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun decodePrivateKey(base64: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))

    fun sign(claims: Claims, key: PrivateKey): String {
        val payload = ProtocolJson.encodeToString(Claims.serializer(), claims)
        val signingInput = encode(HEADER.toByteArray()) + "." + encode(payload.toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(signingInput.toByteArray())
            sign()
        }
        return signingInput + "." + encode(derToRaw(der))
    }

    /**
     * The claims of [token] if it is signed by its issuer's key ([keyFor]) and not expired.
     * The caller still has to check [Claims.aud].
     */
    fun verify(token: String, keyFor: (issuer: String) -> PublicKey?, nowSeconds: Long): Claims? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return runCatching {
            val header = String(decoder.decode(parts[0]))
            if (ProtocolJson.parseToJsonElement(header).toString() != ProtocolJson.parseToJsonElement(HEADER).toString()) return null
            val claims = ProtocolJson.decodeFromString(Claims.serializer(), String(decoder.decode(parts[1])))
            val key = keyFor(claims.iss) ?: return null
            val valid = Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray())
                verify(rawToDer(decoder.decode(parts[2])))
            }
            claims.takeIf { valid && it.exp > nowSeconds && it.iat <= nowSeconds + CLOCK_SKEW_SECONDS }
        }.getOrNull()
    }

    private const val CLOCK_SKEW_SECONDS = 300

    private fun encode(bytes: ByteArray) = encoder.encodeToString(bytes)

    /** JWS wants the signature as r‖s (32 bytes each); Java produces and expects DER. */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var i = 2 + if (der[1].toInt() and 0x80 != 0) der[1].toInt() and 0x7f else 0
        fun next(): ByteArray {
            check(der[i].toInt() == 0x02)
            val length = der[i + 1].toInt()
            val value = der.copyOfRange(i + 2, i + 2 + length)
            i += 2 + length
            return value
        }
        return fixed(next()) + fixed(next())
    }

    internal fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64)
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun fixed(value: ByteArray): ByteArray {
        val trimmed = value.dropWhile { it.toInt() == 0 }.toByteArray()
        require(trimmed.size <= 32)
        return ByteArray(32 - trimmed.size) + trimmed
    }
}
