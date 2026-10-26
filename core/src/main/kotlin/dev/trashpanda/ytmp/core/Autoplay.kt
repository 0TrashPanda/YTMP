package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.QueueItemOrigin
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.Job

/**
 * The autoplay queue (docs/features/queue.md#autoplay-queue): what plays when the queue runs
 * out, refilled with a radio from its last song so it never runs dry. The room fills it; this
 * keeps its state. Only used with the room locked.
 */
internal class Autoplay(private val clock: () -> Long) {
    val items = ArrayList<QueueItem>()
    /** Set by Autoplay from here (or a radio, or a playlist): it keeps going even with the autoplay setting off. */
    var seed: Song? = null
    /** Who chose it (the autoplay songs are theirs), or null for the room's own autoplay. */
    var by: Pair<String, String>? = null
    /** Songs being fetched for it. */
    var job: Job? = null
    /** The queue ran out while autoplay was still loading: start playing once it's there. */
    var startWhenLoaded = false
    /** Someone cleared the autoplay queue: don't refill it until something new is played or started. */
    var dismissed = false

    val loading: Boolean get() = job?.isActive == true

    /** It plays when the queue runs out: there's a radio, nobody cleared it, and it's on (or started on purpose). */
    fun active(hasRadio: Boolean, setting: Boolean) = hasRadio && !dismissed && (seed != null || setting)

    fun changed() = Event.AutoplayChanged(seed, items.toList())

    /** Empties it (and stops loading). False when there was nothing to empty. */
    fun clear(): Boolean {
        job?.cancel()
        startWhenLoaded = false
        if (items.isEmpty() && seed == null) return false
        items.clear()
        seed = null
        by = null
        return true
    }

    /** From now on it's [songs], started by [chosenBy] from [from]. */
    fun replace(from: Song, chosenBy: Pair<String, String>, songs: List<Song>, origin: QueueItemOrigin) {
        dismissed = false
        seed = from
        by = chosenBy
        items.clear()
        items += songs.distinctBy { it.id }.map { item(it, origin) }
    }

    fun add(songs: List<Song>, origin: QueueItemOrigin = QueueItemOrigin.AUTOPLAY) {
        items += songs.map { item(it, origin) }
    }

    /** [items] someone added, at the start (they play before the radio goes on). */
    fun addFirst(added: List<QueueItem>) {
        dismissed = false
        items.addAll(0, added)
    }

    private fun item(song: Song, origin: QueueItemOrigin) =
        QueueItem(Ids.short(), song, by?.first ?: "autoplay", by?.second ?: "Autoplay", clock(), result = null, origin = origin)
}
