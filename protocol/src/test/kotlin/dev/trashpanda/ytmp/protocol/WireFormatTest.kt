package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class WireFormatTest {
    private val song = Song("ytm:abc", "Title", listOf(ArtistRef(null, "Artist")), null, 1000, emptyList())

    @Test
    fun `messages use type and commands use kind`() {
        val message: ClientMessage = ClientMessage.CommandMessage("c1", Command.AddSongs(listOf(song), QueuePosition.NEXT))
        val json = ProtocolJson.encodeToJsonElement(ClientMessage.serializer(), message).jsonObject

        assertEquals("command", json["type"]!!.jsonPrimitive.content)
        val command = json["command"]!!.jsonObject
        assertEquals("AddSongs", command["kind"]!!.jsonPrimitive.content)
        assertEquals("next", command["position"]!!.jsonPrimitive.content)
    }

    @Test
    fun `objects round trip`() {
        val text = """{"type":"command","id":"c2","command":{"kind":"Skip"}}"""
        val message = ProtocolJson.decodeFromString(ClientMessage.serializer(), text)
        assertEquals(ClientMessage.CommandMessage("c2", Command.Skip), message)
    }
}
