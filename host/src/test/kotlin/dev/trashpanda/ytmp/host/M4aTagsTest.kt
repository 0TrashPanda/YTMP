package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.Thumbnail
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class M4aTagsTest {
    private val dir = createTempDirectory("m4a").toFile().apply { deleteOnExit() }
    private val audio = ByteArray(1000) { (it * 7).toByte() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(int(8 + parts.sumOf { it.size }))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun long(value: Long) = ByteBuffer.allocate(8).putLong(value).array()

    private fun tagged(input: ByteArray, tags: M4aTags.Tags): ByteArray {
        val from = File(dir, "in.m4a").apply { writeBytes(input) }
        val to = File(dir, "out.m4a")
        M4aTags.write(from, to, tags)
        return to.readBytes()
    }

    /** The byte offset [at] in [file] read as a 32-bit or 64-bit number. */
    private fun intAt(file: ByteArray, at: Int) = ByteBuffer.wrap(file).getInt(at)
    private fun longAt(file: ByteArray, at: Int) = ByteBuffer.wrap(file).getLong(at)
    private fun indexOf(file: ByteArray, type: String) = String(file, Charsets.ISO_8859_1).indexOf(type) - 4

    @Test
    fun `tags go in and the chunk offsets follow the audio`() {
        val ftyp = box("ftyp", "M4A ".toByteArray(), int(0))
        // moov before mdat, as in a normal (not fragmented) file, with an old tag to replace.
        fun moov(offset: Int) = box(
            "moov",
            box("mvhd", ByteArray(20)),
            box("trak", box("mdia", box("minf", box("stbl", box("stco", int(0), int(1), int(offset)))))),
            box("udta", box("meta", int(0), box("ilst", box("©too", box("data", int(1), int(0), "Google".toByteArray()))))),
        )
        val mdatStart = ftyp.size + moov(0).size
        val file = ftyp + moov(mdatStart + 8) + box("mdat", audio)

        val out = tagged(file, M4aTags.Tags("Song", artist = "Someone", album = "Album", cover = jpeg))

        val tags = M4aTags.read(out)
        assertEquals("Song", tags["©nam"]!!.decodeToString())
        assertEquals("Someone", tags["©ART"]!!.decodeToString())
        assertEquals("Album", tags["©alb"]!!.decodeToString())
        assertContentEquals(jpeg, tags["covr"])
        assertNull(tags["©too"])
        // The chunk offset still points at the audio.
        val chunk = intAt(out, indexOf(out, "stco") + 16)
        assertContentEquals(audio, out.copyOfRange(chunk, chunk + audio.size))
    }

    @Test
    fun `fragments with an absolute base offset move along`() {
        val ftyp = box("ftyp", "dash".toByteArray(), int(0))
        val moov = box("moov", box("mvhd", ByteArray(20)), box("trak", box("mdia", box("minf", box("stbl", box("stco", int(0), int(0)))))))
        fun moof(base: Long) = box("moof", box("mfhd", int(0), int(1)), box("traf", box("tfhd", int(1), int(1), long(base))))
        val mdatBody = ftyp.size + moov.size + moof(0).size + 8L
        val file = ftyp + moov + moof(mdatBody) + box("mdat", audio)

        val out = tagged(file, M4aTags.Tags("Song"))

        assertEquals("Song", M4aTags.read(out)["©nam"]!!.decodeToString())
        val base = longAt(out, indexOf(out, "tfhd") + 16).toInt()
        assertContentEquals(audio, out.copyOfRange(base, base + audio.size))
    }

    @Test
    fun `the song ID goes in, and is read back from the start of the file`() {
        val ftyp = box("ftyp", "dash".toByteArray(), int(0))
        val moov = box("moov", box("mvhd", ByteArray(20)))
        val file = ftyp + moov + box("sidx", ByteArray(12)) + box("moof", ByteArray(16)) + box("mdat", audio)

        val out = tagged(file, M4aTags.Tags("Song", cover = jpeg, songId = "ytm:abc_-1"))

        assertEquals("ytm:abc_-1", M4aTags.read(out)[M4aTags.ID_ITEM]!!.decodeToString())
        assertEquals("ytm:abc_-1", M4aTags.readSongId(out.inputStream()))
        assertNull(M4aTags.readSongId(tagged(file, M4aTags.Tags("Song")).inputStream()))
        assertNull(M4aTags.readSongId(ByteArray(3).inputStream()))
    }

    @Test
    fun `a file name without characters file systems refuse`() {
        val song = Song("ytm:a", "What? / Why: \"this\"", listOf(ArtistRef(null, "AC/DC")), null, 0, emptyList())
        assertEquals("AC_DC - What_ _ Why_ _this_.m4a", SongFiles.fileName(song, "m4a"))
        assertEquals("Song.m4a", SongFiles.fileName(song.copy(title = "...", artists = emptyList()), "m4a"))
    }

    @Test
    fun `tags for songs and episodes`() {
        val song = Song("ytm:a", "Title", listOf(ArtistRef(null, "A"), ArtistRef(null, "B")), AlbumRef(null, "Album"), 0, emptyList())
        assertEquals(M4aTags.Tags("Title", "A, B", "Album", "A", songId = "ytm:a"), SongFiles.tags(song, null))
        val episode = song.copy(album = null, podcast = PodcastRef("p", "The Show"))
        assertEquals(M4aTags.Tags("Title", "A, B", "The Show", "A", "Podcast", songId = "ytm:a"), SongFiles.tags(episode, null))
    }

    @Test
    fun `covers only from YouTube's picture servers, large`() {
        fun song(vararg urls: Pair<String, Int>) =
            Song("ytm:a", "T", emptyList(), null, 0, urls.map { (url, width) -> Thumbnail(url, width, width) })
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            SongFiles.coverUrl(song("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj" to 60, "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj" to 120)),
        )
        assertEquals("https://i.ytimg.com/vi/x/hqdefault.jpg", SongFiles.coverUrl(song("https://i.ytimg.com/vi/x/hqdefault.jpg" to 480)))
        assertNull(SongFiles.coverUrl(song("http://192.168.1.1/admin" to 500)))
        assertNull(SongFiles.coverUrl(song("https://evil.example/x.googleusercontent.com" to 500)))
        assertNull(SongFiles.coverUrl(song()))
    }
}
