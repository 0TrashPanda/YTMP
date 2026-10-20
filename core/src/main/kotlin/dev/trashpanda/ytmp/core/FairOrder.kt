package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.QueueItemOrigin

/**
 * Fair order (docs/features/queue.md#fair-ordering): the queue in rounds, one song per person
 * per round. [current] is the song playing now: its person has had their turn this round.
 */
internal object FairOrder {
    /**
     * Each queued song's round in fair order: the how-manieth song of the person who added it.
     * The person whose song plays now has had their turn: their first queued song is round 1.
     */
    fun rounds(items: List<QueueItem>, current: QueueItem?): List<Int> {
        val count = HashMap<String, Int>()
        current?.takeIf { it.origin != QueueItemOrigin.AUTOPLAY }?.let { count[it.addedBy] = 1 }
        return items.map { item -> (count.merge(item.addedBy, 1, Int::plus) ?: 1) - 1 }
    }

    /**
     * Where a new song by [personId] goes in fair order: in the next round where they have no
     * song yet, after everyone else's song in that round. Someone with nothing queued lands
     * near the top, however long the queue is.
     */
    fun indexFor(personId: String, queue: List<QueueItem>, current: QueueItem?): Int {
        val rounds = rounds(queue, current)
        val theirs = queue.count { it.addedBy == personId } +
            if (current?.let { it.addedBy == personId && it.origin != QueueItemOrigin.AUTOPLAY } == true) 1 else 0
        return rounds.indexOfLast { it <= theirs } + 1
    }

    /** [items] in fair order: round by round, each person's songs in their order. */
    fun inRounds(items: List<QueueItem>, current: QueueItem?): List<QueueItem> {
        val rounds = rounds(items, current)
        return items.indices.sortedWith(compareBy({ rounds[it] }, { it })).map { items[it] }
    }

    /**
     * Shuffle: the songs in a random order. With [fair] it keeps the rounds: each person's songs
     * are shuffled, and so is the order within each round.
     */
    fun shuffled(items: List<QueueItem>, current: QueueItem?, fair: Boolean): List<QueueItem> {
        if (!fair) return items.shuffled()
        val mixed = items.groupBy { it.addedBy }.values.flatMap { it.shuffled() }
        val rounds = rounds(mixed, current)
        val luck = mixed.map { Math.random() }
        return mixed.indices.sortedWith(compareBy({ rounds[it] }, { luck[it] })).map { mixed[it] }
    }
}
