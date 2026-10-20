package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

private val log = LoggerFactory.getLogger("ytmp")

/** A song's whole audio, for downloads (docs/implementation/downloads.md). */
fun interface AudioFiles {
    /** Writes all of [songId]'s audio to [to]; answers its type, e.g. "audio/mp4". Throws if it stops halfway. */
    suspend fun download(songId: String, to: File): String
}

/** A song made into a file to keep: delete [file] when done with it. */
class SongFile(val file: File, val name: String, val mimeType: String)

/**
 * Makes songs into files to keep: the audio from [audio], with title, artist, album and cover
 * written in (YouTube's .m4a needs no converting). Temporary files go to [dir] (null: the system's).
 */
class SongFiles(private val audio: AudioFiles, private val dir: File? = null) {
    suspend fun make(song: Song): SongFile = withContext(Dispatchers.IO) {
        val raw = File.createTempFile("ytmp-download", ".part", dir)
        try {
            val type = audio.download(song.id, raw).substringBefore(';').trim()
            if (type != MP4) {
                // Only when YouTube has no .m4a: kept as it came, without tags.
                log.info("download {}: {}, saved without tags", song.id, type)
                return@withContext SongFile(raw, fileName(song, extension(type)), type)
            }
            val tagged = File.createTempFile("ytmp-download", ".m4a", dir)
            try {
                M4aTags.write(raw, tagged, tags(song, cover(song)))
            } catch (e: IllegalArgumentException) {
                log.warn("download {}: couldn't write the tags, saved without: {}", song.id, e.message)
                tagged.delete()
                return@withContext SongFile(raw, fileName(song, "m4a"), MP4)
            }
            raw.delete()
            SongFile(tagged, fileName(song, "m4a"), MP4)
        } catch (e: Throwable) {
            raw.delete()
            throw e
        }
    }

    companion object {
        const val MP4 = "audio/mp4"
        private const val COVER_SIZE = 1200
        private const val MAX_COVER_BYTES = 5 * 1024 * 1024
        /** Covers only come from YouTube's picture servers (the host fetches them). */
        private val COVER_HOSTS = listOf(".googleusercontent.com", ".ggpht.com", ".ytimg.com")
        private val GOOGLE_SIZE = Regex("""=w(\d+)-h(\d+)""")

        fun tags(song: Song, cover: ByteArray?) = M4aTags.Tags(
            title = song.title,
            artist = song.artists.joinToString(", ") { it.name }.ifBlank { null },
            album = song.album?.name ?: song.podcast?.name,
            albumArtist = song.artists.firstOrNull()?.name,
            genre = if (song.podcast != null) "Podcast" else null,
            cover = cover,
        )

        /** "Artist - Title.m4a", without characters that file systems refuse. */
        fun fileName(song: Song, extension: String): String {
            val artist = song.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            val base = listOfNotNull(artist, song.title).joinToString(" - ")
                .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
                .trim(' ', '.')
                .take(150)
                .ifBlank { "Song" }
            return "$base.$extension"
        }

        private fun extension(type: String) = when (type) {
            MP4 -> "m4a"
            "audio/webm" -> "webm"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            else -> "audio"
        }

        /** The song's biggest picture, asked for at [COVER_SIZE] when Google can make it so; null if there's none. */
        fun coverUrl(song: Song): String? {
            val best = song.thumbnails.maxByOrNull { it.width } ?: return null
            val host = runCatching { URI(best.url).host }.getOrNull() ?: return null
            if (!best.url.startsWith("https://") || COVER_HOSTS.none { host.endsWith(it) }) return null
            if (best.width >= COVER_SIZE || !GOOGLE_SIZE.containsMatchIn(best.url)) return best.url
            val height = if (best.width > 0) COVER_SIZE * best.height / best.width else COVER_SIZE
            return best.url.replace(GOOGLE_SIZE, "=w$COVER_SIZE-h$height")
        }

        private fun cover(song: Song): ByteArray? {
            val url = coverUrl(song) ?: return null
            return try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                try {
                    if (connection.responseCode != 200) return null
                    val bytes = java.io.ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (bytes.size() <= MAX_COVER_BYTES) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes.write(buffer, 0, n)
                        }
                    }
                    bytes.toByteArray().takeIf { it.size <= MAX_COVER_BYTES }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                log.info("download {}: no cover ({})", song.id, e.toString())
                null
            }
        }
    }
}
