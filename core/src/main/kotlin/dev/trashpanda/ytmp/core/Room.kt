package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.BanInfo
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.EPISODE_SPEEDS
import dev.trashpanda.ytmp.protocol.EpisodeProgress
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.ItemList
import dev.trashpanda.ytmp.protocol.MAX_START_WAIT_SECONDS
import dev.trashpanda.ytmp.protocol.Migrant
import dev.trashpanda.ytmp.protocol.MigrationMode
import dev.trashpanda.ytmp.protocol.MigrationOffer
import dev.trashpanda.ytmp.protocol.NowPlaying
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.PlaybackStatus
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.QueueItemOrigin
import dev.trashpanda.ytmp.protocol.QueueItemResult
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.ROOM_CODE
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.RepeatMode
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomClone
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.SkipVotes
import dev.trashpanda.ytmp.protocol.Song
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
 *
 * The room is where everything meets: who's here, what plays and what comes next. Parts that
 * stand on their own have their own file, used with the room locked: [Member], [RoomRoles]
 * (permissions and ranking), [FairOrder], [QueueLimits], [SkipVote], [PlaybackClock] and
 * [StartTogether], [RoomOutputs] (speakers and TVs) and [Autoplay].
 */
class Room(
    code: String,
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
    /** Where people are in podcast episodes, on their accounts: to resume them in any room. Null: only this room's own. */
    private val bookmarks: EpisodeBookmarks? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : OutputRoom {
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

    /** The room's code; it can change ([Command.SetRoomCode]). */
    @Volatile
    override var code: String = code
        private set

    /** Codes the room had before, newest first: they still lead to it ([RoomManager.get]). */
    @Volatile
    var formerCodes: List<String> = emptyList()
        private set

    /**
     * Takes the new code for the room where it lives (its [RoomManager]), or throws
     * [RoomCodeTaken]. Null: the code can't change (a room on its own, in tests).
     */
    internal var claimCode: ((String) -> Unit)? = null

    /** Joining needs this password (hashed, see [RoomPasswords]); null: none. */
    @Volatile
    private var passwordHash: String? = null

    private val mutex = Mutex()
    private val members = LinkedHashMap<String, Member>()
    private val queue = ArrayList<QueueItem>()
    private val history = ArrayList<QueueItem>()
    private var seq = 0L

    private val autoplay = Autoplay(clock)

    /** Where podcast episodes were left off (song ID -> ms), to resume them. Oldest first. */
    private val episodePositions = LinkedHashMap<String, Long>()

    private var repeat = RepeatMode.OFF

    /** The first song (item id) of the run of queue songs playing now: what repeat queue plays again. */
    private var runStart: String? = null

    /** The speed podcast episodes play at ([Command.SetSpeed]); music always plays at 1. */
    private var episodeSpeed = 1.0

    private val skipVote = SkipVote()
    private val limits = QueueLimits(clock)

    private val roles: RoomRoles
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
        roles = RoomRoles(start.roles)
        settings = start.settings
    }

    private var current: QueueItem? = null
    private var streamUrl: String? = null
    private var currentStartedAt = 0L

    /** The user wants music to play. It actually plays once the stream is resolved. */
    private var wantPlaying = false
    private val playback = PlaybackClock(clock)
    /**
     * A new song waits for the players to load it ([Command.PlayerReady], [outputReady]), so
     * nobody misses its start; then everyone starts it together. At most [RoomSettings.startWaitSeconds].
     */
    private val startGate = StartTogether(clock)

    private val outputs = RoomOutputs()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    private var resolveJob: Job? = null
    private var endJob: Job? = null
    /** Pauses the music when nothing plays it any more (see [pauseWhenNobodyHears]). */
    private var emptyJob: Job? = null

    /** Songs in a row that couldn't load; after [MAX_FAILED_IN_A_ROW] the room pauses (likely no internet). */
    private var failedInARow = 0

    /** Called (with the room locked, so it must not block) when a song finished playing. */
    @Volatile
    var onPlayFinished: ((FinishedPlay) -> Unit)? = null

    /** Called (with the room locked, so it must not block) when a song starts, before its stream is looked up. */
    @Volatile
    var onSongStarted: ((Song) -> Unit)? = null

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
        // A party screen someone here linked (see [addScreen]): it may watch a solo room too.
        val screen = members.values.firstOrNull { it.screen && it.token == hello.guestToken }
        if (visibility == RoomVisibility.PRIVATE && !local && !isOwner && screen == null) {
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
                isOwner -> roles.top.id
                migrant != null && roles.has(migrant.roleId) -> migrant.roleId
                account != null -> settings.defaultAccountRole
                else -> settings.defaultGuestRole
            },
            allow = migrant?.allow?.toSet().orEmpty(),
            deny = migrant?.deny?.toSet().orEmpty(),
            joinedAt = clock(),
        ).also { members[it.id] = it }

        member.outbox?.takeIf { it !== outbox }?.send(ServerMessage.Rejected(RejectReason.REPLACED))
        member.outbox = outbox
        if (!member.screen) member.name = account?.displayName ?: name
        member.accountId = account?.id.takeUnless { member.screen }
        hello.device?.trim()?.take(MAX_NAME_LENGTH)?.let { member.device = it }
        member.accountToken = hello.accountToken.takeIf { account != null }
        member.hideFromHistory = hello.hideFromHistory || account?.hideFromHistory == true
        // Logging in later (same guest token, now with the owner's account) makes you the owner.
        member.isOwner = (member.isOwner || isOwner) && !member.screen
        member.offlineSince = null
        lastActive = clock()

        outbox.send(ServerMessage.Welcome(member.id, member.token, seq, snapshot(), accountId = account?.id, migrationKey = member.migrationKey))
        emit(if (returning == null) Event.ParticipantJoined(member.toParticipant()) else Event.ParticipantUpdated(member.toParticipant()))
        if (adminless && roles.isAdmin(member)) {
            adminless = false
            adminSeenAt = clock()
            emit(Event.AdminlessChanged(false))
        }
        updateSkipVotes()
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
        outbox.send(ServerMessage.Welcome(member.id, member.token, seq, snapshot(), accountId = member.accountId, migrationKey = member.migrationKey))
        member.id
    }

    /**
     * Someone here ([byGuestToken]) shows the room on a TV: a party screen participant, which
     * only watches (no role). Returns the guest token it joins with, or null when [byGuestToken]
     * isn't anyone here or the room has [MAX_SCREENS] already.
     */
    suspend fun addScreen(byGuestToken: String): String? = mutex.withLock {
        val by = members.values.firstOrNull { it.token == byGuestToken && !it.screen } ?: return@withLock null
        if (members.values.count { it.screen } >= MAX_SCREENS) return@withLock null
        val screen = newScreen(SCREEN_NAME, plays = false)
        emit(Event.Notice("${by.name} shows the room on a TV"))
        log.info("Room {}: {} linked a party screen", code, by.name)
        screen.token
    }

    /**
     * Someone here ([byGuestToken]) shows the room on a TV on their own network, with the app
     * (a Chromecast the host can't reach): a party screen named [name] that plays the music
     * too, like their own device, so they need *play on their own device*. Returns its guest
     * token, or why not.
     */
    suspend fun addPlayingScreen(byGuestToken: String, name: String): Result<String> = mutex.withLock {
        val by = members.values.firstOrNull { it.token == byGuestToken && !it.screen }
            ?: return@withLock Result.failure(ScreenRefused(ErrorInfo(ErrorCode.NOT_FOUND, "You're not in this room")))
        need(by, Permission.LISTEN_LOCALLY)?.let { return@withLock Result.failure(ScreenRefused(it)) }
        if (members.values.count { it.screen } >= MAX_SCREENS) return@withLock Result.failure(ScreenRefused(invalid("This room can't show on another screen")))
        val screen = newScreen(name.trim().ifEmpty { SCREEN_NAME }, plays = true)
        emit(Event.Notice("${by.name} shows the room on ${screen.name}"))
        log.info("Room {}: {} shows the party screen on their own TV", code, by.name)
        Result.success(screen.token)
    }

    /** The party screen with [screenToken] goes (its TV stopped showing it). False if there's none. */
    suspend fun removeScreen(screenToken: String): Boolean = mutex.withLock {
        val screen = members.values.firstOrNull { it.screen && it.token == screenToken } ?: return@withLock false
        outputs.screens.entries.removeIf { it.value == screen.id }
        dropScreen(screen)
        true
    }

    /** A party screen participant; [plays]: its page plays the music too (a Chromecast output). */
    private fun newScreen(name: String, plays: Boolean): Member {
        val screen = Member(
            id = Ids.short(), token = Ids.token(), name = name.take(MAX_NAME_LENGTH), isOwner = false, outbox = null, roleId = "",
            offlineSince = clock(), joinedAt = clock(), screen = true,
            allow = if (plays) setOf(Permission.LISTEN_LOCALLY) else emptySet(),
        )
        members[screen.id] = screen
        emit(Event.ParticipantJoined(screen.toParticipant()))
        return screen
    }

    /** An output stops showing the party screen: its screen participant goes. */
    private fun dropScreenOutput(outputId: String) {
        val screen = outputs.screens.remove(outputId)?.let(members::get) ?: return
        dropScreen(screen)
    }

    private fun dropScreen(screen: Member) {
        members.remove(screen.id)
        screen.disconnectAll(RejectReason.KICKED)
        emit(Event.ParticipantLeft(screen.id))
    }

    /** [guestToken] is a party screen's: it may join without an account. */
    suspend fun isScreen(guestToken: String?): Boolean = mutex.withLock {
        guestToken != null && members.values.any { it.screen && it.token == guestToken }
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
        member.speakers = emptyList()
        member.offlineSince = clock()
        emit(Event.ParticipantUpdated(member.toParticipant()))
        pauseWhenNobodyHears(GONE_PAUSE_MS)
        updateSkipVotes()
    }

    /** Something plays the room's music: a device with "Play here" on, speakers near someone's phone, or a speaker or TV. */
    private fun heard() = members.values.any { it.online && (it.listening || it.speakers.isNotEmpty()) } || outputs.active.isNotEmpty()

    /**
     * The last device or speaker playing the music went: the music pauses after [afterMs] (0
     * when it was switched off on purpose; a while when a device disconnected, since a reload or
     * a short network drop isn't leaving). Called with [mutex] held.
     */
    private fun pauseWhenNobodyHears(afterMs: Long) {
        emptyJob?.cancel()
        if (!wantPlaying || heard()) return
        emptyJob = scope.launch {
            delay(afterMs)
            mutex.withLock {
                if (wantPlaying && !heard()) {
                    log.info("Room {}: nothing plays the music any more, pausing", code)
                    pausePlayback()
                    emit(Event.Notice("Nothing is playing the music any more, so it paused"))
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
                    // Who's here, their permissions or the settings may have changed what a vote needs.
                    updateSkipVotes()
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
            formerCodes = formerCodes,
            name = name,
            ownerToken = ownerToken,
            visibility = visibility,
            members = members.values.map { SavedMember(it.id, it.token, it.name, it.isOwner, it.accountId, it.roleId, it.allow.sorted(), it.deny.sorted(), it.joinedAt, it.screen) },
            queue = queue.toList(),
            history = history.takeLast(SAVED_HISTORY),
            current = current,
            positionMs = position(),
            // Someone connected right now counts as activity, so the room isn't deleted as stale on restore.
            lastActive = if (members.values.any { it.online }) clock() else lastActive,
            ownerAccount = ownerAccount,
            roles = roles.all,
            settings = settings,
            bans = bans.map { SavedBan(it.info, it.guestToken) },
            autoplay = autoplay.items.toList(),
            autoplaySeed = autoplay.seed,
            knownPermissions = Permission.entries,
            episodePositions = episodePositions.toMap(),
            syncId = syncId,
            epoch = epoch,
            savedAt = clock(),
            playing = wantPlaying && current != null,
            migrants = migrants.toList(),
            migration = migration,
            passwordHash = passwordHash,
            repeat = repeat,
            runStart = runStart,
            episodeSpeed = episodeSpeed,
        )
    }

    /**
     * The room moved to another of the owner's hosts: everyone here is sent there ([url], if
     * known). Call before closing it.
     */
    suspend fun movedAway(url: String?) = mutex.withLock {
        for (member in members.values) member.disconnectAll(RejectReason.ROOM_MOVED, movedTo = url)
    }

    /** Fills a new room from [saved]. Everyone starts offline and playback starts paused. */
    private fun restore(saved: SavedRoom) {
        val now = clock()
        if (saved.roles != null && saved.settings != null) {
            val upgraded = Permissions.upgrade(saved.roles, saved.knownPermissions?.toSet() ?: Permissions.BEFORE_RADIO)
            val restored = Permissions.sanitize(RoleTemplate(upgraded, saved.settings))
            roles.replaceAll(restored.roles)
            settings = restored.settings.copy(openElsewhere = saved.settings.openElsewhere, alwaysOn = saved.settings.alwaysOn)
        }
        migrants += saved.migrants
        migration = saved.migration
        formerCodes = saved.formerCodes.filter { it != code }.distinct().take(MAX_FORMER_CODES)
        passwordHash = saved.passwordHash
        repeat = saved.repeat
        runStart = saved.runStart
        episodeSpeed = saved.episodeSpeed.takeIf { it in EPISODE_SPEEDS } ?: 1.0
        for (b in saved.bans) bans += Ban(b.info, b.guestToken)
        autoplay.items += saved.autoplay
        autoplay.seed = saved.autoplaySeed
        episodePositions += saved.episodePositions
        for (m in saved.members) {
            val roleId = m.roleId?.takeIf { roles.has(it) || m.screen }
                ?: if (m.isOwner) roles.top.id else if (m.accountId != null) settings.defaultAccountRole else settings.defaultGuestRole
            members[m.id] = Member(
                m.id, m.token, m.name, m.isOwner, outbox = null, roleId = roleId, accountId = m.accountId,
                offlineSince = now, allow = m.allow.toSet(), deny = m.deny.toSet(), joinedAt = m.joinedAt, screen = m.screen,
            )
        }
        queue += saved.queue
        history += saved.history
        // The stream URL has probably expired; it is resolved again when someone presses play.
        current = saved.current
        currentStartedAt = now
        playback.set(saved.positionMs)
        playback.setRate(speedOf(current), moving = false)
        lastActive = saved.lastActive
    }

    fun close() {
        emptyJob?.cancel()
        autoplay.job?.cancel()
        resolveJob?.cancel()
        endJob?.cancel()
        changeListeners.clear()
    }

    // --- outputs ----------------------------------------------------------------------

    /** Called by the host when it finds or loses speakers. */
    suspend fun setAvailableOutputs(devices: List<OutputDevice>) = mutex.withLock {
        for (gone in outputs.setAvailable(devices)) dropScreenOutput(gone)
        emit(Event.OutputsChanged(outputs.infos()))
    }

    /** Called by an output driver with the volume the device actually has. */
    override suspend fun reportOutputVolume(outputId: String, volume: Double): Unit = mutex.withLock {
        if (outputId in outputs.active && outputs.active[outputId] != volume) {
            outputs.active[outputId] = volume
            emit(Event.OutputsChanged(outputs.infos()))
        }
    }

    /**
     * A speaker or TV started playing something else (another app, like Spotify, took it):
     * the room lets go of it instead of taking it back at the next song.
     */
    override suspend fun outputTakenOver(outputId: String): Unit = mutex.withLock {
        if (outputId !in outputs.active) return@withLock
        outputs.active.remove(outputId)
        dropScreenOutput(outputId)
        val name = outputs.device(outputId)?.name ?: "A speaker"
        log.info("Room {}: {} plays something else now, letting go of it", code, name)
        emit(Event.OutputsChanged(outputs.infos()))
        emit(Event.Notice("$name is playing something else now, so this room stopped playing there"))
        pauseWhenNobodyHears(0)
    }

    /** Tells everyone in the room about a problem, e.g. a speaker that couldn't play a song. */
    override suspend fun notice(message: String): Unit = mutex.withLock { emit(Event.Notice(message)) }

    /** [listener] is called (on any thread, without waiting) after every change. Read [view] to see what changed. */
    override fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    override suspend fun view(): RoomView = mutex.withLock {
        RoomView(
            current = current.takeIf { streamUrl != null },
            streamUrl = streamUrl,
            playing = playingNow(),
            positionMs = position(),
            hostTimeMs = clock(),
            starting = startGate.waiting,
            speed = playback.rate,
            activeOutputs = LinkedHashMap(outputs.active),
            screens = outputs.screens.mapNotNull { (output, id) -> members[id]?.let { output to it.token } }.toMap(),
            previous = history.lastOrNull(),
            // Speakers move on to the next song by themselves (and the room follows them). With
            // repeat one, or repeat queue at the end of the queue, the room decides what comes next.
            next = when {
                repeat == RepeatMode.ONE -> null
                queue.isNotEmpty() -> queue.first()
                repeat == RepeatMode.QUEUE -> null
                else -> autoplay.items.firstOrNull()
            },
        )
    }

    /**
     * A speaker skipped by itself (its own buttons, its app, or Google Home): follow it. Only
     * if [fromItemId] is still the current song, so a skip isn't done twice. A speaker moving
     * on at the very end of a song counts as the song having played.
     */
    override suspend fun outputReady(outputId: String, itemId: String): Unit = mutex.withLock { ready(itemId, "o:$outputId") }

    override suspend fun outputSkipped(fromItemId: String, forward: Boolean): Unit = mutex.withLock {
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

    // --- commands ---------------------------------------------------------------------

    /** Runs a command. Returns an error, or null on success. Called with [mutex] held. */
    private fun execute(member: Member, command: Command): ErrorInfo? {
        when (command) {
            is Command.AddSongs -> {
                need(member, Permission.ADD_SONGS)?.let { return it }
                if (command.songs.isEmpty()) return invalid("No songs")
                val (songs, leftOut) = withinLimits(member, command.songs, countPerPerson = true)
                if (songs.isEmpty()) return leftOut
                autoplay.dismissed = false
                val items = songs.map { newItem(it, member) }
                // Fair order: each song in the next round where they have none yet ("play next"
                // too, unless they may reorder the queue anyway).
                if (settings.fairOrder && (command.position == QueuePosition.END || !can(member, Permission.REORDER))) {
                    for (item in items) queue.add(FairOrder.indexFor(member.id, queue, current), item)
                    if (items.size == 1) emit(Event.QueueItemsAdded(items, queue.indexOf(items[0]))) else emit(Event.QueueReplaced(queue.toList()))
                } else {
                    val index = if (command.position == QueuePosition.NEXT) 0 else queue.size
                    queue.addAll(index, items)
                    emit(Event.QueueItemsAdded(items, index))
                }
                if (current == null) {
                    wantPlaying = true
                    playNextFromQueue()
                }
                return leftOut
            }
            is Command.PlayNow -> {
                need(member, Permission.PLAY_NOW)?.let { return it }
                val (songs, leftOut) = withinLimits(member, listOf(command.song), countPerPerson = false)
                if (songs.isEmpty()) return leftOut
                autoplay.dismissed = false
                retireCurrent(QueueItemResult.SKIPPED)
                wantPlaying = true
                setCurrent(newItem(command.song, member))
            }
            Command.ShuffleQueue -> {
                need(member, Permission.REORDER)?.let { return it }
                if (queue.size < 2) return null
                replaceQueue(FairOrder.shuffled(queue, current, settings.fairOrder))
            }
            is Command.SetRepeat -> {
                need(member, Permission.REPEAT)?.let { return it }
                if (repeat != command.mode) {
                    repeat = command.mode
                    emit(Event.RepeatChanged(repeat))
                }
            }
            is Command.VoteSkip -> {
                need(member, Permission.VOTE_SKIP)?.let { return it }
                return vote(member, command.on)
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
                        autoplay.items.remove(item)
                        emit(autoplay.changed())
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
                autoplay.dismissed = true
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
                startRadio(command.song, member.id to member.name, command.songs?.take(MAX_BROUGHT_RADIO))
            }
            is Command.AutoplayFromHere -> {
                need(member, Permission.AUTOPLAY_FROM_HERE)?.let { return it }
                autoplayFromHere(command.song, member.id to member.name, command.songs?.take(MAX_BROUGHT_RADIO))
            }
            is Command.AutoplaySongs -> {
                need(member, Permission.AUTOPLAY_FROM_HERE)?.let { return it }
                if (command.songs.isEmpty()) return invalid("No songs")
                autoplaySongs(command.songs, member.id to member.name)
            }
            Command.Play -> {
                need(member, Permission.PLAY_PAUSE)?.let { return it }
                if (current == null) {
                    if (queue.isEmpty()) return invalid("The queue is empty")
                    wantPlaying = true
                    playNextFromQueue()
                } else if (!wantPlaying) {
                    wantPlaying = true
                    playback.resume()
                    emitPlayback()
                    // A restored room has a current song but no stream yet.
                    if (streamUrl == null && resolveJob?.isActive != true) resolve(current!!)
                }
            }
            Command.Pause -> {
                need(member, Permission.PLAY_PAUSE)?.let { return it }
                pausePlayback()
            }
            is Command.Skip -> {
                if (!mayVoteOrSkip(member)) return need(member, Permission.SKIP)
                if (current == null) return invalid("Nothing is playing")
                // Someone who may only vote: their skip is a vote.
                if (!can(member, Permission.SKIP) && settings.voteSkip) return vote(member, true)
                retireCurrent(QueueItemResult.SKIPPED)
                // Next while paused plays (if they asked, and may press play).
                if (command.play && can(member, Permission.PLAY_PAUSE)) wantPlaying = true
                playNextFromQueue()
            }
            Command.Previous -> {
                if (!can(member, Permission.SKIP) && !(can(member, Permission.VOTE_SKIP) && !settings.voteSkip)) return need(member, Permission.SKIP)
                val last = history.lastOrNull()
                if (position() > PREVIOUS_RESTART_MS || last == null) seek(0) else return jumpTo(last.itemId)
            }
            is Command.Seek -> {
                need(member, Permission.SEEK)?.let { return it }
                if (current == null) return invalid("Nothing is playing")
                seek(command.positionMs)
            }
            is Command.SetSpeed -> {
                need(member, Permission.PLAY_PAUSE)?.let { return it }
                if (command.speed !in EPISODE_SPEEDS) return invalid("Not a speed episodes can play at")
                episodeSpeed = command.speed
                // Only an episode changes speed; music plays at 1 (the speed waits for the next episode).
                if (current?.song?.podcast != null && playback.rate != episodeSpeed) {
                    playback.setRate(episodeSpeed, moving = playingNow())
                    emitPlayback()
                }
            }
            is Command.SetListening -> {
                if (command.on) need(member, Permission.LISTEN_LOCALLY)?.let { return it }
                member.listening = command.on
                member.startsTogether = command.on && command.startsTogether
                emit(Event.ParticipantUpdated(member.toParticipant()))
                if (command.on) emptyJob?.cancel() else pauseWhenNobodyHears(0)
            }
            is Command.SetSpeakers -> {
                val names = command.names.map { it.trim().take(MAX_NAME_LENGTH) }.filter { it.isNotEmpty() }.distinct()
                if (names.isNotEmpty()) need(member, Permission.LISTEN_LOCALLY)?.let { return it }
                if (names.size > MAX_SPEAKERS) return invalid("Too many speakers")
                member.speakersStartTogether = command.startsTogether
                if (names == member.speakers) return null
                member.speakers = names
                emit(Event.ParticipantUpdated(member.toParticipant()))
                if (names.isNotEmpty()) emptyJob?.cancel() else pauseWhenNobodyHears(0)
            }
            is Command.PlayerReady -> ready(command.itemId, if (command.speakers) "${member.id}:speakers" else member.id)
            is Command.SetOutput -> {
                need(member, Permission.CHANGE_OUTPUTS)?.let { return it }
                val device = outputs.device(command.outputId) ?: return ErrorInfo(ErrorCode.NOT_FOUND, "That speaker isn't available")
                if (command.active && command.screen && device.kind != OutputKind.CHROMECAST) return invalid("Only TVs and displays with Chromecast can show the party screen")
                if (command.active) outputs.active.putIfAbsent(command.outputId, null) else outputs.active.remove(command.outputId)
                // The party screen plays the music itself, as a screen participant that may listen.
                if (command.active && command.screen) {
                    if (command.outputId !in outputs.screens) outputs.screens[command.outputId] = newScreen(device.name, plays = true).id
                } else {
                    dropScreenOutput(command.outputId)
                }
                emit(Event.OutputsChanged(outputs.infos()))
                if (command.active) emptyJob?.cancel() else pauseWhenNobodyHears(0)
            }
            is Command.SetOutputVolume -> {
                need(member, Permission.OUTPUT_VOLUME)?.let { return it }
                if (command.outputId !in outputs.active) return ErrorInfo(ErrorCode.NOT_FOUND, "Not playing on that speaker")
                outputs.active[command.outputId] = command.volume.coerceIn(0.0, 1.0)
                emit(Event.OutputsChanged(outputs.infos()))
            }
            is Command.SetDeviceVolume -> {
                if (!command.volume.isFinite()) return invalid("Not a volume")
                val volume = command.volume.coerceIn(0.0, 1.0)
                val target = command.participantId?.let { members[it] ?: return notFoundPerson() } ?: member
                if (target !== member) {
                    // Only your own devices: someone here with the same account.
                    if (member.accountId == null || target.accountId != member.accountId || target.screen) {
                        return ErrorInfo(ErrorCode.PERMISSION_DENIED, "That's not one of your devices")
                    }
                    target.volumeChanges++
                } else if (target.volume == volume) {
                    return null
                }
                target.volume = volume
                emit(Event.ParticipantUpdated(target.toParticipant()))
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
                if (target.screen) return invalid("A party screen can't own the room")
                changeOwner(target)
            }
            is Command.LeaveRoom -> return leave(member, command.newOwnerId, command.stayOwner)
            is Command.SetRoomCode -> return setRoomCode(member, command.code)
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
            is Command.CreateRole, is Command.UpdateRole, is Command.DeleteRole, is Command.MoveRole ->
                return roles.handle(member, command, setOf(settings.defaultGuestRole, settings.defaultAccountRole), ::emit, ::roleDeleted)
            is Command.Kick, is Command.Ban, is Command.Unban, is Command.AssignRole, is Command.SetParticipantPermissions -> return managePeople(member, command)
            is Command.UpdateSettings -> return updateSettings(member, command)
        }
        return null
    }

    private fun setRoomCode(member: Member, code: String): ErrorInfo? {
        need(member, Permission.CHANGE_SETTINGS)?.let { return it }
        val wanted = code.trim().uppercase()
        if (!ROOM_CODE.matches(wanted)) return invalid("Room codes are 3 to 12 letters and digits")
        if (wanted == this.code) return null
        val claim = claimCode ?: return invalid("This room's code can't be changed here")
        try {
            claim(wanted)
        } catch (e: RoomCodeTaken) {
            return invalid("The code $wanted is taken. Pick another one")
        }
        log.info("Room {} is now {}", this.code, wanted)
        formerCodes = (listOf(this.code) + formerCodes - wanted).take(MAX_FORMER_CODES)
        this.code = wanted
        emit(Event.RoomUpdated(info))
        onInfoChanged()
        return null
    }

    // --- people, roles, settings --------------------------------------------------------

    /** Kicks, bans, and people's roles and permissions. Called with [mutex] held. */
    private fun managePeople(actor: Member, command: Command): ErrorInfo? {
        when (command) {
            is Command.Kick, is Command.Ban -> {
                val targetId = if (command is Command.Kick) command.participantId else (command as Command.Ban).participantId
                val ban = command is Command.Ban
                need(actor, if (ban) Permission.BAN else Permission.KICK)?.let { return it }
                val target = members[targetId] ?: return notFoundPerson()
                if (target === actor) return invalid("You can't remove yourself")
                if (!roles.outranks(actor, target)) return RoomRoles.outranked()
                members.remove(target.id)
                if (ban) bans += Ban(BanInfo(Ids.short(), target.name, target.accountId), target.token)
                target.disconnectAll(if (ban) RejectReason.BANNED else RejectReason.KICKED)
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
                roles.assign(actor, target, command.roleId)?.let { return it }
                emit(Event.ParticipantUpdated(target.toParticipant()))
            }
            is Command.SetParticipantPermissions -> {
                need(actor, Permission.ASSIGN_ROLES)?.let { return it }
                val target = members[command.participantId] ?: return notFoundPerson()
                if (!roles.outranks(actor, target)) return RoomRoles.outranked()
                val allow = command.allow.toSet()
                roles.cannotGrant(actor, allow - target.allow)?.let { return it }
                target.allow = allow
                target.deny = command.deny.toSet() - allow
                emit(Event.ParticipantUpdated(target.toParticipant()))
            }
            else -> Unit
        }
        return null
    }

    /** A role was deleted: whoever had it gets the role new people get. */
    private fun roleDeleted(roleId: String) {
        for (m in members.values.filter { it.roleId == roleId }) {
            m.roleId = if (m.accountId != null) settings.defaultAccountRole else settings.defaultGuestRole
            emit(Event.ParticipantUpdated(m.toParticipant()))
        }
    }

    private fun updateSettings(actor: Member, command: Command.UpdateSettings): ErrorInfo? {
        need(actor, Permission.CHANGE_SETTINGS)?.let { return it }
        val newName = command.name?.trim()
        if (newName != null && (newName.isEmpty() || newName.length > 64)) return invalid("Room names are 1–64 characters")
        if (listOfNotNull(command.defaultGuestRole, command.defaultAccountRole).any { !roles.has(it) }) return RoomRoles.notFound()
        if (command.voteSkipPercent != null && command.voteSkipPercent !in 1..100) return invalid("The share of votes is 1–100%")
        val newLimits = listOfNotNull(command.maxSongsPerPerson, command.maxSongMinutes, command.noRepeatMinutes)
        if (newLimits.any { it !in 0..Permissions.MAX_LIMIT }) return invalid("Limits are 0 (off) to ${Permissions.MAX_LIMIT}")
        if (command.startWaitSeconds != null && command.startWaitSeconds !in 0..MAX_START_WAIT_SECONDS) return invalid("Waiting for the players is 0 (off) to $MAX_START_WAIT_SECONDS seconds")
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
            fairOrder = command.fairOrder ?: settings.fairOrder,
            voteSkip = command.voteSkip ?: settings.voteSkip,
            voteSkipPercent = command.voteSkipPercent ?: settings.voteSkipPercent,
            maxSongsPerPerson = command.maxSongsPerPerson ?: settings.maxSongsPerPerson,
            maxSongMinutes = command.maxSongMinutes ?: settings.maxSongMinutes,
            noRepeatMinutes = command.noRepeatMinutes ?: settings.noRepeatMinutes,
            startWaitSeconds = command.startWaitSeconds ?: settings.startWaitSeconds,
            karaoke = command.karaoke ?: settings.karaoke,
        )
        if (updated == settings) return null
        val autoplayChanged = updated.autoplay != settings.autoplay
        val fairNow = updated.fairOrder && !settings.fairOrder
        settings = updated
        emit(Event.SettingsChanged(settings))
        if (autoplayChanged) {
            autoplay.dismissed = false
            if (!settings.autoplay) clearAutoplay() else refillAutoplay()
        }
        // Fair order from now on: the queue is put in rounds right away.
        if (fairNow && queue.size > 1) {
            val fair = FairOrder.inRounds(queue, current)
            if (fair != queue) replaceQueue(fair)
        }
        if (!settings.voteSkip) skipVote.clear()
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
                m.roleId = roles.top.id
                emit(Event.ParticipantUpdated(m.toParticipant()))
            }
        }
        target.isOwner = true
        target.roleId = roles.top.id
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
                .filter { it !== member && !it.isOwner && it.online && it.roleId == roles.top.id }
                .minByOrNull { it.joinedAt }
            if (next != null) changeOwner(next)
        }
        members.remove(member.id)
        member.dropAttached(RejectReason.LEFT)
        member.outbox = null
        emit(Event.ParticipantLeft(member.id))
        // The last one out: no need to wait.
        pauseWhenNobodyHears(0)
        return null
    }

    /** Forced migration: everyone but [sender] is sent to [url] and leaves; the music stops here. */
    private fun moveEveryone(sender: Member, url: String) {
        pausePlayback()
        for (m in members.values.filter { it !== sender }) {
            members.remove(m.id)
            m.disconnectAll(RejectReason.MIGRATED, movedTo = url)
            emit(Event.ParticipantLeft(m.id))
        }
    }

    /**
     * Marks a party room as [RoomState.adminless] when nobody who runs it has been connected
     * for [afterMs], so people know they can clone it. Called now and then by the host.
     */
    suspend fun checkAdminless(afterMs: Long) = mutex.withLock {
        val now = clock()
        if (members.values.any { it.online && roles.isAdmin(it) }) adminSeenAt = now
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
            roles.replaceAll(copied.roles)
            settings = copied.settings
        }
        queue += clone.queue.take(MAX_CLONE_QUEUE)
        history += clone.history.takeLast(SAVED_HISTORY)
        autoplay.items += clone.autoplay.take(MAX_CLONE_QUEUE)
        autoplay.seed = clone.autoplaySeed
        current = clone.current
        currentStartedAt = clock()
        playback.set(clone.positionMs.coerceAtLeast(0))
        migrants += clone.migrants.filter { it.tag.isNotBlank() }.distinctBy { it.tag }.take(MAX_MIGRANTS)
    }

    private fun can(member: Member, permission: Permission) = roles.can(member, permission)

    private fun need(member: Member, permission: Permission) = roles.need(member, permission)

    private fun notFoundPerson() = ErrorInfo(ErrorCode.NOT_FOUND, "That person isn't in the room")

    private fun banInfos() = bans.map { it.info }

    // --- the queue, the history and the autoplay queue ------------------------------------

    /** The list an item is in (not the current song). */
    private fun find(itemId: String): Pair<ItemList, QueueItem>? =
        queue.firstOrNull { it.itemId == itemId }?.let { ItemList.QUEUE to it }
            ?: history.firstOrNull { it.itemId == itemId }?.let { ItemList.HISTORY to it }
            ?: autoplay.items.firstOrNull { it.itemId == itemId }?.let { ItemList.AUTOPLAY to it }

    private fun listOf(list: ItemList): MutableList<QueueItem> = when (list) {
        ItemList.HISTORY -> history
        ItemList.QUEUE -> queue
        ItemList.AUTOPLAY -> autoplay.items
    }

    /** The queue becomes [items] (the same songs in another order, or none). */
    private fun replaceQueue(items: List<QueueItem>) {
        queue.clear()
        queue += items
        emit(Event.QueueReplaced(queue.toList()))
    }

    private fun moveItem(member: Member, command: Command.MoveItem): ErrorInfo? {
        val playing = current
        if (playing != null && playing.itemId == command.itemId) return moveCurrent(member, command)
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
        val to = if (adding && settings.fairOrder && !can(member, Permission.REORDER)) FairOrder.indexFor(member.id, queue, current)
        else (command.toIndex + if (command.list == ItemList.HISTORY) historyOffset else 0).coerceIn(0, target.size)
        target.add(to, item)

        if (from == ItemList.QUEUE && command.list == ItemList.QUEUE) {
            emit(Event.QueueItemMoved(item.itemId, to))
        } else {
            for (list in setOf(from, command.list)) when (list) {
                ItemList.QUEUE -> emit(Event.QueueReplaced(queue.toList()))
                ItemList.HISTORY -> emit(Event.HistoryReplaced(history.takeLast(SNAPSHOT_HISTORY)))
                ItemList.AUTOPLAY -> emit(autoplay.changed())
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

    /**
     * The current song dragged somewhere else, like YTM's one list: it keeps playing at its new
     * place, and the songs it was dragged past change sides. Down past queued songs: they count
     * as played (skipped); into autoplay: the whole queue too, and the autoplay songs before it
     * go (they never played). Up past played songs: they play again, after it.
     */
    private fun moveCurrent(member: Member, command: Command.MoveItem): ErrorInfo? {
        need(member, Permission.REORDER)?.let { return it }
        when (command.list) {
            ItemList.QUEUE, ItemList.AUTOPLAY -> {
                val passed = if (command.list == ItemList.QUEUE) command.toIndex.coerceIn(0, queue.size) else queue.size
                skipQueued(passed)
                if (command.list == ItemList.AUTOPLAY) {
                    val dropped = command.toIndex.coerceIn(0, autoplay.items.size)
                    if (dropped > 0) {
                        autoplay.items.subList(0, dropped).clear()
                        emit(autoplay.changed())
                    }
                }
            }
            ItemList.HISTORY -> {
                // History indexes count from the first song clients see (the snapshot only has the last ones).
                val from = (command.toIndex + maxOf(0, history.size - SNAPSHOT_HISTORY)).coerceIn(0, history.size)
                val reopened = history.subList(from, history.size).toList()
                if (reopened.isEmpty()) return null
                history.subList(from, history.size).clear()
                reopened.forEach { emit(Event.HistoryItemRemoved(it.itemId)) }
                val items = reopened.map { it.copy(result = null) }
                queue.addAll(0, items)
                emit(Event.QueueItemsAdded(items, 0))
            }
        }
        refillAutoplay()
        return null
    }

    /** The first [count] queued songs go to the history as skipped. */
    private fun skipQueued(count: Int) {
        repeat(count) {
            val skipped = queue.removeAt(0)
            emit(Event.QueueItemRemoved(skipped.itemId))
            appendHistory(skipped, QueueItemResult.SKIPPED)
        }
    }

    private fun jumpTo(itemId: String): ErrorInfo? {
        val autoplayIndex = autoplay.items.indexOfFirst { it.itemId == itemId }
        if (autoplayIndex >= 0) {
            // Like YTM: the autoplay songs before it are dropped (they never played).
            retireCurrent(QueueItemResult.SKIPPED)
            val item = autoplay.items[autoplayIndex]
            autoplay.items.subList(0, autoplayIndex + 1).clear()
            emit(autoplay.changed())
            wantPlaying = true
            setCurrent(item)
            refillAutoplay()
            return null
        }
        val queueIndex = queue.indexOfFirst { it.itemId == itemId }
        if (queueIndex >= 0) {
            // Songs before the target are skipped, like jumping ahead in YTM.
            retireCurrent(QueueItemResult.SKIPPED)
            skipQueued(queueIndex)
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

    /**
     * Repeat queue, and the queue ran out: the songs of this run (since the queue last started
     * playing, autoplay songs left out) are queued again.
     */
    private fun requeueRun() {
        val start = runStart?.let { id -> history.indexOfLast { it.itemId == id } } ?: -1
        val run = if (start >= 0) history.subList(start, history.size).toList() else history.takeLastWhile { it.origin != QueueItemOrigin.AUTOPLAY }
        val items = run.filter { it.origin != QueueItemOrigin.AUTOPLAY }.takeLast(MAX_REPEAT_RUN)
            .map { it.copy(itemId = Ids.short(), result = null) }
        if (items.isEmpty()) return
        queue += items
        emit(Event.QueueItemsAdded(items, 0))
        // The first of them starts the next run.
        runStart = null
    }

    // --- votes and limits ---------------------------------------------------------------

    /** May skip right away, or vote to (also right away when the room doesn't vote). */
    private fun mayVoteOrSkip(member: Member) = can(member, Permission.SKIP) || can(member, Permission.VOTE_SKIP)

    private fun vote(member: Member, on: Boolean): ErrorInfo? {
        if (!settings.voteSkip) return invalid("This room doesn't vote to skip")
        if (current == null) return invalid("Nothing is playing")
        skipVote.vote(member.id, on)
        return null
    }

    /** The vote on the current song, or null when the room doesn't vote (or nothing plays). */
    private fun skipVotes(): SkipVotes? =
        skipVote.votes(settings, playing = current != null, people = members.values.count { it.online && mayVoteOrSkip(it) })

    /**
     * Tells everyone when the vote changed (a vote, people coming or going, the settings), and
     * skips the song once enough people voted. Called with [mutex] held.
     */
    private fun updateSkipVotes() {
        skipVote.keepOnly { id -> members[id]?.let { it.online && mayVoteOrSkip(it) } == true }
        val votes = skipVotes()
        if (skipVote.changed(votes)) emit(Event.SkipVotesChanged(votes))
        val item = current
        if (votes == null || item == null || !votes.passed()) return
        log.info("Room {}: {} voted to skip \"{}\"", code, votes.voters.size, item.song.title)
        emit(Event.Notice("Skipped \"${item.song.title}\": enough votes"))
        retireCurrent(QueueItemResult.SKIPPED)
        playNextFromQueue()
        // A new song: no votes yet.
        updateSkipVotes()
    }

    /**
     * The room's queue limits ([QueueLimits]): the songs of [songs] that [member] may add, and
     * why the others were left out (null: none were). People who can change the room's
     * settings aren't limited (they could turn the limits off anyway). [countPerPerson]: the
     * songs stay in the queue (adding), so the songs-per-person limit counts too.
     */
    private fun withinLimits(member: Member, songs: List<Song>, countPerPerson: Boolean): Pair<List<Song>, ErrorInfo?> {
        if (can(member, Permission.CHANGE_SETTINGS)) return songs to null
        return limits.check(settings, songs, queuedByThem = if (countPerPerson) queue.count { it.addedBy == member.id } else null)
    }

    // --- playback ---------------------------------------------------------------------

    private fun pausePlayback() {
        if (!wantPlaying) return
        current?.takeIf { it.song.podcast != null && streamUrl != null }?.let { bookmark(it.song, position()) }
        playback.set(position())
        wantPlaying = false
        startGate.stop()
        emitPlayback()
    }

    /** The song plays now (not paused, its stream is ready, and it isn't waiting for the players). */
    private fun playingNow() = wantPlaying && streamUrl != null && !startGate.waiting

    private fun position(): Long = playback.position(playingNow())

    private fun seek(positionMs: Long) {
        val duration = current?.song?.durationMs ?: 0
        playback.set(positionMs.coerceIn(0, maxOf(0, duration)))
        // Seeking while it waits for the players: it plays from there now.
        startGate.stop()
        emitPlayback()
    }

    /** Players and outputs that should load a starting song and haven't said so yet. */
    private fun notReady(): List<String> = startGate.missing(
        buildList {
            for (m in members.values) {
                if (!m.online) continue
                if (m.listening && m.startsTogether) add(m.id)
                if (m.speakers.isNotEmpty() && m.speakersStartTogether) add("${m.id}:speakers")
            }
            // A TV showing the party screen plays through its page, a participant.
            for (id in outputs.active.keys) if (id !in outputs.screens) add("o:$id")
        },
    )

    /** The new song's stream is ready: wait for the players to load it, if any will say so. */
    private fun waitForPlayers() {
        startGate.reset()
        if (!wantPlaying || settings.startWaitSeconds <= 0 || notReady().isEmpty()) return
        startGate.wait(scope, mutex) {
            val late = notReady()
            // Someone left or stopped listening (late is empty then), or it waited long enough.
            if (startGate.waiting && (late.isEmpty() || startGate.waitedMs >= settings.startWaitSeconds * 1000L)) startTogether(late)
        }
    }

    private fun ready(itemId: String, who: String) {
        if (!startGate.waiting || current?.itemId != itemId) return
        startGate.has(who)
        if (notReady().isEmpty()) startTogether(emptyList())
    }

    /** Everyone has the song (or it waited long enough for [late]): it starts now, for all of them. */
    private fun startTogether(late: List<String>) {
        log.info("Room {}: starts {} after {} ms{}", code, current?.song?.id, startGate.waitedMs, if (late.isEmpty()) "" else " without $late")
        startGate.stop()
        playback.resume()
        emitPlayback()
    }

    private fun retireCurrent(result: QueueItemResult) {
        val item = current ?: return
        if (item.song.podcast != null) {
            val at = if (result == QueueItemResult.PLAYED) item.song.durationMs else position()
            rememberEpisodePosition(item.song, at)
            if (streamUrl != null) bookmark(item.song, at)
        }
        reportFinished(item, result)
        current = null
        appendHistory(item, result)
    }

    /** For the listening history; only songs that actually played count. */
    private fun reportFinished(item: QueueItem, result: QueueItemResult) {
        if (streamUrl == null) return
        val heard = if (result == QueueItemResult.PLAYED) item.song.durationMs else position()
        val listeners = members.values.filter { it.online && !it.screen }.map {
            FinishedPlay.Listener(it.id, it.name, it.accountId, it.accountToken, it.hideFromHistory)
        }
        onPlayFinished?.invoke(FinishedPlay(item, currentStartedAt, heard, result == QueueItemResult.SKIPPED, code, name, listeners, visibility))
    }

    /** Repeat one: the song ended and starts over (it counts as played for the listening history). */
    private fun replayCurrent() {
        val item = current ?: return
        reportFinished(item, QueueItemResult.PLAYED)
        onSongStarted?.invoke(item.song)
        currentStartedAt = clock()
        limits.started(item.song.id)
        playback.set(0)
        skipVote.clear()
        emitPlayback()
    }

    /** Remembers where an episode was left; one that's (nearly) done or barely started starts over. */
    private fun rememberEpisodePosition(song: Song, positionMs: Long) {
        episodePositions.remove(song.id)
        if (positionMs < RESUME_MIN_MS || positionMs > song.durationMs - RESUME_END_MS) return
        episodePositions[song.id] = positionMs
        while (episodePositions.size > MAX_EPISODE_POSITIONS) episodePositions.remove(episodePositions.keys.first())
    }

    /** Everyone listening is at [positionMs] in [episode]: their accounts keep it ([bookmarks]). */
    private fun bookmark(episode: Song, positionMs: Long) {
        val store = bookmarks ?: return
        for (m in members.values) if (m.online && !m.screen) m.accountToken?.let { store.report(it, episode, positionMs) }
    }

    /**
     * An episode starts where whoever added it stopped, on any room or device ([bookmarks]),
     * unless this room has a place of its own for it or it was played. Before its stream is ready.
     */
    private fun resumeFrom(progress: EpisodeProgress?, item: QueueItem) {
        if (progress == null || progress.played || position() >= RESUME_MIN_MS) return
        val at = progress.positionMs
        val duration = item.song.durationMs
        if (at >= RESUME_MIN_MS && (duration <= 0 || at <= duration - RESUME_END_MS)) playback.set(at)
    }

    private fun appendHistory(item: QueueItem, result: QueueItemResult) {
        val done = item.copy(result = result)
        history.add(done)
        emit(Event.HistoryAppended(done))
    }

    private fun playNextFromQueue() {
        if (queue.isEmpty() && repeat == RepeatMode.QUEUE) requeueRun()
        val next = queue.removeFirstOrNull()?.also { emit(Event.QueueItemRemoved(it.itemId)) }
            ?: autoplay.items.removeFirstOrNull()?.also { emit(autoplay.changed()) }
        // Out of songs, but autoplay is still loading: it starts when it's there.
        autoplay.startWhenLoaded = next == null && wantPlaying && autoplayActive()
        setCurrent(next)
        refillAutoplay()
    }

    /** Makes [item] the current song and starts resolving its stream. */
    private fun setCurrent(item: QueueItem?) {
        resolveJob?.cancel()
        current = item
        // A run of queue songs (for repeat queue) starts with the first that isn't autoplay's.
        if (item == null || item.origin == QueueItemOrigin.AUTOPLAY) runStart = null else if (runStart == null) runStart = item.itemId
        skipVote.clear()
        if (item != null) limits.started(item.song.id)
        streamUrl = null
        startGate.stop()
        currentStartedAt = clock()
        // An episode that was left halfway goes on from there, at the room's episode speed.
        playback.set(item?.song?.takeIf { it.podcast != null }?.let { episodePositions[it.id] } ?: 0)
        playback.setRate(speedOf(item), moving = false)
        if (item == null) wantPlaying = false
        emit(Event.NowPlayingChanged(item))
        emitPlayback()
        if (item != null) {
            onSongStarted?.invoke(item.song)
            resolve(item)
        }
    }

    /** Resolves the stream of the current [item], then starts it (or skips it if it can't play). */
    private fun resolve(item: QueueItem) {
        resolveJob?.cancel()
        // An episode: where whoever added it stopped, looked up while the stream loads.
        val bookmarkToken = item.song.takeIf { it.podcast != null && bookmarks != null }?.let { members[item.addedBy]?.accountToken }
        resolveJob = scope.launch {
            val progress = bookmarkToken?.let { token ->
                async {
                    try {
                        bookmarks?.progress(token, item.song.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.info("Room {}: no episode progress for {}: {}", code, item.song.id, e.message)
                        null
                    }
                }
            }
            val started = System.nanoTime()
            val stream = runCatching { streams.resolve(item.song.id) }
            val took = (System.nanoTime() - started) / 1_000_000
            stream.onSuccess { log.info("Room {}: {} \"{}\" ready in {} ms", code, item.song.id, item.song.title, took) }
                .onFailure { if (it !is CancellationException) log.warn("Room {}: {} \"{}\" can't play after {} ms: {}", code, item.song.id, item.song.title, took, it.message) }
            // Not while holding the room: the lookup may take a moment.
            val saved = progress?.await()
            val next = mutex.withLock {
                if (current?.itemId != item.itemId) return@withLock null
                stream.onSuccess {
                    failedInARow = 0
                    exactDuration(item, it.durationMs)
                    resumeFrom(saved, current ?: item)
                    streamUrl = it.url
                    playback.resume()
                    emit(Event.StreamReady(item.itemId, it.url))
                    waitForPlayers()
                    emitPlayback()
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    if (e is SourceBlocked) {
                        failedInARow = 0
                        emit(Event.Notice("Paused: ${e.message}"))
                        pausePlayback()
                        return@withLock null
                    }
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
        if (!playingNow() || item.song.durationMs <= 0) return
        // At another speed, the rest of the song takes longer or shorter.
        val remaining = ((item.song.durationMs - position()) / playback.rate).toLong()
        endJob = scope.launch {
            delay(maxOf(0, remaining))
            mutex.withLock {
                if (current?.itemId != item.itemId) return@withLock
                if (repeat == RepeatMode.ONE) {
                    replayCurrent()
                    return@withLock
                }
                retireCurrent(QueueItemResult.PLAYED)
                playNextFromQueue()
            }
        }
    }

    // --- radio and autoplay -----------------------------------------------------------

    private fun autoplayActive() = autoplay.active(hasRadio = radio != null, setting = settings.autoplay)

    private fun clearAutoplay() {
        if (autoplay.clear()) emit(autoplay.changed())
    }

    /** Songs that shouldn't come up again soon. */
    private fun recentSongIds(): Set<String> =
        (history.takeLast(RECENT_SONGS) + queue + autoplay.items + listOfNotNull(current)).map { it.song.id }.toSet()

    /** Nothing plays and nothing is queued: start what autoplay has. */
    private fun startIfIdle() {
        if (current == null && queue.isEmpty()) {
            wantPlaying = true
            playNextFromQueue()
        }
    }

    /**
     * Start radio: plays [song] now, and the queue becomes a radio from it. On the song that's
     * playing, it keeps playing (like YTM) and only the queue changes. [brought]: the radio the
     * one who starts it brought (their own account's); null: the room's.
     */
    private fun startRadio(song: Song, by: Pair<String, String>, brought: List<Song>? = null) {
        val source = radio
        if (source == null && brought == null) return
        scope.launch {
            val songs = runCatching { brought ?: source!!.radio(song.id) }
            mutex.withLock {
                songs.onFailure { e ->
                    if (e is CancellationException) throw e
                    emit(Event.Notice("Couldn't start a radio from \"${song.title}\": ${e.message}"))
                    return@withLock
                }
                // The queue is cleared and the radio goes into autoplay, the song itself first:
                // it plays from there, so it's heard once, and the radio keeps going after it.
                autoplay.job?.cancel()
                val keepPlaying = current?.song?.id == song.id
                if (!keepPlaying) retireCurrent(QueueItemResult.SKIPPED)
                if (queue.isNotEmpty()) replaceQueue(emptyList())
                val radioSongs = listOf(song).takeUnless { keepPlaying }.orEmpty() + songs.getOrThrow().filter { it.id != song.id }
                autoplay.replace(song, by, radioSongs, QueueItemOrigin.RADIO)
                emit(autoplay.changed())
                if (keepPlaying) return@withLock
                wantPlaying = true
                playNextFromQueue()
            }
        }
    }

    /** Autoplay from here: the autoplay queue becomes a radio from [song]; the queue stays. */
    private fun autoplayFromHere(song: Song, by: Pair<String, String>, brought: List<Song>? = null) {
        val source = radio
        if (source == null && brought == null) return
        autoplay.job?.cancel()
        autoplay.job = scope.launch {
            val songs = runCatching { brought ?: source!!.radio(song.id) }
            mutex.withLock {
                songs.onFailure { e ->
                    if (e is CancellationException) throw e
                    emit(Event.Notice("Couldn't load songs like \"${song.title}\": ${e.message}"))
                    return@withLock
                }
                val skip = recentSongIds() - autoplay.items.map { it.song.id }.toSet()
                autoplay.replace(song, by, songs.getOrThrow().filter { it.id != song.id && it.id !in skip }, QueueItemOrigin.AUTOPLAY)
                emit(autoplay.changed())
                startIfIdle()
            }
        }
    }

    /** A playlist as the autoplay queue; when it runs low, the radio goes on from its last song. Called with [mutex] held. */
    private fun autoplaySongs(songs: List<Song>, by: Pair<String, String>) {
        autoplay.job?.cancel()
        autoplay.startWhenLoaded = false
        autoplay.replace(songs.first(), by, songs, QueueItemOrigin.AUTOPLAY)
        emit(autoplay.changed())
        startIfIdle()
    }

    /**
     * Keeps the autoplay queue topped up while the queue is (nearly) empty: a radio from the
     * last song that will play. Called with [mutex] held.
     */
    private fun refillAutoplay() {
        val source = radio ?: return
        // Repeating the queue, the autoplay queue never plays: no need for more songs.
        if (repeat == RepeatMode.QUEUE) return
        if (!autoplayActive() || autoplay.items.size >= AUTOPLAY_LOW || queue.size > 1 || autoplay.loading) return
        val first = (autoplay.items.lastOrNull() ?: queue.lastOrNull() ?: current ?: history.lastOrNull())?.song ?: return
        // After a podcast episode the room stops, like a podcast app: a music radio from it makes no sense.
        if (first.podcast != null) return
        // If a radio only has songs we just heard, try one from a few other recent songs.
        val recentMusic = history.takeLast(RECENT_SONGS).map { it.song }.filter { it.podcast == null }
        val seeds = (listOf(first) + recentMusic.shuffled().take(2)).distinctBy { it.id }
        autoplay.job = scope.launch {
            var songs: Result<List<Song>> = Result.success(emptyList())
            for (seed in seeds) {
                songs = runCatching { source.radio(seed.id) }
                val skip = mutex.withLock { recentSongIds() }
                if (songs.isFailure || songs.getOrThrow().any { it.id !in skip }) break
            }
            mutex.withLock {
                val start = autoplay.startWhenLoaded
                autoplay.startWhenLoaded = false
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
                autoplay.add(fresh)
                emit(autoplay.changed())
                if (start && current == null) {
                    wantPlaying = true
                    playNextFromQueue()
                }
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------

    private fun playbackStatus() = PlaybackStatus(
        playing = playingNow(),
        positionMs = position(),
        hostTimeMs = clock(),
        starting = startGate.waiting,
        speed = playback.rate,
    )

    /** How fast [item] plays: an episode at the room's episode speed, music at 1. */
    private fun speedOf(item: QueueItem?) = if (item?.song?.podcast != null) episodeSpeed else 1.0

    private fun snapshot() = RoomState(
        room = info,
        participants = members.values.map { it.toParticipant() },
        queue = queue.toList(),
        history = history.takeLast(SNAPSHOT_HISTORY),
        nowPlaying = current?.let { NowPlaying(it, streamUrl) },
        playback = playbackStatus(),
        outputs = outputs.infos(),
        roles = roles.all,
        settings = settings,
        bans = banInfos(),
        autoplay = autoplay.items.toList(),
        autoplaySeed = autoplay.seed,
        migration = migration,
        adminless = adminless,
        repeat = repeat,
        skipVotes = skipVotes(),
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
        /** Party screens one room can have. */
        const val MAX_SCREENS = 5
        /** Speakers one person plays the room on near their phone ([Command.SetSpeakers]). */
        const val MAX_SPEAKERS = 10
        const val SCREEN_NAME = "Party screen"
        /** A radio someone brings ([Command.StartRadio.songs]) keeps at most this many songs. */
        const val MAX_BROUGHT_RADIO = 100
        /** The last listening device disconnected this long ago: the music pauses (a reload isn't leaving). */
        const val GONE_PAUSE_MS = 60_000L
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
        /** Repeat queue plays at most this many songs again. */
        const val MAX_REPEAT_RUN = 500
        /** Earlier codes a room keeps leading to. */
        const val MAX_FORMER_CODES = 10

        /** Brings back a room saved with [save]. */
        fun restore(
            saved: SavedRoom,
            streams: StreamResolver,
            scope: CoroutineScope,
            onInfoChanged: () -> Unit = {},
            onOwnerChanged: (oldAccount: String?, oldSyncId: String?) -> Unit = { _, _ -> },
            radio: RadioSource? = null,
            bookmarks: EpisodeBookmarks? = null,
            /** The code here, when it's not [SavedRoom.code] (a room that moved here, whose code was taken). */
            code: String = saved.code,
            /** The epoch here: one more when it moved here. */
            epoch: Int = saved.epoch,
            clock: () -> Long = System::currentTimeMillis,
        ) = Room(
            code, saved.name, saved.ownerToken, saved.visibility, streams, scope, saved.ownerAccount, radio = radio,
            onInfoChanged = onInfoChanged, onOwnerChanged = onOwnerChanged, bookmarks = bookmarks, clock = clock,
            // Rooms saved before syncing get their ID now.
            syncId = saved.syncId ?: saved.ownerAccount?.let { Ids.token() },
            epoch = epoch,
        ).apply { restore(saved) }
    }
}

/** A party screen can't be added: [error] says why. */
class ScreenRefused(val error: ErrorInfo) : Exception(error.message)
