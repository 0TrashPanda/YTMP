package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.SkipVotes

/**
 * Vote to skip ([RoomSettings.voteSkip]): who voted on the current song, and how many votes it
 * needs. Only used with the room locked.
 */
internal class SkipVote {
    private val voters = LinkedHashSet<String>()
    /** What everyone was told last. */
    private var sent: SkipVotes? = null

    fun vote(participantId: String, on: Boolean) {
        if (on) voters += participantId else voters -= participantId
    }

    /** A new song (or the room stopped voting): no votes yet. */
    fun clear() = voters.clear()

    /** Only votes of people who are still [here] and may vote count. */
    fun keepOnly(here: (String) -> Boolean) = voters.retainAll(here)

    /**
     * The vote on the current song, or null when the room doesn't vote (or nothing plays).
     * [people]: everyone here who may skip or vote, so votes are a share of the people listening along.
     */
    fun votes(settings: RoomSettings, playing: Boolean, people: Int): SkipVotes? {
        if (!settings.voteSkip || !playing) return null
        val needed = maxOf(1, (people * settings.voteSkipPercent + 99) / 100)
        return SkipVotes(voters.toList(), needed)
    }

    /** Whether [votes] is news that everyone should hear (and remembers it as told). */
    fun changed(votes: SkipVotes?): Boolean {
        if (votes == sent) return false
        sent = votes
        return true
    }
}

/** Enough people voted: the song is skipped. */
internal fun SkipVotes.passed() = voters.isNotEmpty() && voters.size >= needed
