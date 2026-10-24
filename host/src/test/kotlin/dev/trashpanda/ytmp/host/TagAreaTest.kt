package dev.trashpanda.ytmp.host

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagAreaTest {
    private val link = "https://music.youtube.com/watch?v=rfUeWe7u364"
    /** A cover-sized lump of bytes without a link in it. */
    private val cover = Random(1).nextBytes(300 * 1024)

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            val size = body.size + 8
            write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(body)
        }.toByteArray()

    /** An M4A the way yt-dlp and mutagen leave it: the cover before the comment, the moov at [moovFirst] or after the audio. */
    private fun m4a(moovFirst: Boolean): ByteArray {
        val ilst = box("ilst", box("covr", box("data", cover)) + box("©cmt", box("data", link.toByteArray())))
        val moov = box("moov", box("mvhd", ByteArray(100)) + box("udta", box("meta", ByteArray(4) + ilst)))
        val mdat = box("mdat", Random(2).nextBytes(2 * 1024 * 1024))
        val ftyp = box("ftyp", "M4A isomiso2".toByteArray())
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    @Test
    fun `an M4A's link after a big cover is found, with the moov at the start or the end`() {
        for (first in listOf(true, false)) {
            val file = m4a(first)
            // What was read before: only the first 128 KB.
            assertNull(LocalMatcher.idInBytes(file.copyOf(128 * 1024)))
            val area = TagArea.read(file.inputStream())
            assertEquals("rfUeWe7u364", LocalMatcher.idInBytes(area))
            assertTrue(area.size < 400 * 1024, "only the moov, not the audio")
        }
    }

    @Test
    fun `an MP3's whole ID3 tag is read, and only that`() {
        val frames = box("APIC", cover) + box("COMM", ("eng\u0000" + link).toByteArray())
        val size = frames.size
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
            ((size ushr 21) and 0x7F).toByte(), ((size ushr 14) and 0x7F).toByte(), ((size ushr 7) and 0x7F).toByte(), (size and 0x7F).toByte())
        val file = header + frames + Random(3).nextBytes(1024 * 1024)
        val area = TagArea.read(file.inputStream())
        assertEquals(10 + size, area.size)
        assertEquals("rfUeWe7u364", LocalMatcher.idInBytes(area))
    }

    @Test
    fun `other files give their first megabyte, and short files all of it`() {
        val ogg = "OggS".toByteArray() + ByteArray(500 * 1024) + "PURL=$link".toByteArray() + ByteArray(2 * 1024 * 1024)
        assertEquals(TagArea.OTHER_BYTES, TagArea.read(ogg.inputStream()).size)
        assertEquals("rfUeWe7u364", LocalMatcher.idInBytes(TagArea.read(ogg.inputStream())))
        assertEquals(5, TagArea.read("short".toByteArray().inputStream()).size)
    }
}
