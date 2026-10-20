package dev.trashpanda.ytmp.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the current song is: a position at a moment (the anchor), moving on from there while
 * it plays. Clients follow the same numbers ([dev.trashpanda.ytmp.protocol.PlaybackStatus]).
 */
internal class PlaybackClock(private val clock: () -> Long) {
    private var positionAtAnchor = 0L
    private var anchorTime = 0L

    /** The position now; it moves on while [moving]. */
    fun position(moving: Boolean): Long = if (moving) positionAtAnchor + (clock() - anchorTime) else positionAtAnchor

    /** The song is at [positionMs] now. */
    fun set(positionMs: Long) {
        positionAtAnchor = positionMs
        anchorTime = clock()
    }

    /** It moves on from where it was left, starting now (it starts or resumes). */
    fun resume() {
        anchorTime = clock()
    }
}

/**
 * Starting a new song together (docs/features/playback-and-outputs.md): it waits until every
 * player that says so ([dev.trashpanda.ytmp.protocol.Command.PlayerReady]) has loaded it, then
 * everyone starts it at once. Who is waited for: a participant's player (their id), their
 * speakers (`id:speakers`), an output (`o:id`). Only used with the room locked.
 */
internal class StartTogether(private val clock: () -> Long) {
    /** The song waits for players now. */
    var waiting = false
        private set
    private var since = 0L
    private val ready = HashSet<String>()
    private var job: Job? = null

    /** How long it has waited. */
    val waitedMs: Long get() = clock() - since

    /** Of the players that [expected] to load the song, the ones that haven't said so yet. */
    fun missing(expected: List<String>): List<String> = expected.filter { it !in ready }

    fun has(who: String) {
        ready += who
    }

    /** A new song: nobody has it yet, and nothing waits. */
    fun reset() {
        stop()
        ready.clear()
    }

    /** Starts waiting; [check] runs (locked with [mutex]) every little while to see whether it's done. */
    fun wait(scope: CoroutineScope, mutex: Mutex, check: () -> Unit) {
        waiting = true
        since = clock()
        job = scope.launch {
            while (waiting) {
                delay(CHECK_MS)
                mutex.withLock { check() }
            }
        }
    }

    fun stop() {
        waiting = false
        job?.cancel()
        job = null
    }

    private companion object {
        /** How often a waiting song checks whether it waited long enough, or nobody's left to wait for. */
        const val CHECK_MS = 100L
    }
}
