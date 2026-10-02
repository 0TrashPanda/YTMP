package dev.trashpanda.ytmp.cast

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Talks to a real Cast device: asks for its status (and optionally sets the volume). Plays nothing.
 * Runs only with `CAST_HOST=<ip> ./gradlew :cast:test`; `CAST_SET_VOLUME=0.2` also sets the volume.
 */
class RealDeviceTest {
    @Test
    fun `reads the status of a real device`() {
        val host = System.getenv("CAST_HOST")?.takeIf { it.isNotEmpty() } ?: return
        runBlocking {
            val channel = CastChannel.open(host)
            try {
                System.getenv("CAST_SET_VOLUME")?.toDoubleOrNull()?.let { CastMediaPlayer(channel).setVolume(it) }
                val status = CastMediaPlayer(channel).receiverStatus()
                println("Cast device $host: volume=${status.volume} muted=${status.muted} apps=${status.runningAppIds} via ${channel.localAddress}")
                assertTrue(status.volume != null)
            } finally {
                channel.close()
            }
        }
    }
}
