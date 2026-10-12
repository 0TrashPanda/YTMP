package dev.trashpanda.ytmp.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Room passwords are kept hashed (rooms on a server, and accounts' default passwords there),
 * so nobody can read them back, only change them. PBKDF2 (in the JDK and on Android), slow
 * enough that guessing takes a while.
 */
object RoomPasswords {
    const val MAX_LENGTH = 64
    private const val ITERATIONS = 20_000
    private val random = SecureRandom()

    /** `pbkdf2:<iterations>:<salt>:<hash>` */
    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2:$ITERATIONS:${b64(salt)}:${b64(derive(password, salt, ITERATIONS))}"
    }

    fun verify(password: String, hash: String): Boolean {
        val parts = hash.split(':')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, iterations), expected)
    }

    /** Whether [password] can be a room password: 1 to [MAX_LENGTH] characters, not only spaces. */
    fun valid(password: String) = password.isNotBlank() && password.length <= MAX_LENGTH

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)
}
