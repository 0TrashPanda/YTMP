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
    /** A code of your own choosing ([ROOM_CODE] letters and digits); null: a random one. */
    val code: String? = null,
)

/** What a chosen room code may be: 3 to 12 letters and digits (case doesn't matter). */
val ROOM_CODE = Regex("[A-Z0-9]{3,12}")

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

/**
 * `POST /api/recognize`: Shazam's signature of a few seconds from the microphone (made by the
 * page, `shazamSignature.ts`, as its data URI) and how long that sound was.
 */
@Serializable
@SerialName("RecognizeRequest")
data class RecognizeRequest(val signature: String, val sampleMs: Long)

/**
 * The song Shazam recognized: its name, artist, album, cover (a URL) and ISRC; all null when it
 * didn't know it. `GET /api/recognize/song` with these finds it on YouTube Music.
 */
@Serializable
@SerialName("RecognizeResponse")
data class RecognizeResponse(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val art: String? = null,
    val isrc: String? = null,
)

/**
 * `POST /api/screens`: a TV asks for a code to show (docs/features/ui/party-screen.md). It
 * then waits with [secret] (`GET /api/screens/{secret}`) until someone in a room links it.
 */
@Serializable
@SerialName("ScreenPairing")
data class ScreenPairing(
    /** What the TV shows, e.g. `482 913`; entered on a phone in the room. */
    val code: String,
    /** Only the TV knows it. */
    val secret: String,
    val expiresAt: Long,
)

/** `POST /api/screens/link`: someone in [roomCode] (by their guest token) shows it on the TV showing [code]. */
@Serializable
@SerialName("ScreenLinkRequest")
data class ScreenLinkRequest(val code: String, val roomCode: String, val guestToken: String)

/** What the TV gets once it's linked: it joins [roomCode] with [guestToken] (a party screen participant). */
@Serializable
@SerialName("ScreenLinked")
data class ScreenLinked(val roomCode: String, val guestToken: String)

/**
 * `POST /api/screens/cast`: someone in [roomCode] (by their guest token) shows the party
 * screen on a TV on their own network, named [name], with the app (it starts DashCast there
 * with `/tv#cast=…`). The screen plays the music too. Answers [ScreenLinked].
 */
@Serializable
@SerialName("ScreenCastRequest")
data class ScreenCastRequest(val roomCode: String, val guestToken: String, val name: String)

/** `POST /api/screens/unlink`: the TV showing the party screen with [guestToken] stopped; it leaves [roomCode]. */
@Serializable
@SerialName("ScreenUnlinkRequest")
data class ScreenUnlinkRequest(val roomCode: String, val guestToken: String)

/** Error body of every `/api` endpoint. */
@Serializable
@SerialName("ApiError")
data class ApiError(val error: ErrorInfo)

@Serializable
@SerialName("HostKind")
enum class HostKind {
    @SerialName("server") SERVER,
    @SerialName("phone") PHONE,
    /** The desktop app's own host: no rooms, only this computer's music, song cache and speakers (docs/implementation/apps/desktop-app.md). */
    @SerialName("computer") COMPUTER,
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
    /**
     * Every address other devices may reach this host at (a phone on Wi-Fi and its own hotspot,
     * a VPN, …), to pick one when sharing a room. [shareUrl] is the usual one.
     */
    val shareAddresses: List<ShareAddress> = emptyList(),
    /** Whether a song can be recognized from the microphone here (`POST /api/recognize`). */
    val canRecognize: Boolean = false,
)

/** An address of this host for links, with what it is ("Wi-Fi", "Hotspot", "VPN", …). */
@Serializable
@SerialName("ShareAddress")
data class ShareAddress(val url: String, val label: String)

/** `GET /api/rooms`: the rooms on this host (only for the hosting device). */
@Serializable
@SerialName("RoomListResponse")
data class RoomListResponse(val rooms: List<RoomInfo>)
