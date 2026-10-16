package dev.trashpanda.ytmp.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/** What an MPD is doing, from `status` and `currentsong`. */
data class MpdStatus(
    /** play, pause or stop. */
    val state: String,
    val positionMs: Long,
    /** The current song's file or URL; null when its queue is empty. */
    val file: String?,
    /** 0–100; null when MPD has no mixer for its output (no volume control). */
    val volume: Int?,
    /** Why the last song couldn't play (e.g. it couldn't fetch or decode it), until it's cleared. */
    val error: String?,
    val receivedAt: Long,
)

/** One of MPD's audio outputs (`outputs`), e.g. "Bluetooth Speaker". */
data class MpdOutput(val name: String, val enabled: Boolean)

class MpdException(message: String) : Exception(message)

/**
 * Controls one MPD (Music Player Daemon) with its text protocol on port 6600: a song URL in its
 * queue, play, pause, seek, volume. MPD fetches the audio itself. One short connection per call.
 * See docs/implementation/mpd.md.
 */
class MpdPlayer(val host: String, private val port: Int = DEFAULT_PORT) {
    /** This machine's address as MPD sees it, so it can reach our audio URLs. */
    val localAddress: InetAddress by lazy {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port))
            socket.localAddress
        }
    }

    suspend fun outputs(): List<MpdOutput> = session {
        records(command("outputs"), "outputid").map { MpdOutput(it["outputname"] ?: "MPD", it["outputenabled"] == "1") }
    }

    /** Replaces MPD's queue with the song at [url] (doesn't start it). */
    suspend fun load(url: String) = session {
        command("clearerror")
        command("clear")
        command("addid ${quote(url)}")
    }

    /** Plays the loaded song from the start. */
    suspend fun start() = session { command("play 0") }

    suspend fun resume() = session { command("pause 0") }

    suspend fun pause() = session { command("pause 1") }

    /** Stops and empties the queue, so nothing of ours is left to play. */
    suspend fun stop() = session {
        command("stop")
        command("clear")
    }

    /** Only while a song plays or is paused. */
    suspend fun seek(positionMs: Long) = session { command("seekcur ${seconds(positionMs)}") }

    suspend fun status(): MpdStatus = session {
        val status = command("status").toMap()
        val song = command("currentsong").toMap()
        MpdStatus(
            state = status["state"] ?: "stop",
            positionMs = status["elapsed"]?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0,
            file = song["file"],
            volume = status["volume"]?.toIntOrNull()?.takeIf { it >= 0 },
            error = status["error"],
            receivedAt = System.currentTimeMillis(),
        )
    }

    /** MPD's `stats`: songs, artists, db_update (when its database last changed), … */
    suspend fun stats(): Map<String, String> = session { command("stats").toMap() }

    /**
     * Every song in MPD's database with its tags (`listallinfo`). A database too big for MPD's
     * output buffer is read folder by folder (`lsinfo`).
     */
    suspend fun songs(): List<Map<String, String>> = session {
        try {
            songRecords(command("listallinfo"))
        } catch (e: MpdException) {
            val all = ArrayList<Map<String, String>>()
            val folders = ArrayDeque(listOf(""))
            while (folders.isNotEmpty()) {
                val answer = command("lsinfo ${quote(folders.removeFirst())}")
                answer.filter { it.first == "directory" }.forEach { folders += it.second }
                all += songRecords(answer)
            }
            all
        }
    }

    /** 0.0–1.0; fails when MPD has no mixer. */
    suspend fun setVolume(level: Double) = session { command("setvol ${Math.round(level.coerceIn(0.0, 1.0) * 100)}") }

    private suspend fun <T> session(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 10_000
                val connection = Connection(socket.getInputStream().bufferedReader(Charsets.UTF_8), socket.getOutputStream())
                val greeting = connection.reader.readLine()
                if (greeting?.startsWith("OK MPD") != true) throw MpdException("not an MPD")
                connection.block()
            }
        } catch (e: java.io.IOException) {
            throw MpdException(e.message ?: "no connection")
        }
    }

    private class Connection(val reader: BufferedReader, private val out: OutputStream) {
        /** Sends one command; its answer as key-value lines. */
        fun command(line: String): List<Pair<String, String>> {
            out.write("$line\n".toByteArray(Charsets.UTF_8))
            out.flush()
            val answer = ArrayList<Pair<String, String>>()
            while (true) {
                val text = reader.readLine() ?: throw MpdException("the connection closed")
                if (text == "OK") return answer
                if (text.startsWith("ACK ")) throw MpdException(ackText(text))
                val colon = text.indexOf(": ")
                if (colon > 0) answer += text.substring(0, colon) to text.substring(colon + 2)
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 6600

        /** `ACK [50@0] {play} No such song` → "No such song". */
        internal fun ackText(line: String) = line.substringAfter("} ", line.removePrefix("ACK ")).ifBlank { "error" }

        /** MPD's argument quoting. */
        internal fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        internal fun seconds(ms: Long) = String.format(Locale.ROOT, "%.3f", maxOf(0, ms) / 1000.0)

        /** The songs (`file` records) of a listing that also has folders and playlists. */
        internal fun songRecords(answer: List<Pair<String, String>>): List<Map<String, String>> {
            val all = ArrayList<MutableMap<String, String>>()
            var inSong = false
            for ((key, value) in answer) {
                when (key) {
                    "file" -> {
                        all += LinkedHashMap<String, String>().apply { put(key, value) }
                        inSong = true
                    }
                    "directory", "playlist" -> inSong = false
                    else -> if (inSong) all.last().putIfAbsent(key, value)
                }
            }
            return all
        }

        /** Splits an answer into records, each starting at the key [first]. */
        internal fun records(answer: List<Pair<String, String>>, first: String): List<Map<String, String>> {
            val all = ArrayList<MutableMap<String, String>>()
            for ((key, value) in answer) {
                if (key == first || all.isEmpty()) all += LinkedHashMap()
                all.last()[key] = value
            }
            return all
        }
    }
}
