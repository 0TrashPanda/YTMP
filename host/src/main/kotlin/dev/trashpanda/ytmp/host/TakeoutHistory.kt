package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.TakeoutSummary
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** A song in a Takeout history and when it was played on YouTube Music (ms, oldest first). */
@Serializable
data class TakeoutSong(val id: String, val title: String, val artist: String? = null, val plays: List<Long>) {
    /** As a song to look for in the cache: only its ID counts there (it's looked up before it's fetched). */
    fun song(): Song = Song("ytm:$id", title, emptyList(), null, 0, emptyList())
}

@Serializable
private data class TakeoutFile(val songs: List<TakeoutSong>)

/**
 * The owner's YouTube Music plays from a Google Takeout export
 * (docs/implementation/song-cache.md#from-a-google-takeout-history), kept in [file]: every play,
 * years back, so the song cache can keep what was played most ([pick]).
 */
class TakeoutHistory(private val file: File) {
    private val lock = Any()
    private var loaded: List<TakeoutSong>? = null

    val songs: List<TakeoutSong>
        get() = synchronized(lock) {
            loaded ?: (runCatching { json.decodeFromString<TakeoutFile>(file.readText()).songs }.getOrDefault(emptyList())).also { loaded = it }
        }

    @Volatile
    private var summary: Pair<List<TakeoutSong>, TakeoutSummary?>? = null

    fun summary(): TakeoutSummary? {
        val songs = songs
        summary?.takeIf { it.first === songs }?.let { return it.second }
        val made = if (songs.isEmpty()) null
        else TakeoutSummary(songs.size, songs.sumOf { it.plays.size }, songs.minOf { it.plays.first() }, songs.maxOf { it.plays.last() })
        summary = songs to made
        return made
    }

    /** Reads an export ([Takeout.read]) and keeps it instead of the one before. */
    fun import(input: InputStream, zone: ZoneId = ZoneId.systemDefault()): TakeoutSummary {
        val songs = Takeout.read(input, zone)
        val temp = File(file.path + ".part")
        temp.writeText(json.encodeToString(TakeoutFile.serializer(), TakeoutFile(songs)))
        if (!temp.renameTo(file)) throw IllegalStateException("Couldn't save the history")
        synchronized(lock) { loaded = songs }
        return summary()!!
    }

    fun forget() {
        file.delete()
        synchronized(lock) { loaded = emptyList() }
    }

    /**
     * The songs played at least [minPlays] times in the last [days] days (0: the whole export),
     * most played there first, then the ones played last.
     */
    fun pick(days: Int, minPlays: Int, now: Long): List<TakeoutSong> {
        val since = if (days > 0) now - days * DAY_MS else Long.MIN_VALUE
        val least = minPlays.coerceAtLeast(1)
        return songs.mapNotNull { song -> song.plays.count { it >= since }.takeIf { it >= least }?.let { song to it } }
            .sortedWith(compareByDescending<Pair<TakeoutSong, Int>> { it.second }.thenByDescending { it.first.plays.last() })
            .map { it.first }
    }

    companion object {
        private const val DAY_MS = 24L * 3600 * 1000
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Reads the YouTube Music plays in a Google Takeout export: the zip, or the watch history in it
 * (`watch-history.html`, Takeout's default, or `watch-history.json`). Plain YouTube is left out.
 */
object Takeout {
    private const val CELL = "class=\"outer-cell"
    private val title = Regex("""mdl-typography--title">(.*?)<br""")
    private val body = Regex("""mdl-typography--body-1">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val link = Regex("""<a href="([^"]*)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val videoId = Regex("""[?&]v=([\w-]{11})""")
    private val tag = Regex("<[^>]*>")

    private class Plays {
        val songs = LinkedHashMap<String, Triple<String, String?, MutableList<Long>>>()
        var undated = 0

        fun add(id: String, title: String, artist: String?, at: Long?) {
            if (at == null) {
                undated++
                return
            }
            songs.getOrPut(id) { Triple(title, artist, mutableListOf()) }.third += at
        }
    }

    fun read(input: InputStream, zone: ZoneId): List<TakeoutSong> {
        val plays = Plays()
        val stream = BufferedInputStream(input)
        stream.mark(4)
        val head = ByteArray(4).also { stream.read(it) }
        stream.reset()
        if (head.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
            var found = false
            val zip = ZipInputStream(stream)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (name != "watch-history.html" && name != "watch-history.json") continue
                found = true
                history(object : FilterInputStream(zip) { override fun close() {} }, plays, zone)
            }
            if (!found) throw IllegalArgumentException("There's no YouTube watch history in this zip: in Takeout, pick YouTube and YouTube Music with its history")
        } else {
            history(stream, plays, zone)
        }
        if (plays.songs.isEmpty()) {
            throw IllegalArgumentException(
                if (plays.undated > 0) "Couldn't read the dates in this history: export it again with the history as JSON" else "There are no YouTube Music plays in this file",
            )
        }
        return plays.songs.map { (id, song) -> TakeoutSong(id, song.first, song.second, song.third.sorted()) }
    }

    private fun history(input: InputStream, plays: Plays, zone: ZoneId) {
        val stream = BufferedInputStream(input)
        stream.mark(1024)
        val head = ByteArray(1024).let { buf -> buf.copyOf(stream.read(buf).coerceAtLeast(0)) }
        stream.reset()
        // The first thing that isn't space (or a UTF-8 BOM): "[" is the JSON export.
        val first = head.firstOrNull { it.toInt().toChar() !in " \t\r\n" && it.toInt() and 0x80 == 0 }
        if (first?.toInt()?.toChar() == '[') json(stream, plays) else html(stream.bufferedReader(), plays, zone)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun json(input: InputStream, plays: Plays) {
        for (element in Json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
            val item = element as? JsonObject ?: continue
            val url = item["titleUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            val header = item["header"]?.jsonPrimitive?.contentOrNull
            if (header != "YouTube Music" && "music.youtube.com" !in url) continue
            val id = videoId.find(url)?.groupValues?.get(1) ?: continue
            val title = item["title"]?.jsonPrimitive?.contentOrNull?.removePrefix("Watched ") ?: id
            val artist = runCatching { item["subtitles"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }.getOrNull()
            val at = item["time"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            plays.add(id, title, artist?.removeSuffix(" - Topic"), at)
        }
    }

    /** The HTML a cell at a time: the file can be 50 MB and more. */
    private fun html(reader: Reader, plays: Plays, zone: ZoneId) {
        val buf = StringBuilder()
        val chunk = CharArray(64 * 1024)
        while (true) {
            val n = reader.read(chunk)
            if (n > 0) buf.append(chunk, 0, n)
            while (true) {
                val start = buf.indexOf(CELL)
                if (start < 0) {
                    if (buf.length > CELL.length) buf.delete(0, buf.length - CELL.length)
                    break
                }
                val next = buf.indexOf(CELL, start + CELL.length)
                if (next < 0) {
                    if (start > 0) buf.delete(0, start)
                    break
                }
                cell(buf.substring(start, next), plays, zone)
                buf.delete(0, next)
            }
            if (n < 0) break
        }
        if (buf.startsWith(CELL)) cell(buf.toString(), plays, zone)
    }

    private fun cell(cell: String, plays: Plays, zone: ZoneId) {
        val header = title.find(cell)?.groupValues?.get(1)?.trim()
        val content = body.find(cell)?.groupValues?.get(1) ?: return
        val links = link.findAll(content).toList()
        val url = links.firstOrNull()?.groupValues?.get(1) ?: return
        if (header != "YouTube Music" && "music.youtube.com" !in url) return
        val id = videoId.find(url)?.groupValues?.get(1) ?: return
        val name = text(links[0].groupValues[2])
        val artist = links.getOrNull(1)?.let { text(it.groupValues[2]).removeSuffix(" - Topic") }
        val date = content.split("<br>").map { text(it) }.lastOrNull { it.isNotBlank() }
        plays.add(id, name, artist, date?.let { TakeoutDates.parse(it, zone) })
    }

    private fun text(html: String): String =
        html.replace(tag, "").replace("&nbsp;", " ").replace("&emsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace(' ', ' ').trim()
}

/** The dates in Takeout's HTML, written the way the account's language writes them ("23 Oct 2026, 17:30:20 CEST"). */
object TakeoutDates {
    private val time = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp])\.?\s?[Mm]\.?|(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val iso = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val numeric = Regex("""(\d{1,2})[./-](\d{1,2})[./-](\d{4})""")
    private val year = Regex("""\b(\d{4})\b""")
    private val word = Regex("""\p{L}+""")
    private val day = Regex("""\b(\d{1,2})\b""")

    /** The first letters of month names (English, Dutch, German, Spanish, French, Italian). */
    private val months = listOf(
        listOf("jan", "ene", "gen", "janv"),
        listOf("feb", "fév", "fev", "febr"),
        listOf("mar", "mrt", "mär", "maa"),
        listOf("apr", "abr", "avr"),
        listOf("may", "mei", "mai", "mag"),
        listOf("jun", "juin", "giu"),
        listOf("jul", "juil", "lug"),
        listOf("aug", "ago", "aoû", "aou"),
        listOf("sep", "set"),
        listOf("oct", "okt", "ott"),
        listOf("nov"),
        listOf("dec", "dez", "dic", "déc"),
    )

    fun parse(text: String, zone: ZoneId): Long? {
        val clock = time.find(text)
        var rest = if (clock != null) text.removeRange(clock.range) else text
        val at = clock?.let { m ->
            val g = m.groupValues
            if (g[1].isNotEmpty()) {
                val hour = g[1].toInt() % 12 + if (g[4].equals("p", ignoreCase = true)) 12 else 0
                LocalTime.of(hour, g[2].toInt(), g[3].ifEmpty { "0" }.toInt())
            } else {
                LocalTime.of(g[5].toInt().coerceAtMost(23), g[6].toInt(), g[7].ifEmpty { "0" }.toInt())
            }
        } ?: LocalTime.NOON
        val date = runCatching {
            iso.find(rest)?.let { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
                ?: numeric.find(rest)?.let { LocalDate.of(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
                ?: run {
                    val y = year.find(rest) ?: return null
                    rest = rest.removeRange(y.range)
                    val month = word.findAll(rest).firstNotNullOfOrNull { month(it.value) } ?: return null
                    val d = day.find(rest)?.groupValues?.get(1)?.toInt() ?: return null
                    LocalDate.of(y.groupValues[1].toInt(), month, d)
                }
        }.getOrNull() ?: return null
        return date.atTime(at).atZone(zone).toInstant().toEpochMilli()
    }

    private fun month(word: String): Int? {
        val w = word.lowercase()
        if (w.length < 3) return null
        // The longest name that fits, so "juil" isn't "jul"-y "juin".
        return months.withIndex().flatMap { (i, names) -> names.map { it to i + 1 } }
            .filter { (name, _) -> w.startsWith(name) }
            .maxByOrNull { it.first.length }?.second
    }
}
