package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.CastChannel
import dev.trashpanda.ytmp.cast.CastWebPage
import dev.trashpanda.ytmp.protocol.ScreenUnlinkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The party screen of a server's room on a TV only the phone can reach. */
class CastScreensTest {
    @Test
    fun `the phone shows a room's party screen on its TV, stops it, and hears when someone else takes the TV`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            val casts = CastOutputs(scope) { "http://unused" }
            casts.add(CastDeviceAddress("cast:tv", "Living room TV", "127.0.0.1", device.port))
            val ended = CopyOnWriteArrayList<ShownScreen>()
            val screens = CastScreens(scope, casts, checkMs = 100) { ended += it }

            // The server's https page: in DashCast's frame.
            val url = "https://ytmp.example.com/tv#cast=ABCD.tok%2Ben"
            screens.show("cast:tv", url)
            eventually { device.dashCastUrl == url }
            assertEquals(false, device.dashCastForce)
            assertEquals(listOf(ShownScreen("cast:tv", "Living room TV", url)), screens.shown.value)

            screens.stop("cast:tv")
            assertTrue(!device.dashCastRunning)
            assertTrue(screens.shown.value.isEmpty())
            eventually { ended.size == 1 }
            assertEquals("https://ytmp.example.com/api/screens/unlink" to ScreenUnlinkRequest("ABCD", "tok+en"), ended.single().unlink())

            // Someone casts YouTube to the TV: the screen ended.
            screens.show("cast:tv", url)
            device.takenOverBy("233637DE")
            eventually { ended.size == 2 && screens.shown.value.isEmpty() }
        }
        scope.cancel()
    }

    @Test
    fun `an http page (a phone's address) replaces DashCast's own page, which can't frame it`() = runBlocking {
        FakeCastDevice().use { device ->
            val channel = CastChannel.open("127.0.0.1", device.port)
            val page = CastWebPage(channel)
            page.show("http://10.0.0.102:8765/tv#cast=ABCD.token")
            eventually { device.dashCastUrl != null }
            assertEquals(true, device.dashCastForce)
            // Shown again (DashCast no longer hears us after that): it starts afresh.
            val launches = device.types(CastChannel.NS_RECEIVER).count { it == "LAUNCH" }
            page.show("http://10.0.0.102:8765/tv#cast=ABCD.other")
            assertTrue("STOP" in device.types(CastChannel.NS_RECEIVER))
            assertEquals(launches + 1, device.types(CastChannel.NS_RECEIVER).count { it == "LAUNCH" })
            channel.close()
        }
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(50)
    }
}
