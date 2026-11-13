package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.ErrorCode
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Songs from the music on a desktop app's computer (docs/implementation/apps/desktop-app.md#music-and-songs-on-this-computer-):
 * `local:dev-<device>:s:<key>`. That computer plays them from its own disk. Only when another
 * player or a speaker loads one (`/api/audio`) does the server ask the computer for it (the app
 * waits for that at [wanted]) and keep it a while, so listening alone nothing leaves the computer.
 */
class DeviceSongs(private val dir: File, private val maxBytes: Long = MAX_BYTES, private val clock: () -> Long = System::currentTimeMillis) {
    private class Stored(val file: File, val type: String, var used: Long)

    /** Per device: the SHA-256 of the secret it first came with (the app keeps it). */
    private val secrets = ConcurrentHashMap<String, String>()
    private val wantedBy = ConcurrentHashMap<String, MutableStateFlow<Set<String>>>()
    private val stored = ConcurrentHashMap<String, Stored>()
    /** Moves on with every song that comes in. */
    private val arrivals = MutableStateFlow(0L)

    init {
        // What came in before a restart is gone from memory: start empty.
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
    }

    fun streams(inner: StreamResolver): StreamResolver = object : StreamResolver {
        override suspend fun resolveStream(songId: String) = if (deviceOf(songId) != null) path(songId) else inner.resolveStream(songId)

        override suspend fun resolve(songId: String) = if (deviceOf(songId) != null) ResolvedStream(path(songId), durationMs = null) else inner.resolve(songId)
    }

    fun audio(inner: AudioProxy?): AudioProxy = AudioProxy { call, songId, range ->
        if (deviceOf(songId) == null) return@AudioProxy inner?.respond(call, songId, range) ?: call.respond(HttpStatusCode.NotFound)
        val song = fetchStored(songId, WAIT_MS)
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCode.NOT_FOUND, "The computer with this song didn't send it")
        SongCache.respondFile(call, song.file, song.type, range)
    }

    /** The song once it's here: asks its computer for it when it isn't, and waits up to [waitMs]. */
    internal suspend fun fetch(songId: String, waitMs: Long): File? = fetchStored(songId, waitMs)?.file

    private suspend fun fetchStored(songId: String, waitMs: Long): Stored? {
        stored[songId]?.let { it.used = clock(); return it }
        val device = deviceOf(songId) ?: return null
        log.info("Device songs: asking {} for {} (another player needs it)", device, songId)
        wanted(device).update { it + songId }
        return withTimeoutOrNull(waitMs) { arrivals.first { stored[songId] != null }; stored[songId] }
            ?.also { it.used = clock() }
    }

    private fun wanted(device: String) = wantedBy.getOrPut(device) { MutableStateFlow(emptySet()) }

    /** The songs [device] should send, waiting up to [waitMs] for one. */
    suspend fun waitForWanted(device: String, waitMs: Long = POLL_MS): List<String> {
        val flow = wanted(device)
        withTimeoutOrNull(waitMs) { flow.first { it.isNotEmpty() } }
        return flow.getAndUpdate { emptySet() }.toList()
    }

    /** A song from its computer: [type] its audio type, the file in [input]. */
    suspend fun receive(songId: String, type: String, input: java.io.InputStream): Boolean = withContext(Dispatchers.IO) {
        val name = sha256(songId)
        val part = File(dir, "$name.part")
        var size = 0L
        part.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                if (size > MAX_FILE_BYTES) {
                    out.close()
                    part.delete()
                    return@withContext false
                }
                out.write(buffer, 0, n)
            }
        }
        val file = File(dir, name)
        if (!part.renameTo(file)) {
            file.delete()
            part.renameTo(file)
        }
        stored[songId] = Stored(file, type, clock())
        trim(keep = songId)
        arrivals.update { it + 1 }
        log.info("Device songs: {} came in ({} KB)", songId, size / 1024)
        true
    }

    /** Over [maxBytes], or unused for [KEEP_MS]: the least recently used go. */
    private fun trim(keep: String) {
        val now = clock()
        var total = stored.values.sumOf { it.file.length() }
        for ((id, song) in stored.entries.sortedBy { it.value.used }) {
            if (id == keep) continue
            if (total <= maxBytes && now - song.used < KEEP_MS) continue
            total -= song.file.length()
            song.file.delete()
            stored.remove(id)
        }
    }

    /** The first secret a device comes with is its own from then on. */
    fun allowed(device: String, secret: String?): Boolean {
        if (secret == null || secret.length < 32 || !DEVICE.matches(device)) return false
        val hash = sha256(secret)
        return secrets.putIfAbsent(device, hash)?.let { MessageDigest.isEqual(it.toByteArray(), hash.toByteArray()) } ?: true
    }

    @Serializable
    data class Wanted(val songs: List<String>)

    companion object {
        private val log = LoggerFactory.getLogger(DeviceSongs::class.java)
        private val DEVICE = Regex("[a-z0-9]{8,64}")
        private val SONG = Regex("""local:dev-([a-z0-9]{8,64}):s:[A-Za-z0-9_-]{1,64}""")

        /** How long a player's request waits for the computer to send the song. */
        const val WAIT_MS = 60_000L
        /** How long the app's question waits for a song to be wanted. */
        const val POLL_MS = 25_000L
        const val KEEP_MS = 24 * 60 * 60_000L
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_FILE_BYTES = 500L * 1024 * 1024

        /** The device a song of a computer's music is on; null for any other song. */
        fun deviceOf(songId: String): String? = SONG.matchEntire(songId)?.groupValues?.get(1)

        fun path(songId: String) = "/api/audio/" + URLEncoder.encode(songId, "UTF-8")

        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** The desktop app asks which of its songs are wanted, and sends them (`/api/devices/…`). */
fun Route.deviceSongRoutes(songs: DeviceSongs) {
    get("/devices/{device}/wanted") {
        val device = call.parameters["device"]!!
        if (!songs.allowed(device, call.request.header("X-Device-Secret"))) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Not this device")
        call.respond(DeviceSongs.Wanted(songs.waitForWanted(device)))
    }
    put("/devices/{device}/songs/{songId}") {
        val device = call.parameters["device"]!!
        val songId = call.parameters["songId"]!!
        if (!songs.allowed(device, call.request.header("X-Device-Secret"))) throw ApiException(HttpStatusCode.Forbidden, ErrorCode.PERMISSION_DENIED, "Not this device")
        if (DeviceSongs.deviceOf(songId) != device) throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "Not a song of this device")
        val type = call.request.header("Content-Type")?.takeIf { it.startsWith("audio/") } ?: "application/octet-stream"
        val ok = songs.receive(songId, type, call.receiveChannel().toInputStream())
        if (!ok) throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCode.INVALID, "That file is too big")
        call.respond(HttpStatusCode.NoContent)
    }
}
