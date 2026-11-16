package dev.trashpanda.ytmp.host

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkDiscoveryTest {
    @Test
    fun `a Cast device's id comes from its instance name`() {
        assertEquals("1c3bbcf8b1a8091269f7b10e47be1b11", castIdFrom("Chromecast-1c3bbcf8b1a8091269f7b10e47be1b11"))
        assertEquals("56ed1dd188aa4c0a9e3b2f1d0c9b8a76", castIdFrom("Bose-Soundbar-700-56ed1dd188aa4c0a9e3b2f1d0c9b8a76"))
        assertEquals("Living room", castIdFrom("Living room"))
    }
}
