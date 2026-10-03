package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.SearchSuggestions
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Remembers search results and suggestions, radios, the home page, artist, album, playlist and podcast pages for a while: browsing back and
 * forth between an artist and their albums is instant, and YouTube gets fewer requests
 * (which helps against its "not a bot" check). Requests for the same thing at the same time
 * share one fetch. Failures aren't remembered.
 */
class CachedSource(
    private val search: SongSearch,
    private val radio: RadioSource,
    private val catalog: CatalogSource,
    private val suggest: SearchSuggestions? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : SongSearch, RadioSource, CatalogSource, SearchSuggestions {
    private val searches = TtlCache<Pair<SearchType, String>, SearchPage>(200, 30.minutes, clock)
    private val radios = TtlCache<String, List<Song>>(200, 1.hours, clock)
    private val artists = TtlCache<String, ArtistPage>(100, 6.hours, clock)
    private val albums = TtlCache<String, AlbumPage>(300, 6.hours, clock)
    private val playlists = TtlCache<String, PlaylistPage>(100, 1.hours, clock)
    // Short: new episodes should show up soon.
    private val podcasts = TtlCache<String, PodcastPage>(100, 10.minutes, clock)
    private val homes = TtlCache<Unit, HomePage>(1, 30.minutes, clock)
    private val suggestionLists = TtlCache<String, List<String>>(500, 1.hours, clock)

    override suspend fun search(query: String, type: SearchType) =
        searches.get(type to query.trim().lowercase()) { search.search(query, type) }

    override suspend fun suggestions(query: String): List<String> {
        val source = suggest ?: return emptyList()
        return suggestionLists.get(query.lowercase()) { source.suggestions(query) }
    }

    override suspend fun radio(seedSongId: String) = radios.get(seedSongId) { radio.radio(seedSongId) }

    override suspend fun artist(id: String) = artists.get(id) { catalog.artist(id) }

    override suspend fun album(id: String) = albums.get(id) { catalog.album(id) }

    // Shorter: people edit their playlists.
    override suspend fun playlist(id: String) = playlists.get(id) { catalog.playlist(id) }

    override suspend fun podcast(id: String) = podcasts.get(id) { catalog.podcast(id) }

    override suspend fun home() = homes.get(Unit) { catalog.home() }
}

/** A small least-recently-used cache whose entries expire after [ttl]. */
class TtlCache<K : Any, V>(private val maxSize: Int, private val ttl: Duration, private val clock: () -> Long) {
    private class Entry<V>(val value: Deferred<V>, val at: Long)

    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>) = size > maxSize
    }

    suspend fun get(key: K, load: suspend () -> V): V {
        val mine = CompletableDeferred<V>()
        val existing = synchronized(entries) {
            val entry = entries[key]?.takeIf { clock() - it.at < ttl.inWholeMilliseconds && !(it.value.isCompleted && it.value.isFailed()) }
            entry?.value ?: run {
                entries[key] = Entry(mine, clock())
                null
            }
        }
        if (existing != null) return existing.await()
        return try {
            load().also { mine.complete(it) }
        } catch (e: Throwable) {
            synchronized(entries) { if (entries[key]?.value === mine) entries.remove(key) }
            mine.completeExceptionally(e)
            throw e
        }
    }

    /** Forgets everything, e.g. after signing in or out. */
    fun clear() = synchronized(entries) { entries.clear() }

    private fun Deferred<V>.isFailed() = runCatching { getCompletionExceptionOrNull() != null }.getOrDefault(false)
}
