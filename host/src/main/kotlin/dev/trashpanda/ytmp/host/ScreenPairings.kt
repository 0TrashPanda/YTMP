package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ScreenLinked
import dev.trashpanda.ytmp.protocol.ScreenPairing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Base64

/**
 * Linking a TV as a party screen, like YouTube's "Link with TV code" (docs/features/ui.md#party-screen):
 * the TV gets a short code and waits; someone in a room enters the code on their phone, and
 * the TV is let into that room. Codes live [LIFETIME_MS], only in memory.
 */
class ScreenPairings(private val clock: () -> Long = System::currentTimeMillis) {
    private class Pending(val pairing: ScreenPairing, val linked: CompletableDeferred<ScreenLinked> = CompletableDeferred())

    private val pending = LinkedHashMap<String, Pending>() // by secret
    private val random = SecureRandom()

    /** A new code for a TV to show. */
    @Synchronized
    fun start(): ScreenPairing {
        expire()
        // Many TVs asking at once (or someone trying to use up the codes): the oldest go.
        while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
        var code: String
        do {
            code = (0 until CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
        } while (pending.values.any { it.pairing.code == code })
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
        val pairing = ScreenPairing(code, secret, clock() + LIFETIME_MS)
        pending[pairing.secret] = Pending(pairing)
        return pairing
    }

    /**
     * The TV waits for someone to link it: what it joins, or null after [timeoutMs] (it asks
     * again). Throws [NoSuchElementException] when the code expired or never existed.
     */
    suspend fun await(secret: String, timeoutMs: Long): ScreenLinked? {
        val waiting = synchronized(this) {
            expire()
            pending[secret] ?: throw NoSuchElementException("This code expired")
        }
        val linked = withTimeoutOrNull(timeoutMs) { waiting.linked.await() }
        if (linked != null) synchronized(this) { pending.remove(secret) }
        return linked
    }

    /**
     * Someone entered [code]: [join] adds the screen to their room and returns what the TV
     * joins with (null: they can't). False when the code isn't (or no longer) valid.
     */
    suspend fun link(code: String, join: suspend () -> ScreenLinked?): Boolean {
        val digits = code.filter(Char::isDigit)
        val waiting = synchronized(this) {
            expire()
            pending.values.firstOrNull { it.pairing.code == digits && !it.linked.isCompleted }
        } ?: return false
        val linked = join() ?: return false
        return waiting.linked.complete(linked)
    }

    private fun expire() {
        val now = clock()
        pending.values.removeIf { it.pairing.expiresAt < now }
    }

    companion object {
        const val CODE_LENGTH = 6
        const val LIFETIME_MS = 10 * 60_000L
        const val MAX_PENDING = 200
    }
}
