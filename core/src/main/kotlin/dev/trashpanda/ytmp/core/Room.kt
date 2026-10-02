package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.BanInfo
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.NowPlaying
import dev.trashpanda.ytmp.protocol.OutputInfo
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Participant
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.PlaybackStatus
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.QueueItemResult
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoleTemplate
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

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
    val ownerToken: String,
    visibility: RoomVisibility,
    private val streams: StreamResolver,
    private val scope: CoroutineScope,
    /** The account that created the room: it is the owner on any device. */
    val ownerAccount: String? = null,
    /** The roles the room starts with (the creator's template, or the defaults). */
    template: RoleTemplate = DefaultRoles.template,
    /** Called when the room's [info] changes, e.g. it was made public. */
    private val onInfoChanged: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var visibility: RoomVisibility = visibility
        private set

    @Volatile
    var name: String = name
        private set

    val info: RoomInfo get() = RoomInfo(code, name, visibility)

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
    ) {
        fun toParticipant() = Participant(
            id, name, isOwner, online = outbox != null, listening = listening, accountId = accountId,
            roleId = roleId, allow = allow.sorted(), deny = deny.sorted(),
        )
    }

    /** A ban; guests are recognised by the guest token they had. */
    private class Ban(val info: BanInfo, val guestToken: String?)

    private val mutex = Mutex()
    private val members = LinkedHashMap<String, Member>()
    private val queue = ArrayList<QueueItem>()
    private val history = ArrayList<QueueItem>()
    private var seq = 0L

    /** Ranked, highest first. */
    private val roles = ArrayList<Role>()
    private var settings: RoomSettings
    private val bans = ArrayList<Ban>()

    init {
        val start = Permissions.sanitize(template)
        roles += start.roles
        settings = start.settings
    }

    private var current: QueueItem? = null
    private var streamUrl: String? = null

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
     * only local connections may join a private (solo) room. [account] is set when the
     * participant proved an account; it then shows on them, and the room's account owner
     * is recognised on any device.
     */
    suspend fun join(hello: ClientMessage.Hello, outbox: Outbox, local: Boolean = true, account: AccountIdentity? = null): String? = mutex.withLock {
        if (visibility == RoomVisibility.PRIVATE && !local) {
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

        val isOwner = hello.ownerToken == ownerToken || (account != null && account.id == ownerAccount)
        val banned = bans.any { (account != null && it.info.accountId == account.id) || (it.guestToken != null && it.guestToken == hello.guestToken) }
        if (banned && !isOwner) {
            outbox.send(ServerMessage.Rejected(RejectReason.BANNED))
            return null
        }
        val returning = members.values.firstOrNull { it.token == hello.guestToken }
        val member = returning ?: Member(
            id = Ids.short(),
            token = Ids.token(),
            name = name,
            isOwner = isOwner,
            outbox = null,
            roleId = when {
                isOwner -> roles.first().id
                account != null -> settings.defaultAccountRole
                else -> settings.defaultGuestRole
            },
        ).also { members[it.id] = it }

        member.outbox?.takeIf { it !== outbox }?.send(ServerMessage.Rejected(RejectReason.REPLACED))
        member.outbox = outbox
        member.name = account?.displayName ?: name
        member.accountId = account?.id
        // Logging in later (same guest token, now with the owner's account) makes you the owner.
        member.isOwner = member.isOwner || isOwner
        member.offlineSince = null
        lastActive = clock()

        outbox.send(ServerMessage.Welcome(member.id, member.token, seq, snapshot(), accountId = account?.id))
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

    /** Pauses playback, e.g. when the phone stops hosting. */
    suspend fun pause() = mutex.withLock { pausePlayback() }

    /** Everything needed to bring this room back after a restart. */
    suspend fun save(): SavedRoom = mutex.withLock {
        SavedRoom(
            code = code,
            name = name,
            ownerToken = ownerToken,
            visibility = visibility,
            members = members.values.map { SavedMember(it.id, it.token, it.name, it.isOwner, it.accountId, it.roleId, it.allow.sorted(), it.deny.sorted()) },
            queue = queue.toList(),
            history = history.takeLast(SAVED_HISTORY),
            current = current,
            positionMs = position(),
            // Someone connected right now counts as activity, so the room isn't deleted as stale on restore.
            lastActive = if (members.values.any { it.outbox != null }) clock() else lastActive,
            ownerAccount = ownerAccount,
            roles = roles.toList(),
            settings = settings,
            bans = bans.map { SavedBan(it.info, it.guestToken) },
        )
    }

    /** Fills a new room from [saved]. Everyone starts offline and playback starts paused. */
    private fun restore(saved: SavedRoom) {
        val now = clock()
        if (saved.roles != null && saved.settings != null) {
            val restored = Permissions.sanitize(RoleTemplate(saved.roles, saved.settings))
            roles.clear()
            roles += restored.roles
            settings = restored.settings
        }
        for (b in saved.bans) bans += Ban(b.info, b.guestToken)
        val roleIds = roles.map { it.id }.toSet()
        for (m in saved.members) {
            val roleId = m.roleId?.takeIf { it in roleIds }
                ?: if (m.isOwner) roles.first().id else if (m.accountId != null) settings.defaultAccountRole else settings.defaultGuestRole
            members[m.id] = Member(
                m.id, m.token, m.name, m.isOwner, outbox = null, roleId = roleId, accountId = m.accountId,
                offlineSince = now, allow = m.allow.toSet(), deny = m.deny.toSet(),
            )
        }
        queue += saved.queue
        history += saved.history
        // The stream URL has probably expired; it is resolved again when someone presses play.
        current = saved.current
        positionAtAnchor = saved.positionMs
        anchorTime = now
        lastActive = saved.lastActive
    }

    fun close() {
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
        )
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
                retireCurrent(QueueItemResult.SKIPPED)
                wantPlaying = true
                setCurrent(newItem(command.song, member))
            }
            is Command.RemoveQueueItem -> {
                val item = queue.firstOrNull { it.itemId == command.itemId } ?: return notFound()
                need(member, if (item.addedBy == member.id) Permission.REMOVE_OWN else Permission.REMOVE_OTHERS)?.let { return it }
                queue.remove(item)
                emit(Event.QueueItemRemoved(command.itemId))
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
            is Command.SetVisibility -> {
                if (!member.isOwner) return ErrorInfo(ErrorCode.PERMISSION_DENIED, "Only the owner can change this")
                if (visibility != command.visibility) {
                    visibility = command.visibility
                    emit(Event.RoomUpdated(info))
                    onInfoChanged()
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
                val updated = RoomSettings(command.defaultGuestRole ?: settings.defaultGuestRole, command.defaultAccountRole ?: settings.defaultAccountRole)
                if (updated != settings) {
                    settings = updated
                    emit(Event.SettingsChanged(settings))
                }
            }
            else -> Unit
        }
        return null
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
        if (item != null) resolve(item)
    }

    /** Resolves the stream of the current [item], then starts it (or skips it if it can't play). */
    private fun resolve(item: QueueItem) {
        resolveJob?.cancel()
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
    )

    private fun newItem(song: Song, member: Member) =
        QueueItem(Ids.short(), song, member.id, member.name, clock(), result = null)

    private fun emit(event: Event) {
        val message = ServerMessage.EventMessage(++seq, event)
        isPlaying = wantPlaying
        for (member in members.values) member.outbox?.send(message)
        for (listener in changeListeners) listener()
    }

    private fun invalid(message: String) = ErrorInfo(ErrorCode.INVALID, message)

    private fun notFound() = ErrorInfo(ErrorCode.NOT_FOUND, "Not in the queue")

    companion object {
        const val MAX_NAME_LENGTH = 32
        const val PREVIOUS_RESTART_MS = 3_000L
        const val SNAPSHOT_HISTORY = 200
        const val SAVED_HISTORY = 500

        /** Brings back a room saved with [save]. */
        fun restore(
            saved: SavedRoom,
            streams: StreamResolver,
            scope: CoroutineScope,
            onInfoChanged: () -> Unit = {},
            clock: () -> Long = System::currentTimeMillis,
        ) = Room(saved.code, saved.name, saved.ownerToken, saved.visibility, streams, scope, saved.ownerAccount, onInfoChanged = onInfoChanged, clock = clock)
            .apply { restore(saved) }
    }
}
