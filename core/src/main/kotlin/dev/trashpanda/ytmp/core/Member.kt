package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.BanInfo
import dev.trashpanda.ytmp.protocol.Participant
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.ServerMessage

/** Someone in a [Room] (or a party screen), with their connections. Only used with the room locked. */
internal class Member(
    val id: String,
    val token: String,
    var name: String,
    var isOwner: Boolean,
    var outbox: Outbox?,
    var roleId: String,
    var accountId: String? = null,
    var listening: Boolean = false,
    /** Their player says when it has a new song (Command.PlayerReady): songs wait for it. */
    var startsTogether: Boolean = false,
    /** Speakers near their phone that they play the room on (Command.SetSpeakers). */
    var speakers: List<String> = emptyList(),
    var speakersStartTogether: Boolean = false,
    var offlineSince: Long? = null,
    var allow: Set<Permission> = emptySet(),
    var deny: Set<Permission> = emptySet(),
    var accountToken: String? = null,
    var hideFromHistory: Boolean = false,
    /** When they joined; the longest here gets the room when its owner leaves. */
    val joinedAt: Long = 0,
    /** A party screen: no role (it only watches), and not a person (votes, "listened with"). */
    val screen: Boolean = false,
) {
    /** Only for them: proves who they were in a clone of this room ([dev.trashpanda.ytmp.protocol.Migrant]). */
    val migrationKey: String by lazy { Ids.sha256("ytmp-migrate:$token") }

    /** Shown to everyone: recognises them in a clone, without giving away [migrationKey]. */
    val tag: String by lazy { Ids.sha256(migrationKey) }

    /** Extra connections for this participant (see [Room.attach]). */
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

    /** Sends [reason] to every connection and forgets them: they're out of the room. */
    fun disconnectAll(reason: RejectReason, movedTo: String? = null) {
        outbox?.send(ServerMessage.Rejected(reason, movedTo = movedTo))
        outbox = null
        for (extra in attached) extra.send(ServerMessage.Rejected(reason, movedTo = movedTo))
        attached.clear()
    }

    fun toParticipant() = Participant(
        id, name, isOwner, online = online, listening = listening, accountId = accountId,
        roleId = roleId, allow = allow.sorted(), deny = deny.sorted(), tag = tag, screen = screen, speakers = speakers,
    )
}

/** A ban; guests are recognised by the guest token they had. */
internal class Ban(val info: BanInfo, val guestToken: String?)
