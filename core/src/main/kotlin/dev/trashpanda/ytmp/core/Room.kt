package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.NowPlaying
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Participant
import dev.trashpanda.ytmp.protocol.PlaybackStatus
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.QueueItemResult
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One room: participants, the queue, the history and the playback clock.
 *
 * All state changes happen under [mutex], and every change is broadcast as an [Event] with
 * an increasing sequence number. The host is the only one that decides what plays; clients
 * only follow [PlaybackStatus].
 */
class Room(
    val code: String,
    val name: String,
    val ownerToken: String,
    private val streams: StreamResolver,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Member(
        val id: String,
        val token: String,
        var name: String,
        val isOwner: Boolean,
        var outbox: Outbox?,
        var listening: Boolean = false,
        var offlineSince: Long? = null,
    ) {
        fun toParticipant() = Participant(id, name, isOwner, online = outbox != null, listening = listening)
    }

    private val mutex = Mutex()
    private val members = LinkedHashMap<String, Member>()
    private val queue = ArrayList<QueueItem>()
    private val history = ArrayList<QueueItem>()
    private var seq = 0L

    private var current: QueueItem? = null
    private var streamUrl: String? = null

    /** The user wants music to play. It actually plays once the stream is resolved. */
    private var wantPlaying = false
    private var positionAtAnchor = 0L
    private var anchorTime = 0L

    private var resolveJob: Job? = null
    private var endJob: Job? = null

    /** Last time someone was connected. Used to delete empty rooms. */
    @Volatile
    var lastActive: Long = clock()
        private set

    suspend fun join(hello: ClientMessage.Hello, outbox: Outbox): String? = mutex.withLock {
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            outbox.send(ServerMessage.Rejected(RejectReason.VERSION_MISMATCH))
            return null
        }
        val name = hello.guestName.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            outbox.send(ServerMessage.Rejected(RejectReason.INVALID_NAME))
            return null
        }

        val returning = members.values.firstOrNull { it.token == hello.guestToken }
        val member = returning ?: Member(
            id = Ids.short(),
            token = Ids.token(),
            name = name,
            isOwner = hello.ownerToken == ownerToken,
            outbox = null,
        ).also { members[it.id] = it }

        member.outbox?.takeIf { it !== outbox }?.send(ServerMessage.Rejected(RejectReason.REPLACED))
        member.outbox = outbox
        member.name = name
        member.offlineSince = null
        lastActive = clock()

        outbox.send(ServerMessage.Welcome(member.id, member.token, seq, snapshot()))
        emit(if (returning == null) Event.ParticipantJoined(member.toParticipant()) else Event.ParticipantUpdated(member.toParticipant()))
        member.id
    }

    suspend fun disconnect(participantId: String, outbox: Outbox) = mutex.withLock {
        val member = members[participantId] ?: return@withLock
        if (member.outbox !== outbox) return@withLock
        member.outbox = null
        member.listening = false
        member.offlineSince = clock()
        lastActive = clock()
        emit(Event.ParticipantUpdated(member.toParticipant()))
    }

    suspend fun handle(participantId: String, message: ClientMessage) {
        mutex.withLock {
            val member = members[participantId] ?: return@withLock
            val outbox = member.outbox ?: return@withLock
            when (message) {
                is ClientMessage.Ping -> outbox.send(ServerMessage.Pong(message.clientTime, clock()))
                is ClientMessage.RequestSnapshot -> outbox.send(ServerMessage.Snapshot(seq, snapshot()))
                is ClientMessage.CommandMessage -> {
                    val error = runCatching { execute(member, message.command) }
                        .fold({ it }, { e -> if (e is CancellationException) throw e else ErrorInfo(ErrorCode.INVALID, e.message ?: "error") })
                    outbox.send(ServerMessage.Result(message.id, error))
                }
                is ClientMessage.Hello -> Unit
            }
        }
    }

    /** Removes participants that have been offline for too long. Returns true if nobody is left online. */
    suspend fun cleanup(offlineTimeoutMs: Long): Boolean = mutex.withLock {
        val now = clock()
        val expired = members.values.filter { it.offlineSince?.let { since -> now - since > offlineTimeoutMs } == true }
        for (member in expired) {
            members.remove(member.id)
            emit(Event.ParticipantLeft(member.id))
        }
        members.values.none { it.outbox != null }
    }

    fun close() {
        resolveJob?.cancel()
        endJob?.cancel()
    }

    // --- commands ---------------------------------------------------------------------

    /** Runs a command. Returns an error, or null on success. Called with [mutex] held. */
    private fun execute(member: Member, command: Command): ErrorInfo? {
        when (command) {
            is Command.AddSongs -> {
                if (command.songs.isEmpty()) return invalid("No songs")
                val items = command.songs.map { newItem(it, member) }
                val index = if (command.position == QueuePosition.NEXT) 0 else queue.size
                queue.addAll(index, items)
                emit(Event.QueueItemsAdded(items, index))
                if (current == null) {
                    wantPlaying = true
                    playNextFromQueue()
                }
            }
            is Command.PlayNow -> {
                retireCurrent(QueueItemResult.SKIPPED)
                wantPlaying = true
                setCurrent(newItem(command.song, member))
            }
            is Command.RemoveQueueItem -> {
                if (!queue.removeIf { it.itemId == command.itemId }) return notFound()
                emit(Event.QueueItemRemoved(command.itemId))
            }
            is Command.MoveQueueItem -> {
                val from = queue.indexOfFirst { it.itemId == command.itemId }
                if (from < 0) return notFound()
                val item = queue.removeAt(from)
                val to = command.toIndex.coerceIn(0, queue.size)
                queue.add(to, item)
                emit(Event.QueueItemMoved(item.itemId, to))
            }
            is Command.JumpTo -> return jumpTo(command.itemId)
            Command.Play -> {
                if (current == null) {
                    if (queue.isEmpty()) return invalid("The queue is empty")
                    wantPlaying = true
                    playNextFromQueue()
                } else if (!wantPlaying) {
                    wantPlaying = true
                    anchorTime = clock()
                    emitPlayback()
                }
            }
            Command.Pause -> if (wantPlaying) {
                positionAtAnchor = position()
                anchorTime = clock()
                wantPlaying = false
                emitPlayback()
            }
            Command.Skip -> {
                if (current == null) return invalid("Nothing is playing")
                retireCurrent(QueueItemResult.SKIPPED)
                playNextFromQueue()
            }
            Command.Previous -> {
                val last = history.lastOrNull()
                if (position() > PREVIOUS_RESTART_MS || last == null) seek(0) else return jumpTo(last.itemId)
            }
            is Command.Seek -> {
                if (current == null) return invalid("Nothing is playing")
                seek(command.positionMs)
            }
            is Command.SetListening -> {
                member.listening = command.on
                emit(Event.ParticipantUpdated(member.toParticipant()))
            }
        }
        return null
    }

    private fun jumpTo(itemId: String): ErrorInfo? {
        val queueIndex = queue.indexOfFirst { it.itemId == itemId }
        if (queueIndex >= 0) {
            // Songs before the target are skipped, like jumping ahead in YTM.
            retireCurrent(QueueItemResult.SKIPPED)
            repeat(queueIndex) {
                val skipped = queue.removeAt(0)
                emit(Event.QueueItemRemoved(skipped.itemId))
                appendHistory(skipped, QueueItemResult.SKIPPED)
            }
            wantPlaying = true
            playNextFromQueue()
            return null
        }

        val historyIndex = history.indexOfFirst { it.itemId == itemId }
        if (historyIndex < 0) return notFound()
        // Jumping back: everything after the target becomes upcoming again.
        val target = history[historyIndex]
        val reopened = history.subList(historyIndex + 1, history.size).toList() + listOfNotNull(current)
        for (item in listOf(target) + reopened.filter { it !== current }) {
            history.remove(item)
            emit(Event.HistoryItemRemoved(item.itemId))
        }
        if (reopened.isNotEmpty()) {
            val items = reopened.map { it.copy(result = null) }
            queue.addAll(0, items)
            emit(Event.QueueItemsAdded(items, 0))
        }
        current = null
        wantPlaying = true
        setCurrent(target.copy(result = null))
        return null
    }

    // --- playback ---------------------------------------------------------------------

    private fun position(): Long {
        val playing = wantPlaying && streamUrl != null
        return if (playing) positionAtAnchor + (clock() - anchorTime) else positionAtAnchor
    }

    private fun seek(positionMs: Long) {
        val duration = current?.song?.durationMs ?: 0
        positionAtAnchor = positionMs.coerceIn(0, maxOf(0, duration))
        anchorTime = clock()
        emitPlayback()
    }

    private fun retireCurrent(result: QueueItemResult) {
        val item = current ?: return
        current = null
        appendHistory(item, result)
    }

    private fun appendHistory(item: QueueItem, result: QueueItemResult) {
        val done = item.copy(result = result)
        history.add(done)
        emit(Event.HistoryAppended(done))
    }

    private fun playNextFromQueue() {
        val next = queue.removeFirstOrNull()
        if (next != null) emit(Event.QueueItemRemoved(next.itemId))
        setCurrent(next)
    }

    /** Makes [item] the current song and starts resolving its stream. */
    private fun setCurrent(item: QueueItem?) {
        resolveJob?.cancel()
        current = item
        streamUrl = null
        positionAtAnchor = 0
        anchorTime = clock()
        if (item == null) wantPlaying = false
        emit(Event.NowPlayingChanged(item))
        emitPlayback()
        if (item == null) return

        resolveJob = scope.launch {
            val url = runCatching { streams.resolveStream(item.song.id) }
            val next = mutex.withLock {
                if (current?.itemId != item.itemId) return@withLock null
                url.onSuccess {
                    streamUrl = it
                    anchorTime = clock()
                    emit(Event.StreamReady(item.itemId, it))
                    emitPlayback()
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    emit(Event.Notice("Couldn't play \"${item.song.title}\": ${e.message}"))
                    retireCurrent(QueueItemResult.SKIPPED)
                    playNextFromQueue()
                }
                queue.firstOrNull()
            }
            // Warm up the next song so it starts quickly.
            if (next != null) runCatching { streams.resolveStream(next.song.id) }
        }
    }

    private fun emitPlayback() {
        emit(Event.PlaybackChanged(playbackStatus()))
        scheduleEnd()
    }

    /** Moves to the next song when the current one ends. */
    private fun scheduleEnd() {
        endJob?.cancel()
        val item = current ?: return
        // Without a known duration the song can't end on its own; it plays until skipped.
        if (!wantPlaying || streamUrl == null || item.song.durationMs <= 0) return
        val remaining = item.song.durationMs - position()
        endJob = scope.launch {
            delay(maxOf(0, remaining))
            mutex.withLock {
                if (current?.itemId != item.itemId) return@withLock
                retireCurrent(QueueItemResult.PLAYED)
                playNextFromQueue()
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------

    private fun playbackStatus() = PlaybackStatus(
        playing = wantPlaying && streamUrl != null,
        positionMs = position(),
        hostTimeMs = clock(),
    )

    private fun snapshot() = RoomState(
        room = RoomInfo(code, name),
        participants = members.values.map { it.toParticipant() },
        queue = queue.toList(),
        history = history.takeLast(SNAPSHOT_HISTORY),
        nowPlaying = current?.let { NowPlaying(it, streamUrl) },
        playback = playbackStatus(),
    )

    private fun newItem(song: Song, member: Member) =
        QueueItem(Ids.short(), song, member.id, member.name, clock(), result = null)

    private fun emit(event: Event) {
        val message = ServerMessage.EventMessage(++seq, event)
        for (member in members.values) member.outbox?.send(message)
    }

    private fun invalid(message: String) = ErrorInfo(ErrorCode.INVALID, message)

    private fun notFound() = ErrorInfo(ErrorCode.NOT_FOUND, "Not in the queue")

    companion object {
        const val MAX_NAME_LENGTH = 32
        const val PREVIOUS_RESTART_MS = 3_000L
        const val SNAPSHOT_HISTORY = 200
    }
}
