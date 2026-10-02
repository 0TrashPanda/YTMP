package dev.trashpanda.ytmp.cast

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Plays [CAST_URL] for 15 s on a real Cast device at 15% volume, printing every message.
 * Runs only with `CAST_HOST=<ip> CAST_URL=<audio url> ./gradlew :cast:test`.
 */
class RealPlaybackTest {
    @Test
    fun `plays a short test on a real device`() {
        val host = System.getenv("CAST_HOST")?.takeIf { it.isNotEmpty() } ?: return
        val url = System.getenv("CAST_URL")?.takeIf { it.isNotEmpty() } ?: return
        runBlocking {
            val channel = CastChannel.open(host)
            val printer = launch { channel.messages.collect { (ns, json) -> println("<- ${ns.substringAfterLast('.')}: $json") } }
            try {
                val player = CastMediaPlayer(channel)
                player.launch()
                println("launched; local address ${channel.localAddress}")
                player.setVolume(0.15)
                println("load -> " + player.load(url, "audio/mp4", CastMetadata("YTMP test", "YTMP", null, null), 0, autoplay = true))
                repeat(5) {
                    delay(3000)
                    println("status -> " + player.status())
                }
                player.stop()
            } finally {
                printer.cancel()
                channel.close()
            }
        }
    }
}
