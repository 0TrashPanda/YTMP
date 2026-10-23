package dev.trashpanda.ytmp.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Looking up the song that has to play goes first: other work on the same source (a radio,
 * lyrics, a page, a search) waits while a stream is looked up, up to [maxWaitMs], so it doesn't
 * slow that down. For a source that does everything in one place, like the phone's Python.
 */
class PlaybackFirst(private val maxWaitMs: Long = 8_000) {
    private val lookups = MutableStateFlow(0)

    /** A stream lookup: goes right away, and holds up [other] work while it runs. */
    suspend fun <T> playback(block: suspend () -> T): T {
        lookups.update { it + 1 }
        try {
            return block()
        } finally {
            lookups.update { it - 1 }
        }
    }

    /** Anything else: waits until no stream is being looked up (or for [maxWaitMs] at most). */
    suspend fun <T> other(block: suspend () -> T): T {
        withTimeoutOrNull(maxWaitMs) { lookups.first { it == 0 } }
        return block()
    }
}
