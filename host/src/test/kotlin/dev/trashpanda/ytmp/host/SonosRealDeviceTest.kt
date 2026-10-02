package dev.trashpanda.ytmp.host

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** Read-only checks against a real Sonos: `SONOS_HOST=192.168.68.100 ./gradlew :host:test --tests '*SonosRealDevice*'`. */
class SonosRealDeviceTest {
    @Test
    fun `reads name, state and volume`() = runBlocking {
        val host = System.getenv("SONOS_HOST") ?: return@runBlocking
        val sonos = SonosPlayer(host)
        println("info: ${sonos.info()}")
        println("status: ${sonos.status()}")
        println("volume: ${sonos.volume()}")
        println("we are: ${sonos.localAddress}")
    }
}
