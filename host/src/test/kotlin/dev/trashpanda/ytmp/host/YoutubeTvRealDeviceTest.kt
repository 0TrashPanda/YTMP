package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.Dial
import dev.trashpanda.ytmp.cast.LoungeStatus
import dev.trashpanda.ytmp.cast.YoutubeLounge
import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test

/**
 * Checks against a real TV with the YouTube app:
 * `YOUTUBE_TV=http://192.168.68.100:56790/dd.xml ./gradlew :host:test --tests '*YoutubeTvRealDevice*'`
 * reads it and pairs without playing anything; with `YOUTUBE_TV_PLAY=1` too, a room plays a
 * song there for about a minute at 20% volume (the TV's volume is put back afterwards).
 */
class YoutubeTvRealDeviceTest {
    @Test
    fun `reads the TV and pairs with its YouTube app`() = runBlocking {
        val location = System.getenv("YOUTUBE_TV")?.ifEmpty { null } ?: return@runBlocking
        val device = Dial.describe(location)
        println("device: $device")
        val app = Dial.youtube(device!!.appsUrl)
        println("YouTube app: $app")
        println("a Chromecast: ${Dial.isCastDevice(java.net.URI(location).host)}")
        val screenId = app?.screenId ?: return@runBlocking
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lounge = YoutubeLounge(screenId, "YTMP test")
        lounge.connect(scope)
        lounge.requestStatus()
        delay(3_000)
        println("status: ${lounge.status.value} (open: ${lounge.isOpen})")
        lounge.close()
        scope.cancel()
    }

    @Test
    fun `plays a room on the TV`() = runBlocking {
        val location = System.getenv("YOUTUBE_TV")?.ifEmpty { null } ?: return@runBlocking
        if (System.getenv("YOUTUBE_TV_PLAY") != "1") return@runBlocking
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val appsUrl = Dial.describe(location)!!.appsUrl
        val screenId = Dial.youtube(appsUrl)?.screenId ?: Dial.launchYoutube(appsUrl)!!
        // A second remote that watches the TV (and sets a low volume before anything plays).
        val watcher = YoutubeLounge(screenId, "YTMP test watcher")
        watcher.connect(scope)
        watcher.requestStatus()
        delay(2_000)
        val before = watcher.status.value
        println("before: $before")
        watcher.setVolume(20)
        delay(1_000)

        val tvs = YoutubeTvOutputs(scope)
        tvs.addLocation(location)
        withTimeout(10_000) { while (tvs.devices.value.isEmpty()) delay(100) }
        val id = tvs.devices.value.single().id
        val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = tvs.devices)
        tvs.attach(rooms)
        val room = rooms.create("TV test")
        val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
        suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))
        suspend fun watch(what: String, timeoutMs: Long = 30_000, check: suspend (LoungeStatus) -> Boolean) {
            val started = System.currentTimeMillis()
            val ok = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    watcher.requestStatus()
                    delay(1_000)
                    if (check(watcher.status.value)) break
                }
                true
            } != null
            println("${if (ok) "OK  " else "FAIL"} $what after ${System.currentTimeMillis() - started} ms: ${watcher.status.value}")
        }
        try {
            run(Command.AddSongs(listOf(Song("ytm:FGBhQbmPwH8", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())), QueuePosition.END))
            run(Command.SetOutput(id, active = true))
            watch("plays the song") { it.videoId == "FGBhQbmPwH8" && it.state == LoungeStatus.PLAYING }
            println("room volume: ${room.view().activeOutputs[id]}")
            delay(8_000)
            run(Command.Pause)
            watch("paused") { it.state == LoungeStatus.PAUSED }
            run(Command.Play)
            watch("playing again") { it.state == LoungeStatus.PLAYING }
            run(Command.Seek(60_000))
            watch("moved to the room's position") {
                it.state == LoungeStatus.PLAYING && kotlin.math.abs(it.positionMs - room.view().positionAt(System.currentTimeMillis())) < 4_000
            }
            println("drift: ${watcher.status.value.positionMs + (System.currentTimeMillis() - watcher.status.value.receivedAt) - room.view().positionAt(System.currentTimeMillis())} ms")
            delay(10_000)
            run(Command.SetOutput(id, active = false))
            watch("stopped") { it.videoId != "FGBhQbmPwH8" || it.state !in setOf(LoungeStatus.PLAYING, LoungeStatus.LOADING) }
        } finally {
            before.volume?.let { watcher.setVolume(it) }
            before.autoplay?.let { watcher.setAutoplay(it) }
            delay(1_000)
            println("after: ${watcher.status.value}")
            watcher.close()
            scope.cancel()
        }
    }
}
