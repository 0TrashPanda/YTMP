package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.BanInfo
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.ItemList
import dev.trashpanda.ytmp.protocol.Migrant
import dev.trashpanda.ytmp.protocol.MigrationMode
import dev.trashpanda.ytmp.protocol.MigrationOffer
import dev.trashpanda.ytmp.protocol.NowPlaying
import dev.trashpanda.ytmp.protocol.OutputInfo
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Participant
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.PlaybackStatus
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.QueueItemOrigin
import dev.trashpanda.ytmp.protocol.QueueItemResult
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomClone
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory

/**
 * One room: participants, the queue, the history and the playback clock.
 *
 * All state changes happen under [mutex], and every change is broadcast as an [Event] with
 * an increasing sequence number. The host is the only one that decides what plays; clients
 * only follow [PlaybackStatus].
 */
class Room(
    val code: String,
    name: String,
    ownerToken: String,
    visibility: RoomVisibility,
    private val streams: StreamResolver,
    private val scope: CoroutineScope,
    /** The account that owns the room: it is the owner on any device. */
    ownerAccount: String? = null,
    /** The roles the room starts with (the creator's template, or the defaults). */
    template: RoleTemplate = DefaultRoles.template,
    /** Similar songs, for Start radio and the autoplay queue. Null: no radio. */
    private val radio: RadioSource? = null,
    /**
     * The same room on all the owner's hosts (docs/features/room-sync.md): only rooms with an
     * owner account have one.
     */
    syncId: String? = ownerAccount?.let { Ids.token() },
    /** Goes up every time the room moves to another host; the highest one is the real room. */
    epoch: Int = 0,
    /** Called when the room's [info] changes, e.g. it was made public. */
    private val onInfoChanged: () -> Unit = {},
    /**
     * The room got an owner with another account (or none): it's no longer the old owner's
     * room on their other devices ([oldSyncId]). Called with the room locked, must not block.
     */
    private val onOwnerChanged: (oldAccount: String?, oldSyncId: String?) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Proves the owner (given to whoever created the room, and to a new owner). Changes when the owner does. */
    @Volatile
    var ownerToken: String = ownerToken
        private set

    @Volatile
    var ownerAccount: String? = ownerAccount
        private set

    @Volatile
    var syncId: String? = syncId
        private set

    @Volatile
    var epoch: Int = epoch
        private set

    @Volatile
    var visibility: RoomVisibility = visibility
        private set

    @Volatile
    var name: String = name
        private set

    val info: RoomInfo get() = RoomInfo(code, name, visibility, hasPassword = passwordHash != null)

    /** Joining needs this password (hashed, see [RoomPasswords]); null: none. */
    @Volatile
    private var passwordHash: String? = null

    private class Member(
        val id: String,
        val token: String,
        var name: String,
        var isOwner: Boolean,
        var outbox: Outbox?,
        var roleId: String,
        var accountId: String? = null,
        var listening: Boolean = false,
        var offlineSince: Long? = null,
        var allow: Set<Permission> = emptySet(),
        var deny: Set<Permission> = emptySet(),
        var accountToken: String? = null,
        var hideFromHistory: Boolean = false,
        /** When they joined; the longest here gets the room when its owner leaves. */
        val joinedAt: Long = 0,
    ) {
        /** Only for them: proves who they were in a clone of this room ([Migrant]). */
        val migrationKey: String by lazy { Ids.sha256("ytmp-migrate:$token") }

        /** Shown to everyone: recognises them in a clone, without giving away [migrationKey]. */
        val tag: String by lazy { Ids.sha256(migrationKey) }

        /** Extra connections for this participant (see [attach]). */
        val attached = LinkedHashSet<Outbox>()

        /**
         * Connected: the main connection, or an attached one. The app's player keeps its own
         * connection while Android freezes the page (and the page's connection times out): the
         * phone still plays, so it's still here and listening.
         */
        val online: Boolean get() = outbox != null || attached.isNotEmpty()

        /** Sends to the participant's connection and to all its attached ones. */
        fun send(message: ServerMessage) {
            outbox?.send(message)
            for (extra in attached) extra.send(message)
        }

        /** Tells all attached connections that they're done (kicked, banned, removed). */
        fun dropAttached(reason: RejectReason) {
            for (extra in attached) extra.send(ServerMessage.Rejected(reason))
            attached.clear()
        }

        fun toParticipant() = Participant(
            id, name, isOwner, online = online, listening = listening, accountId = accountId,
            roleId = roleId, allow = allow.sorted(), deny = deny.sorted(), tag = tag,
        )
    }

    /** A ban; guests are recognised by the guest token they had. */
    private class Ban(val info: BanInfo, val guestToken: String?)

    private val mutex = Mutex()
    private val members = LinkedHashMap<String, Member>()
    private val queue = ArrayList<QueueItem>()
    private val history = ArrayList<QueueItem>()
    private var seq = 0L

    /** Plays when the queue runs out. Refilled with a radio from its last song, so it never runs dry. */
    private val autoplay = ArrayList<QueueItem>()
    /** Set by Autoplay from here: the autoplay queue keeps going even with the autoplay setting off. */
    private var autoplaySeed: Song? = null

    /** Where podcast episodes were left off (song ID -> ms), to resume them. Oldest first. */
    private val episodePositions = LinkedHashMap<String, Long>()
    /** Who chose Autoplay from here (the autoplay songs are theirs), or null for the room's own autoplay. */
    private var autoplayBy: Pair<String, String>? = null
    private var autoplayJob: Job? = null
    /** The queue ran out while autoplay was still loading: start playing once it's there. */
    private var startWhenAutoplayLoaded = false
    /** Someone cleared the autoplay queue: don't refill it until something new is played or started. */
    private var autoplayDismissed = false

    /** Ranked, highest first. */
    private val roles = ArrayList<Role>()
    @Volatile
    private var settings: RoomSettings
    private val bans = ArrayList<Ban>()

    /** People from the room this one is a clone of, until they come ([Migrant]). */
    private val migrants = ArrayList<Migrant>()

    /** An invite to another room (see [Command.SendMigration]). */
    private var migration: MigrationOffer? = null

    /** Nobody who runs the room has been here for a while (see [checkAdminless]). */
    private var adminless = false
    private var adminSeenAt = clock()

    /** Never deleted for being empty. */
    val alwaysOn: Boolean get() = settings.alwaysOn

    init {
        val start = Permissions.sanitize(template)
        roles += start.roles
        settings = start.settings
    }

    private var current: QueueItem? = null
    private var streamUrl: String? = null
    private var currentStartedAt = 0L

    /** The user wants music to play. It actually plays once the stream is resolved. */
    private var wantPlaying = false
    private var positionAtAnchor = 0L
    private var anchorTime = 0L

    /** Outputs the host found, and the ones this room plays on (with their wanted volume). */
    private var availableOutputs: List<OutputDevice> = emptyList()
    private val activeOutputs = LinkedHashMap<String, Double?>()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    private var resolveJob: Job? = null
    private var endJob: Job? = null
    /** Pauses the music a while after the last person went (see [pauseWhenEveryoneGone]). */
    private var emptyJob: Job? = null

    /** Songs in a row that couldn't load; after [MAX_FAILED_IN_A_ROW] the room pauses (likely no internet). */
    private var failedInARow = 0

    /** Called (with the room locked, so it must not block) when a song finished playing. */
    @Volatile
    var onPlayFinished: ((FinishedPlay) -> Unit)? = null

    /** Last time someone was connected. Used to delete empty rooms. */
    @Volatile
    var lastActive: Long = clock()
        private set

    /** The room wants music to play (it may still be loading the stream). */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /**
     * Adds a participant. [local] means the connection comes from the hosting device itself;
     * a private (solo) room only lets in local connections and its owner (by owner token or
     * account, e.g. on a server). [account] is set when the
     * participant proved an account; it then shows on them, and the room's account owner
     * is recognised on any device.
     */
    suspend fun join(hello: ClientMessage.Hello, outbox: Outbox, local: Boolean = true, account: AccountIdentity? = null): String? = mutex.withLock {
        val isOwner = hello.ownerToken == ownerToken || (account != null && account.id == ownerAccount)
        if (visibility == RoomVisibility.PRIVATE && !local && !isOwner) {
            outbox.send(ServerMessage.Rejected(RejectReason.PRIVATE_ROOM))
            return null
        }
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            outbox.send(ServerMessage.Rejected(RejectReason.VERSION_MISMATCH))
            return null
        }
        val name = hello.guestName.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            outbox.send(ServerMessage.Rejected(RejectReason.INVALID_NAME))
            return null
        }

        val banned = bans.any { (account != null && it.info.accountId == account.id) || (it.guestToken != null && it.guestToken == hello.guestToken) }
        if (banned && !isOwner) {
            outbox.send(ServerMessage.Rejected(RejectReason.BANNED))
            return null
        }
        val returning = members.values.firstOrNull { it.token == hello.guestToken }
        // Moved here from the room this is a clone of: their role (and id, so their songs are theirs) comes along.
        val migrant = if (returning == null) hello.migrationKey?.let { key -> Ids.sha256(key).let { tag -> migrants.firstOrNull { it.tag == tag } } } else null
        // The password: not for the owner, people already here, the hosting device, or people moved here.
        val hash = passwordHash
        if (hash != null && returning == null && migrant == null && !isOwner && !local) {
            val password = hello.password
            if (password == null || !RoomPasswords.verify(password, hash)) {
                outbox.send(ServerMessage.Rejected(if (password == null) RejectReason.PASSWORD_REQUIRED else RejectReason.WRONG_PASSWORD))
                return null
            }
        }
        if (migrant != null) migrants.remove(migrant)
        val member = returning ?: Member(
            id = migrant?.id?.takeIf { it.isNotBlank() && it !in members } ?: Ids.short(),
            token = Ids.token(),
            name = name,
            isOwner = isOwner,
            outbox = null,
            roleId = when {
                isOwner -> roles.first().id
                migrant != null && roles.any { it.id == migrant.roleId } -> migrant.roleId
                account != null -> settings.defaultAccountRole
                else -> settings.defaultGuestRole
            },
            allow = migrant?.allow?.toSet().orEmpty(),
            deny = migrant?.deny?.toSet().orEmpty(),
            joinedAt = clock(),
        ).also { members[it.id] = it }

        member.outbox?.takeIf { it !== outbox }?.send(ServerMessage.Rejected(RejectReason.REPLACED))
        member.outbox = outbox
        member.name = account?.displayName ?: name
        member.accountId = account?.id
        member.accountToken = hello.accountToken.takeIf { account != null }
        member.hideFromHistory = hello.hideFromHistory || account?.hideFromHistory == true
        // Logging in later (same guest token, now with the owner's account) makes you the owner.
        member.isOwner = member.isOwner || isOwner
        member.offlineSince = null
        lastActive = clock()
        emptyJob?.cancel()

        outbox.send(ServerMessage.Welcome(member.id, member.token, seq, snapshot(), accountId = account?.id, migrationKey = member.migrationKey))
        emit(if (returning == null) Event.ParticipantJoined(member.toParticipant()) else Event.ParticipantUpdated(member.toParticipant()))
        if (adminless && isAdmin(member)) {
            adminless = false
            adminSeenAt = clock()
            emit(Event.AdminlessChanged(false))
        }
        member.id
    }

    /**
     * Adds a second connection for a participant who is already here (see
     * [ClientMessage.Attach]). It gets everything the participant gets, but doesn't make
     * them online or replace their connection. Returns the participant's id. [accountsOnly]:
     * only for participants who joined with an account.
     */
    suspend fun attach(message: ClientMessage.Attach, outbox: Outbox, local: Boolean = true, accountsOnly: Boolean = false): String? = mutex.withLock {
        if (message.protocolVersion != PROTOCOL_VERSION) {
            outbox.send(ServerMessage.Rejected(RejectReason.VERSION_MISMATCH))
            return null
        }
        val member = members.values.firstOrNull { it.token == message.guestToken }
        if (member == null) {
            // Not (or no longer) in the room, e.g. kicked or removed after being away.
            outbox.send(ServerMessage.Rejected(RejectReason.KICKED))
            return null
        }
        if (visibility == RoomVisibility.PRIVATE && !local && !member.isOwner) {
            outbox.send(ServerMessage.Rejected(RejectReason.PRIVATE_ROOM))
            return null
        }
        if (accountsOnly && member.accountId == null) {
            outbox.send(ServerMessage.Rejected(RejectReason.ACCOUNT_REQUIRED))
            return null
        }
        member.attached += outbox
        emptyJob?.cancel()
        outbox.send(ServerMessage.Welcome(member.id, member.token, seq, snapshot(), accountId = member.accountId, migrationKey = member.migrationKey))
        member.id
    }

    suspend fun disconnect(participantId: String, outbox: Outbox) = mutex.withLock {
        val member = members[participantId] ?: return@withLock
        if (!member.attached.remove(outbox)) {
            if (member.outbox !== outbox) return@withLock
            member.outbox = null
        }
        lastActive = clock()
        // Still connected another way (the app's player while its page is frozen): still here.
        if (member.online) return@withLock
        member.listening = false
        member.offlineSince = clock()
        emit(Event.ParticipantUpdated(member.toParticipant()))
        pauseWhenEveryoneGone(EVERYONE_GONE_PAUSE_MS)
    }

    /**
     * Nobody is here any more: the music pauses after [afterMs] (a reload or a short network
     * drop isn't leaving), unless it plays on speakers or a TV (people may be listening there).
     * Called with [mutex] held.
     */
    private fun pauseWhenEveryoneGone(afterMs: Long) {
        emptyJob?.cancel()
        if (!wantPlaying || members.values.any { it.online } || activeOutputs.isNotEmpty()) return
        emptyJob = scope.launch {
            delay(afterMs)
            mutex.withLock {
                if (wantPlaying && members.values.none { it.online } && activeOutputs.isEmpty()) {
                    log.info("Room {}: everyone left, pausing", code)
                    pausePlayback()
                }
            }
        }
    }

    /** Handles a message from [from], one of [participantId]'s connections (by default their main one). */
    suspend fun handle(participantId: String, message: ClientMessage, from: Outbox? = null) {
        mutex.withLock {
            val member = members[participantId] ?: return@withLock
            val outbox = from ?: member.outbox ?: return@withLock
            // A replaced or dropped connection can't act anymore.
            if (outbox !== member.outbox && outbox !in member.attached) return@withLock
            when (message) {
                is ClientMessage.Ping -> outbox.send(ServerMessage.Pong(message.clientTime, clock()))
                is ClientMessage.RequestSnapshot -> outbox.send(ServerMessage.Snapshot(seq, snapshot()))
                is ClientMessage.CommandMessage -> {
                    val error = runCatching { execute(member, message.command) }
                        .fold({ it }, { e -> if (e is CancellationException) throw e else ErrorInfo(ErrorCode.INVALID, e.message ?: "error") })
                    outbox.send(ServerMessage.Result(message.id, error))
                }
                is ClientMessage.Hello, is ClientMessage.Attach -> Unit
            }
        }
    }

    /** Removes participants that have been offline for too long. Returns true if nobody is left online. */
    suspend fun cleanup(offlineTimeoutMs: Long): Boolean = mutex.withLock {
        val now = clock()
        val expired = members.values.filter { it.offlineSince?.let { since -> now - since > offlineTimeoutMs } == true }
        for (member in expired) {
            members.remove(member.id)
            member.dropAttached(RejectReason.KICKED)
            emit(Event.ParticipantLeft(member.id))
        }
        members.values.none { it.online }
    }

    /** Pauses playback, e.g. when the phone stops hosting. */
    suspend fun pause() = mutex.withLock { pausePlayback() }

    /** Everything needed to bring this room back after a restart. */
    suspend fun save(): SavedRoom = mutex.withLock {
        SavedRoom(
            code = code,
            name = name,
            ownerToken = ownerToken,
            visibility = visibility,
            members = members.values.map { SavedMember(it.id, it.token, it.name, it.isOwner, it.accountId, it.roleId, it.allow.sorted(), it.deny.sorted(), it.joinedAt) },
            queue = queue.toList(),
            history = history.takeLast(SAVED_HISTORY),
            current = current,
            positionMs = position(),
            // Someone connected right now counts as activity, so the room isn't deleted as stale on restore.
            lastActive = if (members.values.any { it.online }) clock() else lastActive,
            ownerAccount = ownerAccount,
            roles = roles.toList(),
            settings = settings,
            bans = bans.map { SavedBan(it.info, it.guestToken) },
            autoplay = autoplay.toList(),
            autoplaySeed = autoplaySeed,
            knownPermissions = Permission.entries,
            episodePositions = episodePositions.toMap(),
            syncId = syncId,
            epoch = epoch,
            savedAt = clock(),
            playing = wantPlaying && current != null,
            migrants = migrants.toList(),
            migration = migration,
            passwordHash = passwordHash,
        )
    }

    /**
     * The room moved to another of the owner's hosts: everyone here is sent there ([url], if
     * known). Call before closing it.
     */
    suspend fun movedAway(url: String?) = mutex.withLock {
        for (member in members.values) {
            val message = ServerMessage.Rejected(RejectReason.ROOM_MOVED, movedTo = url)
            member.outbox?.send(message)
            member.attached.forEach { it.send(message) }
            member.outbox = null
            member.attached.clear()
        }
    }

    /** Fills a new room from [saved]. Everyone starts offline and playback starts paused. */
    private fun restore(saved: SavedRoom) {
        val now = clock()
        if (saved.roles != null && saved.settings != null) {
            val upgraded = Permissions.upgrade(saved.roles, saved.knownPermissions?.toSet() ?: Permissions.BEFORE_RADIO)
            val restored = Permissions.sanitize(RoleTemplate(upgraded, saved.settings))
            roles.clear()
            roles += restored.roles
            settings = restored.settings.copy(openElsewhere = saved.settings.openElsewhere, alwaysOn = saved.settings.alwaysOn)
        }
        migrants += saved.migrants
        migration = saved.migration
        passwordHash = saved.passwordHash
        for (b in saved.bans) bans += Ban(b.info, b.guestToken)
        autoplay += saved.autoplay
        autoplaySeed = saved.autoplaySeed
        episodePositions += saved.episodePositions
        val roleIds = roles.map { it.id }.toSet()
        for (m in saved.members) {
            val roleId = m.roleId?.takeIf { it in roleIds }
                ?: if (m.isOwner) roles.first().id else if (m.accountId != null) settings.defaultAccountRole else settings.defaultGuestRole
            members[m.id] = Member(
                m.id, m.token, m.name, m.isOwner, outbox = null, roleId = roleId, accountId = m.accountId,
                offlineSince = now, allow = m.allow.toSet(), deny = m.deny.toSet(), joinedAt = m.joinedAt,
            )
        }
        queue += saved.queue
        history += saved.history
        // The stream URL has probably expired; it is resolved again when someone presses play.
        current = saved.current
        currentStartedAt = now
        positionAtAnchor = saved.positionMs
        anchorTime = now
        lastActive = saved.lastActive
    }

    fun close() {
        emptyJob?.cancel()
        autoplayJob?.cancel()
        resolveJob?.cancel()
        endJob?.cancel()
        changeListeners.clear()
    }

    // --- outputs ----------------------------------------------------------------------

    /** Called by the host when it finds or loses speakers. */
    suspend fun setAvailableOutputs(devices: List<OutputDevice>) = mutex.withLock {
        availableOutputs = devices
        activeOutputs.keys.retainAll(devices.map { it.id }.toSet())
        emit(Event.OutputsChanged(outputInfos()))
    }

    /** Called by an output driver with the volume the device actually has. */
    suspend fun reportOutputVolume(outputId: String, volume: Double) = mutex.withLock {
        if (outputId in activeOutputs && activeOutputs[outputId] != volume) {
            activeOutputs[outputId] = volume
            emit(Event.OutputsChanged(outputInfos()))
        }
    }

    /** Tells everyone in the room about a problem, e.g. a speaker that couldn't play a song. */
    suspend fun notice(message: String) = mutex.withLock { emit(Event.Notice(message)) }

    /** [listener] is called (on any thread, without waiting) after every change. Read [view] to see what changed. */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    suspend fun view(): RoomView = mutex.withLock {
        RoomView(
            current = current.takeIf { streamUrl != null },
            streamUrl = streamUrl,
            playing = wantPlaying && streamUrl != null,
            positionMs = position(),
            hostTimeMs = clock(),
            activeOutputs = LinkedHashMap(activeOutputs),
            previous = history.lastOrNull(),
            next = queue.firstOrNull() ?: autoplay.firstOrNull(),
        )
    }

    /**
     * A speaker skipped by itself (its own buttons, its app, or Google Home): follow it. Only
     * if [fromItemId] is still the current song, so a skip isn't done twice. A speaker moving
     * on at the very end of a song counts as the song having played.
     */
    suspend fun outputSkipped(fromItemId: String, forward: Boolean) = mutex.withLock {
        val item = current ?: return@withLock
        if (item.itemId != fromItemId) return@withLock
        if (forward) {
            val ended = item.song.durationMs > 0 && position() >= item.song.durationMs - END_GRACE_MS
            retireCurrent(if (ended) QueueItemResult.PLAYED else QueueItemResult.SKIPPED)
            playNextFromQueue()
        } else {
            history.lastOrNull()?.let { jumpTo(it.itemId) }
        }
    }

    private fun outputInfos(): List<OutputInfo> = availableOutputs.map {
        OutputInfo(it.id, it.name, it.kind, active = it.id in activeOutputs, volume = activeOutputs[it.id])
    }

    // --- commands ---------------------------------------------------------------------

    /** Runs a command. Returns an error, or null on success. Called with [mutex] held. */
    private fun execute(member: Member, command: Command): ErrorInfo? {
        when (command) {
            is Command.AddSongs -> {
                need(member, Permission.ADD_SONGS)?.let { return it }
                if (command.songs.isEmpty()) return invalid("No songs")
                autoplayDismissed = false
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
                need(member, Permission.PLAY_NOW)?.let { return it }
                autoplayDismissed = false
                retireCurrent(QueueItemResult.SKIPPED)
                wantPlaying = true
                setCurrent(newItem(command.song, member))
            }
            is Command.RemoveQueueItem -> {
                // The current song: it stops, and doesn't stay in the history.
                val playing = current
                if (playing != null && playing.itemId == command.itemId) {
                    need(member, if (playing.addedBy == member.id) Permission.REMOVE_OWN else Permission.REMOVE_OTHERS)?.let { return it }
                    retireCurrent(QueueItemResult.SKIPPED)
                    history.removeLastOrNull()?.let { emit(Event.HistoryItemRemoved(it.itemId)) }
                    playNextFromQueue()
                    return null
                }
                // Any other item: upcoming, played or autoplay.
                val (list, item) = find(command.itemId) ?: return notFound()
                need(member, if (item.addedBy == member.id) Permission.REMOVE_OWN else Permission.REMOVE_OTHERS)?.let { return it }
                when (list) {
                    ItemList.QUEUE -> {
                        queue.remove(item)
                        emit(Event.QueueItemRemoved(command.itemId))
                        refillAutoplay()
                    }
                    ItemList.HISTORY -> {
                        history.remove(item)
                        emit(Event.HistoryItemRemoved(command.itemId))
                    }
                    ItemList.AUTOPLAY -> {
                        autoplay.remove(item)
                        emitAutoplay()
                        refillAutoplay()
                    }
                }
            }
            is Command.MoveItem -> return moveItem(member, command)
            Command.ClearQueue -> {
                if (queue.isEmpty()) return null
                need(member, if (queue.all { it.addedBy == member.id }) Permission.REMOVE_OWN else Permission.REMOVE_OTHERS)?.let { return it }
                queue.clear()
                emit(Event.QueueReplaced(emptyList()))
                refillAutoplay()
            }
            Command.ClearAutoplay -> {
                need(member, Permission.AUTOPLAY_FROM_HERE)?.let { return it }
                clearAutoplay()
                autoplayDismissed = true
            }
            is Command.MoveQueueItem -> {
                need(member, Permission.REORDER)?.let { return it }
                val from = queue.indexOfFirst { it.itemId == command.itemId }
                if (from < 0) return notFound()
                val item = queue.removeAt(from)
                val to = command.toIndex.coerceIn(0, queue.size)
                queue.add(to, item)
                emit(Event.QueueItemMoved(item.itemId, to))
            }
            is Command.JumpTo -> {
                need(member, Permission.PLAY_NOW)?.let { return it }
                return jumpTo(command.itemId)
            }
            is Command.StartRadio -> {
                need(member, Permission.START_RADIO)?.let { return it }
                startRadio(command.song, member.id, member.name)
            }
            is Command.AutoplayFromHere -> {
                need(member, Permission.AUTOPLAY_FROM_HERE)?.let { return it }
                autoplayFromHere(command.song, member.id, member.name)
            }
            is Command.AutoplaySongs -> {
                need(member, Permission.AUTOPLAY_FROM_HERE)?.let { return it }
                if (command.songs.isEmpty()) return invalid("No songs")
                autoplaySongs(command.songs, member.id, member.name)
            }
            Command.Play -> {
                need(member, Permission.PLAY_PAUSE)?.let { return it }
                if (current == null) {
                    if (queue.isEmpty()) return invalid("The queue is empty")
                    wantPlaying = true
                    playNextFromQueue()
                } else if (!wantPlaying) {
                    wantPlaying = true
                    anchorTime = clock()
                    emitPlayback()
                    // A restored room has a current song but no stream yet.
                    if (streamUrl == null && resolveJob?.isActive != true) resolve(current!!)
                }
            }
            Command.Pause -> {
                need(member, Permission.PLAY_PAUSE)?.let { return it }
                pausePlayback()
            }
            Command.Skip -> {
                need(member, Permission.SKIP)?.let { return it }
                if (current == null) return invalid("Nothing is playing")
                retireCurrent(QueueItemResult.SKIPPED)
                playNextFromQueue()
            }
            Command.Previous -> {
                need(member, Permission.SKIP)?.let { return it }
                val last = history.lastOrNull()
                if (position() > PREVIOUS_RESTART_MS || last == null) seek(0) else return jumpTo(last.itemId)
            }
            is Command.Seek -> {
                need(member, Permission.SEEK)?.let { return it }
                if (current == null) return invalid("Nothing is playing")
                seek(command.positionMs)
            }
            is Command.SetListening -> {
                if (command.on) need(member, Permission.LISTEN_LOCALLY)?.let { return it }
                member.listening = command.on
                emit(Event.ParticipantUpdated(member.toParticipant()))
            }
            is Command.SetOutput -> {
                need(member, Permission.CHANGE_OUTPUTS)?.let { return it }
                if (availableOutputs.none { it.id == command.outputId }) return ErrorInfo(ErrorCode.NOT_FOUND, "That speaker isn't available")
                if (command.active) activeOutputs.putIfAbsent(command.outputId, null) else activeOutputs.remove(command.outputId)
                emit(Event.OutputsChanged(outputInfos()))
            }
            is Command.SetOutputVolume -> {
                need(member, Permission.OUTPUT_VOLUME)?.let { return it }
                if (command.outputId !in activeOutputs) return ErrorInfo(ErrorCode.NOT_FOUND, "Not playing on that speaker")
                activeOutputs[command.outputId] = command.volume.coerceIn(0.0, 1.0)
                emit(Event.OutputsChanged(outputInfos()))
            }
            is Command.SetOpenElsewhere -> {
                if (!member.isOwner) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "Only the owner can change this")
                if (settings.openElsewhere != command.mode) {
                    settings = settings.copy(openElsewhere = command.mode)
                    emit(Event.SettingsChanged(settings))
                }
            }
            is Command.SetVisibility -> {
                if (!member.isOwner) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "Only the owner can change this")
                if (visibility != command.visibility) {
                    visibility = command.visibility
                    emit(Event.RoomUpdated(info))
                    onInfoChanged()
                }
            }
            is Command.ChangeOwner -> {
                if (!member.isOwner) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "Only the owner can hand the room over")
                val target = members[command.participantId] ?: return notFoundPerson()
                if (target === member) return invalid("You're the owner already")
                changeOwner(target)
            }
            is Command.LeaveRoom -> return leave(member, command.newOwnerId, command.stayOwner)
            is Command.SetPassword -> {
                need(member, Permission.CHANGE_SETTINGS)?.let { return it }
                val password = command.password
                if (password != null && !RoomPasswords.valid(password)) return invalid("A password is 1–${RoomPasswords.MAX_LENGTH} characters")
                passwordHash = password?.let(RoomPasswords::hash)
                emit(Event.RoomUpdated(info))
                emit(Event.Notice(if (password == null) "${member.name} removed the room's password" else "${member.name} set a password for the room"))
                onInfoChanged()
            }
            is Command.SendMigration -> {
                need(member, Permission.SEND_MIGRATION)?.let { return it }
                val target = command.target
                if (!target.url.startsWith("http://") && !target.url.startsWith("https://") || target.url.length > MAX_URL_LENGTH) return invalid("Not a room link")
                if (target.code.isBlank() || target.code.length > 16 || target.name.length > 64) return invalid("Not a room")
                if (target.code.equals(code, ignoreCase = true) && target.url.contains("/room/$code")) return invalid("That's this room")
                migration = MigrationOffer(target.copy(name = target.name.trim()), command.mode, member.name)
                emit(Event.MigrationChanged(migration))
                if (command.mode == MigrationMode.FORCED) moveEveryone(member, target.url)
            }
            Command.CancelMigration -> {
                need(member, Permission.SEND_MIGRATION)?.let { return it }
                if (migration != null) {
                    migration = null
                    emit(Event.MigrationChanged(null))
                }
            }
            is Command.Kick, is Command.Ban, is Command.Unban, is Command.AssignRole, is Command.SetParticipantPermissions,
            is Command.CreateRole, is Command.UpdateRole, is Command.DeleteRole, is Command.MoveRole, is Command.UpdateSettings,
            -> return manage(member, command)
        }
        return null
    }

    // --- people, roles, settings --------------------------------------------------------

    /** Kicks, bans, roles and settings. Called with [mutex] held. */
    private fun manage(actor: Member, command: Command): ErrorInfo? {
        when (command) {
            is Command.Kick, is Command.Ban -> {
                val targetId = if (command is Command.Kick) command.participantId else (command as Command.Ban).participantId
                val ban = command is Command.Ban
                need(actor, if (ban) Permission.BAN else Permission.KICK)?.let { return it }
                val target = members[targetId] ?: return notFoundPerson()
                if (target === actor) return invalid("You can't remove yourself")
                if (!outranks(actor, target)) return outranked()
                members.remove(target.id)
                if (ban) bans += Ban(BanInfo(Ids.short(), target.name, target.accountId), target.token)
                target.outbox?.send(ServerMessage.Rejected(if (ban) RejectReason.BANNED else RejectReason.KICKED))
                target.outbox = null
                target.dropAttached(if (ban) RejectReason.BANNED else RejectReason.KICKED)
                emit(Event.ParticipantLeft(target.id))
                if (ban) emit(Event.BansChanged(banInfos()))
            }
            is Command.Unban -> {
                need(actor, Permission.BAN)?.let { return it }
                if (!bans.removeIf { it.info.id == command.banId }) return ErrorInfo(ErrorCode.NOT_FOUND, "Not banned")
                emit(Event.BansChanged(banInfos()))
            }
            is Command.AssignRole -> {
                need(actor, Permission.ASSIGN_ROLES)?.let { return it }
                val target = members[command.participantId] ?: return notFoundPerson()
                if (!outranks(actor, target)) return outranked()
                val index = roles.indexOfFirst { it.id == command.roleId }
                if (index < 0) return notFoundRole()
                if (!canManageRole(actor, index)) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "You can only give roles below your own")
                target.roleId = command.roleId
                emit(Event.ParticipantUpdated(target.toParticipant()))
            }
            is Command.SetParticipantPermissions -> {
                need(actor, Permission.ASSIGN_ROLES)?.let { return it }
                val target = members[command.participantId] ?: return notFoundPerson()
                if (!outranks(actor, target)) return outranked()
                val allow = command.allow.toSet()
                cannotGrant(actor, allow - target.allow)?.let { return it }
                target.allow = allow
                target.deny = command.deny.toSet() - allow
                emit(Event.ParticipantUpdated(target.toParticipant()))
            }
            is Command.CreateRole -> {
                need(actor, Permission.EDIT_ROLES)?.let { return it }
                val name = command.name.trim()
                if (name.isEmpty() || name.length > Permissions.MAX_ROLE_NAME) return invalid("Role names are 1–${Permissions.MAX_ROLE_NAME} characters")
                if (roles.size >= Permissions.MAX_ROLES) return invalid("A room can have at most ${Permissions.MAX_ROLES} roles")
                cannotGrant(actor, command.permissions.toSet())?.let { return it }
                roles += Role(Ids.short(), name, Permissions.validColor(command.color), command.permissions.distinct())
                emit(Event.RolesChanged(roles.toList()))
            }
            is Command.UpdateRole -> {
                need(actor, Permission.EDIT_ROLES)?.let { return it }
                val index = roles.indexOfFirst { it.id == command.role.id }
                if (index < 0) return notFoundRole()
                if (!canManageRole(actor, index)) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "You can only change roles below your own")
                val name = command.role.name.trim()
                if (name.isEmpty() || name.length > Permissions.MAX_ROLE_NAME) return invalid("Role names are 1–${Permissions.MAX_ROLE_NAME} characters")
                cannotGrant(actor, command.role.permissions.toSet() - roles[index].permissions.toSet())?.let { return it }
                roles[index] = Role(command.role.id, name, Permissions.validColor(command.role.color), command.role.permissions.distinct())
                emit(Event.RolesChanged(roles.toList()))
            }
            is Command.DeleteRole -> {
                need(actor, Permission.EDIT_ROLES)?.let { return it }
                val index = roles.indexOfFirst { it.id == command.roleId }
                if (index < 0) return notFoundRole()
                if (!canManageRole(actor, index)) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "You can only delete roles below your own")
                if (command.roleId == settings.defaultGuestRole || command.roleId == settings.defaultAccountRole) {
                    return invalid("New people get this role. Pick another default role first.")
                }
                roles.removeAt(index)
                emit(Event.RolesChanged(roles.toList()))
                for (m in members.values.filter { it.roleId == command.roleId }) {
                    m.roleId = if (m.accountId != null) settings.defaultAccountRole else settings.defaultGuestRole
                    emit(Event.ParticipantUpdated(m.toParticipant()))
                }
            }
            is Command.MoveRole -> {
                need(actor, Permission.EDIT_ROLES)?.let { return it }
                val from = roles.indexOfFirst { it.id == command.roleId }
                if (from < 0) return notFoundRole()
                val to = command.toIndex.coerceIn(0, roles.size - 1)
                if (!canManageRole(actor, from) || !canManageRole(actor, to)) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "You can only move roles below your own")
                roles.add(to, roles.removeAt(from))
                emit(Event.RolesChanged(roles.toList()))
            }
            is Command.UpdateSettings -> {
                need(actor, Permission.CHANGE_SETTINGS)?.let { return it }
                val newName = command.name?.trim()
                if (newName != null && (newName.isEmpty() || newName.length > 64)) return invalid("Room names are 1–64 characters")
                val ids = roles.map { it.id }.toSet()
                if (listOfNotNull(command.defaultGuestRole, command.defaultAccountRole).any { it !in ids }) return notFoundRole()
                if (newName != null && newName != name) {
                    name = newName
                    emit(Event.RoomUpdated(info))
                    onInfoChanged()
                }
                val updated = settings.copy(
                    defaultGuestRole = command.defaultGuestRole ?: settings.defaultGuestRole,
                    defaultAccountRole = command.defaultAccountRole ?: settings.defaultAccountRole,
                    autoplay = command.autoplay ?: settings.autoplay,
                    alwaysOn = command.alwaysOn ?: settings.alwaysOn,
                )
                if (updated != settings) {
                    val autoplayChanged = updated.autoplay != settings.autoplay
                    settings = updated
                    emit(Event.SettingsChanged(settings))
                    if (autoplayChanged) {
                        autoplayDismissed = false
                        if (!settings.autoplay) clearAutoplay() else refillAutoplay()
                    }
                }
            }
            else -> Unit
        }
        return null
    }

    // --- owner, leaving, moving everyone ----------------------------------------------

    /** Makes [target] the owner. Whoever was owner keeps the top role. Called with [mutex] held. */
    private fun changeOwner(target: Member) {
        val oldAccount = ownerAccount
        val oldSyncId = syncId
        for (m in members.values) {
            if (m.isOwner && m !== target) {
                m.isOwner = false
                m.roleId = roles.first().id
                emit(Event.ParticipantUpdated(m.toParticipant()))
            }
        }
        target.isOwner = true
        target.roleId = roles.first().id
        // The old owner's token (kept by their browser) and account don't make them the owner any more.
        ownerToken = Ids.token()
        ownerAccount = target.accountId
        if (ownerAccount != oldAccount) {
            // Someone else's room now: not the old owner's on their other devices.
            syncId = ownerAccount?.let { Ids.token() }
            epoch = 0
            onOwnerChanged(oldAccount, oldSyncId)
        }
        emit(Event.ParticipantUpdated(target.toParticipant()))
        target.send(ServerMessage.OwnerToken(ownerToken))
        emit(Event.Notice("${target.name} is the owner now"))
        log.info("Room {}: {} is the owner now", code, target.name)
        onInfoChanged()
    }

    /**
     * [member] leaves for good. The owner of a party room hands it over first (unless they
     * [stayOwner]): to [newOwnerId], or to whoever has the top role and has been here the
     * longest (if anyone).
     */
    private fun leave(member: Member, newOwnerId: String?, stayOwner: Boolean): ErrorInfo? {
        if (member.isOwner && visibility == RoomVisibility.PUBLIC && !stayOwner) {
            val chosen = newOwnerId?.let { members[it] ?: return notFoundPerson() }?.takeIf { it !== member }
            val next = chosen ?: members.values
                .filter { it !== member && !it.isOwner && it.online && it.roleId == roles.first().id }
                .minByOrNull { it.joinedAt }
            if (next != null) changeOwner(next)
        }
        members.remove(member.id)
        member.dropAttached(RejectReason.LEFT)
        member.outbox = null
        emit(Event.ParticipantLeft(member.id))
        // The last one out: no need to wait.
        pauseWhenEveryoneGone(0)
        return null
    }

    /** Forced migration: everyone but [sender] is sent to [url] and leaves; the music stops here. */
    private fun moveEveryone(sender: Member, url: String) {
        pausePlayback()
        for (m in members.values.filter { it !== sender }) {
            members.remove(m.id)
            val message = ServerMessage.Rejected(RejectReason.MIGRATED, movedTo = url)
            m.outbox?.send(message)
            m.outbox = null
            m.dropAttached(RejectReason.MIGRATED)
            emit(Event.ParticipantLeft(m.id))
        }
    }

    /** Runs the room: the owner, or someone with the top role. */
    private fun isAdmin(member: Member) = member.isOwner || member.roleId == roles.firstOrNull()?.id

    /**
     * Marks a party room as [RoomState.adminless] when nobody who runs it has been connected
     * for [afterMs], so people know they can clone it. Called now and then by the host.
     */
    suspend fun checkAdminless(afterMs: Long) = mutex.withLock {
        val now = clock()
        if (members.values.any { it.online && isAdmin(it) }) adminSeenAt = now
        val value = visibility == RoomVisibility.PUBLIC && now - adminSeenAt > afterMs
        if (value != adminless) {
            adminless = value
            emit(Event.AdminlessChanged(value))
        }
    }

    /** A new room starts with this password ([RoomPasswords.hash]). Only before anyone can join. */
    internal fun startWithPassword(hash: String) {
        passwordHash = hash
    }

    /** A new room starts as a copy of another one ([clone]). Only before anyone can join. */
    internal fun startFrom(clone: RoomClone) {
        val cloneSettings = clone.settings
        if (clone.roles.isNotEmpty() && cloneSettings != null) {
            val copied = Permissions.sanitize(RoleTemplate(clone.roles, cloneSettings))
            roles.clear()
            roles += copied.roles
            settings = copied.settings
        }
        queue += clone.queue.take(MAX_CLONE_QUEUE)
        history += clone.history.takeLast(SAVED_HISTORY)
        autoplay += clone.autoplay.take(MAX_CLONE_QUEUE)
        autoplaySeed = clone.autoplaySeed
        val now = clock()
        current = clone.current
        currentStartedAt = now
        positionAtAnchor = clone.positionMs.coerceAtLeast(0)
        anchorTime = now
        migrants += clone.migrants.filter { it.tag.isNotBlank() }.distinctBy { it.tag }.take(MAX_MIGRANTS)
    }

    private fun can(member: Member, permission: Permission): Boolean =
        permission in Permissions.effective(roles.firstOrNull { it.id == member.roleId }, member.allow, member.deny, member.isOwner)

    private fun need(member: Member, permission: Permission): ErrorInfo? =
        if (can(member, permission)) null else ErrorInfo(ErrorCode.PERMISSION_DENIED, "Your role doesn't allow this")

    /** 0 is the top role; the owner is above every role. */
    private fun rank(member: Member): Int =
        if (member.isOwner) -1 else roles.indexOfFirst { it.id == member.roleId }.let { if (it < 0) roles.size else it }

    /** Like Discord: you can only manage people whose role is below yours. Nobody manages the owner. */
    private fun outranks(actor: Member, target: Member) = !target.isOwner && (actor.isOwner || rank(actor) < rank(target))

    private fun canManageRole(actor: Member, roleIndex: Int) = actor.isOwner || roleIndex > rank(actor)

    /** You can't hand out permissions you don't have yourself. */
    private fun cannotGrant(actor: Member, permissions: Set<Permission>): ErrorInfo? =
        if (permissions.all { can(actor, it) }) null else ErrorInfo(ErrorCode.PERMISSION_DENIED, "You can't give permissions you don't have")

    private fun outranked() = ErrorInfo(ErrorCode.PERMISSION_DENIED, "Their role is not below yours")

    private fun notFoundPerson() = ErrorInfo(ErrorCode.NOT_FOUND, "That person isn't in the room")

    private fun notFoundRole() = ErrorInfo(ErrorCode.NOT_FOUND, "No such role")

    private fun banInfos() = bans.map { it.info }

    /** The list an item is in (not the current song). */
    private fun find(itemId: String): Pair<ItemList, QueueItem>? =
        queue.firstOrNull { it.itemId == itemId }?.let { ItemList.QUEUE to it }
            ?: history.firstOrNull { it.itemId == itemId }?.let { ItemList.HISTORY to it }
            ?: autoplay.firstOrNull { it.itemId == itemId }?.let { ItemList.AUTOPLAY to it }

    private fun listOf(list: ItemList): MutableList<QueueItem> = when (list) {
        ItemList.HISTORY -> history
        ItemList.QUEUE -> queue
        ItemList.AUTOPLAY -> autoplay
    }

    private fun moveItem(member: Member, command: Command.MoveItem): ErrorInfo? {
        val playing = current
        if (playing != null && playing.itemId == command.itemId) return moveCurrent(member, playing, command)
        val (from, original) = find(command.itemId) ?: return notFound()
        // Taking an autoplay song into the queue (next or last) is like adding it; anything else is reordering.
        val toEnd = command.toIndex >= queue.size
        val adding = from == ItemList.AUTOPLAY && command.list == ItemList.QUEUE && (command.toIndex == 0 || toEnd)
        need(member, if (adding) Permission.ADD_SONGS else Permission.REORDER)?.let { return it }

        // History indexes count from the first song clients see (the snapshot only has the last ones).
        val historyOffset = maxOf(0, history.size - SNAPSHOT_HISTORY)
        val source = listOf(from)
        val fromIndex = source.indexOf(original)
        source.removeAt(fromIndex)
        val item = when {
            command.list == ItemList.HISTORY -> original
            from == ItemList.AUTOPLAY && command.list == ItemList.QUEUE ->
                original.copy(addedBy = member.id, addedByName = member.name, addedAt = clock(), origin = QueueItemOrigin.MANUAL)
            else -> original.copy(result = null)
        }
        val target = listOf(command.list)
        // The client saw history from historyOffset on, without the moved item: count from there.
        val to = (command.toIndex + if (command.list == ItemList.HISTORY) historyOffset else 0).coerceIn(0, target.size)
        target.add(to, item)

        if (from == ItemList.QUEUE && command.list == ItemList.QUEUE) {
            emit(Event.QueueItemMoved(item.itemId, to))
        } else {
            for (list in setOf(from, command.list)) when (list) {
                ItemList.QUEUE -> emit(Event.QueueReplaced(queue.toList()))
                ItemList.HISTORY -> emit(Event.HistoryReplaced(history.takeLast(SNAPSHOT_HISTORY)))
                ItemList.AUTOPLAY -> emitAutoplay()
            }
        }
        // Nothing playing and a song lands in the queue: start it, as adding would.
        if (current == null && queue.isNotEmpty() && command.list == ItemList.QUEUE) {
            wantPlaying = true
            playNextFromQueue()
        } else {
            refillAutoplay()
        }
        return null
    }

    /** The current song dragged somewhere else: it goes there, and the next song starts. */
    private fun moveCurrent(member: Member, playing: QueueItem, command: Command.MoveItem): ErrorInfo? {
        need(member, Permission.REORDER)?.let { return it }
        val historyOffset = maxOf(0, history.size - SNAPSHOT_HISTORY)
        current = null
        val target = listOf(command.list)
        val item = if (command.list == ItemList.HISTORY) playing.copy(result = QueueItemResult.SKIPPED) else playing.copy(result = null)
        val to = (command.toIndex + if (command.list == ItemList.HISTORY) historyOffset else 0).coerceIn(0, target.size)
        target.add(to, item)
        when (command.list) {
            ItemList.QUEUE -> emit(Event.QueueReplaced(queue.toList()))
            ItemList.HISTORY -> emit(Event.HistoryReplaced(history.takeLast(SNAPSHOT_HISTORY)))
            ItemList.AUTOPLAY -> emitAutoplay()
        }
        playNextFromQueue()
        return null
    }

    private fun jumpTo(itemId: String): ErrorInfo? {
        val autoplayIndex = autoplay.indexOfFirst { it.itemId == itemId }
        if (autoplayIndex >= 0) {
            // Like YTM: the autoplay songs before it are dropped (they never played).
            retireCurrent(QueueItemResult.SKIPPED)
            val item = autoplay[autoplayIndex]
            autoplay.subList(0, autoplayIndex + 1).clear()
            emitAutoplay()
            wantPlaying = true
            setCurrent(item)
            refillAutoplay()
            return null
        }
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

    private fun pausePlayback() {
        if (!wantPlaying) return
        positionAtAnchor = position()
        anchorTime = clock()
        wantPlaying = false
        emitPlayback()
    }

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
        if (item.song.podcast != null) rememberEpisodePosition(item.song, if (result == QueueItemResult.PLAYED) item.song.durationMs else position())
        // Only songs that actually played count for the listening history.
        if (streamUrl != null) {
            val heard = if (result == QueueItemResult.PLAYED) item.song.durationMs else position()
            val listeners = members.values.filter { it.online }.map {
                FinishedPlay.Listener(it.id, it.name, it.accountId, it.accountToken, it.hideFromHistory)
            }
            onPlayFinished?.invoke(FinishedPlay(item, currentStartedAt, heard, result == QueueItemResult.SKIPPED, code, name, listeners, visibility))
        }
        current = null
        appendHistory(item, result)
    }

    /** Remembers where an episode was left; one that's (nearly) done or barely started starts over. */
    private fun rememberEpisodePosition(song: Song, positionMs: Long) {
        episodePositions.remove(song.id)
        if (positionMs < RESUME_MIN_MS || positionMs > song.durationMs - RESUME_END_MS) return
        episodePositions[song.id] = positionMs
        while (episodePositions.size > MAX_EPISODE_POSITIONS) episodePositions.remove(episodePositions.keys.first())
    }

    private fun appendHistory(item: QueueItem, result: QueueItemResult) {
        val done = item.copy(result = result)
        history.add(done)
        emit(Event.HistoryAppended(done))
    }

    private fun playNextFromQueue() {
        val next = queue.removeFirstOrNull()?.also { emit(Event.QueueItemRemoved(it.itemId)) }
            ?: autoplay.removeFirstOrNull()?.also { emitAutoplay() }
        // Out of songs, but autoplay is still loading: it starts when it's there.
        startWhenAutoplayLoaded = next == null && wantPlaying && autoplayActive()
        setCurrent(next)
        refillAutoplay()
    }

    // --- radio and autoplay -----------------------------------------------------------

    private fun autoplayActive() = radio != null && !autoplayDismissed && (autoplaySeed != null || settings.autoplay)

    private fun emitAutoplay() = emit(Event.AutoplayChanged(autoplaySeed, autoplay.toList()))

    private fun clearAutoplay() {
        autoplayJob?.cancel()
        startWhenAutoplayLoaded = false
        if (autoplay.isEmpty() && autoplaySeed == null) return
        autoplay.clear()
        autoplaySeed = null
        autoplayBy = null
        emitAutoplay()
    }

    /** Songs that shouldn't come up again soon. */
    private fun recentSongIds(): Set<String> =
        (history.takeLast(RECENT_SONGS) + queue + autoplay + listOfNotNull(current)).map { it.song.id }.toSet()

    private fun radioItem(song: Song, origin: QueueItemOrigin, by: Pair<String, String>?) =
        QueueItem(Ids.short(), song, by?.first ?: "autoplay", by?.second ?: "Autoplay", clock(), result = null, origin = origin)

    /**
     * Start radio: plays [song] now, and the queue becomes a radio from it. On the song that's
     * playing, it keeps playing (like YTM) and only the queue changes.
     */
    private fun startRadio(song: Song, byId: String, byName: String) {
        val source = radio ?: return
        scope.launch {
            val songs = runCatching { source.radio(song.id) }
            mutex.withLock {
                songs.onFailure { e ->
                    if (e is CancellationException) throw e
                    emit(Event.Notice("Couldn't start a radio from \"${song.title}\": ${e.message}"))
                    return@withLock
                }
                // The queue is cleared and the radio goes into autoplay, the song itself first:
                // it plays from there, so it's heard once, and the radio keeps going after it.
                autoplayJob?.cancel()
                autoplayDismissed = false
                val keepPlaying = current?.song?.id == song.id
                if (!keepPlaying) retireCurrent(QueueItemResult.SKIPPED)
                if (queue.isNotEmpty()) {
                    queue.clear()
                    emit(Event.QueueReplaced(emptyList()))
                }
                autoplaySeed = song
                autoplayBy = byId to byName
                autoplay.clear()
                autoplay += (listOf(song).takeUnless { keepPlaying }.orEmpty() + songs.getOrThrow().filter { it.id != song.id }).distinctBy { it.id }
                    .map { radioItem(it, QueueItemOrigin.RADIO, autoplayBy) }
                emitAutoplay()
                if (keepPlaying) return@withLock
                wantPlaying = true
                playNextFromQueue()
            }
        }
    }

    /** Autoplay from here: the autoplay queue becomes a radio from [song]; the queue stays. */
    private fun autoplayFromHere(song: Song, byId: String, byName: String) {
        val source = radio ?: return
        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            val songs = runCatching { source.radio(song.id) }
            mutex.withLock {
                songs.onFailure { e ->
                    if (e is CancellationException) throw e
                    emit(Event.Notice("Couldn't load songs like \"${song.title}\": ${e.message}"))
                    return@withLock
                }
                autoplayDismissed = false
                autoplaySeed = song
                autoplayBy = byId to byName
                val skip = recentSongIds() - autoplay.map { it.song.id }.toSet()
                autoplay.clear()
                autoplay += songs.getOrThrow().filter { it.id != song.id && it.id !in skip }.distinctBy { it.id }
                    .map { radioItem(it, QueueItemOrigin.AUTOPLAY, autoplayBy) }
                emitAutoplay()
                if (current == null && queue.isEmpty()) {
                    wantPlaying = true
                    playNextFromQueue()
                }
            }
        }
    }

    /** A playlist as the autoplay queue; when it runs low, the radio goes on from its last song. Called with [mutex] held. */
    private fun autoplaySongs(songs: List<Song>, byId: String, byName: String) {
        autoplayJob?.cancel()
        startWhenAutoplayLoaded = false
        autoplayDismissed = false
        autoplaySeed = songs.first()
        autoplayBy = byId to byName
        autoplay.clear()
        autoplay += songs.distinctBy { it.id }.map { radioItem(it, QueueItemOrigin.AUTOPLAY, autoplayBy) }
        emitAutoplay()
        if (current == null && queue.isEmpty()) {
            wantPlaying = true
            playNextFromQueue()
        }
    }

    /**
     * Keeps the autoplay queue topped up while the queue is (nearly) empty: a radio from the
     * last song that will play. Called with [mutex] held.
     */
    private fun refillAutoplay() {
        val source = radio ?: return
        if (!autoplayActive() || autoplay.size >= AUTOPLAY_LOW || queue.size > 1 || autoplayJob?.isActive == true) return
        val first = (autoplay.lastOrNull() ?: queue.lastOrNull() ?: current ?: history.lastOrNull())?.song ?: return
        // After a podcast episode the room stops, like a podcast app: a music radio from it makes no sense.
        if (first.podcast != null) return
        // If a radio only has songs we just heard, try one from a few other recent songs.
        val recentMusic = history.takeLast(RECENT_SONGS).map { it.song }.filter { it.podcast == null }
        val seeds = (listOf(first) + recentMusic.shuffled().take(2)).distinctBy { it.id }
        autoplayJob = scope.launch {
            var songs: Result<List<Song>> = Result.success(emptyList())
            for (seed in seeds) {
                songs = runCatching { source.radio(seed.id) }
                val skip = mutex.withLock { recentSongIds() }
                if (songs.isFailure || songs.getOrThrow().any { it.id !in skip }) break
            }
            mutex.withLock {
                val start = startWhenAutoplayLoaded
                startWhenAutoplayLoaded = false
                songs.onFailure { e ->
                    if (e is CancellationException) throw e
                    // Only worth a message when the music stops because of it.
                    if (start) emit(Event.Notice("Autoplay couldn't find more songs: ${e.message}"))
                    return@withLock
                }
                if (!autoplayActive()) return@withLock
                val skip = recentSongIds()
                val fresh = songs.getOrThrow().filter { it.id !in skip }.distinctBy { it.id }
                if (fresh.isEmpty()) return@withLock
                autoplay += fresh.map { radioItem(it, QueueItemOrigin.AUTOPLAY, autoplayBy) }
                emitAutoplay()
                if (start && current == null) {
                    wantPlaying = true
                    playNextFromQueue()
                }
            }
        }
    }

    /** Makes [item] the current song and starts resolving its stream. */
    private fun setCurrent(item: QueueItem?) {
        resolveJob?.cancel()
        current = item
        streamUrl = null
        currentStartedAt = clock()
        // An episode that was left halfway goes on from there.
        positionAtAnchor = item?.song?.takeIf { it.podcast != null }?.let { episodePositions[it.id] } ?: 0
        anchorTime = clock()
        if (item == null) wantPlaying = false
        emit(Event.NowPlayingChanged(item))
        emitPlayback()
        if (item != null) resolve(item)
    }

    /** Resolves the stream of the current [item], then starts it (or skips it if it can't play). */
    private fun resolve(item: QueueItem) {
        resolveJob?.cancel()
        resolveJob = scope.launch {
            val started = System.nanoTime()
            val stream = runCatching { streams.resolve(item.song.id) }
            val took = (System.nanoTime() - started) / 1_000_000
            stream.onSuccess { log.info("Room {}: {} \"{}\" ready in {} ms", code, item.song.id, item.song.title, took) }
                .onFailure { if (it !is CancellationException) log.warn("Room {}: {} \"{}\" can't play after {} ms: {}", code, item.song.id, item.song.title, took, it.message) }
            val next = mutex.withLock {
                if (current?.itemId != item.itemId) return@withLock null
                stream.onSuccess {
                    failedInARow = 0
                    exactDuration(item, it.durationMs)
                    streamUrl = it.url
                    anchorTime = clock()
                    emit(Event.StreamReady(item.itemId, it.url))
                    emitPlayback()
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    if (++failedInARow >= MAX_FAILED_IN_A_ROW) {
                        // Probably no internet: don't skip through the whole queue, wait on this song.
                        failedInARow = 0
                        emit(Event.Notice("Couldn't load songs (no internet?). Paused: press play to try again."))
                        pausePlayback()
                        return@withLock null
                    }
                    emit(Event.Notice("Couldn't play \"${item.song.title}\": ${e.message}"))
                    retireCurrent(QueueItemResult.SKIPPED)
                    playNextFromQueue()
                }
                queue.firstOrNull()
            }
            // Warm up the next song so it starts quickly.
            if (next != null) {
                runCatching { streams.resolveStream(next.song.id) }
                    .onFailure { if (it !is CancellationException) log.info("Room {}: warming up the next song {} failed: {}", code, next.song.id, it.message) }
            }
        }
    }

    /**
     * Listings round some lengths (podcasts: "3 hr 36 min") or leave them out, but the song
     * ends by its length. When the stream knows better, the current song gets its exact length.
     */
    private fun exactDuration(item: QueueItem, durationMs: Long?) {
        if (durationMs == null || durationMs <= 0 || abs(durationMs - item.song.durationMs) < DURATION_TOLERANCE_MS) return
        val exact = item.copy(song = item.song.copy(durationMs = durationMs))
        current = exact
        // Clients take it as the same song (same item ID) with a new length; the stream follows.
        emit(Event.NowPlayingChanged(exact))
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
        room = info,
        participants = members.values.map { it.toParticipant() },
        queue = queue.toList(),
        history = history.takeLast(SNAPSHOT_HISTORY),
        nowPlaying = current?.let { NowPlaying(it, streamUrl) },
        playback = playbackStatus(),
        outputs = outputInfos(),
        roles = roles.toList(),
        settings = settings,
        bans = banInfos(),
        autoplay = autoplay.toList(),
        autoplaySeed = autoplaySeed,
        migration = migration,
        adminless = adminless,
    )

    private fun newItem(song: Song, member: Member) =
        QueueItem(Ids.short(), song, member.id, member.name, clock(), result = null)

    private fun emit(event: Event) {
        val message = ServerMessage.EventMessage(++seq, event)
        isPlaying = wantPlaying
        for (member in members.values) member.send(message)
        for (listener in changeListeners) listener()
    }

    private fun invalid(message: String) = ErrorInfo(ErrorCode.INVALID, message)

    private fun notFound() = ErrorInfo(ErrorCode.NOT_FOUND, "Not in the queue")

    companion object {
        private val log = LoggerFactory.getLogger(Room::class.java)
        const val MAX_NAME_LENGTH = 32
        const val MAX_URL_LENGTH = 300
        /** A clone's queue (and autoplay queue) keeps at most this many songs. */
        const val MAX_CLONE_QUEUE = 1000
        const val MAX_MIGRANTS = 200
        /** Everyone gone this long: the music pauses. */
        const val EVERYONE_GONE_PAUSE_MS = 60_000L
        const val PREVIOUS_RESTART_MS = 3_000L
        const val SNAPSHOT_HISTORY = 200
        const val SAVED_HISTORY = 500
        /** A speaker moving to the next song this close to the end means the song finished. */
        const val END_GRACE_MS = 5_000L
        /** A stream length this close to the listed one is the same; no need to update it. */
        const val DURATION_TOLERANCE_MS = 1_500L

        /** Songs that fail to load in a row before the room pauses instead of skipping on. */
        const val MAX_FAILED_IN_A_ROW = 3
        /** Episodes resume from where they were left after this much... */
        const val RESUME_MIN_MS = 30_000L
        /** ...unless less than this was left. */
        const val RESUME_END_MS = 60_000L
        const val MAX_EPISODE_POSITIONS = 200
        /** Fetch more autoplay songs when fewer than this are left. */
        const val AUTOPLAY_LOW = 5
        /** Radio songs don't repeat anything from the last this many songs. */
        const val RECENT_SONGS = 50

        /** Brings back a room saved with [save]. */
        fun restore(
            saved: SavedRoom,
            streams: StreamResolver,
            scope: CoroutineScope,
            onInfoChanged: () -> Unit = {},
            onOwnerChanged: (oldAccount: String?, oldSyncId: String?) -> Unit = { _, _ -> },
            radio: RadioSource? = null,
            /** The code here, when it's not [SavedRoom.code] (a room that moved here, whose code was taken). */
            code: String = saved.code,
            /** The epoch here: one more when it moved here. */
            epoch: Int = saved.epoch,
            clock: () -> Long = System::currentTimeMillis,
        ) = Room(
            code, saved.name, saved.ownerToken, saved.visibility, streams, scope, saved.ownerAccount, radio = radio,
            onInfoChanged = onInfoChanged, onOwnerChanged = onOwnerChanged, clock = clock,
            // Rooms saved before syncing get their ID now.
            syncId = saved.syncId ?: saved.ownerAccount?.let { Ids.token() },
            epoch = epoch,
        ).apply { restore(saved) }
    }
}
