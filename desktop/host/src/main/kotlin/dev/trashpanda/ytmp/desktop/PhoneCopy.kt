package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.host.CachedSongFile
import dev.trashpanda.ytmp.host.CachedSongFiles
import dev.trashpanda.ytmp.host.SongCache
import dev.trashpanda.ytmp.protocol.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardCopyOption
import java.time.Duration
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Songs from your phone (docs/implementation/desktop-app.md#songs-from-your-phone): the phones
 * on this computer's network (they announce themselves as `_ytmp-device._tcp` while the app
 * runs), and copying a phone's song cache into this computer's, over Wi-Fi. The phone only
 * hands its songs to its owner: the window gets a token for it from your server.
 */
class PhoneCopy(private val cache: SongCache, private val scope: CoroutineScope) {
    @Serializable
    data class Phone(val id: String, val name: String, val url: String)

    /** One copy, as the window shows it. */
    @Serializable
    data class Status(
        val phone: String,
        val running: Boolean,
        /** Songs on the phone that aren't here yet (and fit), copied so far, and their size. */
        val total: Int = 0,
        val copied: Int = 0,
        val bytes: Long = 0,
        /** Songs that were here already. */
        val here: Int = 0,
        /** Songs left out because this computer's cache was full. */
        val noRoom: Int = 0,
        val current: String? = null,
        val error: String? = null,
    )

    @Serializable
    data class State(val phones: List<Phone>, val copy: Status?)

    private val phones = java.util.concurrent.ConcurrentHashMap<String, Phone>()
    @Volatile private var status: Status? = null
    private var job: Job? = null

    fun state() = State(phones.values.sortedBy { it.name }, status)

    /** Looks for phones on every network interface until the app ends. */
    fun discover() {
        JmmDNS.Factory.getInstance().addServiceListener(
            "_ytmp-device._tcp.local.",
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) = Unit

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                    val name = info.getPropertyString("name") ?: info.name
                    phones[info.name] = Phone(info.name, name, "http://$host:${info.port}")
                    log.info("Found phone {} at {}", name, host)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    phones.remove(event.name)
                }
            },
        )
    }

    fun phone(id: String): Phone? = phones[id]

    /** Copies [phone]'s songs that aren't here, as many as fit, with [token] (the owner's, for that phone). */
    @Synchronized
    fun start(phone: Phone, token: String): State {
        if (job?.isActive == true) return state()
        status = Status(phone.name, running = true)
        job = scope.launch {
            try {
                copy(phone, token)
            } catch (e: CancellationException) {
                status = status?.copy(running = false, current = null)
                throw e
            } catch (e: Exception) {
                log.info("Copying from {} stopped: {}", phone.name, e.toString())
                status = status?.copy(running = false, current = null, error = e.message ?: "The phone stopped answering")
            }
        }
        return state()
    }

    @Synchronized
    fun stop(): State {
        job?.cancel()
        return state()
    }

    private suspend fun copy(phone: Phone, token: String) {
        val list = withContext(Dispatchers.IO) { get(phone, "/api/cache/songs", token, HttpResponse.BodyHandlers.ofString()) }
        val songs = ProtocolJson.decodeFromString(CachedSongFiles.serializer(), list.body()).songs
        val here = songs.count { cache.has(it.song.id) }
        // Newest first would need the phone's plays; its order is fine. Only what fits: the cache would push songs out otherwise.
        var room = cache.maxBytes - cache.totalBytes
        val wanted = ArrayList<CachedSongFile>()
        var noRoom = 0
        for (s in songs.filter { !cache.has(it.song.id) }) if (s.size <= room) { wanted += s; room -= s.size } else noRoom++
        status = Status(phone.name, running = true, total = wanted.size, here = here, noRoom = noRoom)
        log.info("Copying {} songs from {} ({} here already, {} don't fit)", wanted.size, phone.name, here, noRoom)
        var copied = 0
        var bytes = 0L
        for (s in wanted) {
            status = status?.copy(current = s.song.title)
            val kept = cache.take(s.song) { file ->
                withContext(Dispatchers.IO) {
                    val answer = get(phone, "/api/cache/songs/" + URLEncoder.encode(s.song.id, "UTF-8"), token, HttpResponse.BodyHandlers.ofInputStream())
                    answer.body().use { java.nio.file.Files.copy(it, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            if (kept) {
                copied++
                bytes += s.size
            }
            status = status?.copy(copied = copied, bytes = bytes)
        }
        status = status?.copy(running = false, current = null)
        log.info("Copied {} songs ({} MB) from {}", copied, bytes / (1024 * 1024), phone.name)
    }

    private fun <T> get(phone: Phone, path: String, token: String, body: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI(phone.url + path))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val answer = http.send(request, body)
        if (answer.statusCode() == 403) throw IllegalStateException("The phone only gives its songs to its owner: log in with the phone's account")
        if (answer.statusCode() == 404 && path == "/api/cache/songs") throw IllegalStateException("Update YTMP on the phone first")
        if (answer.statusCode() >= 400) throw IllegalStateException("The phone answered ${answer.statusCode()}")
        return answer
    }

    companion object {
        private val log = LoggerFactory.getLogger(PhoneCopy::class.java)
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }
}
