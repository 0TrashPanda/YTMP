package dev.trashpanda.ytmp.cast

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class CastMessageTest {
    @Test
    fun `round trips through the wire format`() {
        val message = CastMessage("sender-ytmp", "receiver-0", CastChannel.NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
        val bytes = message.encode()
        assertEquals(bytes.size - 4, (bytes[0].toInt() shl 24) or (bytes[1].toInt() shl 16) or (bytes[2].toInt() shl 8) or (bytes[3].toInt() and 0xff))
        assertEquals(message, CastMessage.read(DataInputStream(ByteArrayInputStream(bytes))))
    }

    @Test
    fun `long payloads use multi-byte lengths`() {
        val message = CastMessage("a", "b", "c", "x".repeat(1000) + "é")
        assertEquals(message, CastMessage.read(DataInputStream(ByteArrayInputStream(message.encode()))))
    }

    @Test
    fun `matches the bytes of the reference encoding`() {
        // protocol_version=0, source "s", destination "d", namespace "n", payload_type=0, payload "{}"
        val expected = byteArrayOf(0x08, 0x00, 0x12, 0x01, 's'.code.toByte(), 0x1a, 0x01, 'd'.code.toByte(), 0x22, 0x01, 'n'.code.toByte(), 0x28, 0x00, 0x32, 0x02, '{'.code.toByte(), '}'.code.toByte())
        assertEquals(expected.toList(), CastMessage("s", "d", "n", "{}").encode().drop(4))
    }

    @Test
    fun `skips fixed-size fields it doesn't know`() {
        // A 64-bit field (15) and a 32-bit field (14) between the namespace and the payload.
        val fixed = byteArrayOf((15 shl 3 or 1).toByte(), 1, 2, 3, 4, 5, 6, 7, 8, (14 shl 3 or 5).toByte(), 1, 2, 3, 4)
        val bytes = CastMessage("s", "d", "n", "{}").encode().drop(4)
        val split = bytes.indexOf(0x28.toByte())
        val decoded = CastMessage.decode((bytes.take(split) + fixed.toList() + bytes.drop(split)).toByteArray())
        assertEquals(CastMessage("s", "d", "n", "{}"), decoded)
    }
}
