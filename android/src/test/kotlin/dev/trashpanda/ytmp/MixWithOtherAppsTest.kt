package dev.trashpanda.ytmp

import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class MixWithOtherAppsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val target = """{"enabled":true,"item":null,"streamUrl":null,"proxyUrl":null,"playing":true,"positionMs":0,"hostTimeMs":0,"clockOffset":0,"volume":1"""

    @Test
    fun `off unless the page says so, so a page from before the setting changes nothing`() {
        assertFalse(json.decodeFromString(PlaybackTarget.serializer(), "$target}").mixWithOtherApps)
        assertTrue(json.decodeFromString(PlaybackTarget.serializer(), """$target,"mixWithOtherApps":true}""").mixWithOtherApps)
    }

    @Test
    fun `ringing, a phone call and an internet call are calls`() {
        assertTrue(isCallMode(AudioManager.MODE_RINGTONE))
        assertTrue(isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(isCallMode(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(isCallMode(AudioManager.MODE_CALL_SCREENING))
    }

    @Test
    fun `no call is no call`() {
        assertFalse(isCallMode(AudioManager.MODE_NORMAL))
    }
}
