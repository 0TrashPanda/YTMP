package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Friends, groups and songs shared with them, see docs/features/friends.md. Kept on the
// accounts' server; reached like the listening history (your own devices' pages, or a token
// for it on other hosts' pages).

/** An account on the same server, as a friend (or someone asking to be one). */
@Serializable
@SerialName("Person")
data class Person(val username: String, val displayName: String)

/** A group of friends you can share songs with. */
@Serializable
@SerialName("FriendGroup")
data class FriendGroup(
    val id: String,
    val name: String,
    /** Who made it: they rename it, add and remove people, and delete it. */
    val owner: Person,
    /** Everyone in it, the owner too. */
    val members: List<Person>,
)

/** `GET /api/account/friends`: your friends, requests both ways, and your groups. */
@Serializable
@SerialName("FriendsOverview")
data class FriendsOverview(
    /** You: friends add you by your username. */
    val me: Person,
    val friends: List<Person>,
    /** People asking to be your friend. */
    val incoming: List<Person>,
    /** People you asked, who haven't said yes yet. */
    val outgoing: List<Person>,
    val groups: List<FriendGroup>,
)

/** `POST /api/account/friends`: ask [username] to be friends (or say yes when they asked you). */
@Serializable
@SerialName("FriendRequest")
data class FriendRequest(val username: String)

/** `POST /api/account/groups` (a new group) and `PUT /api/account/groups/{id}` (the owner changes it). */
@Serializable
@SerialName("GroupRequest")
data class GroupRequest(
    val name: String,
    /** Usernames besides yourself: your friends (and on a change, anyone already in it). */
    val members: List<String>,
)

/** `POST /api/account/shares`: a song for some friends and groups. */
@Serializable
@SerialName("ShareRequest")
data class ShareRequest(
    val song: Song,
    /** Usernames of your friends. */
    val friends: List<String> = emptyList(),
    /** Ids of groups you're in. */
    val groups: List<String> = emptyList(),
    /** A few words with it, or empty. */
    val note: String = "",
)

/** The answer to a [ShareRequest]: the share (to take it back) and how many people get it. */
@Serializable
@SerialName("ShareResult")
data class ShareResult(val id: String, val recipients: Int)

/** A song someone shared with you. */
@Serializable
@SerialName("ReceivedShare")
data class ReceivedShare(
    val id: String,
    val song: Song,
    val from: Person,
    /** Shared with this group (null: with you). */
    val group: String?,
    val note: String,
    /** ms since the epoch. */
    val at: Long,
)

/** `GET /api/account/shares`: what friends shared with you, newest first. */
@Serializable
@SerialName("ReceivedShares")
data class ReceivedShares(val shares: List<ReceivedShare>)
