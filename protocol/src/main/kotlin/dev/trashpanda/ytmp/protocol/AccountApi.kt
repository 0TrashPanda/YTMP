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

/** `GET /api/admin/accounts` */
@Serializable
@SerialName("AccountListResponse")
data class AccountListResponse(val accounts: List<AccountInfo>)

/** `POST /api/admin/accounts` */
@Serializable
@SerialName("CreateAccountRequest")
data class CreateAccountRequest(val username: String, val password: String, val displayName: String? = null)

/** `POST /api/admin/accounts/{username}/password` */
@Serializable
@SerialName("SetPasswordRequest")
data class SetPasswordRequest(val password: String)

/** `POST /api/admin/invites`: the invite code goes into a sign-up link. */
@Serializable
@SerialName("InviteResponse")
data class InviteResponse(val code: String, val expiresAt: Long)

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
