package dev.trashpanda.ytmp.share

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Type4TagTest {
    private fun hex(text: String) = text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.status() = copyOfRange(size - 2, size)
    private fun ByteArray.data() = copyOfRange(0, size - 2)

    private val selectApp = hex("00 A4 04 00 07 D2760000850101 00")
    private val selectCc = hex("00 A4 00 0C 02 E103")
    private val selectNdef = hex("00 A4 00 0C 02 E104")
    private fun read(offset: Int, length: Int) = byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), length.toByte())

    @Test
    fun `a reader reads the link like from a tag`() {
        val tag = Type4Tag { "https://music.youtube.com/watch?v=dQw4w9WgXcQ" }
        assertContentEquals(Type4Tag.OK, tag.respond(selectApp))
        assertContentEquals(Type4Tag.OK, tag.respond(selectCc))
        val cc = tag.respond(read(0, 15))
        assertContentEquals(Type4Tag.OK, cc.status())
        val ccData = cc.data()
        assertEquals(15, ccData.size)
        assertEquals(0x20, ccData[2].toInt())
        assertContentEquals(hex("0406E104"), ccData.copyOfRange(7, 11))
        assertEquals(0xFF.toByte(), ccData[14]) // read-only

        assertContentEquals(Type4Tag.OK, tag.respond(selectNdef))
        val nlen = tag.respond(read(0, 2)).data()
        val length = ((nlen[0].toInt() and 0xFF) shl 8) or (nlen[1].toInt() and 0xFF)
        val message = tag.respond(read(2, length)).data()
        assertContentEquals(Ndef.uriMessage("https://music.youtube.com/watch?v=dQw4w9WgXcQ"), message)
        assertEquals(length, message.size)
    }

    @Test
    fun `the URI record abbreviates https`() {
        val message = Ndef.uriMessage("https://music.youtube.com/watch?v=abc")
        // Short well-known record, type "U", payload: 0x04 for https:// and the rest.
        assertEquals(0xD1.toByte(), message[0])
        assertEquals(1, message[1].toInt())
        val rest = "music.youtube.com/watch?v=abc"
        assertEquals(rest.length + 1, message[2].toInt())
        assertEquals('U'.code.toByte(), message[3])
        assertEquals(0x04.toByte(), message[4])
        assertEquals(rest, String(message.copyOfRange(5, message.size)))
        assertEquals(0x03.toByte(), Ndef.uriMessage("http://192.168.1.5:8765/room/ABCD")[4])
    }

    @Test
    fun `a long link gets a long record`() {
        val url = "https://example.com/" + "a".repeat(300)
        val message = Ndef.uriMessage(url)
        assertEquals(0xC1.toByte(), message[0])
        val length = ((message[2].toInt() and 0xFF) shl 24) or ((message[3].toInt() and 0xFF) shl 16) or ((message[4].toInt() and 0xFF) shl 8) or (message[5].toInt() and 0xFF)
        assertEquals(url.length - "https://".length + 1, length)
        // And it's read in parts.
        val tag = Type4Tag { url }
        tag.respond(selectApp)
        tag.respond(selectNdef)
        val file = Type4Tag.ndefFile(url)
        val first = tag.respond(read(0, 0xFF)).data()
        val second = tag.respond(read(0xFF, 0xFF)).data()
        assertContentEquals(file, first + second)
    }

    @Test
    fun `nothing shared means no tag here`() {
        val tag = Type4Tag { null }
        assertContentEquals(Type4Tag.NOT_FOUND, tag.respond(selectApp))
        assertContentEquals(Type4Tag.NOT_FOUND, tag.respond(selectCc))
        assertContentEquals(Type4Tag.NOT_FOUND, tag.respond(read(0, 15)))
    }

    @Test
    fun `other applications, files and commands are refused`() {
        val tag = Type4Tag { "https://example.com/room/ABCD" }
        assertContentEquals(Type4Tag.NOT_FOUND, tag.respond(hex("00 A4 04 00 07 A0000000041010 00")))
        tag.respond(selectApp)
        assertContentEquals(Type4Tag.NOT_FOUND, tag.respond(hex("00 A4 00 0C 02 E105")))
        assertContentEquals(Type4Tag.NOT_FOUND, tag.respond(read(0, 2))) // no file selected yet
        assertContentEquals(Type4Tag.SECURITY_NOT_SATISFIED, tag.respond(hex("00 D6 00 00 02 0000")))
        assertContentEquals(Type4Tag.INS_NOT_SUPPORTED, tag.respond(hex("00 CA 00 00 00")))
        assertContentEquals(Type4Tag.CLA_NOT_SUPPORTED, tag.respond(hex("80 A4 04 00 00")))
        assertContentEquals(Type4Tag.WRONG_LENGTH, tag.respond(hex("00 A4")))
        tag.respond(selectNdef)
        assertContentEquals(Type4Tag.WRONG_OFFSET, tag.respond(read(0x400, 2)))
    }

    @Test
    fun `the link is taken when the reader starts, and forgotten when it goes`() {
        var link: String? = "https://a.example/room/AAAA"
        val tag = Type4Tag { link }
        tag.respond(selectApp)
        link = "https://b.example/room/BBBB"
        tag.respond(selectNdef)
        val file = tag.respond(read(0, 0)).data()
        assertContentEquals(Type4Tag.ndefFile("https://a.example/room/AAAA"), file)
        tag.reset()
        assertContentEquals(Type4Tag.NOT_FOUND, tag.respond(selectNdef))
    }

    @Test
    fun `names the app after the link when asked`() {
        val plain = Ndef.uriMessage("https://music.youtube.com/watch?v=abc")
        val message = Ndef.uriMessage("https://music.youtube.com/watch?v=abc", "dev.trashpanda.ytmp")
        // The link's record no longer ends the message (ME off), then the app record ends it.
        assertEquals((plain[0].toInt() and 0xBF).toByte(), message[0])
        val aar = message.copyOfRange(plain.size, message.size)
        assertEquals(0x54.toByte(), aar[0]) // ME, short record, external type
        assertEquals("android.com:pkg", String(aar, 3, aar[1].toInt(), Charsets.US_ASCII))
        assertEquals("dev.trashpanda.ytmp", String(aar, 3 + aar[1], aar[2].toInt(), Charsets.US_ASCII))
    }
}
