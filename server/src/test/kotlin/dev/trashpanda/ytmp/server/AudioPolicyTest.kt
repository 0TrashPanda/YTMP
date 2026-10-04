package dev.trashpanda.ytmp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioPolicyTest {
    @Test
    fun `local network addresses`() {
        for (lan in listOf("127.0.0.1", "10.0.0.109", "172.18.0.3", "192.168.68.125", "::1", "fe80::1", "fd12:3456::1", "::ffff:192.168.1.5", "localhost")) {
            assertTrue(isLanAddress(lan), lan)
        }
        for (internet in listOf("8.8.8.8", "172.32.0.1", "2a02:1810::1", "example.com", "")) {
            assertFalse(isLanAddress(internet), internet)
        }
    }

    @Test
    fun `X-Forwarded-For only counts from a proxy on the local network`() {
        // Through Caddy on this machine: the address Caddy added.
        assertEquals("81.82.83.84", clientAddress("127.0.0.1", "81.82.83.84"))
        assertEquals("81.82.83.84", clientAddress("172.18.0.2", "192.168.1.9, 81.82.83.84"))
        // Straight from the internet: a made-up header changes nothing.
        assertEquals("81.82.83.84", clientAddress("81.82.83.84", "192.168.1.9"))
        assertEquals("192.168.1.9", clientAddress("192.168.1.9", null))
    }

    @Test
    fun `parses the setting`() {
        assertEquals(AudioProxyMode.LAN, AudioProxyMode.parse(" Lan "))
        assertFailsWith<IllegalStateException> { AudioProxyMode.parse("sometimes") }
        assertEquals(AudioProxyMode.NEVER, Config.load(null, env = mapOf("YTMP_AUDIO_PROXY" to "never")).audio.proxyMode())
        assertEquals(AudioProxyMode.ALWAYS, Config.load(null, env = emptyMap()).audio.proxyMode())
    }
}
