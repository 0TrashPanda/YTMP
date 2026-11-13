package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.host.LocalMusic
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The music in folders picked on a computer, as a library of the desktop app's host. */
class ComputerLibraryTest {
    /** About 1.3 s of silent MP3 (MPEG-1 layer III, 128 kbps, 44.1 kHz). */
    private fun mp3(file: File, title: String? = null, artist: String? = null, album: String? = null, track: String? = null) {
        val frame = ByteArray(417).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0x64 }
        file.parentFile.mkdirs()
        file.outputStream().use { out -> repeat(50) { out.write(frame) } }
        if (title == null) return
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, title)
        artist?.let { tag.setField(FieldKey.ARTIST, it) }
        album?.let { tag.setField(FieldKey.ALBUM, it) }
        track?.let { tag.setField(FieldKey.TRACK, it) }
        audio.commit()
    }

    @Test
    fun `songs from the picked folders, with their tags, and only those`() = runBlocking {
        val home = Files.createTempDirectory("computer-music").toFile()
        val music = File(home, "Music")
        mp3(File(music, "Album/01.mp3"), "First", "The Band", "The Album", "1")
        mp3(File(music, "Album/02.mp3"), "Second", "The Band", "The Album", "2")
        mp3(File(music, "Loose Song.mp3"))
        File(music, "notes.txt").writeText("not music")
        mp3(File(home, "Elsewhere/Other.mp3"), "Other")

        val library = ComputerLibrary("abc12345", File(home, "index.json"), "ytmphost://localhost", this)
        library.setFolders(listOf(music))
        library.rescan(wait = true)

        val tracks = library.tracks().sortedBy { it.title }
        assertEquals(listOf("First", "Loose Song", "Second"), tracks.map { it.title }, "untagged: its file name; another folder: left out")
        val first = tracks.first()
        assertEquals("The Band", first.artist)
        assertEquals("The Album", first.album)
        assertEquals(1, first.track)
        assertEquals("Music/Album", first.folder)
        assertTrue(first.durationMs in 1_000..1_500, "length from the file: ${first.durationMs}")

        // Its songs are this computer's (the desktop app plays them from here; the server asks only when needed).
        val songId = LocalMusic.songId(library.id, first.key)
        assertTrue(songId.startsWith("local:dev-abc12345:s:"))
        assertEquals(File(music, "Album/01.mp3").absolutePath, library.fileOf(songId)?.absolutePath)
        assertNull(library.fileOf("local:dev-other:s:${first.key}"))

        // Kept: a new library finds them without reading the files again, and a removed folder's songs go.
        val again = ComputerLibrary("abc12345", File(home, "index.json"), "ytmphost://localhost", this)
        again.setFolders(listOf(music))
        assertEquals(3, again.tracks().size)
        again.setFolders(emptyList())
        assertEquals(0, again.tracks().size)
    }

    @Test
    fun `a cover in the folder is the song's cover`() = runBlocking {
        val music = Files.createTempDirectory("computer-music").toFile()
        mp3(File(music, "A/song.mp3"), "Song")
        File(music, "A/cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val library = ComputerLibrary("abc12345", File(music, "index.json"), "ytmphost://localhost", this)
        library.setFolders(listOf(music))
        library.rescan(wait = true)
        val key = library.tracks().single().key
        assertNotNull(library.artUrl(key, 544)).let { assertTrue(it.startsWith("ytmphost://localhost/api/local/art/local%3Adev-abc12345")) }
        assertEquals(listOf<Byte>(1, 2, 3), ComputerLibrary.cover(File(music, "A/song.mp3"))!!.first.toList())
    }
}
