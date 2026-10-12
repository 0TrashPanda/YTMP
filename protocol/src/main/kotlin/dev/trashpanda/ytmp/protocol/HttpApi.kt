package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request/response bodies of the host's HTTP API (`/api/...`).

/** `POST /api/rooms` */
@Serializable
@SerialName("CreateRoomRequest")
data class CreateRoomRequest(
    val name: String,
    val visibility: RoomVisibility = RoomVisibility.PUBLIC,
    /** Start as a copy of another room (cloning). */
    val clone: RoomClone? = null,
)

@Serializable
@SerialName("CreateRoomResponse")
data class CreateRoomResponse(
    val code: String,
    val ownerToken: String,
    /** The room's link for other devices, when the host knows its address. */
    val url: String? = null,
)

/** `GET /api/search?q=` */
@Serializable
@SerialName("SearchResponse")
data class SearchResponse(val items: List<Song>)

/** `GET /api/search/suggestions?q=`: what to search for, while typing. */
@Serializable
@SerialName("SuggestionsResponse")
data class SuggestionsResponse(val items: List<String>)

/** Error body of every `/api` endpoint. */
@Serializable
@SerialName("ApiError")
data class ApiError(val error: ErrorInfo)

@Serializable
@SerialName("HostKind")
enum class HostKind {
    @SerialName("server") SERVER,
    @SerialName("phone") PHONE,
}

/** `GET /api/host`: what kind of host served this page. */
@Serializable
@SerialName("HostInfo")
data class HostInfo(
    val kind: HostKind,
    /** Base URL other devices can use to reach this host, or null if this page's own address works. */
    val shareUrl: String?,
    /** Whether the caller may create rooms and list this host's rooms. */
    val canCreateRooms: Boolean,
    /** Whether solo (private) rooms can be created here. */
    val supportsPrivateRooms: Boolean,
    /** Auth servers whose accounts can join here. Empty: guests only. */
    val authServers: List<AuthServerRef> = emptyList(),
    /** Only for people logged in with an account: no guests ([authServers] says where to log in). */
    val accountsOnly: Boolean = false,
)

/** `GET /api/rooms`: the rooms on this host (only for the hosting device). */
@Serializable
@SerialName("RoomListResponse")
data class RoomListResponse(val rooms: List<RoomInfo>)
