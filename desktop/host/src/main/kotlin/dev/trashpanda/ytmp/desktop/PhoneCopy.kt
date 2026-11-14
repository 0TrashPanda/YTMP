package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.host.COPY_KEY_HEADER
import dev.trashpanda.ytmp.host.CachedSongFile
import dev.trashpanda.ytmp.host.CachedSongFiles
import dev.trashpanda.ytmp.host.CopyKey
import dev.trashpanda.ytmp.host.SongCache
import dev.trashpanda.ytmp.protocol.ProtocolJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Songs from your phone (docs/implementation/apps/desktop-app.md#songs-from-your-phone-): copying a
 * phone's songs (its song cache, downloads and own music files that are a YouTube Music song)
 * into this computer's song cache, over Wi-Fi.
 *
 * Your phones only: the window gives the ones your server says are yours, or one shows itself on
 * the network (asked, or with *Send songs to my computer* on it) with your account's mark. The
 * phone gives its songs only to its owner: the first time with a login from your server, which
 * also gets a key from the phone ([keys]); with that key it works without the server too, and a
 * phone with a key that shows itself is copied from by itself.
 */
class PhoneCopy(
    private val cache: SongCache,
    private val scope: CoroutineScope,
    private val keysFile: File,
    /** Which of the phone's songs to copy ([Which]), kept for the copies that start by themselves too. */
    private val whichFile: File,
) {
    @Serializable
    data class Phone(val id: String, val name: String, val url: String)

    /**
     * Which of the phone's songs to copy: its song cache, its local songs (downloads and its own
     * music files that are a YouTube Music song), or both. The phone's list tells them apart:
     * a cached song comes with its size, a local one without ([CachedSongFile]).
     */
    @Serializable
    enum class Which {
        @SerialName("cache") CACHE,
        @SerialName("both") BOTH,
        @SerialName("local") LOCAL;

        fun takes(song: CachedSongFile): Boolean = when (this) {
            CACHE -> song.size != null
            LOCAL -> song.size == null
            BOTH -> true
        }
    }

    /** One copy, as the window shows it. */
    @Serializable
    data class Status(
        val phone: String,
        val running: Boolean,
        /** Songs on the phone that aren't here yet, copied so far, and their size. */
        val total: Int = 0,
        val copied: Int = 0,
        val bytes: Long = 0,
        /** Songs that were here already. */
        val here: Int = 0,
        /** Songs left out because this computer's cache was full. */
        val noRoom: Int = 0,
        val current: String? = null,
        val error: String? = null,
        val finishedAt: Long? = null,
    )

    /** A phone on the network: [owner] its account's mark, [keyed]: this computer has its key. */
    @Serializable
    data class Found(val id: String, val name: String, val url: String, val owner: String?, val keyed: Boolean)

    @Serializable
    data class State(val found: List<Found>, val copy: Status?, val which: Which = Which.BOTH)

    /** Phones showing themselves on the network now, by id. */
    private val shown = ConcurrentHashMap<String, Pair<Phone, String?>>()
    /** Phone id → its key. */
    private val keys = ConcurrentHashMap<String, String>(loadKeys())
    @Volatile private var status: Status? = null
    /** Cache and local unless you pick otherwise: every song the phone keeps, also one it had downloaded before it was cached. */
    @Volatile var which: Which = runCatching { Which.valueOf(whichFile.readText().trim()) }.getOrDefault(Which.BOTH)
        private set
    private var job: Job? = null

    /** The phones on the network, only [account]'s (by its mark) or ones this computer has a key for. */
    fun state(account: String?): State {
        val mark = account?.let(::ownerMark)
        val mine = shown.values.filter { (phone, owner) -> (mark != null && owner == mark) || keys.containsKey(phone.id) }
            .map { (phone, owner) -> Found(phone.id, phone.name, phone.url, owner, keys.containsKey(phone.id)) }
        return State(mine.sortedBy { it.name }, status, which)
    }

    /** Looks for phones that show themselves, on every network interface, until the app ends. */
    fun discover() {
        JmmDNS.Factory.getInstance().addServiceListener(
            "_ytmp-device._tcp.local.",
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) = Unit

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                    val id = info.getPropertyString("id") ?: return
                    val phone = Phone(id, info.getPropertyString("name") ?: info.name, "http://$host:${info.port}")
                    val before = shown.put(id, phone to info.getPropertyString("owner"))
                    if (before == null) log.info("Phone {} shows itself at {}", phone.name, host)
                    // Asked, or Send songs to my computer on the phone: with its key, copy at once.
                    if (before == null && keys.containsKey(id)) start(phone, token = null)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    shown.values.removeIf { it.first.name == event.name || it.first.id == event.info?.getPropertyString("id") }
                }
            },
        )
    }

    /**
     * Copies [phone]'s songs that aren't here, as many as fit: with its key, else with [token]
     * (your login for that phone, from your server), which also gets a key for next time.
     */
    @Synchronized
    fun start(phone: Phone, token: String?, which: Which? = null): State {
        if (job?.isActive == true) return state(null)
        which?.let(::choose)
        if (cache.maxBytes <= 0) {
            status = Status(phone.name, running = false, error = "Turn the song cache on first", finishedAt = System.currentTimeMillis())
            return state(null)
        }
        status = Status(phone.name, running = true)
        job = scope.launch {
            try {
                copy(phone, token)
            } catch (e: CancellationException) {
                status = status?.copy(running = false, current = null, finishedAt = System.currentTimeMillis())
                throw e
            } catch (e: Exception) {
                log.info("Copying from {} stopped: {}", phone.name, e.toString())
                status = status?.copy(running = false, current = null, error = e.message ?: "The phone stopped answering", finishedAt = System.currentTimeMillis())
            }
        }
        return state(null)
    }

    /** Which songs to copy from now on (also when a phone with a key shows itself). */
    @Synchronized
    fun choose(which: Which) {
        this.which = which
        runCatching {
            whichFile.parentFile?.mkdirs()
            whichFile.writeText(which.name)
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
    }

    private suspend fun copy(phone: Phone, token: String?) {
        val auth = withContext(Dispatchers.IO) { login(phone, token) }
        val list = withContext(Dispatchers.IO) { get(phone, "/api/cache/songs", auth, HttpResponse.BodyHandlers.ofString()) }
        val which = which
        val songs = ProtocolJson.decodeFromString(CachedSongFiles.serializer(), list.body()).songs.distinctBy { it.song.id }.filter(which::takes)
        val here = songs.count { cache.has(it.song.id) }
        val wanted = songs.filter { !cache.has(it.song.id) }
        status = Status(phone.name, running = true, total = wanted.size, here = here)
        log.info("Copying up to {} songs from {} ({} here already; {})", wanted.size, phone.name, here, which)
        var copied = 0
        var bytes = 0L
        var noRoom = 0
        for (s in wanted) {
            status = status?.copy(current = s.song.title)
            val answer = withContext(Dispatchers.IO) {
                get(phone, "/api/cache/songs/" + URLEncoder.encode(s.song.id, "UTF-8"), auth, HttpResponse.BodyHandlers.ofInputStream())
            }
            val size = answer.headers().firstValueAsLong("Content-Length").orElse(s.size ?: 0)
            // Only what fits in what's left: the cache would push songs out otherwise.
            if (size <= 0 || size > cache.maxBytes - cache.totalBytes) {
                withContext(Dispatchers.IO) { answer.body().close() }
                noRoom++
                status = status?.copy(noRoom = noRoom)
                continue
            }
            val kept = cache.take(s.song) { file -> withContext(Dispatchers.IO) { answer.body().use { save(it, file) } } }
            if (!kept) withContext(Dispatchers.IO) { answer.body().close() }
            if (kept) {
                copied++
                bytes += size
            }
            status = status?.copy(copied = copied, bytes = bytes)
        }
        status = status?.copy(running = false, current = null, finishedAt = System.currentTimeMillis())
        log.info("Copied {} songs ({} MB) from {}; {} didn't fit", copied, bytes / (1024 * 1024), phone.name, noRoom)
    }

    private fun save(from: InputStream, to: File) {
        Files.copy(from, to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** How to ask [phone]: its key when this computer has one, else [token], getting a key with it. */
    private fun login(phone: Phone, token: String?): Pair<String, String> {
        keys[phone.id]?.let { return COPY_KEY_HEADER to it }
        token ?: throw IllegalStateException("Copy once while your server is up: the phone needs to know this computer first")
        val bearer = "Authorization" to "Bearer $token"
        // An older phone app has no keys: copy with the login this time.
        val answer = send(phone, HttpRequest.newBuilder(URI(phone.url + "/api/cache/songs/key")).POST(HttpRequest.BodyPublishers.noBody()), bearer, HttpResponse.BodyHandlers.ofString())
        if (answer.statusCode() == 403) throw IllegalStateException("The phone only gives its songs to its owner: log in with the phone's account")
        if (answer.statusCode() >= 400) return bearer
        val key = ProtocolJson.decodeFromString(CopyKey.serializer(), answer.body())
        keys[phone.id] = key.key
        saveKeys()
        return COPY_KEY_HEADER to key.key
    }

    private fun <T> get(phone: Phone, path: String, auth: Pair<String, String>, body: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val answer = send(phone, HttpRequest.newBuilder(URI(phone.url + path)).GET(), auth, body)
        if (answer.statusCode() == 403) {
            // A key the phone forgot (its data cleared): ask with a login next time.
            if (auth.first == COPY_KEY_HEADER && keys.remove(phone.id) != null) saveKeys()
            throw IllegalStateException("The phone only gives its songs to its owner: log in with the phone's account")
        }
        if (answer.statusCode() == 404 && path == "/api/cache/songs") throw IllegalStateException("Update YTMP on the phone first")
        if (answer.statusCode() >= 400) throw IllegalStateException("The phone answered ${answer.statusCode()}")
        return answer
    }

    private fun <T> send(phone: Phone, request: HttpRequest.Builder, auth: Pair<String, String>, body: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        http.send(request.timeout(Duration.ofMinutes(2)).header(auth.first, auth.second).build(), body)
            .also { if (it.statusCode() >= 500) log.info("{} answered {}", phone.name, it.statusCode()) }

    private fun loadKeys(): Map<String, String> =
        runCatching { ProtocolJson.decodeFromString(keysSerializer, keysFile.readText()) }.getOrDefault(emptyMap())

    @Synchronized
    private fun saveKeys() {
        keysFile.parentFile?.mkdirs()
        keysFile.writeText(ProtocolJson.encodeToString(keysSerializer, keys.toMap()))
    }

    companion object {
        private val log = LoggerFactory.getLogger(PhoneCopy::class.java)
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        private val keysSerializer = MapSerializer(String.serializer(), String.serializer())

        /** The same as the phone's (PhoneCopyLink.ownerMark): whose phone it is, without naming the account. */
        fun ownerMark(account: String): String =
            MessageDigest.getInstance("SHA-256").digest("ytmp-phone:$account".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
