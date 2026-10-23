package dev.trashpanda.ytmp.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The auth server's HTTP API (`/api/auth/...`, `/api/account/...`, `/api/admin/...`).
// See docs/implementation/auth.md. Only servers are auth servers.

/** Who may create an account on an auth server. */
@Serializable
@SerialName("SignupMode")
enum class SignupMode {
    /** Anyone who can reach the server. */
    @SerialName("open") OPEN,

    /** Only with an invite link from the server admin. */
    @SerialName("invite") INVITE,

    /** Only the server admin creates accounts. */
    @SerialName("admin") ADMIN,
}

/** `GET /api/auth/info`: what hosts need to check this server's account tokens. */
@Serializable
@SerialName("AuthServerInfo")
data class AuthServerInfo(
    /** The server's name, the part after `@` in account IDs. */
    val issuer: String,
    /** ES256 public key, X.509 (SubjectPublicKeyInfo), base64. */
    val publicKey: String,
    val signup: SignupMode,
    /** No account exists yet: the first one becomes the server admin. */
    val needsAdmin: Boolean,
)

@Serializable
@SerialName("AccountInfo")
data class AccountInfo(
    /** `username@issuer` */
    val id: String,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean,
)

/** `POST /api/account/login` */
@Serializable
@SerialName("LoginRequest")
data class LoginRequest(val username: String, val password: String)

/** `POST /api/account/signup` */
@Serializable
@SerialName("SignupRequest")
data class SignupRequest(
    val username: String,
    val password: String,
    val displayName: String? = null,
    val invite: String? = null,
)

/** A login on the auth server's own pages. Sent back as `Authorization: Bearer`. */
@Serializable
@SerialName("SessionResponse")
data class SessionResponse(val sessionToken: String, val account: AccountInfo)

/** `POST /api/account/password` */
@Serializable
@SerialName("ChangePasswordRequest")
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/** `POST /api/account/host-token`: a token that lets a host's page join rooms with this account. */
@Serializable
@SerialName("HostTokenRequest")
data class HostTokenRequest(
    /** Origin of the host's page, e.g. `http://192.168.1.23:8765`. The token only works there. */
    val origin: String,
)

@Serializable
@SerialName("HostTokenResponse")
data class HostTokenResponse(val token: String, val expiresAt: Long, val account: AccountInfo)

/** An account in the server admin's list. */
@Serializable
@SerialName("AdminAccountInfo")
data class AdminAccountInfo(
    /** `username@issuer` */
    val id: String,
    val username: String,
    val displayName: String,
    val isAdmin: Boolean,
    /** When it was made (ms). */
    val createdAt: Long,
    /** The last time one of its logins here was used (ms); null: never logged in, or logged out everywhere. */
    val lastSeen: Long? = null,
    /** How many devices are logged in to it here. */
    val logins: Int = 0,
    /** 0: enabled; -1: disabled until an admin enables it; else disabled until then (ms). */
    val disabledUntil: Long = 0,
)

/** `GET /api/admin/accounts` */
@Serializable
@SerialName("AccountListResponse")
data class AccountListResponse(val accounts: List<AdminAccountInfo>)

/** `POST /api/admin/accounts` */
@Serializable
@SerialName("CreateAccountRequest")
data class CreateAccountRequest(val username: String, val password: String, val displayName: String? = null, val isAdmin: Boolean = false)

/** `POST /api/admin/accounts/{username}/disable`: for [minutes], or (null) until it is enabled again. */
@Serializable
@SerialName("DisableAccountRequest")
data class DisableAccountRequest(val minutes: Long? = null)

/** One thing a server admin did (`GET /api/admin/audit`). */
@Serializable
@SerialName("AuditEntry")
data class AuditEntry(val at: Long, val actor: String, val action: String, val target: String, val detail: String)

@Serializable
@SerialName("AuditLogResponse")
data class AuditLogResponse(val entries: List<AuditEntry>)

/** `PUT /api/admin/accounts/{username}`: what to change (null: leave it). */
@Serializable
@SerialName("UpdateAccountRequest")
data class UpdateAccountRequest(val displayName: String? = null, val isAdmin: Boolean? = null)

/** `POST /api/admin/accounts/{username}/password` */
@Serializable
@SerialName("SetPasswordRequest")
data class SetPasswordRequest(val password: String)

/** `POST /api/admin/invites`: the invite code goes into a sign-up link. */
@Serializable
@SerialName("InviteResponse")
data class InviteResponse(val code: String, val expiresAt: Long)

/** An invite link nobody has used yet. Its code isn't kept, so it can't be shown again; [id] only revokes it. */
@Serializable
@SerialName("InviteInfo")
data class InviteInfo(val id: String, val createdBy: String, val createdAt: Long, val expiresAt: Long)

/** `GET /api/admin/invites` */
@Serializable
@SerialName("InviteListResponse")
data class InviteListResponse(val invites: List<InviteInfo>)

/**
 * The server settings the admin page changes. `ytmp.toml` (and `YTMP_*` environment variables)
 * give them first; a value set on the admin page wins.
 */
@Serializable
@SerialName("ServerSettingValues")
data class ServerSettingValues(
    val signup: SignupMode,
    /** People without an account may use the server. */
    val guests: Boolean,
    /** New accounts keep a listening history until they turn it off. */
    val trackingDefault: Boolean,
    /** Other YTMP servers (URLs) whose accounts may join rooms here. */
    val trusted: List<String>,
    /** letters | digits | alnum */
    val roomCodeStyle: String,
    val roomCodeLength: Int,
    /** Who may stream audio through the server: always | lan | never. */
    val audioProxy: String,
    /** The song cache's size; 0: off. */
    val cacheBytes: Long,
    /** info | debug */
    val logLevel: String,
    /** Who may create rooms: everyone | accounts | admins. */
    val roomCreate: String = "everyone",
)

/** The same settings as [ServerSettingValues], as set on the admin page: null means "as in ytmp.toml". */
@Serializable
@SerialName("ServerSettingOverrides")
data class ServerSettingOverrides(
    val signup: SignupMode? = null,
    val guests: Boolean? = null,
    val trackingDefault: Boolean? = null,
    val trusted: List<String>? = null,
    val roomCodeStyle: String? = null,
    val roomCodeLength: Int? = null,
    val audioProxy: String? = null,
    val cacheBytes: Long? = null,
    val logLevel: String? = null,
    val roomCreate: String? = null,
)

/** `GET`/`PUT /api/admin/settings` (`PUT` sends [ServerSettingOverrides]). */
@Serializable
@SerialName("AdminSettings")
data class AdminSettings(
    /** What's in use now. */
    val values: ServerSettingValues,
    /** What ytmp.toml (and environment variables) say. */
    val file: ServerSettingValues,
    /** What's set on the admin page. */
    val overrides: ServerSettingOverrides,
)

/** A room on the server, in the admin's list (`GET /api/admin/rooms`). */
@Serializable
@SerialName("AdminRoomInfo")
data class AdminRoomInfo(
    val code: String,
    val name: String,
    val visibility: RoomVisibility,
    /** The owner's account ID, or null: a guest's room. */
    val owner: String? = null,
    /** People connected now (not party screens). */
    val online: Int,
    /** People in the room, connected or not. */
    val members: Int,
    val playing: Boolean,
    /** The song playing or paused now. */
    val current: String? = null,
    val queued: Int,
    val alwaysOn: Boolean,
    /** Last time someone was connected (ms). */
    val lastActive: Long,
)

@Serializable
@SerialName("AdminRoomList")
data class AdminRoomList(val rooms: List<AdminRoomInfo>)

/** What an uploaded backup holds (`POST /api/admin/restore`). It replaces the database when the server starts again. */
@Serializable
@SerialName("BackupInfo")
data class BackupInfo(val accounts: Int, val rooms: Int)

/** `GET /api/admin/about`: versions. */
@Serializable
@SerialName("ServerAbout")
data class ServerAbout(
    /** This server's version, or null: a build of its own (not a release). */
    val version: String? = null,
    /** The newest release on GitHub, or null: unknown (not looked up yet, or no internet). */
    val latestVersion: String? = null,
    /** The YouTube Music module's yt-dlp, or null: the module can't be reached. */
    val ytDlp: String? = null,
    val ytmusicapi: String? = null,
    /** The module answers. */
    val moduleOnline: Boolean = false,
    val accounts: Int = 0,
    val rooms: Int = 0,
    /** The database can be backed up and restored here (SQLite). */
    val backupSupported: Boolean = false,
    /** An uploaded backup waits for the server to start again. */
    val restorePending: Boolean = false,
)

/** An auth server whose accounts can join rooms on a host. */
@Serializable
@SerialName("AuthServerRef")
data class AuthServerRef(
    /** Where to log in, or null for the host itself (a server's own accounts). */
    val url: String?,
    val issuer: String,
)

/** `PUT /api/host/auth-server` (phone only): use a YTMP server for accounts, or none. */
@Serializable
@SerialName("LinkAuthServerRequest")
data class LinkAuthServerRequest(val url: String?)
