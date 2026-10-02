package dev.trashpanda.ytmp.cast

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Talks to a real Cast device, read-only (asks for its status; plays nothing).
 * Runs only with `CAST_HOST=<ip> ./gradlew :cast:test`.
 */
class RealDeviceTest {
    @Test
    fun `reads the status of a real device`() {
        val host = System.getenv("CAST_HOST")?.takeIf { it.isNotEmpty() } ?: return
        runBlocking {
            val channel = CastChannel.open(host)
            try {
                val status = CastMediaPlayer(channel).receiverStatus()
                println("Cast device $host: volume=${status.volume} muted=${status.muted} apps=${status.runningAppIds} via ${channel.localAddress}")
                assertTrue(status.volume != null)
            } finally {
                channel.close()
            }
        }
    }
}
